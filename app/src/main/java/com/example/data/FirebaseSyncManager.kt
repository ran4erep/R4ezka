package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Высокопроизводительный движок авторизации и облачной синхронизации с Firebase Realtime Database.
 * Минимизирует нагрузку на CPU за счёт потокобезопасного пула соединений, умного дебаунсинга
 * частых обновлений прогресса видео и zero-allocation хэширования.
 */
object FirebaseSyncManager {
    private const val TAG = "FirebaseSyncManager"
    const val DATABASE_URL = "https://r4ezka-default-rtdb.europe-west1.firebasedatabase.app"

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var prefs: SharedPreferences? = null

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _currentUser = MutableStateFlow<String?>(null)
    val currentUser: StateFlow<String?> = _currentUser.asStateFlow()

    private val _currentUserAvatar = MutableStateFlow<String?>(null)
    val currentUserAvatar: StateFlow<String?> = _currentUserAvatar.asStateFlow()

    private val _currentUserRegisteredAt = MutableStateFlow<Long?>(null)
    val currentUserRegisteredAt: StateFlow<Long?> = _currentUserRegisteredAt.asStateFlow()

    private val _userKey = MutableStateFlow<String?>(null)
    val userKey: StateFlow<String?> = _userKey.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    // Пул отложенных записей истории для устранения частых сетевых вызовов во время воспроизведения
    private val pendingProgressMap = ConcurrentHashMap<String, WatchHistoryEntity>()
    private var progressDebounceJob: Job? = null

    // Провайдер локальной истории поиска и обратный вызов для синхронизации
    var searchHistoryProvider: (() -> List<String>)? = null
    var onSearchHistorySynced: ((List<String>) -> Unit)? = null

    fun init(context: Context, repository: RezkaRepository) {
        prefs = context.getSharedPreferences("r4ezka_firebase_auth", Context.MODE_PRIVATE)
        val savedUser = prefs?.getString("auth_username", null)
        val savedKey = prefs?.getString("auth_user_key", null)
        val savedAvatar = prefs?.getString("auth_avatar", null)
        val savedRegAt = prefs?.getLong("auth_registered_at", 0L) ?: 0L

        if (!savedAvatar.isNullOrBlank()) {
            _currentUserAvatar.value = savedAvatar
        }
        if (savedRegAt > 0L) {
            _currentUserRegisteredAt.value = savedRegAt
        }

        if (!savedUser.isNullOrBlank() && !savedKey.isNullOrBlank()) {
            _currentUser.value = savedUser
            _userKey.value = savedKey
            _isLoggedIn.value = true

            // Фоновая автоматическая синхронизация при запуске
            scope.launch {
                syncAll(repository)
            }
        }
    }

