package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val httpClient = ResilientSslEngine.configure(
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
    ).build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var prefs: SharedPreferences? = null

    // Стабильный идентификатор устройства: настройки синхронизируются раздельно для каждого устройства
    // при одном и том же аккаунте (телефон / планшет / ТВ).
    private var deviceId: String = ""

    val currentDeviceId: String get() = deviceId

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
        // Стабильный составной ID устройства: sha256(ANDROID_ID | Build.FINGERPRINT).
        // Переживает переустановку приложения (ANDROID_ID стабилен при той же подписи),
        // привязан к конкретному устройству и прошивке, что исключает коллизии между
        // одинаковыми моделями и эмуляторами. Без UUID.
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Exception) {
            null
        }
        val idSeed = buildString {
            append(androidId?.takeIf { it.isNotBlank() } ?: "")
            append('|')
            append(Build.FINGERPRINT)
        }
        deviceId = if (idSeed.replace("|", "").isBlank()) {
            "android_unknown"
        } else {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(idSeed.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(64)
            for (b in bytes) {
                sb.append(String.format("%02x", b))
            }
            sb.toString()
        }
        val savedUser = prefs?.getString("auth_username", null)
        val savedKey = prefs?.getString("auth_user_key", null)
        val savedAvatar = prefs?.getString("auth_avatar", null)
        val savedRegAt = prefs?.getLong("auth_registered_at", 0L) ?: 0L
        val savedScheme = prefs?.getString("auth_scheme", null)

        // Сессия восстанавливается только для новой секретной схемы (метка v2).
        // Старые сессии (легаси-ключ) сбрасываются: пользователь один раз входит заново,
        // и его данные корректно мигрируют на секретный ключ.
        if (!savedUser.isNullOrBlank() && !savedKey.isNullOrBlank() && savedScheme == "v2") {
            if (!savedAvatar.isNullOrBlank()) {
                _currentUserAvatar.value = savedAvatar
            }
            if (savedRegAt > 0L) {
                _currentUserRegisteredAt.value = savedRegAt
            }

            _currentUser.value = savedUser
            _userKey.value = savedKey
            _isLoggedIn.value = true

            // Фоновая автоматическая синхронизация при запуске
            scope.launch {
                syncAll(repository)
            }
        } else {
            prefs?.edit()
                ?.remove("auth_username")
                ?.remove("auth_user_key")
                ?.remove("auth_avatar")
                ?.remove("auth_registered_at")
                ?.remove("auth_scheme")
                ?.apply()
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

    /**
     * Секретный ключ пользователя для новой схемы: SHA-256(логин + ":" + пароль).
     * Знает его только владелец аккаунта, поэтому путь недоступен посторонним даже при известном логине.
     */
    fun generateSecretKey(username: String, password: String): String {
        val normalized = username.trim().lowercase()
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest("$normalized:$password".toByteArray(Charsets.UTF_8))
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

        val key = generateSecretKey(cleanName, password)
        val legacyKey = generateUserKey(cleanName)

        try {
            // 1. Проверяем, существует ли уже такой пользователь (и в новой, и в старой схеме)
            val checkUrls = listOf(
                "$DATABASE_URL/users/$key/private/profile.json",
                "$DATABASE_URL/users/$legacyKey/profile.json"
            )
            for (checkUrl in checkUrls) {
                val checkRequest = Request.Builder().url(checkUrl).get().build()
                val checkResp = httpClient.newCall(checkRequest).execute()
                if (checkResp.isSuccessful) {
                    val body = checkResp.body?.string()?.trim() ?: "null"
                    if (body != "null" && body.isNotEmpty()) {
                        return@withContext Result.failure(Exception("Пользователь с таким логином уже зарегистрирован"))
                    }
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
                .url("$DATABASE_URL/users/$key/private/profile.json")
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
                        .url("$DATABASE_URL/users/$key/private/avatar.json")
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
                ?.putString("auth_scheme", "v2")
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
     * Вход пользователя: сначала новая секретная схема, затем фолбэк на старую
     * с автоматической миграцией данных на секретный путь.
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

        val key = generateSecretKey(cleanName, password)
        val legacyKey = generateUserKey(cleanName)

        try {
            // 1. Новая схема: секретный ключ зависит от пароля, путь никто не знает.
            var profile: JSONObject? = null
            var fetchedAvatar: String? = null
            var registeredAt = 0L
            var realUsername = cleanName

            runCatching {
                val resp = httpClient.newCall(
                    Request.Builder().url("$DATABASE_URL/users/$key/private/profile.json").get().build()
                ).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string()?.trim() ?: "null"
                    if (body != "null" && body.isNotEmpty()) {
                        profile = JSONObject(body)
                    }
                }
            }

            if (profile != null) {
                val storedHash = profile!!.optString("passwordHash", "")
                val storedSalt = profile!!.optString("salt", "")
                realUsername = profile!!.optString("username", cleanName).ifBlank { cleanName }
                registeredAt = profile!!.optLong("registeredAt", 0L)
                if (storedHash.isEmpty() || storedSalt.isEmpty()) {
                    return@withContext Result.failure(Exception("Ошибка структуры профиля в базе данных"))
                }
                val computedHash = hashPassword(password, storedSalt)
                if (computedHash != storedHash) {
                    return@withContext Result.failure(Exception("Неверный пароль"))
                }
                try {
                    val avResp = httpClient.newCall(
                        Request.Builder().url("$DATABASE_URL/users/$key/private/avatar.json").get().build()
                    ).execute()
                    if (avResp.isSuccessful) {
                        val avBody = avResp.body?.string()?.trim() ?: "null"
                        if (avBody != "null" && avBody.isNotEmpty()) {
                            fetchedAvatar = decodeJsonString(avBody)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error fetching avatar", e)
                }
            } else {
                // 2. Фолбэк: старая схема (пользователь ещё не мигрировал).
                val legacyResp = httpClient.newCall(
                    Request.Builder().url("$DATABASE_URL/users/$legacyKey/profile.json").get().build()
                ).execute()
                if (!legacyResp.isSuccessful) {
                    return@withContext Result.failure(Exception("Ошибка базы данных: HTTP ${legacyResp.code}"))
                }
                val body = legacyResp.body?.string()?.trim() ?: "null"
                if (body == "null" || body.isEmpty()) {
                    return@withContext Result.failure(Exception("Пользователь с таким логином не найден"))
                }
                val json = JSONObject(body)
                val storedHash = json.optString("passwordHash", "")
                val storedSalt = json.optString("salt", "")
                realUsername = json.optString("username", cleanName).ifBlank { cleanName }
                registeredAt = json.optLong("registeredAt", 0L)
                if (storedHash.isEmpty() || storedSalt.isEmpty()) {
                    return@withContext Result.failure(Exception("Ошибка структуры профиля в базе данных"))
                }
                val computedHash = hashPassword(password, storedSalt)
                if (computedHash != storedHash) {
                    return@withContext Result.failure(Exception("Неверный пароль"))
                }
                try {
                    val avResp = httpClient.newCall(
                        Request.Builder().url("$DATABASE_URL/users/$legacyKey/avatar.json").get().build()
                    ).execute()
                    if (avResp.isSuccessful) {
                        val avBody = avResp.body?.string()?.trim() ?: "null"
                        if (avBody != "null" && avBody.isNotEmpty()) {
                            fetchedAvatar = decodeJsonString(avBody)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error fetching avatar", e)
                }

                // 3. Автомиграция: переносим все данные на секретный путь.
                migrateLegacyUser(legacyKey, key)
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
                ?.putString("auth_scheme", "v2")
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

    /** Распаковывает JSON-строку из RTDB (например, аватар) в обычную строку. */
    private fun decodeJsonString(raw: String): String {
        val t = raw.trim()
        return if (t.startsWith("\"") && t.endsWith("\"")) {
            t.substring(1, t.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\/", "/")
        } else {
            t
        }
    }

    /** Переносит данные старой схемы в новую секретную ветку. */
    private suspend fun migrateLegacyUser(legacyKey: String, newKey: String) {
        val fields = listOf(
            "profile", "avatar", "settings", "favorites", "history",
            "subscriptions", "searchHistory", "voice_commands", "voiceCommands",
            "itemResizeModes", "updatedAt"
        )
        for (field in fields) {
            try {
                val resp = httpClient.newCall(
                    Request.Builder().url("$DATABASE_URL/users/$legacyKey/$field.json").get().build()
                ).execute()
                if (!resp.isSuccessful) continue
                val body = resp.body?.string()?.trim() ?: "null"
                resp.close()
                if (body == "null" || body.isEmpty()) continue
                val putReq = Request.Builder()
                    .url("$DATABASE_URL/users/$newKey/private/$field.json")
                    .put(body.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                executeAndLog(putReq, "migrate_$field")
            } catch (e: Exception) {
                Log.w(TAG, "Migration failed for $field: ${e.message}")
            }
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
                    .url("$DATABASE_URL/users/$key/private/avatar.json")
                    .put(JSONObject.quote(avatar).toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            } else {
                Request.Builder()
                    .url("$DATABASE_URL/users/$key/private/avatar.json")
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

    private fun executeAndLog(request: Request, operationName: String): Boolean {
        return try {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string()?.take(300) ?: ""
                    Log.w(TAG, "[$operationName] Failed with HTTP ${resp.code}: $err")
                    false
                } else {
                    true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[$operationName] Network error: ${e.message}")
            false
        }
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
                    .url("$DATABASE_URL/users/$key/private/favorites/$safeId.json")
                    .put(json.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                executeAndLog(request, "onFavoriteAdded")
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
                    .url("$DATABASE_URL/users/$key/private/favorites/$safeId.json")
                    .delete()
                    .build()
                executeAndLog(request, "onFavoriteRemoved")
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
                    .url("$DATABASE_URL/users/$key/private/subscriptions/$safeId.json")
                    .put(json.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                executeAndLog(request, "onSubscriptionAdded")
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
                    .url("$DATABASE_URL/users/$key/private/subscriptions/$safeId.json")
                    .delete()
                    .build()
                executeAndLog(request, "onSubscriptionRemoved")
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
                    .url("$DATABASE_URL/users/$key/private/subscriptions/$safeId.json")
                    .patch(patchJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                executeAndLog(request, "onSubscriptionProgressUpdated")
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
                        .url("$DATABASE_URL/users/$key/private/history/$safeId.json")
                        .put(json.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .build()
                    executeAndLog(request, "flushProgress_$safeId")
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
                    .url("$DATABASE_URL/users/$key/private/itemResizeModes/$safeId.json")
                    .put(json.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                executeAndLog(request, "onItemResizeModeUpdated")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync item resize mode for $safeId: ${e.message}")
            }
        }
    }

    // Поля настроек, синхронизируемые раздельно по устройствам.
    // Каждое поле хранит строку-токен: "deviceId=<id>(<значение>),deviceId=<id>(<значение>)"
    private val SETTING_FIELDS = arrayOf(
        "mirror", "defaultQuality", "autoNextEpisode", "dirtyHandsMode",
        "preferredSubtitleLang", "subtitleTextScale", "resizeMode", "tvMode",
        "cardGridMode", "seriesBadgeMode", "defaultCatalogType", "defaultCatalogSection",
        "defaultCatalogGenre", "defaultCatalogCountry"
    )

    // Сериализует сетевые записи настроек (всегда идут под мьютексом).
    private val settingsWriteMutex = Mutex()

    private suspend fun fetchSettingsRoot(key: String): JSONObject {
        return try {
            val resp = httpClient.newCall(
                Request.Builder().url("$DATABASE_URL/users/$key/private/settings.json").get().build()
            ).execute()
            if (resp.isSuccessful) {
                val b = resp.body?.string()?.trim().orEmpty()
                if (b.isNotEmpty() && b != "null") JSONObject(b) else JSONObject()
            } else JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }
    }

    // Токены настроек пишутся с префиксом deviceId=. Исторически (до перехода на
    // deviceId=) в облаке могли остаться токены со старым префиксом AndroidId= —
    // оба префикса распознаются, чтобы значение профиля не «затерялось».
    // Символы ( ) внутри значений кодируются (percent-encoding), чтобы не ломали парсер.
    private fun encodeTokenValue(value: String): String =
        value.replace("%", "%25").replace("(", "%28").replace(")", "%29")

    private fun decodeTokenValue(encoded: String): String =
        encoded.replace("%28", "(").replace("%29", ")").replace("%25", "%")

    private fun isTokenized(raw: String): Boolean =
        raw.contains("deviceId=") || raw.contains("AndroidId=")

    /** Устойчивый парсер токенов настроек вида "deviceId=<id>(<значение>),…". */
    private fun parseTokens(raw: String): List<Pair<String, String>> {
        if (raw.isEmpty()) return emptyList()
        val result = mutableListOf<Pair<String, String>>()
        var i = 0
        val n = raw.length
        while (i < n) {
            val di = raw.indexOf("deviceId=", i)
            val ai = raw.indexOf("AndroidId=", i)
            val start = when {
                di == -1 && ai == -1 -> return result
                di == -1 -> ai
                ai == -1 -> di
                else -> if (di < ai) di else ai
            }
            val markerLen = if (raw.startsWith("AndroidId=", start)) 10 else 9
            val idStart = start + markerLen
            val open = raw.indexOf('(', idStart)
            if (open == -1) break
            val id = raw.substring(idStart, open)
            var depth = 0
            var j = open
            var closed = false
            while (j < n) {
                when (raw[j]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) {
                            closed = true
                            break
                        }
                    }
                }
                j++
            }
            if (!closed) break
            val value = raw.substring(open + 1, j)
            result.add(id to decodeTokenValue(value))
            i = j + 1
        }
        return result
    }

    private fun upsertToken(current: String, value: String): String {
        val entries = parseTokens(current).toMutableList()
        entries.removeAll { it.first == deviceId }
        entries.add(deviceId to encodeTokenValue(value))
        return entries.joinToString(",") { "deviceId=${it.first}(${it.second})" }
    }

    /** Достаёт значения токенов конкретного устройства из «сырых» значений полей. */
    private fun tokenValueMap(root: JSONObject, targetId: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        for (field in SETTING_FIELDS) {
            val mine = parseTokens(root.optString(field, "")).firstOrNull { it.first == targetId }?.second
            if (mine != null) out[field] = mine
        }
        return out
    }

    /** Значения первого попавшегося токена любого устройства (фоллбэк). */
    private fun anyTokenValueMap(root: JSONObject): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        for (field in SETTING_FIELDS) {
            val first = parseTokens(root.optString(field, "")).firstOrNull()?.second
            if (first != null) out[field] = first
        }
        return out
    }

    /** Первый deviceId, встреченный среди токенов всех полей. */
    private fun firstTokenDeviceId(root: JSONObject): String? {
        for (field in SETTING_FIELDS) {
            val id = parseTokens(root.optString(field, "")).firstOrNull()?.first
            if (id != null) return id
        }
        return null
    }

    /** Есть ли старые «плоские» значения без токенов. */
    private fun hasLegacyFlat(root: JSONObject): Boolean =
        SETTING_FIELDS.any { f ->
            val raw = root.optString(f, "")
            raw.isNotEmpty() && !isTokenized(raw)
        }

    // Правила БД для tvMode допускают только auto | tv | mobile,
    // локально приложение хранит auto | force_tv | force_mobile.
    private fun normalizeTvModeForCloud(local: String): String = when (local) {
        "force_tv" -> "tv"
        "force_mobile" -> "mobile"
        "tv", "mobile" -> local
        else -> "auto"
    }

    private fun localTvModeFromCloud(cloud: String): String = when (cloud) {
        "tv" -> "force_tv"
        "mobile" -> "force_mobile"
        else -> "auto"
    }

    /** Текущие локальные значения как «сырые» строки (уже в облачных форматах). */
    private fun currentRawValues(): LinkedHashMap<String, String> = linkedMapOf(
        "mirror" to RezkaService.currentMirror.value,
        "defaultQuality" to RezkaService.defaultQuality.value,
        "autoNextEpisode" to RezkaService.autoNextEpisode.value.toString(),
        "dirtyHandsMode" to RezkaService.dirtyHandsMode.value.toString(),
        "preferredSubtitleLang" to RezkaService.preferredSubtitleLang.value,
        "subtitleTextScale" to RezkaService.subtitleTextScale.value.coerceIn(0.01f, 0.2f).toString(),
        "resizeMode" to RezkaService.defaultResizeMode.value,
        "tvMode" to normalizeTvModeForCloud(RezkaService.tvModePreference.value),
        "cardGridMode" to RezkaService.cardGridMode.value,
        "seriesBadgeMode" to RezkaService.seriesBadgeMode.value.id,
        "defaultCatalogType" to (RezkaService.defaultCatalogType.value?.name ?: "ALL"),
        "defaultCatalogSection" to RezkaService.defaultCatalogSection.value.name,
        "defaultCatalogGenre" to RezkaService.defaultCatalogGenre.value,
        "defaultCatalogCountry" to RezkaService.defaultCatalogCountry.value
    )

    /** «Сырые» значения из старых плоских полей облака (первое устройство).
     *  Поля, уже содержащие токены, не трогаем — для них берём текущее локальное значение. */
    private fun legacyRawValues(root: JSONObject): LinkedHashMap<String, String> {
        val current = currentRawValues()
        fun flat(field: String): String {
            val raw = root.optString(field, "")
            return if (raw.isNotEmpty() && !isTokenized(raw)) raw else current[field] ?: ""
        }
        val scale = flat("subtitleTextScale").toDoubleOrNull()
            ?.coerceIn(0.01, 0.2)?.toString() ?: current["subtitleTextScale"]!!
        return linkedMapOf(
            "mirror" to flat("mirror"),
            "defaultQuality" to flat("defaultQuality"),
            "autoNextEpisode" to flat("autoNextEpisode"),
            "dirtyHandsMode" to flat("dirtyHandsMode"),
            "preferredSubtitleLang" to flat("preferredSubtitleLang"),
            "subtitleTextScale" to scale,
            "resizeMode" to flat("resizeMode"),
            "tvMode" to normalizeTvModeForCloud(flat("tvMode")),
            "cardGridMode" to flat("cardGridMode"),
            "seriesBadgeMode" to flat("seriesBadgeMode"),
            "defaultCatalogType" to flat("defaultCatalogType"),
            "defaultCatalogSection" to flat("defaultCatalogSection"),
            "defaultCatalogGenre" to flat("defaultCatalogGenre"),
            "defaultCatalogCountry" to flat("defaultCatalogCountry")
        )
    }

    /**
     * Запись настроек: каждое поле — строка с токенами устройств,
     * например defaultCatalogType: "deviceId=12345(CARTOON),deviceId=6789(MOVIE)".
     * firstDeviceId — тоже значение вида "deviceId=<id>". Никаких новых веток БД.
     */
    private suspend fun writeDeviceSettings(rawValues: Map<String, String>, markFirst: Boolean): Boolean {
        val key = _userKey.value ?: return false
        var written = false
        settingsWriteMutex.withLock {
            try {
                val root = fetchSettingsRoot(key)
                val firstRaw = root.optString("firstDeviceId", "")
                val existingFirst = parseTokens(firstRaw).firstOrNull()?.first
                val firstId = when {
                    existingFirst != null -> existingFirst
                    markFirst -> deviceId
                    else -> firstTokenDeviceId(root) ?: deviceId
                }

                val body = JSONObject()
                for ((field, value) in rawValues) {
                    body.put(field, upsertToken(root.optString(field, ""), value))
                }
                if (existingFirst != null) {
                    body.put("firstDeviceId", firstRaw)
                } else {
                    body.put("firstDeviceId", "deviceId=$firstId")
                }
                body.put("updatedAt", System.currentTimeMillis())
                val bodyStr = body.toString()
                val url = "$DATABASE_URL/users/$key/private/settings.json"

                // Сначала PATCH (частичное обновление), при неудаче — полная замена PUT
                val patchReq = Request.Builder().url(url)
                    .patch(bodyStr.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                written = executeAndLog(patchReq, "writeDeviceSettings_patch")

                if (!written) {
                    val putReq = Request.Builder().url(url)
                        .put(bodyStr.toRequestBody(JSON_MEDIA_TYPE))
                        .build()
                    written = executeAndLog(putReq, "writeDeviceSettings_put")
                }
            } catch (_: Exception) {
                // молча: неудача записи настроек не должна ронять синхронизацию
            }
        }
        return written
    }

    /** Убирает поле зеркала из набора значений (для наследования/легаси). */
    private fun withoutMirror(values: Map<String, String>): Map<String, String> =
        values.filterKeys { it != "mirror" }

    /**
     * Очищает «сырое» значение настройки от любых признаков токенов синхронизации.
     * Если в облако по какой-то причине попал сырой токен (например, старый AndroidId=…),
     * извлекаем из него настоящее значение. Пустые/мусорные значения отбрасываются.
     */
    private fun unwrapAppliedValue(field: String, raw: String): String {
        var s = raw.trim()
        if (s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length - 1).trim()
        }
        var prev: String? = null
        while (s != prev) {
            prev = s
            if (!isTokenized(s)) break
            val parsed = parseTokens(s)
            val mine = parsed.firstOrNull { it.first == deviceId } ?: parsed.firstOrNull()
            if (mine == null) break
            s = mine.second.trim()
        }
        return if (s.length <= 80) s else ""
    }

    /** Санитизация всей карты настроек перед применением/записью. */
    private fun sanitizeSettingsValues(values: Map<String, String>): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((field, raw) in values) {
            out[field] = unwrapAppliedValue(field, raw)
        }
        return out
    }

    /**
     * Применяет «сырые» значения (из токенов или legacy) к локальным настройкам.
     */
    private suspend fun applySettingsValues(values: Map<String, String>) {
        if (values.isEmpty()) return

        // Зеркало применяется из облака ТОЛЬКО если это значение собственного токена устройства
        // (см. syncAll — из наследования/легаси зеркало исключается). Чужое устройство
        // не может молча сменить зеркало этого устройства.
        values["mirror"]?.takeIf { it.isNotBlank() && it != RezkaService.currentMirror.value }
            ?.let { withContext(Dispatchers.Main) { RezkaService.setMirror(it) } }
        values["defaultQuality"]?.takeIf { it.isNotBlank() && it != RezkaService.defaultQuality.value }
            ?.let { withContext(Dispatchers.Main) { RezkaService.setDefaultQuality(it) } }
        values["autoNextEpisode"]?.let {
            val b = it.equals("true", ignoreCase = true)
            if (b != RezkaService.autoNextEpisode.value) {
                withContext(Dispatchers.Main) { RezkaService.setAutoNextEpisode(b) }
            }
        }
        values["dirtyHandsMode"]?.let {
            val b = it.equals("true", ignoreCase = true)
            if (b != RezkaService.dirtyHandsMode.value) {
                withContext(Dispatchers.Main) { RezkaService.setDirtyHandsMode(b) }
            }
        }
        values["preferredSubtitleLang"]?.takeIf { it.isNotBlank() && it != RezkaService.preferredSubtitleLang.value }
            ?.let { withContext(Dispatchers.Main) { RezkaService.setPreferredSubtitleLang(it) } }
        values["subtitleTextScale"]?.toFloatOrNull()?.let {
            val clamped = it.coerceIn(0.01f, 0.2f)
            if (clamped != RezkaService.subtitleTextScale.value) {
                withContext(Dispatchers.Main) { RezkaService.setSubtitleTextScale(clamped) }
            }
        }
        values["resizeMode"]?.takeIf { it.isNotBlank() && it != RezkaService.defaultResizeMode.value }
            ?.let { withContext(Dispatchers.Main) { RezkaService.setDefaultResizeMode(it) } }
        values["tvMode"]?.takeIf { it.isNotBlank() }?.let {
            val local = localTvModeFromCloud(it)
            if (local != RezkaService.tvModePreference.value) {
                withContext(Dispatchers.Main) { RezkaService.setTvModePreference(local) }
            }
        }
        values["cardGridMode"]?.takeIf { it.isNotBlank() && it != RezkaService.cardGridMode.value }
            ?.let { withContext(Dispatchers.Main) { RezkaService.setCardGridMode(it) } }
        values["seriesBadgeMode"]?.takeIf { it.isNotBlank() }?.let {
            val mode = com.example.ui.util.SeriesBadgeMode.fromId(it)
            if (mode != RezkaService.seriesBadgeMode.value) {
                withContext(Dispatchers.Main) { RezkaService.setSeriesBadgeMode(mode) }
            }
        }
        values["defaultCatalogType"]?.takeIf { it.isNotBlank() }?.let { remoteType ->
            try {
                val t = if (remoteType.equals("ALL", ignoreCase = true)) null else RezkaType.valueOf(remoteType)
                if (t != RezkaType.COLLECTIONS && t != RezkaService.defaultCatalogType.value) {
                    withContext(Dispatchers.Main) { RezkaService.setDefaultCatalogType(t) }
                }
            } catch (_: Exception) {}
        }
        values["defaultCatalogSection"]?.takeIf { it.isNotBlank() }?.let { remoteSection ->
            try {
                val s = SectionType.valueOf(remoteSection)
                if (s != RezkaService.defaultCatalogSection.value) {
                    withContext(Dispatchers.Main) { RezkaService.setDefaultCatalogSection(s) }
                }
            } catch (_: Exception) {}
        }
        values["defaultCatalogGenre"]?.let {
            if (it != RezkaService.defaultCatalogGenre.value) {
                withContext(Dispatchers.Main) { RezkaService.setDefaultCatalogGenre(it) }
            }
        }
        values["defaultCatalogCountry"]?.let {
            if (it != RezkaService.defaultCatalogCountry.value) {
                withContext(Dispatchers.Main) { RezkaService.setDefaultCatalogCountry(it) }
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
        dirtyHandsMode: Boolean = RezkaService.dirtyHandsMode.value,
        preferredSubtitleLang: String = RezkaService.preferredSubtitleLang.value,
        subtitleTextScale: Float = RezkaService.subtitleTextScale.value,
        resizeMode: String = RezkaService.defaultResizeMode.value,
        tvMode: String = RezkaService.tvModePreference.value,
        cardGridMode: String = RezkaService.cardGridMode.value,
        seriesBadgeMode: String = RezkaService.seriesBadgeMode.value.id,
        defaultCatalogType: String = RezkaService.defaultCatalogType.value?.name ?: "ALL",
        defaultCatalogSection: String = RezkaService.defaultCatalogSection.value.name,
        defaultCatalogGenre: String = RezkaService.defaultCatalogGenre.value,
        defaultCatalogCountry: String = RezkaService.defaultCatalogCountry.value
    ) {
        val key = _userKey.value ?: return
        scope.launch {
            val raw = linkedMapOf(
                "mirror" to mirror,
                "defaultQuality" to quality,
                "autoNextEpisode" to autoNextEpisode.toString(),
                "dirtyHandsMode" to dirtyHandsMode.toString(),
                "preferredSubtitleLang" to preferredSubtitleLang,
                "subtitleTextScale" to subtitleTextScale.coerceIn(0.01f, 0.2f).toString(),
                "resizeMode" to resizeMode,
                "tvMode" to normalizeTvModeForCloud(tvMode),
                "cardGridMode" to cardGridMode,
                "seriesBadgeMode" to seriesBadgeMode,
                "defaultCatalogType" to defaultCatalogType,
                "defaultCatalogSection" to defaultCatalogSection,
                "defaultCatalogGenre" to defaultCatalogGenre,
                "defaultCatalogCountry" to defaultCatalogCountry
            )
            writeDeviceSettings(raw, markFirst = false)
        }
    }

    /**
     * Выгрузка сжатого ZLIB образца голосовой команды в облако Firebase.
     * Занимает считанные сотни байт в базе данных.
     */
    fun onVoiceCommandUpdated(actionId: String, compressedBase64: String) {
        val key = _userKey.value ?: return
        scope.launch {
            try {
                val json = JSONObject().apply {
                    put(actionId, compressedBase64)
                }
                val request = Request.Builder()
                    .url("$DATABASE_URL/users/$key/private/voice_commands.json")
                    .patch(json.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                executeAndLog(request, "onVoiceCommandUpdated")
                Log.d(TAG, "Uploaded compressed voice command $actionId to cloud (~${compressedBase64.length} chars)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to upload voice command to Firebase: ${e.message}")
            }
        }
    }

    /**
     * Удаление голосовой команды из облачной базы данных Firebase.
     */
    fun onVoiceCommandDeleted(actionId: String) {
        val key = _userKey.value ?: return
        scope.launch {
            try {
                val request = Request.Builder()
                    .url("$DATABASE_URL/users/$key/private/voice_commands/$actionId.json")
                    .delete()
                    .build()
                executeAndLog(request, "onVoiceCommandDeleted")
                Log.d(TAG, "Deleted voice command $actionId from cloud")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete voice command from Firebase: ${e.message}")
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
                    .url("$DATABASE_URL/users/$key/private/history/$safeId.json")
                    .delete()
                    .build()
                executeAndLog(request, "onHistoryDeleted")
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
                    .url("$DATABASE_URL/users/$key/private/history.json")
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
                                .url("$DATABASE_URL/users/$key/private/history/$k.json")
                                .delete()
                                .build()
                            executeAndLog(delReq, "deleteHistory_$k")
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
                    .url("$DATABASE_URL/users/$key/private/history.json")
                    .delete()
                    .build()
                executeAndLog(request, "onAllHistoryCleared")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear all history: ${e.message}")
            }
        }
    }

    /**
     * Сохранение истории поиска в облако Firebase RTDB.
     * Сохраняет структуру объектов { query: String, timestamp: Long } с безопасными ключами,
     * что строго удовлетворяет правилам безопасности и ускоряет индексацию без лишних аллокаций памяти.
     */
    fun onSearchHistoryUpdated(queries: List<String>) {
        val key = _userKey.value ?: return
        scope.launch {
            try {
                val historyObj = JSONObject()
                val now = System.currentTimeMillis()
                queries.take(15).forEachIndexed { index, query ->
                    val clean = query.trim()
                    if (clean.isNotEmpty()) {
                        val itemObj = JSONObject().apply {
                            put("query", clean)
                            put("timestamp", now - index * 1000L)
                        }
                        val safeId = safeFirebaseKey(clean)
                        historyObj.put(safeId, itemObj)
                    }
                }
                val request = Request.Builder()
                    .url("$DATABASE_URL/users/$key/private/searchHistory.json")
                    .put(historyObj.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                executeAndLog(request, "onSearchHistoryUpdated")
                Log.d(TAG, "Search history synced to cloud: ${historyObj.length()} queries")
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
                    .url("$DATABASE_URL/users/$key/private/searchHistory.json")
                    .delete()
                    .build()
                executeAndLog(request, "onSearchHistoryCleared")
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
                val profReq = Request.Builder().url("$DATABASE_URL/users/$key/private/profile.json").get().build()
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
            try {
            val favReq = Request.Builder().url("$DATABASE_URL/users/$key/private/favorites.json").get().build()
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
                        .url("$DATABASE_URL/users/$key/private/favorites.json")
                        .patch(favUpdateJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .build()
                    executeAndLog(patchRequest, "syncAll_patchFavorites")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to patch favorites: ${e.message}")
                }
            }

            } catch (_: Exception) {
                // продолжаем, чтобы сбой избранного не блокировал синхронизацию настроек
            }

            // 2. Синхронизация Истории просмотров (серии, сезоны, секунды прогресса)
            try {
            val histReq = Request.Builder().url("$DATABASE_URL/users/$key/private/history.json").get().build()
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
                            remoteHistory.add(jsonToHistory(obj, k))
                        }
                    }
                }
            }

            val localHistory = repository.getAllHistoryList()
            val localHistMap = localHistory.associateBy { it.id }
            val remoteHistMap = remoteHistory.associateBy { it.id }

            val histToUpdateLocally = mutableListOf<WatchHistoryEntity>()
            val histUpdateJson = JSONObject()

            // 1. Проверяем удаленные записи: если в локальной базе нет или удаленная новее
            for (remoteItem in remoteHistory) {
                val localItem = localHistMap[remoteItem.id]
                if (localItem == null) {
                    histToUpdateLocally.add(remoteItem)
                } else {
                    if (remoteItem.timestamp > localItem.timestamp) {
                        histToUpdateLocally.add(remoteItem)
                    } else if (localItem.timestamp > remoteItem.timestamp) {
                        val safeId = safeFirebaseKey(localItem.id)
                        histUpdateJson.put(safeId, historyToJson(localItem))
                    } else if (localItem.isFullyWatched != remoteItem.isFullyWatched) {
                        if (localItem.isFullyWatched) {
                            val safeId = safeFirebaseKey(localItem.id)
                            histUpdateJson.put(safeId, historyToJson(localItem))
                        } else {
                            histToUpdateLocally.add(remoteItem)
                        }
                    } else if (remoteItem.progressMs > localItem.progressMs) {
                        histToUpdateLocally.add(remoteItem)
                    }
                }
            }

            // 2. Дозаливаем в облако локальные записи, которых еще нет в облаке
            for (localItem in localHistory) {
                if (!remoteHistMap.containsKey(localItem.id)) {
                    val safeId = safeFirebaseKey(localItem.id)
                    histUpdateJson.put(safeId, historyToJson(localItem))
                }
            }

            if (histToUpdateLocally.isNotEmpty()) {
                repository.insertHistoryList(histToUpdateLocally)
            }

            if (histUpdateJson.length() > 0) {
                try {
                    val patchRequest = Request.Builder()
                        .url("$DATABASE_URL/users/$key/private/history.json")
                        .patch(histUpdateJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .build()
                    executeAndLog(patchRequest, "syncAll_patchHistory")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to patch history: ${e.message}")
                }
            }

            } catch (_: Exception) {
                // продолжаем, чтобы сбой истории не блокировал синхронизацию настроек
            }

            // 3. Синхронизация Настроек — раздельно по устройствам ВНУТРИ значений полей:
            //    "deviceId=123(CARTOON),deviceId=456(MOVIE)". Никаких новых веток БД.
            try {
                val setReq = Request.Builder().url("$DATABASE_URL/users/$key/private/settings.json").get().build()
                val setResp = httpClient.newCall(setReq).execute()
                if (setResp.isSuccessful) {
                    val setBody = setResp.body?.string()?.trim() ?: ""
                    if (setBody != "null" && setBody.isNotEmpty()) {
                        val rootJson = JSONObject(setBody)
                        val myTokens = sanitizeSettingsValues(tokenValueMap(rootJson, deviceId))
                        val firstId = firstTokenDeviceId(rootJson)

                        when {
                            // 1. Старые «плоские» значения без токенов — миграция: становимся первым устройством.
                            // Зеркало исключается: легаси не должно молча сменить локальный выбор.
                            hasLegacyFlat(rootJson) -> {
                                val legacy = legacyRawValues(rootJson)
                                val legacySafe = withoutMirror(sanitizeSettingsValues(legacy))
                                applySettingsValues(legacySafe)
                                writeDeviceSettings(legacySafe, markFirst = true)
                            }

                            // 2. У этого устройства уже есть токены — применяем свои настройки.
                            myTokens.isNotEmpty() -> {
                                applySettingsValues(myTokens)
                                if (myTokens.size < SETTING_FIELDS.size) {
                                    writeDeviceSettings(currentRawValues(), markFirst = false)
                                }
                            }

                            // 3. Есть токены других устройств — наследуем первое устройство и создаём свои токены.
                            // Зеркало исключается: чужое устройство не может сменить зеркало этого.
                            firstId != null -> {
                                val inherited = sanitizeSettingsValues(
                                    tokenValueMap(rootJson, firstId).ifEmpty { anyTokenValueMap(rootJson) }
                                )
                                val inheritedSafe = withoutMirror(inherited)
                                if (inheritedSafe.isNotEmpty()) applySettingsValues(inheritedSafe)
                                writeDeviceSettings(currentRawValues(), markFirst = false)
                            }

                            // 4. Совсем нет данных — создаём токены с текущими настройками.
                            else -> {
                                writeDeviceSettings(currentRawValues(), markFirst = true)
                            }
                        }
                    } else {
                        writeDeviceSettings(currentRawValues(), markFirst = true)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync settings from Firebase: ${e.message}")
            }

            // 3.5. Синхронизация индивидуальных настроек масштабирования для фильмов/сериалов
            try {
                val itemScaleReq = Request.Builder().url("$DATABASE_URL/users/$key/private/itemResizeModes.json").get().build()
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
                val searchReq = Request.Builder().url("$DATABASE_URL/users/$key/private/searchHistory.json").get().build()
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
                            val listWithTime = mutableListOf<Pair<String, Long>>()
                            val itKeys = obj.keys()
                            while (itKeys.hasNext()) {
                                val k = itKeys.next()
                                val subObj = obj.optJSONObject(k)
                                if (subObj != null) {
                                    val q = subObj.optString("query")
                                    val ts = subObj.optLong("timestamp", 0L)
                                    if (q.isNotBlank()) listWithTime.add(q to ts)
                                } else {
                                    val q = obj.optString(k)
                                    if (q.isNotBlank()) listWithTime.add(q to (k.toLongOrNull() ?: 0L))
                                }
                            }
                            listWithTime.sortByDescending { it.second }
                            for ((q, _) in listWithTime) {
                                remoteQueries.add(q)
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
                val subReq = Request.Builder().url("$DATABASE_URL/users/$key/private/subscriptions.json").get().build()
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
                            .url("$DATABASE_URL/users/$key/private/subscriptions.json")
                            .patch(subUpdateJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                            .build()
                        executeAndLog(patchRequest, "syncAll_patchSubscriptions")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to patch subscriptions to Firebase: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync subscriptions from Firebase: ${e.message}")
            }

            // 6. Синхронизация голосовых команд режима «Грязные руки» (со сжатием ZLIB)
            try {
                val voiceReq = Request.Builder().url("$DATABASE_URL/users/$key/private/voice_commands.json").get().build()
                val voiceResp = httpClient.newCall(voiceReq).execute()
                if (voiceResp.isSuccessful) {
                    val voiceBody = voiceResp.body?.string()?.trim() ?: ""
                    if (voiceBody != "null" && voiceBody.isNotEmpty() && voiceBody.startsWith("{")) {
                        val cloudCommands = JSONObject(voiceBody)
                        DirtyHandsEngine.importCloudVoiceCommands(cloudCommands)
                    }
                }
                // Дозаливаем локальные команды, которых ещё нет в облаке
                val localCommands = DirtyHandsEngine.getAllCompressedCommands()
                if (localCommands.isNotEmpty()) {
                    val patchJson = JSONObject()
                    for ((cmdAction, cmdData) in localCommands) {
                        patchJson.put(cmdAction, cmdData)
                    }
                    val patchReq = Request.Builder()
                        .url("$DATABASE_URL/users/$key/private/voice_commands.json")
                        .patch(patchJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .build()
                    executeAndLog(patchReq, "syncAll_patchVoiceCommands")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync voice commands from Firebase: ${e.message}")
            }
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
                    .url("$DATABASE_URL/users/$key/private/favorites.json")
                    .patch(favJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                executeAndLog(req, "uploadLocalData_favorites")
            }

            val localHistory = repository.getAllHistoryList()
            if (localHistory.isNotEmpty()) {
                val histJson = JSONObject()
                for (hist in localHistory) {
                    val safeId = safeFirebaseKey(hist.id)
                    histJson.put(safeId, historyToJson(hist))
                }
                val req = Request.Builder()
                    .url("$DATABASE_URL/users/$key/private/history.json")
                    .patch(histJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                executeAndLog(req, "uploadLocalData_history")
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
                    .url("$DATABASE_URL/users/$key/private/subscriptions.json")
                    .patch(subJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                executeAndLog(req, "uploadLocalData_subscriptions")
            }

            // Выгружаем голосовые команды в облако со сжатием ZLIB
            val localVoice = DirtyHandsEngine.getAllCompressedCommands()
            if (localVoice.isNotEmpty()) {
                val voiceJson = JSONObject()
                for ((a, d) in localVoice) {
                    voiceJson.put(a, d)
                }
                val req = Request.Builder()
                    .url("$DATABASE_URL/users/$key/private/voice_commands.json")
                    .patch(voiceJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                executeAndLog(req, "uploadLocalData_voiceCommands")
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
            put("timestamp", sub.subscribedAt)
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
        val subAt = if (json.has("subscribedAt")) {
            json.optLong("subscribedAt", System.currentTimeMillis())
        } else {
            json.optLong("timestamp", System.currentTimeMillis())
        }
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
            subscribedAt = subAt,
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
            if (h.translatorId.isNotBlank()) {
                put("translatorId", h.translatorId)
            }
            put("translatorName", h.translatorName)
            put("season", h.season)
            put("episode", h.episode)
            put("progress", h.progressMs)
            put("progressMs", h.progressMs)
            put("duration", h.durationMs)
            put("durationMs", h.durationMs)
            put("totalEpisodes", h.totalEpisodes)
            put("episodeIndex", h.episodeIndex)
            put("totalSeasons", h.totalSeasons)
            put("isFullyWatched", h.isFullyWatched)
            put("timestamp", h.timestamp)
        }
    }

    private fun jsonToHistory(json: JSONObject, fallbackKey: String = ""): WatchHistoryEntity {
        val rawUrl = json.optString("url", "")
        val season = json.optInt("season", 0)
        val itemType = if (season > 0) RezkaType.SERIES else RezkaType.MOVIE
        val itemId = json.optString("itemId", "")
        val adjustedUrl = RezkaService.adjustUrlToCurrentMirror(rawUrl, itemType, itemId)
        val rawId = json.optString("id", "").ifEmpty { fallbackKey }
        val epStr = if (json.has("episode")) json.opt("episode")?.toString() ?: "" else ""
        val finalId = if (rawId.isNotEmpty()) rawId else "${itemId}_${season}_${epStr}"

        val progress = if (json.has("progressMs")) {
            json.optLong("progressMs", 0L)
        } else {
            json.optLong("progress", 0L)
        }

        val duration = if (json.has("durationMs")) {
            json.optLong("durationMs", 0L)
        } else {
            json.optLong("duration", 0L)
        }

        val transId = if (json.has("translatorId")) json.opt("translatorId")?.toString() ?: "" else ""

        return WatchHistoryEntity(
            id = finalId,
            itemId = itemId,
            title = json.optString("title", ""),
            imageUrl = json.optString("imageUrl", ""),
            subtitle = json.optString("subtitle", ""),
            url = adjustedUrl,
            translatorId = transId,
            translatorName = json.optString("translatorName", ""),
            season = season,
            episode = epStr,
            progressMs = progress,
            durationMs = duration,
            totalEpisodes = json.optInt("totalEpisodes", 0),
            episodeIndex = json.optInt("episodeIndex", 0),
            totalSeasons = json.optInt("totalSeasons", 0),
            isFullyWatched = json.optBoolean("isFullyWatched", false),
            timestamp = json.optLong("timestamp", System.currentTimeMillis())
        )
    }
}