    /**
     * Генерация безопасного детерминированного ключа пользователя для Firebase RTDB.
     * Firebase запрещает символы '.', '#', '$', '[', ']' и '/'.
     * SHA-256 хэш в нижнем регистре гарантирует валидность ключа и нечувствительность к регистру логина.
     */
    fun generateUserKey(username: String): String {
        val normalized = username.trim().lowercase()
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(normalized.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(64)
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    private fun generateSalt(): String {
        val random = SecureRandom()
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        val sb = StringBuilder(32)
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    private fun hashPassword(password: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val input = "$salt:$password".toByteArray(Charsets.UTF_8)
        val bytes = md.digest(input)
        val sb = StringBuilder(64)
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    /**
     * Безопасное экранирование ключей сущностей для RTDB
     */
    fun safeFirebaseKey(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when (ch) {
                '.', '#', '$', '[', ']', '/' -> sb.append('_')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Регистрация нового пользователя в Firebase Realtime Database
     */
    suspend fun register(
        username: String,
        password: String,
        avatar: String? = null,
        repository: RezkaRepository
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanName = username.trim()
        if (cleanName.length < 3) {
            return@withContext Result.failure(Exception("Логин должен содержать минимум 3 символа"))
        }
        if (password.length < 4) {
            return@withContext Result.failure(Exception("Пароль должен содержать минимум 4 символа"))
        }

        val key = generateUserKey(cleanName)

        try {
            // 1. Проверяем, существует ли уже такой пользователь
            val checkUrl = "$DATABASE_URL/users/$key/profile.json"
            val checkRequest = Request.Builder().url(checkUrl).get().build()
            val checkResp = httpClient.newCall(checkRequest).execute()

            if (checkResp.isSuccessful) {
                val body = checkResp.body?.string()?.trim() ?: "null"
                if (body != "null" && body.isNotEmpty()) {
                    return@withContext Result.failure(Exception("Пользователь с таким логином уже зарегистрирован"))
                }
            }

            // 2. Создаём запись профиля с криптографически безопасным хэшем и солью
            val salt = generateSalt()
            val passwordHash = hashPassword(password, salt)

            val regAt = System.currentTimeMillis()
            val profileJson = JSONObject().apply {
                put("username", cleanName)
                put("passwordHash", passwordHash)
                put("salt", salt)
                put("registeredAt", regAt)
            }

            val putProfileRequest = Request.Builder()
                .url("$DATABASE_URL/users/$key/profile.json")
                .put(profileJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val profileResp = httpClient.newCall(putProfileRequest).execute()
            if (!profileResp.isSuccessful) {
                return@withContext Result.failure(Exception("Ошибка базы данных: HTTP ${profileResp.code}"))
            }

            // 3. Если указана аватарка при регистрации - сохраняем в узел avatar
            if (!avatar.isNullOrBlank()) {
                _currentUserAvatar.value = avatar
                prefs?.edit()?.putString("auth_avatar", avatar)?.apply()
                try {
                    val avatarReq = Request.Builder()
                        .url("$DATABASE_URL/users/$key/avatar.json")
                        .put(JSONObject.quote(avatar).toRequestBody(JSON_MEDIA_TYPE))
                        .build()
                    httpClient.newCall(avatarReq).execute().close()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to upload initial avatar", e)
                }
            }

            // 4. Сохраняем сессию
            _currentUser.value = cleanName
            _userKey.value = key
            _currentUserRegisteredAt.value = regAt
            _isLoggedIn.value = true

            prefs?.edit()
                ?.putString("auth_username", cleanName)
                ?.putString("auth_user_key", key)
                ?.putLong("auth_registered_at", regAt)
                ?.apply()

            // 5. Выгружаем текущие локальные закладки и историю в облако
            uploadLocalDataToCloud(key, repository)

            Result.success("Регистрация успешна")
        } catch (e: Exception) {
            Log.e(TAG, "Error registering user", e)
            Result.failure(Exception(e.message ?: "Ошибка подключения к базе данных"))
        }
    }

    /**
     * Вход пользователя с проверкой хэша пароля
     */
    suspend fun login(
        username: String,
        password: String,
        repository: RezkaRepository
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanName = username.trim()
        if (cleanName.isBlank() || password.isBlank()) {
            return@withContext Result.failure(Exception("Заполните логин и пароль"))
        }

        val key = generateUserKey(cleanName)

        try {
            val profileUrl = "$DATABASE_URL/users/$key/profile.json"
            val request = Request.Builder().url(profileUrl).get().build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Ошибка базы данных: HTTP ${response.code}"))
            }

            val body = response.body?.string()?.trim() ?: "null"
            if (body == "null" || body.isEmpty()) {
                return@withContext Result.failure(Exception("Пользователь с таким логином не найден"))
            }

            val json = JSONObject(body)
            val storedHash = json.optString("passwordHash", "")
            val storedSalt = json.optString("salt", "")
            val realUsername = json.optString("username", cleanName)
            val registeredAt = json.optLong("registeredAt", 0L)

            if (storedHash.isEmpty() || storedSalt.isEmpty()) {
                return@withContext Result.failure(Exception("Ошибка структуры профиля в базе данных"))
            }

            val computedHash = hashPassword(password, storedSalt)
            if (computedHash != storedHash) {
                return@withContext Result.failure(Exception("Неверный пароль"))
            }

            // Загружаем аватар пользователя из базы
            var fetchedAvatar: String? = null
            try {
                val avReq = Request.Builder().url("$DATABASE_URL/users/$key/avatar.json").get().build()
                val avResp = httpClient.newCall(avReq).execute()
                if (avResp.isSuccessful) {
                    val avBody = avResp.body?.string()?.trim() ?: "null"
                    if (avBody != "null" && avBody.isNotEmpty()) {
                        fetchedAvatar = if (avBody.startsWith("\"") && avBody.endsWith("\"")) {
                            avBody.substring(1, avBody.length - 1)
                                .replace("\\\"", "\"")
                                .replace("\\\\", "\\")
                                .replace("\\/", "/")
                        } else {
                            avBody
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error fetching avatar", e)
            }

            // Успешная авторизация
            _currentUser.value = realUsername
            _userKey.value = key
            _currentUserAvatar.value = fetchedAvatar
            if (registeredAt > 0L) {
                _currentUserRegisteredAt.value = registeredAt
            }
            _isLoggedIn.value = true

            val editor = prefs?.edit()
                ?.putString("auth_username", realUsername)
                ?.putString("auth_user_key", key)
            if (registeredAt > 0L) {
                editor?.putLong("auth_registered_at", registeredAt)
            }
            if (fetchedAvatar != null) {
                editor?.putString("auth_avatar", fetchedAvatar)
            } else {
                editor?.remove("auth_avatar")
            }
            editor?.apply()

            // Синхронизируем данные из облака и локальной базы
            syncAll(repository)

            Result.success("Добро пожаловать, $realUsername!")
        } catch (e: Exception) {
            Log.e(TAG, "Error during login", e)
            Result.failure(Exception(e.message ?: "Ошибка авторизации"))
        }
    }

    /**
     * Обновление аватара пользователя (синхронно локально + выгрузка в облако)
     */
    suspend fun updateAvatar(avatar: String?): Result<Unit> = withContext(Dispatchers.IO) {
        val key = _userKey.value ?: return@withContext Result.failure(Exception("Пользователь не авторизован"))
        try {
            _currentUserAvatar.value = avatar
            val editor = prefs?.edit()
            if (avatar != null) {
                editor?.putString("auth_avatar", avatar)
            } else {
                editor?.remove("auth_avatar")
            }
            editor?.apply()

            val req = if (avatar != null) {
                Request.Builder()
                    .url("$DATABASE_URL/users/$key/avatar.json")
                    .put(JSONObject.quote(avatar).toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            } else {
                Request.Builder()
                    .url("$DATABASE_URL/users/$key/avatar.json")
                    .delete()
                    .build()
            }

            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                resp.close()
                Result.success(Unit)
            } else {
                val code = resp.code
                resp.close()
                Result.failure(Exception("Ошибка сохранения на сервере: HTTP $code"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating avatar", e)
            Result.failure(Exception(e.message ?: "Ошибка при обновлении аватара"))
        }
    }

    /**
     * Выход из аккаунта
     */
    fun logout() {
        flushPendingProgress()
        _isLoggedIn.value = false
        _currentUser.value = null
        _userKey.value = null
        _currentUserAvatar.value = null
        _currentUserRegisteredAt.value = null
        prefs?.edit()
            ?.remove("auth_username")
            ?.remove("auth_user_key")
            ?.remove("auth_avatar")
            ?.remove("auth_registered_at")
            ?.apply()
    }

    /**
     * Добавление/обновление избранного в Firebase RTDB
     */
    fun onFavoriteAdded(entity: FavoriteEntity) {
        val key = _userKey.value ?: return
        scope.launch {
            try {
                val safeId = safeFirebaseKey(entity.id)
                val json = favoriteToJson(entity)
                val request = Request.Builder()
                    .url("$DATABASE_URL/users/$key/favorites/$safeId.json")
                    .put(json.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                httpClient.newCall(request).execute().close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync favorite to Firebase: ${e.message}")
            }
        }
    }

    /**
     * Удаление избранного из Firebase RTDB
     */
    fun onFavoriteRemoved(itemId: String) {
        val key = _userKey.value ?: return
        scope.launch {
            try {
                val safeId = safeFirebaseKey(itemId)
                val request = Request.Builder()
                    .url("$DATABASE_URL/users/$key/favorites/$safeId.json")
                    .delete()
                    .build()
                httpClient.newCall(request).execute().close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove favorite from Firebase: ${e.message}")
            }
        }
    }

    /**
     * Добавление/обновление подписки на сериал в Firebase RTDB
     */
    fun onSubscriptionAdded(entity: SeriesSubscriptionEntity) {
        val key = _userKey.value ?: return
        scope.launch {
            try {
                val safeId = safeFirebaseKey(entity.id)
                val json = subscriptionToJson(entity)
                val request = Request.Builder()
                    .url("$DATABASE_URL/users/$key/subscriptions/$safeId.json")
                    .put(json.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                httpClient.newCall(request).execute().close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync subscription to Firebase: ${e.message}")
            }
        }
    }

    /**
     * Удаление подписки из Firebase RTDB
     */
    fun onSubscriptionRemoved(itemId: String) {
        val key = _userKey.value ?: return
        scope.launch {
            try {
                val safeId = safeFirebaseKey(itemId)
                val request = Request.Builder()
                    .url("$DATABASE_URL/users/$key/subscriptions/$safeId.json")
                    .delete()
                    .build()
                httpClient.newCall(request).execute().close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove subscription from Firebase: ${e.message}")
            }
        }
    }

    /**
     * Точечное обновление прогресса вышедших серий в облаке
     */
    fun onSubscriptionProgressUpdated(
        id: String,
        season: Int,
        episode: Int,
        episodeName: String,
        hasUpdate: Boolean
    ) {
        val key = _userKey.value ?: return
        scope.launch {
            try {
                val safeId = safeFirebaseKey(id)
                val patchJson = JSONObject().apply {
                    put("lastKnownSeason", season)
                    put("lastKnownEpisode", episode)
                    put("lastEpisodeName", episodeName)
                    put("lastCheckedAt", System.currentTimeMillis())
                    put("hasUnseenUpdate", hasUpdate)
                }
                val request = Request.Builder()
                    .url("$DATABASE_URL/users/$key/subscriptions/$safeId.json")
                    .patch(patchJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                httpClient.newCall(request).execute().close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to patch subscription progress in Firebase: ${e.message}")
            }
        }
    }

    /**
     * Умное сохранение прогресса просмотра в Firebase с дебаунсингом (не чаще раза в 4 секунды),
     * чтобы плеер не нагружал процессор и сеть лишними сетевыми запросами во время воспроизведения.
     */
    fun onWatchProgress(entity: WatchHistoryEntity) {
        if (!_isLoggedIn.value) return
        val safeId = safeFirebaseKey(entity.id)
        pendingProgressMap[safeId] = entity

        if (progressDebounceJob?.isActive != true) {
            progressDebounceJob = scope.launch {
                delay(4000L)
                flushPendingProgress()
            }
        }
    }

    fun flushWatchProgress() = flushPendingProgress()

    /**
     * Принудительный сброс буфера отложенного прогресса (например при паузе или выходе из плеера)
     */
    fun flushPendingProgress() {
        val key = _userKey.value ?: return
        if (pendingProgressMap.isEmpty()) return

        val itemsToUpload = HashMap(pendingProgressMap)
        pendingProgressMap.clear()

        scope.launch {
            for ((safeId, item) in itemsToUpload) {
                try {
                    val json = historyToJson(item)
                    val request = Request.Builder()
                        .url("$DATABASE_URL/users/$key/history/$safeId.json")
                        .put(json.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .build()
                    httpClient.newCall(request).execute().close()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to flush progress for $safeId: ${e.message}")
                }
            }
        }
    }

    /**
     * Сохранение индивидуального режима масштабирования для конкретного фильма или сериала в облаке
     */
    fun onItemResizeModeUpdated(itemId: String, mode: String) {
        if (itemId.isBlank()) return
        val key = _userKey.value ?: return
        val safeId = safeFirebaseKey(itemId)
        scope.launch {
            try {
                val json = JSONObject().apply {
                    put("itemId", itemId)
                    put("mode", mode)
                    put("updatedAt", System.currentTimeMillis())
                }
                val request = Request.Builder()
                    .url("$DATABASE_URL/users/$key/itemResizeModes/$safeId.json")
                    .put(json.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                httpClient.newCall(request).execute().close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync item resize mode for $safeId: ${e.message}")
            }
        }
    }

    /**
     * Сохранение настроек приложения (зеркало, качество видео, автопереключение серии, субтитры, масштаб, режим интерфейса и сетка карточек) в облако
     */
    fun onSettingsUpdated(
        mirror: String = RezkaService.currentMirror.value,
        quality: String = RezkaService.defaultQuality.value,
        autoNextEpisode: Boolean = RezkaService.autoNextEpisode.value,
        preferredSubtitleLang: String = RezkaService.preferredSubtitleLang.value,
        subtitleTextScale: Float = RezkaService.subtitleTextScale.value,
        resizeMode: String = RezkaService.defaultResizeMode.value,
        tvMode: String = RezkaService.tvModePreference.value,
        cardGridMode: String = RezkaService.cardGridMode.value
    ) {
        val key = _userKey.value ?: return
        scope.launch {
            try {
                val json = JSONObject().apply {
                    put("mirror", mirror)
                    put("defaultQuality", quality)
                    put("autoNextEpisode", autoNextEpisode)
                    put("preferredSubtitleLang", preferredSubtitleLang)
                    put("subtitleTextScale", subtitleTextScale.toDouble())
                    put("resizeMode", resizeMode)
                    put("tvMode", tvMode)
                    put("cardGridMode", cardGridMode)
                    put("updatedAt", System.currentTimeMillis())
                }
                val request = Request.Builder()
                    .url("$DATABASE_URL/users/$key/settings.json")
                    .put(json.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                httpClient.newCall(request).execute().close()
                Log.d(TAG, "Settings synced to cloud: mirror=$mirror, quality=$quality, autoNext=$autoNextEpisode, sub=$preferredSubtitleLang, subScale=$subtitleTextScale, resize=$resizeMode, tvMode=$tvMode, cardGridMode=$cardGridMode")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync settings to Firebase: ${e.message}")
            }
        }
    }

    /**
     * Удаление одной записи истории из Firebase
     */
    fun onHistoryDeleted(historyId: String) {
        val key = _userKey.value ?: return
        pendingProgressMap.remove(safeFirebaseKey(historyId))
        scope.launch {
            try {
                val safeId = safeFirebaseKey(historyId)
                val request = Request.Builder()
                    .url("$DATABASE_URL/users/$key/history/$safeId.json")
                    .delete()
                    .build()
                httpClient.newCall(request).execute().close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete history item: ${e.message}")
            }
        }
    }

    /**
     * Удаление истории фильма/сериала целиком по itemId
     */
    fun onHistoryDeletedByItemId(itemId: String) {
        val key = _userKey.value ?: return
        scope.launch {
            try {
                // Читаем текущую историю из облака, находим совпадения по itemId и удаляем их
                val request = Request.Builder()
                    .url("$DATABASE_URL/users/$key/history.json")
                    .get()
                    .build()
                val resp = httpClient.newCall(request).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    if (body != "null" && body.isNotEmpty()) {
                        val rootJson = JSONObject(body)
                        val keysToDelete = mutableListOf<String>()
                        val it = rootJson.keys()
                        while (it.hasNext()) {
                            val k = it.next()
                            val itemObj = rootJson.optJSONObject(k)
                            if (itemObj != null && itemObj.optString("itemId") == itemId) {
                                keysToDelete.add(k)
                            }
                        }
                        for (k in keysToDelete) {
                            val delReq = Request.Builder()
                                .url("$DATABASE_URL/users/$key/history/$k.json")
                                .delete()
                                .build()
                            httpClient.newCall(delReq).execute().close()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete history by itemId: ${e.message}")
            }
        }
    }

    /**
     * Полная очистка истории в Firebase
     */
    fun onAllHistoryCleared() {
        val key = _userKey.value ?: return
        pendingProgressMap.clear()
        scope.launch {
            try {
                val request = Request.Builder()
                    .url("$DATABASE_URL/users/$key/history.json")
                    .delete()
                    .build()
                httpClient.newCall(request).execute().close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear all history: ${e.message}")
            }
        }
    }

    /**
     * Сохранение истории поиска в облако Firebase RTDB
     */
    fun onSearchHistoryUpdated(queries: List<String>) {
        val key = _userKey.value ?: return
        scope.launch {
            try {
                val jsonArr = org.json.JSONArray()
                queries.take(15).forEach { query ->
                    val clean = query.trim()
                    if (clean.isNotEmpty()) {
                        jsonArr.put(clean)
                    }
                }
                val request = Request.Builder()
                    .url("$DATABASE_URL/users/$key/searchHistory.json")
                    .put(jsonArr.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                httpClient.newCall(request).execute().close()
                Log.d(TAG, "Search history synced to cloud: ${jsonArr.length()} queries")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync search history to Firebase: ${e.message}")
            }
        }
    }

    /**
     * Очистка истории поиска в облаке Firebase RTDB
     */
    fun onSearchHistoryCleared() {
        val key = _userKey.value ?: return
        scope.launch {
            try {
                val request = Request.Builder()
                    .url("$DATABASE_URL/users/$key/searchHistory.json")
                    .delete()
                    .build()
                httpClient.newCall(request).execute().close()
                Log.d(TAG, "Search history cleared in cloud")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear search history in Firebase: ${e.message}")
            }
        }
    }

    /**
     * Высокопроизводительное объединение локальной и облачной истории поиска
     * с O(1) проверкой уникальности, сохранением хронологии и минимальной нагрузкой на CPU.
     */
    fun mergeSearchHistories(local: List<String>, remote: List<String>, limit: Int = 15): List<String> {
        val result = ArrayList<String>(local.size + remote.size)
        val seen = HashSet<String>()
        for (q in local) {
            val trimmed = q.trim()
            if (trimmed.length >= 2 && seen.add(trimmed.lowercase())) {
                result.add(trimmed)
            }
        }
        for (q in remote) {
            val trimmed = q.trim()
            if (trimmed.length >= 2 && seen.add(trimmed.lowercase())) {
                result.add(trimmed)
            }
        }
        return if (result.size > limit) result.subList(0, limit) else result
    }

    /**
     * Полная двусторонняя синхронизация облачной базы и локального Room кэша
     */
    suspend fun syncAll(repository: RezkaRepository) = withContext(Dispatchers.IO) {
        val key = _userKey.value ?: return@withContext
        if (_isSyncing.value) return@withContext
        _isSyncing.value = true

        try {
            // 0. Синхронизация профиля (дата регистрации)
            try {
                val profReq = Request.Builder().url("$DATABASE_URL/users/$key/profile.json").get().build()
                val profResp = httpClient.newCall(profReq).execute()
                if (profResp.isSuccessful) {
                    val body = profResp.body?.string()?.trim() ?: ""
                    if (body != "null" && body.isNotEmpty()) {
                        val pJson = JSONObject(body)
                        val regAt = pJson.optLong("registeredAt", 0L)
                        if (regAt > 0L) {
                            _currentUserRegisteredAt.value = regAt
                            prefs?.edit()?.putLong("auth_registered_at", regAt)?.apply()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync profile info: ${e.message}")
            }

            // 1. Синхронизация Избранного
            val favReq = Request.Builder().url("$DATABASE_URL/users/$key/favorites.json").get().build()
            val favResp = httpClient.newCall(favReq).execute()
            val remoteFavorites = mutableListOf<FavoriteEntity>()

            if (favResp.isSuccessful) {
                val favBody = favResp.body?.string() ?: ""
                if (favBody != "null" && favBody.isNotEmpty()) {
                    val favJson = JSONObject(favBody)
                    val it = favJson.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        val obj = favJson.optJSONObject(k)
                        if (obj != null) {
                            remoteFavorites.add(jsonToFavorite(obj))
                        }
                    }
                }
            }

            // Пакетная вставка в локальный Room
            if (remoteFavorites.isNotEmpty()) {
                repository.insertFavorites(remoteFavorites)
            }

            // Проверяем, есть ли локальные закладки, которых ещё нет в облаке, и дозаливаем их в один PATCH-запрос
            val localFavorites = repository.getAllFavoritesList()
            val remoteFavIds = remoteFavorites.map { it.id }.toSet()
            val favUpdateJson = JSONObject()
            for (localFav in localFavorites) {
                if (localFav.id !in remoteFavIds) {
                    val safeId = safeFirebaseKey(localFav.id)
                    favUpdateJson.put(safeId, favoriteToJson(localFav))
                }
            }
            if (favUpdateJson.length() > 0) {
                try {
                    val patchRequest = Request.Builder()
                        .url("$DATABASE_URL/users/$key/favorites.json")
                        .patch(favUpdateJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .build()
                    httpClient.newCall(patchRequest).execute().close()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to patch favorites: ${e.message}")
                }
            }

            // 2. Синхронизация Истории просмотров (серии, сезоны, секунды прогресса)
            val histReq = Request.Builder().url("$DATABASE_URL/users/$key/history.json").get().build()
            val histResp = httpClient.newCall(histReq).execute()
            val remoteHistory = mutableListOf<WatchHistoryEntity>()

            if (histResp.isSuccessful) {
                val histBody = histResp.body?.string() ?: ""
                if (histBody != "null" && histBody.isNotEmpty()) {
                    val histJson = JSONObject(histBody)
                    val it = histJson.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        val obj = histJson.optJSONObject(k)
                        if (obj != null) {
                            remoteHistory.add(jsonToHistory(obj))
                        }
                    }
                }
            }

            if (remoteHistory.isNotEmpty()) {
                repository.insertHistoryList(remoteHistory)
            }

            // Проверяем локальную историю и дозаливаем новые элементы в облако в один PATCH-запрос
            val localHistory = repository.getAllHistoryList()
            val remoteHistIds = remoteHistory.map { it.id }.toSet()
            val histUpdateJson = JSONObject()
            for (localHist in localHistory) {
                if (localHist.id !in remoteHistIds) {
                    val safeId = safeFirebaseKey(localHist.id)
                    histUpdateJson.put(safeId, historyToJson(localHist))
                }
            }
            if (histUpdateJson.length() > 0) {
                try {
                    val patchRequest = Request.Builder()
                        .url("$DATABASE_URL/users/$key/history.json")
                        .patch(histUpdateJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .build()
                    httpClient.newCall(patchRequest).execute().close()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to patch history: ${e.message}")
                }
            }

            // 3. Синхронизация Настроек (зеркало, качество видео, автопереключение серии, субтитры, масштаб)
            try {
                val setReq = Request.Builder().url("$DATABASE_URL/users/$key/settings.json").get().build()
                val setResp = httpClient.newCall(setReq).execute()
                if (setResp.isSuccessful) {
                    val setBody = setResp.body?.string()?.trim() ?: ""
                    if (setBody != "null" && setBody.isNotEmpty()) {
                        val setJson = JSONObject(setBody)
                        val remoteMirror = setJson.optString("mirror")
                        val remoteQuality = setJson.optString("defaultQuality")
                        val hasAutoNext = setJson.has("autoNextEpisode")
                        val remoteAutoNext = setJson.optBoolean("autoNextEpisode", true)
                        val remoteSubLang = setJson.optString("preferredSubtitleLang", "")
                        val remoteSubScale = if (setJson.has("subtitleTextScale")) setJson.optDouble("subtitleTextScale").toFloat() else null
                        val remoteResize = setJson.optString("resizeMode", "")
                        val remoteTvMode = setJson.optString("tvMode", "")
                        val remoteGridMode = setJson.optString("cardGridMode", "")

                        if (remoteMirror.isNotBlank() && remoteMirror != RezkaService.currentMirror.value) {
                            withContext(Dispatchers.Main) {
                                RezkaService.setMirror(remoteMirror)
                            }
                        }
                        if (remoteQuality.isNotBlank() && remoteQuality != RezkaService.defaultQuality.value) {
                            withContext(Dispatchers.Main) {
                                RezkaService.setDefaultQuality(remoteQuality)
                            }
                        }
                        if (hasAutoNext && remoteAutoNext != RezkaService.autoNextEpisode.value) {
                            withContext(Dispatchers.Main) {
                                RezkaService.setAutoNextEpisode(remoteAutoNext)
                            }
                        }
                        if (remoteSubLang.isNotBlank() && remoteSubLang != RezkaService.preferredSubtitleLang.value) {
                            withContext(Dispatchers.Main) {
                                RezkaService.setPreferredSubtitleLang(remoteSubLang)
                            }
                        }
                        if (remoteSubScale != null && remoteSubScale > 0.01f && kotlin.math.abs(remoteSubScale - RezkaService.subtitleTextScale.value) > 0.002f) {
                            withContext(Dispatchers.Main) {
                                RezkaService.setSubtitleTextScale(remoteSubScale)
                            }
                        }
                        if (remoteResize.isNotBlank() && remoteResize != RezkaService.defaultResizeMode.value) {
                            withContext(Dispatchers.Main) {
                                RezkaService.setDefaultResizeMode(remoteResize)
                            }
                        }
                        if (remoteTvMode.isNotBlank() && remoteTvMode != RezkaService.tvModePreference.value) {
                            withContext(Dispatchers.Main) {
                                RezkaService.setTvModePreference(remoteTvMode)
                            }
                        }
                        if (remoteGridMode.isNotBlank() && remoteGridMode != RezkaService.cardGridMode.value) {
                            withContext(Dispatchers.Main) {
                                RezkaService.setCardGridMode(remoteGridMode)
                            }
                        }
                    } else {
                        // В облаке ещё нет настроек пользователя - выгружаем текущие
                        onSettingsUpdated()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync settings from Firebase: ${e.message}")
            }

            // 3.5. Синхронизация индивидуальных настроек масштабирования для фильмов/сериалов
            try {
                val itemScaleReq = Request.Builder().url("$DATABASE_URL/users/$key/itemResizeModes.json").get().build()
                val itemScaleResp = httpClient.newCall(itemScaleReq).execute()
                if (itemScaleResp.isSuccessful) {
                    val bodyStr = itemScaleResp.body?.string()?.trim() ?: ""
                    if (bodyStr != "null" && bodyStr.isNotEmpty() && bodyStr.startsWith("{")) {
                        val obj = JSONObject(bodyStr)
                        val keys = obj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val itemObj = obj.optJSONObject(k)
                            val targetItemId = itemObj?.optString("itemId")?.ifBlank { k } ?: k
                            val targetMode = itemObj?.optString("mode") ?: ""
                            if (targetItemId.isNotBlank() && targetMode.isNotBlank()) {
                                RezkaService.setItemResizeMode(targetItemId, targetMode)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync item resize modes from Firebase: ${e.message}")
            }

            // 4. Синхронизация Истории поиска (недавние поисковые запросы)
            try {
                val searchReq = Request.Builder().url("$DATABASE_URL/users/$key/searchHistory.json").get().build()
                val searchResp = httpClient.newCall(searchReq).execute()
                val remoteQueries = mutableListOf<String>()
                if (searchResp.isSuccessful) {
                    val searchBody = searchResp.body?.string()?.trim() ?: ""
                    if (searchBody != "null" && searchBody.isNotEmpty()) {
                        if (searchBody.startsWith("[")) {
                            val arr = org.json.JSONArray(searchBody)
                            for (i in 0 until arr.length()) {
                                val q = arr.optString(i)
                                if (q.isNotBlank()) remoteQueries.add(q)
                            }
                        } else if (searchBody.startsWith("{")) {
                            val obj = JSONObject(searchBody)
                            val sortedKeys = obj.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: 0 }
                            for (k in sortedKeys) {
                                val q = obj.optString(k)
                                if (q.isNotBlank()) remoteQueries.add(q)
                            }
                        }
                    }
                }

                val localQueries = searchHistoryProvider?.invoke() ?: emptyList()
                val mergedQueries = mergeSearchHistories(localQueries, remoteQueries)

                withContext(Dispatchers.Main) {
                    onSearchHistorySynced?.invoke(mergedQueries)
                }

                if (mergedQueries != remoteQueries && mergedQueries.isNotEmpty()) {
                    onSearchHistoryUpdated(mergedQueries)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync search history from Firebase: ${e.message}")
            }

            // 5. Синхронизация Подписок на сериалы
            try {
                val subReq = Request.Builder().url("$DATABASE_URL/users/$key/subscriptions.json").get().build()
                val subResp = httpClient.newCall(subReq).execute()
                val remoteSubs = mutableListOf<SeriesSubscriptionEntity>()

                if (subResp.isSuccessful) {
                    val subBody = subResp.body?.string()?.trim() ?: ""
                    if (subBody != "null" && subBody.isNotEmpty()) {
                        val subJson = JSONObject(subBody)
                        val it = subJson.keys()
                        while (it.hasNext()) {
                            val k = it.next()
                            val obj = subJson.optJSONObject(k)
                            if (obj != null) {
                                remoteSubs.add(jsonToSubscription(obj))
                            }
                        }
                    }
                }

                val localSubs = repository.getAllSubscriptionsList()
                val localSubMap = localSubs.associateBy { it.id }

                // Слияние удаленных подписок с локальной базой
                val subsToInsert = mutableListOf<SeriesSubscriptionEntity>()
                for (rSub in remoteSubs) {
                    val lSub = localSubMap[rSub.id]
                    if (lSub == null) {
                        subsToInsert.add(rSub)
                    } else {
                        val rIsNewer = rSub.lastKnownSeason > lSub.lastKnownSeason ||
                                (rSub.lastKnownSeason == lSub.lastKnownSeason && rSub.lastKnownEpisode > lSub.lastKnownEpisode)
                        if (rIsNewer) {
                            subsToInsert.add(rSub)
                        }
                    }
                }
                if (subsToInsert.isNotEmpty()) {
                    repository.insertSubscriptions(subsToInsert)
                }

                // Дозаливаем локальные подписки, которых еще нет в облаке
                val remoteSubIds = remoteSubs.map { it.id }.toSet()
                val subUpdateJson = JSONObject()
                for (lSub in localSubs) {
                    if (lSub.id !in remoteSubIds) {
                        val safeId = safeFirebaseKey(lSub.id)
                        subUpdateJson.put(safeId, subscriptionToJson(lSub))
                    }
                }
                if (subUpdateJson.length() > 0) {
                    try {
                        val patchRequest = Request.Builder()
                            .url("$DATABASE_URL/users/$key/subscriptions.json")
                            .patch(subUpdateJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                            .build()
                        httpClient.newCall(patchRequest).execute().close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to patch subscriptions to Firebase: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync subscriptions from Firebase: ${e.message}")
            }

            Log.d(TAG, "Sync complete: ${remoteFavorites.size} favorites, ${remoteHistory.size} history items")
        } catch (e: Exception) {
            Log.e(TAG, "Error during syncAll: ${e.message}", e)
        } finally {
            _isSyncing.value = false
        }
    }

    private suspend fun uploadLocalDataToCloud(key: String, repository: RezkaRepository) {
        try {
            val localFavorites = repository.getAllFavoritesList()
            if (localFavorites.isNotEmpty()) {
                val favJson = JSONObject()
                for (fav in localFavorites) {
                    val safeId = safeFirebaseKey(fav.id)
                    favJson.put(safeId, favoriteToJson(fav))
                }
                val req = Request.Builder()
                    .url("$DATABASE_URL/users/$key/favorites.json")
                    .patch(favJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                httpClient.newCall(req).execute().close()
            }

            val localHistory = repository.getAllHistoryList()
            if (localHistory.isNotEmpty()) {
                val histJson = JSONObject()
                for (hist in localHistory) {
                    val safeId = safeFirebaseKey(hist.id)
                    histJson.put(safeId, historyToJson(hist))
                }
                val req = Request.Builder()
                    .url("$DATABASE_URL/users/$key/history.json")
                    .patch(histJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                httpClient.newCall(req).execute().close()
            }

            // Выгружаем историю поиска в облако
            val localSearch = searchHistoryProvider?.invoke() ?: emptyList()
            if (localSearch.isNotEmpty()) {
                onSearchHistoryUpdated(localSearch)
            }

            // Выгружаем подписки в облако
            val localSubs = repository.getAllSubscriptionsList()
            if (localSubs.isNotEmpty()) {
                val subJson = JSONObject()
                for (sub in localSubs) {
                    val safeId = safeFirebaseKey(sub.id)
                    subJson.put(safeId, subscriptionToJson(sub))
                }
                val req = Request.Builder()
                    .url("$DATABASE_URL/users/$key/subscriptions.json")
                    .patch(subJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                httpClient.newCall(req).execute().close()
            }

            // Выгружаем настройки в облако
            onSettingsUpdated()
        } catch (e: Exception) {
            Log.w(TAG, "Failed initial upload of local data: ${e.message}")
        }
    }

    private fun favoriteToJson(fav: FavoriteEntity): JSONObject {
        return JSONObject().apply {
            put("id", fav.id)
            put("title", fav.title)
            put("subtitle", fav.subtitle)
            put("imageUrl", fav.imageUrl)
            put("rating", fav.rating)
            put("url", fav.url)
            put("type", fav.type)
            put("timestamp", fav.timestamp)
        }
    }

    fun subscriptionToJson(sub: SeriesSubscriptionEntity): JSONObject {
        return JSONObject().apply {
            put("id", sub.id)
            put("title", sub.title)
            put("imageUrl", sub.imageUrl)
            put("url", sub.url)
            put("type", sub.type)
            put("numericPostId", sub.numericPostId)
            put("translatorId", sub.translatorId)
            put("lastKnownSeason", sub.lastKnownSeason)
            put("lastKnownEpisode", sub.lastKnownEpisode)
            put("lastEpisodeName", sub.lastEpisodeName)
            put("subscribedAt", sub.subscribedAt)
            put("lastCheckedAt", sub.lastCheckedAt)
            put("hasUnseenUpdate", sub.hasUnseenUpdate)
            put("lastNotifiedSeason", sub.lastNotifiedSeason)
            put("lastNotifiedEpisode", sub.lastNotifiedEpisode)
        }
    }

    fun jsonToSubscription(json: JSONObject): SeriesSubscriptionEntity {
        val rawUrl = json.optString("url", "")
        val typeStr = json.optString("type", "SERIES")
        val itemType = try { RezkaType.valueOf(typeStr) } catch (_: Exception) { RezkaType.SERIES }
        val id = json.optString("id", "")
        val adjustedUrl = RezkaService.adjustUrlToCurrentMirror(rawUrl, itemType, id)
        return SeriesSubscriptionEntity(
            id = id,
            title = json.optString("title", ""),
            imageUrl = json.optString("imageUrl", ""),
            url = adjustedUrl,
            type = typeStr,
            numericPostId = json.optString("numericPostId", ""),
            translatorId = json.optString("translatorId", ""),
            lastKnownSeason = json.optInt("lastKnownSeason", 1),
            lastKnownEpisode = json.optInt("lastKnownEpisode", 1),
            lastEpisodeName = json.optString("lastEpisodeName", ""),
            subscribedAt = json.optLong("subscribedAt", System.currentTimeMillis()),
            lastCheckedAt = json.optLong("lastCheckedAt", System.currentTimeMillis()),
            hasUnseenUpdate = json.optBoolean("hasUnseenUpdate", false),
            lastNotifiedSeason = json.optInt("lastNotifiedSeason", 0),
            lastNotifiedEpisode = json.optInt("lastNotifiedEpisode", 0)
        )
    }

    private fun jsonToFavorite(json: JSONObject): FavoriteEntity {
        val rawUrl = json.optString("url", "")
        val typeStr = json.optString("type", "MOVIE")
        val itemType = try { RezkaType.valueOf(typeStr) } catch (e: Exception) { RezkaType.MOVIE }
        val id = json.optString("id", "")
        val adjustedUrl = RezkaService.adjustUrlToCurrentMirror(rawUrl, itemType, id)
        return FavoriteEntity(
            id = id,
            title = json.optString("title", ""),
            subtitle = json.optString("subtitle", ""),
            imageUrl = json.optString("imageUrl", ""),
            rating = json.optString("rating", ""),
            url = adjustedUrl,
            type = typeStr,
            timestamp = json.optLong("timestamp", System.currentTimeMillis())
        )
    }

    private fun historyToJson(h: WatchHistoryEntity): JSONObject {
        return JSONObject().apply {
            put("id", h.id)
            put("itemId", h.itemId)
            put("title", h.title)
            put("imageUrl", h.imageUrl)
            put("subtitle", h.subtitle)
            put("url", h.url)
            val transIdNum = h.translatorId.toIntOrNull()
            if (transIdNum != null) {
                put("translatorId", transIdNum)
            }
            put("translatorName", h.translatorName)
            put("season", h.season)
            put("episode", h.episode)
            put("progressMs", h.progressMs)
            put("durationMs", h.durationMs)
            put("totalEpisodes", h.totalEpisodes)
            put("episodeIndex", h.episodeIndex)
            put("totalSeasons", h.totalSeasons)
            put("isFullyWatched", h.isFullyWatched)
            put("timestamp", h.timestamp)
        }
    }

    private fun jsonToHistory(json: JSONObject): WatchHistoryEntity {
        val rawUrl = json.optString("url", "")
        val season = json.optInt("season", 0)
        val itemType = if (season > 0) RezkaType.SERIES else RezkaType.MOVIE
        val itemId = json.optString("itemId", "")
        val adjustedUrl = RezkaService.adjustUrlToCurrentMirror(rawUrl, itemType, itemId)
        return WatchHistoryEntity(
            id = json.optString("id", ""),
            itemId = itemId,
            title = json.optString("title", ""),
            imageUrl = json.optString("imageUrl", ""),
            subtitle = json.optString("subtitle", ""),
            url = adjustedUrl,
            translatorId = json.optString("translatorId", ""),
            translatorName = json.optString("translatorName", ""),
            season = season,
            episode = json.optString("episode", ""),
            progressMs = json.optLong("progressMs", 0L),
            durationMs = json.optLong("durationMs", 0L),
            totalEpisodes = json.optInt("totalEpisodes", 0),
            episodeIndex = json.optInt("episodeIndex", 0),
            totalSeasons = json.optInt("totalSeasons", 0),
            isFullyWatched = json.optBoolean("isFullyWatched", false),
            timestamp = json.optLong("timestamp", System.currentTimeMillis())
        )
    }
}
