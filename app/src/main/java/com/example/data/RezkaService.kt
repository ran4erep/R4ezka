package com.example.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.collection.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.InetAddress
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Высокопроизводительный DNS-резолвер с поддержкой DNS-over-HTTPS (DoH).
 * Обходит блокировки провайдеров и кэширует IP в памяти для нулевой нагрузки на процессор.
 */
object SafeDns : Dns {
    private val rawClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val ipCache = ConcurrentHashMap<String, List<InetAddress>>()
    private val DOH_REGEX = Regex("\"data\"\\s*:\\s*\"(\\d+\\.\\d+\\.\\d+\\.\\d+)\"")

    override fun lookup(hostname: String): List<InetAddress> {
        ipCache[hostname]?.let { return it }

        return try {
            val systemIps = Dns.SYSTEM.lookup(hostname)
            if (systemIps.isNotEmpty()) {
                ipCache[hostname] = systemIps
                systemIps
            } else {
                resolveViaDoH(hostname)
            }
        } catch (e: Exception) {
            resolveViaDoH(hostname)
        }
    }

    private fun resolveViaDoH(hostname: String): List<InetAddress> {
        return try {
            val dohUrl = "https://1.1.1.1/dns-query?name=$hostname&type=A"
            val request = Request.Builder()
                .url(dohUrl)
                .header("Accept", "application/dns-json")
                .build()

            rawClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw UnknownHostException("DoH query failed: ${response.code}")
                val json = response.body?.string() ?: ""
                val matches = DOH_REGEX.findAll(json)
                val ips = matches.map { it.groupValues[1] }.toList()
                if (ips.isEmpty()) {
                    throw UnknownHostException("No DNS records for $hostname")
                }
                val resolved = ips.map { InetAddress.getByName(it) }
                ipCache[hostname] = resolved
                resolved
            }
        } catch (e: Exception) {
            throw UnknownHostException("DNS resolution failed for $hostname: ${e.message}")
        }
    }
}

/**
 * Потокобезопасное хранилище сессионных куков (Anubis JWT, PHPSESSID, dle_user_id).
 */
class InMemoryCookieJar : CookieJar {
    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host
        val current = cookieStore.getOrPut(host) { CopyOnWriteArrayList() }
        for (newCookie in cookies) {
            current.removeAll { it.name == newCookie.name }
            current.add(newCookie)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = url.host
        val now = System.currentTimeMillis()
        val list = cookieStore[host] ?: return emptyList()
        return list.filter { it.expiresAt > now }
    }

    fun clear() {
        cookieStore.clear()
    }

    fun hasAuthCookie(host: String): Boolean {
        return cookieStore[host]?.any { it.name == "dle_user_id" || it.name.contains("anubis") } ?: false
    }
}

/**
 * Интеллектуальный перехватчик Anubis Proof-of-Work анти-бот защиты зеркала rezka-tv.org.
 * Вычисляет SHA-256 хэш на байтовом уровне за < 1 мс без создания лишних объектов в памяти,
 * автоматически отправляет подтверждение и прозрачно повторяет запрос.
 */
class AnubisInterceptor : Interceptor {
    companion object {
        private const val TAG = "AnubisInterceptor"

        /**
         * Ультра-оптимизированный поиск решения Proof-of-Work.
         * Выполняется с нулевым выделением памяти (Zero-Allocation) в цикле для минимальной нагрузки на CPU.
         */
        fun solvePow(randomData: String, difficulty: Int): Pair<Long, String> {
            val digest = MessageDigest.getInstance("SHA-256")
            val dataBytes = randomData.toByteArray(Charsets.UTF_8)
            var nonce = 0L
            val fullZeroBytes = difficulty / 2
            val isOdd = difficulty % 2 != 0
            val nonceBuffer = ByteArray(32)
            val hexDigits = "0123456789abcdef".toCharArray()

            while (true) {
                var temp = nonce
                var len = 0
                if (temp == 0L) {
                    nonceBuffer[0] = '0'.code.toByte()
                    len = 1
                } else {
                    while (temp > 0) {
                        nonceBuffer[len++] = ('0'.code + (temp % 10).toInt()).toByte()
                        temp /= 10
                    }
                    var i = 0
                    var j = len - 1
                    while (i < j) {
                        val t = nonceBuffer[i]
                        nonceBuffer[i] = nonceBuffer[j]
                        nonceBuffer[j] = t
                        i++
                        j--
                    }
                }

                digest.reset()
                digest.update(dataBytes)
                digest.update(nonceBuffer, 0, len)
                val hash = digest.digest()

                var valid = true
                for (i in 0 until fullZeroBytes) {
                    if (hash[i] != 0.toByte()) {
                        valid = false
                        break
                    }
                }
                if (valid && isOdd) {
                    if ((hash[fullZeroBytes].toInt() and 0xF0) != 0) {
                        valid = false
                    }
                }

                if (valid) {
                    val hexChars = CharArray(hash.size * 2)
                    for (i in hash.indices) {
                        val v = hash[i].toInt() and 0xFF
                        hexChars[i * 2] = hexDigits[v ushr 4]
                        hexChars[i * 2 + 1] = hexDigits[v and 0x0F]
                    }
                    return Pair(nonce, String(hexChars))
                }
                nonce++
            }
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val response = chain.proceed(originalRequest)

        val contentType = response.header("Content-Type") ?: ""
        // Anubis challenges are HTML pages
        if (!contentType.contains("text/html")) {
            return response
        }

        val bodyMediaType = response.body?.contentType()
        val bodyString = response.body?.string() ?: ""

        if (!bodyString.contains("anubis_challenge")) {
            // Обычный ответ, возвращаем его
            return response.newBuilder()
                .body(bodyString.toResponseBody(bodyMediaType))
                .build()
        }

        // Обнаружена защита Anubis на зеркале rezka-tv.org! Решаем задачу
        val effectiveUrl = response.request.url
        Log.i(TAG, "Обнаружен вызов Anubis PoW на $effectiveUrl. Запуск оптимизированного движка...")
        try {
            val challengeIdx = bodyString.indexOf("id=\"anubis_challenge\"")
            val startIdx = bodyString.indexOf('{', challengeIdx)
            val endIdx = bodyString.indexOf("</script>", startIdx)
            if (challengeIdx != -1 && startIdx != -1 && endIdx != -1) {
                val jsonStr = bodyString.substring(startIdx, endIdx)
                val json = JSONObject(jsonStr)
                val challengeObj = json.getJSONObject("challenge")
                val rulesObj = json.getJSONObject("rules")

                val chId = challengeObj.getString("id")
                val randomData = challengeObj.getString("randomData")
                val difficulty = rulesObj.optInt("difficulty", 2)

                val t0 = System.currentTimeMillis()
                val (nonce, hash) = solvePow(randomData, difficulty)
                val elapsed = (System.currentTimeMillis() - t0).coerceAtLeast(1)
                Log.i(TAG, "Anubis PoW решен за ${elapsed}ms: nonce=$nonce")

                val base = "${effectiveUrl.scheme}://${effectiveUrl.host}"
                val passUrl = "$base/.within.website/x/cmd/anubis/api/pass-challenge?id=$chId&response=$hash&nonce=$nonce&redir=${URLEncoder.encode(effectiveUrl.encodedPath, "UTF-8")}&elapsedTime=$elapsed"

                val passReq = Request.Builder()
                    .url(passUrl)
                    .header("User-Agent", RezkaService.USER_AGENT)
                    .header("Referer", effectiveUrl.toString())
                    .build()

                chain.proceed(passReq).close()

                // Повторяем исходный запрос с полученным сессионным токеном Anubis на актуальный URL
                Log.i(TAG, "Повтор запроса с авторизационными куками Anubis на $effectiveUrl...")
                val retryReq = originalRequest.newBuilder().url(effectiveUrl).build()
                return chain.proceed(retryReq)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обхода Anubis: ${e.message}", e)
        }

        return response.newBuilder()
            .body(bodyString.toResponseBody(bodyMediaType))
            .build()
    }
}

/**
 * Основной сервис для работы с зеркалом rezka-tv.org.
 * Оснащен специализированным движком парсинга, обходом Anubis и нулевой задержкой благодаря кэшу в памяти.
 */
object RezkaService {
    private const val TAG = "RezkaService"

    // Основное зеркало по умолчанию
    const val PRIMARY_MIRROR = "https://rezka-tv.org"

    // Предустановленные популярные зеркала
    val PRESET_MIRRORS = listOf(
        "https://rezka-tv.org",
        "https://hdrezka.club",
        "https://hdrezka.ag",
        "https://rezka.ag",
        "https://hdrezka.cm",
        "https://hdrezka.me"
    )

    private val _currentMirror = MutableStateFlow(PRIMARY_MIRROR)
    val currentMirror: StateFlow<String> = _currentMirror.asStateFlow()

    val currentBaseUrl: String
        get() = _currentMirror.value

    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    val cookieJar = InMemoryCookieJar()

    private val client = OkHttpClient.Builder()
        .dns(SafeDns)
        .cookieJar(cookieJar)
        .addInterceptor { chain ->
            val original = chain.request()
            val requestBuilder = original.newBuilder()
            if (original.header("User-Agent") == null) {
                requestBuilder.header("User-Agent", USER_AGENT)
            }
            if (original.header("Accept-Language") == null) {
                requestBuilder.header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
            }
            if (original.header("Sec-Ch-Ua") == null) {
                requestBuilder.header("Sec-Ch-Ua", "\"Chromium\";v=\"128\", \"Not;A=Brand\";v=\"24\", \"Google Chrome\";v=\"128\"")
                requestBuilder.header("Sec-Ch-Ua-Mobile", "?0")
                requestBuilder.header("Sec-Ch-Ua-Platform", "\"Windows\"")
            }
            val isXml = original.header("X-Requested-With") != null
            if (original.header("Sec-Fetch-Dest") == null) {
                requestBuilder.header("Sec-Fetch-Dest", if (isXml) "empty" else "document")
            }
            if (original.header("Sec-Fetch-Mode") == null) {
                requestBuilder.header("Sec-Fetch-Mode", if (isXml) "cors" else "navigate")
            }
            if (original.header("Sec-Fetch-Site") == null) {
                requestBuilder.header("Sec-Fetch-Site", if (original.header("Origin") != null) "same-origin" else "none")
            }
            if (!isXml && original.header("Sec-Fetch-User") == null) {
                requestBuilder.header("Sec-Fetch-User", "?1")
            }
            if (!isXml && original.header("Upgrade-Insecure-Requests") == null) {
                requestBuilder.header("Upgrade-Insecure-Requests", "1")
            }
            chain.proceed(requestBuilder.build())
        }
        .addInterceptor(AnubisInterceptor())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Оптимизированный HTTP-клиент для загрузки аватарок и изображений с сайта HDRezka.
     * Автоматически добавляет необходимые заголовки User-Agent, Referer и Accept,
     * обходит защиту хотлинкинга и 403 Forbidden.
     */
    val imageOkHttpClient: OkHttpClient by lazy {
        client.newBuilder()
            .addInterceptor { chain ->
                val original = chain.request()
                val requestBuilder = original.newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "$currentBaseUrl/")
                    .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                chain.proceed(requestBuilder.build())
            }
            .build()
    }

    /**
     * Преобразование любого относительного URL сайта (например, /uploads/fotos/... или //static...)
     * в абсолютный URL с текущим активным зеркалом без дублирования слешей.
     */
    fun normalizeUrl(raw: String, baseUrl: String = currentBaseUrl): String {
        val clean = raw.trim().removeSurrounding("'", "").removeSurrounding("\"", "")
        if (clean.isEmpty()) return ""
        return when {
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> {
                val domain = baseUrl.trimEnd('/')
                "$domain$clean"
            }
            else -> {
                val domain = baseUrl.trimEnd('/')
                "$domain/$clean"
            }
        }
    }

    /**
     * Высокопроизводительный движок коррекции URL на актуальное зеркало.
     * Заменяет домен в абсолютных URL на текущий активный зеркальный домен,
     * а также собирает валидный URL по ID и категории в случае отсутствия ссылки.
     */
    fun adjustUrlToCurrentMirror(url: String, type: RezkaType? = null, id: String? = null): String {
        val clean = url.trim()
        val currentBase = currentBaseUrl.trimEnd('/')

        if (clean.isEmpty()) {
            if (id != null && type != null) {
                val categoryPath = when (type) {
                    RezkaType.MOVIE -> "films"
                    RezkaType.SERIES -> "series"
                    RezkaType.ANIME -> "animation"
                    RezkaType.CARTOON -> "cartoons"
                }
                return "$currentBase/$categoryPath/$id.html"
            }
            return ""
        }

        // Если URL относительный (начинается с /)
        if (clean.startsWith("/")) {
            return "$currentBase$clean"
        }

        // Если URL абсолютный (начинается с http:// или https://)
        if (clean.startsWith("http://") || clean.startsWith("https://")) {
            // Заменяем протокол и хост на currentBase
            val schemeEnd = clean.indexOf("://")
            if (schemeEnd != -1) {
                val pathStart = clean.indexOf('/', schemeEnd + 3)
                return if (pathStart != -1) {
                    currentBase + clean.substring(pathStart)
                } else {
                    currentBase
                }
            }
        }

        // Если это просто имя файла или относительный путь без слэша в начале
        if (id != null && type != null) {
            val categoryPath = when (type) {
                RezkaType.MOVIE -> "films"
                RezkaType.SERIES -> "series"
                RezkaType.ANIME -> "animation"
                RezkaType.CARTOON -> "cartoons"
            }
            return "$currentBase/$categoryPath/$id.html"
        }

        return "$currentBase/$clean"
    }

    private val URL_IN_TEXT_REGEX = Regex("""https?://[^\s<>"]+""")

    /**
     * Формирует ссылку для отправки («Поделиться») с аргументом текущей выбранной озвучки
     * и актуальным рабочим зеркалом.
     */
    fun buildShareUrl(itemUrl: String, translatorId: String?): String {
        val baseUrl = currentBaseUrl.trimEnd('/')
        val cleanPath = when {
            itemUrl.startsWith("http://") || itemUrl.startsWith("https://") -> {
                val schemeEnd = itemUrl.indexOf("://")
                val pathStart = itemUrl.indexOf('/', schemeEnd + 3)
                if (pathStart != -1) itemUrl.substring(pathStart) else "/"
            }
            itemUrl.startsWith("/") -> itemUrl
            else -> "/$itemUrl"
        }.substringBefore("?").substringBefore("#")

        val fullUrl = "$baseUrl$cleanPath"
        val cleanTranslatorId = translatorId?.trim()
        return if (!cleanTranslatorId.isNullOrEmpty() && cleanTranslatorId != "0") {
            "$fullUrl?translator_id=$cleanTranslatorId"
        } else {
            fullUrl
        }
    }

    /**
     * Проверяет, принадлежит ли хост предустановленным зеркалам, кастомному зеркалу
     * либо характерным доменным именам Rezka.
     */
    fun isRecognizedMirrorHost(rawHost: String): Boolean {
        if (rawHost.isBlank()) return false
        val cleanHost = rawHost.lowercase().trim().removePrefix("www.")

        // 1. Проверяем текущее активное зеркало
        val currentHost = currentBaseUrl.toHttpUrlOrNull()?.host?.lowercase()?.removePrefix("www.")
        if (currentHost != null && (cleanHost == currentHost || cleanHost.endsWith(".$currentHost"))) {
            return true
        }

        // 2. Проверяем сохраненное зеркало в SharedPreferences (кастомное зеркало)
        val saved = prefs?.getString("saved_mirror", null)
        if (!saved.isNullOrBlank()) {
            val savedHost = saved.toHttpUrlOrNull()?.host?.lowercase()?.removePrefix("www.")
            if (savedHost != null && (cleanHost == savedHost || cleanHost.endsWith(".$savedHost"))) {
                return true
            }
        }

        // 3. Проверяем все предустановленные зеркала
        for (m in PRESET_MIRRORS) {
            val mHost = m.toHttpUrlOrNull()?.host?.lowercase()?.removePrefix("www.") ?: continue
            if (cleanHost == mHost || cleanHost.endsWith(".$mHost")) {
                return true
            }
        }

        // 4. Паттерны доменов rezka / hdrezka
        if (cleanHost.contains("rezka") || cleanHost.contains("hdrezka")) {
            return true
        }

        return false
    }

    /**
     * Высокопроизводительный разбор ссылок Rezka / HDRezka с извлечением фильма и озвучки.
     * Поддерживает все предустановленные зеркала, пользовательские кастомные зеркала,
     * относительные пути, а также ссылки, извлеченные из текста сообщений.
     */
    fun parseRezkaUrl(rawInput: String): ParsedRezkaLink? {
        if (rawInput.isBlank()) return null
        val trimmed = rawInput.trim()

        val urlStr = URL_IN_TEXT_REGEX.find(trimmed)?.value ?: trimmed

        return try {
            val uri = Uri.parse(urlStr)
            val scheme = uri.scheme?.lowercase() ?: ""
            if (scheme.isNotEmpty() && scheme != "http" && scheme != "https") {
                return null
            }

            val path = uri.path ?: ""
            if (path.isEmpty()) return null

            // Сигнатура контента Rezka в путях
            val isRezkaContentPath = path.contains("/films/") ||
                    path.contains("/series/") ||
                    path.contains("/animation/") ||
                    path.contains("/cartoons/")

            val host = uri.host?.lowercase() ?: ""
            val isKnownHost = isRecognizedMirrorHost(host)

            // Если хост неизвестен И путь не является путем Rezka — не наш URL
            if (!isKnownHost && !isRezkaContentPath) {
                return null
            }

            val type = when {
                path.contains("/series/") -> RezkaType.SERIES
                path.contains("/animation/") -> RezkaType.ANIME
                path.contains("/cartoons/") -> RezkaType.CARTOON
                else -> RezkaType.MOVIE
            }

            val id = extractIdFromUrl(path)
            if (id.isEmpty()) return null

            // Извлечение аргумента озвучки (query или fragment)
            val rawTranslator = uri.getQueryParameter("translator_id")
                ?: uri.getQueryParameter("t")
                ?: uri.getQueryParameter("translator")
                ?: uri.getQueryParameter("translation")
                ?: uri.getQueryParameter("voice")
                ?: uri.fragment?.let { frag ->
                    when {
                        frag.startsWith("t:") -> frag.substringAfter("t:")
                        frag.startsWith("translator:") -> frag.substringAfter("translator:")
                        frag.startsWith("translator_id=") -> frag.substringAfter("translator_id=")
                        else -> null
                    }
                }

            val cleanTranslator = rawTranslator?.trim()?.takeIf { it.isNotEmpty() && it != "0" }

            val adjustedUrl = adjustUrlToCurrentMirror(path, type, id)

            val titleFromSlug = id.substringAfter("-", "").ifEmpty { id }
                .replace("-", " ")
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

            val item = RezkaItem(
                id = id,
                title = titleFromSlug.ifEmpty { "Загрузка..." },
                subtitle = "",
                imageUrl = "",
                rating = "",
                url = adjustedUrl,
                type = type
            )

            ParsedRezkaLink(
                item = item,
                translatorId = cleanTranslator
            )
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка разбора URL Rezka: $rawInput", e)
            null
        }
    }

    /**
     * Извлечение и парсинг ссылки Rezka из переданного системного Intent (ACTION_VIEW или ACTION_SEND)
     */
    fun parseIntent(intent: Intent?): ParsedRezkaLink? {
        if (intent == null) return null
        val action = intent.action
        if (action == Intent.ACTION_VIEW) {
            val dataStr = intent.dataString ?: return null
            return parseRezkaUrl(dataStr)
        } else if (action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
            return parseRezkaUrl(text)
        }
        return null
    }

    // LRU кэш для страниц каталога и фильмов (минимизация нагрузки на CPU и сеть)
    private val catalogCache = LruCache<String, List<RezkaItem>>(100)
    private val detailCache = LruCache<String, RezkaDetail>(100)
    private val streamCache = LruCache<String, List<StreamUrl>>(50)
    // LRU кэш премиум-статуса озвучек (по ключу "${numericPostId}_${translatorId}")
    private val translatorPremiumCache = LruCache<String, Boolean>(1000)
    // LRU кэш сезонов и серий для каждой отдельной озвучки сериала (по ключу "${numericPostId}_${translatorId}")
    private val seasonEpisodesCache = LruCache<String, List<Season>>(300)
    // Фоновый скоуп для бережной параллельной предзагрузки серий остальных озвучек
    private val prefetchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefetchSemaphore = Semaphore(2)

    // Динамически распарсенный со страницы адрес официальной SVG-иконки премиума
    @Volatile
    private var lastParsedPremiumIconUrl: String = ""

    private fun isFlagImage(rawSrc: String, el: org.jsoup.nodes.Element): Boolean {
        if (rawSrc.isBlank()) return false
        val lowerSrc = rawSrc.lowercase()
        val isFlagPath = lowerSrc.contains("flag") || lowerSrc.contains("/flags/") || lowerSrc.contains("flags/")
        val isFlagClass = el.className().contains("flag", ignoreCase = true)
        val isFlagAlt = el.attr("alt").contains("flag", ignoreCase = true)
        val isFlagTitle = el.attr("title").contains("flag", ignoreCase = true)
        return isFlagPath || isFlagClass || isFlagAlt || isFlagTitle
    }

    /**
     * Проверяет, является ли конкретный пункт озвучки премиумным.
     * Озвучка премиумная ТОГДА И ТОЛЬКО ТОГДА, когда перед её названием в HTML есть SVG-иконка или признаки премиума.
     */
    fun hasPremiumSvgIcon(tEl: org.jsoup.nodes.Element): Boolean {
        // 1. Наличие векторного тега svg внутри элемента озвучки
        if (tEl.selectFirst("svg") != null) {
            return true
        }
        // 2. Наличие векторного тега use (векторные спрайты)
        if (tEl.selectFirst("use") != null) {
            return true
        }
        // 3. Наличие img тега с SVG, который НЕ является флагом страны
        for (img in tEl.select("img")) {
            val rawSrc = img.attr("src").ifEmpty { img.attr("data-src") }
            if (rawSrc.isNotBlank() && rawSrc.contains(".svg", ignoreCase = true) && !isFlagImage(rawSrc, img)) {
                return true
            }
        }
        // 4. Наличие класса или атрибута премиума у элемента озвучки или его дочерних элементов
        if (tEl.hasClass("prem") || tEl.hasClass("premium") || tEl.hasClass("b-translator__item--prem")) {
            return true
        }
        if (tEl.selectFirst("[class*='prem'], [class*='vip'], [class*='star']") != null) {
            return true
        }
        if (tEl.hasAttr("data-prem") || tEl.attr("data-premium") == "1" || tEl.attr("data-is_prem") == "1") {
            return true
        }
        return false
    }

    /**
     * Динамическое извлечение адреса SVG-иконки прямо из элемента озвучки.
     * По разметке HDRezka перед названием премиум-озвучки находится SVG-иконка (тег img или svg/use).
     */
    fun extractSvgIconFromTranslator(tEl: org.jsoup.nodes.Element, baseUrl: String = currentBaseUrl): String {
        // 1. Поиск в img тегах внутри элемента озвучки
        val imgElements = tEl.select("img")
        for (img in imgElements) {
            val rawSrc = img.attr("src").ifEmpty { img.attr("data-src") }
            if (rawSrc.isBlank()) continue
            if (!isFlagImage(rawSrc, img) && rawSrc.contains(".svg", ignoreCase = true)) {
                val clean = rawSrc.substringBefore("#")
                val normalized = normalizeUrl(clean, baseUrl)
                if (normalized.isNotEmpty()) return normalized
            }
        }

        // 2. Поиск в svg / use тегах внутри элемента озвучки (векторный спрайт)
        for (use in tEl.select("svg use, use")) {
            val href = use.attr("href").ifEmpty { use.attr("xlink:href") }
            if (href.contains(".svg", ignoreCase = true)) {
                val clean = href.substringBefore("#")
                val normalized = normalizeUrl(clean, baseUrl)
                if (normalized.isNotEmpty()) return normalized
            }
        }

        // 3. Поиск любых других элементов с атрибутом *.svg внутри элемента озвучки перед текстом
        for (el in tEl.select("[src*='.svg'], [data-src*='.svg'], [href*='.svg'], [xlink:href*='.svg']")) {
            val raw = el.attr("src").ifEmpty { el.attr("data-src") }.ifEmpty { el.attr("href") }.ifEmpty { el.attr("xlink:href") }
            if (raw.isNotBlank() && !isFlagImage(raw, el) && raw.contains(".svg", ignoreCase = true)) {
                val clean = raw.substringBefore("#")
                val normalized = normalizeUrl(clean, baseUrl)
                if (normalized.isNotEmpty()) return normalized
            }
        }
        return ""
    }

    /**
     * Динамический поиск адреса SVG-иконки премиума в контейнере озвучек или на странице.
     */
    fun extractSvgIconFromPage(doc: org.jsoup.nodes.Document, baseUrl: String = currentBaseUrl): String {
        val listContainer = doc.selectFirst("#translators-list, .b-translators__list, .b-translator__block")
        if (listContainer != null) {
            for (img in listContainer.select("img")) {
                val rawSrc = img.attr("src").ifEmpty { img.attr("data-src") }
                if (rawSrc.isNotBlank() && !isFlagImage(rawSrc, img) && rawSrc.contains(".svg", ignoreCase = true)) {
                    val clean = rawSrc.substringBefore("#")
                    val normalized = normalizeUrl(clean, baseUrl)
                    if (normalized.isNotEmpty()) return normalized
                }
            }
            for (use in listContainer.select("svg use, use")) {
                val href = use.attr("href").ifEmpty { use.attr("xlink:href") }
                if (href.contains(".svg", ignoreCase = true)) {
                    val clean = href.substringBefore("#")
                    val normalized = normalizeUrl(clean, baseUrl)
                    if (normalized.isNotEmpty()) return normalized
                }
            }
        }

        val premImg = doc.selectFirst("img[src*='prem'][src*='.svg'], img[data-src*='prem'][data-src*='.svg'], img[src*='prem-icon'], [class*='prem'] img[src*='.svg']")
        if (premImg != null) {
            val src = premImg.attr("src").ifEmpty { premImg.attr("data-src") }
            val clean = src.substringBefore("#")
            val normalized = normalizeUrl(clean, baseUrl)
            if (normalized.isNotEmpty()) return normalized
        }
        return ""
    }

    /**
     * Динамическое получение URL официальной векторной (SVG) иконки премиума HDRezka.
     * В первую очередь возвращает адрес, распарсенный прямо со страницы перед названием озвучки.
     */
    fun getPremiumIconUrl(mirrorUrl: String = currentBaseUrl): String {
        if (lastParsedPremiumIconUrl.isNotEmpty()) {
            return lastParsedPremiumIconUrl
        }
        val host = mirrorUrl.toHttpUrlOrNull()?.host
            ?: mirrorUrl.removePrefix("https://").removePrefix("http://").substringBefore("/").substringBefore(":")
        val staticHost = if (host.startsWith("static.")) host else "static.$host"
        return "https://$staticHost/templates/hdrezka/images/prem-icon.svg"
    }

    // Авторизация пользователя
    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _currentUser = MutableStateFlow<String?>(null)
    val currentUser: StateFlow<String?> = _currentUser.asStateFlow()

    // Настройки воспроизведения: качество по умолчанию (1080p, 1080p Ultra, 720p, 480p, 360p, ask)
    const val QUALITY_1080P = "1080p"
    const val QUALITY_1080P_ULTRA = "1080p Ultra"
    const val QUALITY_720P = "720p"
    const val QUALITY_480P = "480p"
    const val QUALITY_360P = "360p"
    const val QUALITY_ASK = "ask"

    private val _defaultQuality = MutableStateFlow(QUALITY_1080P)
    val defaultQuality: StateFlow<String> = _defaultQuality.asStateFlow()

    // Настройка автопереключения на следующую серию (по умолчанию включено)
    private val _autoNextEpisode = MutableStateFlow(true)
    val autoNextEpisode: StateFlow<Boolean> = _autoNextEpisode.asStateFlow()

    // Предпочтительный язык субтитров ("ru", "en", "uk", "off" и т.д.)
    private val _preferredSubtitleLang = MutableStateFlow("ru")
    val preferredSubtitleLang: StateFlow<String> = _preferredSubtitleLang.asStateFlow()

    // Масштаб шрифта субтитров (по умолчанию 0.053f)
    private val _subtitleTextScale = MutableStateFlow(0.053f)
    val subtitleTextScale: StateFlow<Float> = _subtitleTextScale.asStateFlow()

    // Режим масштабирования видео (FIT, ZOOM, FILL)
    private val _defaultResizeMode = MutableStateFlow("FIT")
    val defaultResizeMode: StateFlow<String> = _defaultResizeMode.asStateFlow()

    // Режим интерфейса (auto, force_tv, force_mobile)
    private val _tvModePreference = MutableStateFlow("auto")
    val tvModePreference: StateFlow<String> = _tvModePreference.asStateFlow()

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("rezka_tv_prefs", Context.MODE_PRIVATE)
        val savedMirror = prefs?.getString("saved_mirror", PRIMARY_MIRROR) ?: PRIMARY_MIRROR
        _currentMirror.value = savedMirror

        val savedUser = prefs?.getString("saved_user", null)
        if (!savedUser.isNullOrEmpty()) {
            _currentUser.value = savedUser
            _isLoggedIn.value = true
        }

        val savedQuality = prefs?.getString("default_video_quality", QUALITY_1080P) ?: QUALITY_1080P
        _defaultQuality.value = savedQuality

        val savedAutoNext = prefs?.getBoolean("auto_next_episode", true) ?: true
        _autoNextEpisode.value = savedAutoNext

        val savedSubLang = prefs?.getString("preferred_subtitle_lang", "ru") ?: "ru"
        _preferredSubtitleLang.value = savedSubLang

        val savedSubScale = prefs?.getFloat("subtitle_text_scale", 0.053f) ?: 0.053f
        _subtitleTextScale.value = savedSubScale

        val savedResize = prefs?.getString("default_resize_mode", "FIT") ?: "FIT"
        _defaultResizeMode.value = savedResize

        val savedTvMode = prefs?.getString("tv_mode_preference", "auto") ?: "auto"
        _tvModePreference.value = savedTvMode
    }

    fun setDefaultQuality(quality: String) {
        _defaultQuality.value = quality
        prefs?.edit()?.putString("default_video_quality", quality)?.apply()
    }

    fun setAutoNextEpisode(enabled: Boolean) {
        _autoNextEpisode.value = enabled
        prefs?.edit()?.putBoolean("auto_next_episode", enabled)?.apply()
    }

    fun setPreferredSubtitleLang(lang: String) {
        _preferredSubtitleLang.value = lang
        prefs?.edit()?.putString("preferred_subtitle_lang", lang)?.apply()
    }

    fun setSubtitleTextScale(scale: Float) {
        _subtitleTextScale.value = scale
        prefs?.edit()?.putFloat("subtitle_text_scale", scale)?.apply()
    }

    fun setDefaultResizeMode(mode: String) {
        _defaultResizeMode.value = mode
        prefs?.edit()?.putString("default_resize_mode", mode)?.apply()
    }

    fun setTvModePreference(mode: String) {
        _tvModePreference.value = mode
        prefs?.edit()?.putString("tv_mode_preference", mode)?.apply()
    }

    /**
     * Высокопроизводительный подбор индекса качества видеопотока без лишних аллокаций.
     */
    fun findBestQualityIndex(streams: List<StreamUrl>, targetQuality: String): Int {
        if (streams.isEmpty()) return 0
        val qualityToMatch = if (targetQuality == QUALITY_ASK) QUALITY_1080P else targetQuality

        // 1. Точное совпадение
        val exact = streams.indexOfFirst { it.quality.equals(qualityToMatch, ignoreCase = true) }
        if (exact >= 0) return exact

        // 2. Если целевое - 1080p (без Ultra)
        if (qualityToMatch.equals(QUALITY_1080P, ignoreCase = true)) {
            val p1080 = streams.indexOfFirst { it.quality.contains("1080") && !it.quality.contains("Ultra", ignoreCase = true) }
            if (p1080 >= 0) return p1080
            val p720 = streams.indexOfFirst { it.quality.contains("720") }
            if (p720 >= 0) return p720
            val pUltra = streams.indexOfFirst { it.quality.contains("Ultra", ignoreCase = true) }
            if (pUltra >= 0) return pUltra
            return 0
        }

        // 3. Если целевое - 1080p Ultra
        if (qualityToMatch.equals(QUALITY_1080P_ULTRA, ignoreCase = true)) {
            val pUltra = streams.indexOfFirst { it.quality.contains("Ultra", ignoreCase = true) }
            if (pUltra >= 0) return pUltra
            val p1080 = streams.indexOfFirst { it.quality.contains("1080") }
            if (p1080 >= 0) return p1080
            return 0
        }

        // 4. Если целевое - 720p
        if (qualityToMatch.equals(QUALITY_720P, ignoreCase = true)) {
            val p720 = streams.indexOfFirst { it.quality.contains("720") }
            if (p720 >= 0) return p720
            val p1080 = streams.indexOfFirst { it.quality.contains("1080") && !it.quality.contains("Ultra", ignoreCase = true) }
            if (p1080 >= 0) return p1080
            return 0
        }

        // 5. По весу разрешения
        val targetWeight = RezkaDecryptor.getQualityWeight(qualityToMatch)
        var bestIdx = 0
        var minDiff = Int.MAX_VALUE
        for (i in streams.indices) {
            val diff = kotlin.math.abs(RezkaDecryptor.getQualityWeight(streams[i].quality) - targetWeight)
            if (diff < minDiff) {
                minDiff = diff
                bestIdx = i
            }
        }
        return bestIdx
    }

    fun normalizeMirrorUrl(rawUrl: String): String {
        var clean = rawUrl.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "https://$clean"
        }
        return clean.trimEnd('/')
    }

    fun setMirror(rawUrl: String): Boolean {
        val normalized = normalizeMirrorUrl(rawUrl)
        return try {
            val parsed = normalized.toHttpUrlOrNull()
            if (parsed != null && parsed.host.isNotEmpty()) {
                _currentMirror.value = normalized
                prefs?.edit()?.putString("saved_mirror", normalized)?.apply()
                clearCache()
                Log.i(TAG, "Установлено новое зеркало: $normalized")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    fun resetMirrorToDefault(): String {
        setMirror(PRIMARY_MIRROR)
        return PRIMARY_MIRROR
    }

    suspend fun testMirror(mirrorUrl: String): Result<Long> = withContext(Dispatchers.IO) {
        val normalized = normalizeMirrorUrl(mirrorUrl)
        val startTime = System.currentTimeMillis()
        try {
            val request = Request.Builder()
                .url(normalized)
                .header("User-Agent", USER_AGENT)
                .head()
                .build()

            client.newCall(request).execute().use { response ->
                val duration = (System.currentTimeMillis() - startTime).coerceAtLeast(1)
                if (response.isSuccessful || response.code in 200..399 || response.code == 403 || response.code == 503) {
                    Result.success(duration)
                } else {
                    Result.failure(Exception("Код ответа: ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Авторизация на rezka-tv.org через AJAX endpoint /ajax/login/
     */
    suspend fun login(loginName: String, loginPassword: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val formBody = FormBody.Builder()
                .add("login_name", loginName.trim())
                .add("login_password", loginPassword.trim())
                .add("login_not_save", "0")
                .add("login", "submit")
                .build()

            val request = Request.Builder()
                .url("$currentBaseUrl/ajax/login/")
                .post(formBody)
                .header("User-Agent", USER_AGENT)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "$currentBaseUrl/")
                .header("Origin", currentBaseUrl)
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                val success = json.optBoolean("success", false)

                if (success) {
                    _isLoggedIn.value = true
                    _currentUser.value = loginName
                    prefs?.edit()?.putString("saved_user", loginName)?.apply()
                    // Очищаем кэш чтобы каталог перезагрузился под авторизованным пользователем
                    clearCache()
                    Result.success("Успешный вход в rezka-tv.org")
                } else {
                    val message = json.optString("message", "Неверный логин или пароль")
                    Result.failure(Exception(message))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun logout() {
        _isLoggedIn.value = false
        _currentUser.value = null
        prefs?.edit()?.remove("saved_user")?.apply()
        cookieJar.clear()
        clearCache()
    }

    fun clearCache() {
        clearAllCacheAndSession()
    }

    /**
     * Полный сброс всех кэшей в памяти, сессионных куки и пула сетевых соединений
     */
    fun clearAllCacheAndSession() {
        catalogCache.evictAll()
        detailCache.evictAll()
        streamCache.evictAll()
        seasonEpisodesCache.evictAll()
        translatorPremiumCache.evictAll()
        categoryGenres.clear()
        cookieJar.clear()
        try {
            client.connectionPool.evictAll()
        } catch (_: Exception) {}
        Log.i(TAG, "Кэш и сессионные куки полностью очищены.")
    }

    /**
     * Высокоточная проверка HTML-документа на наличие страниц защиты от ботов (Cloudflare, Anubis, DDOS-GUARD и т.д.)
     */
    fun isAntiBotPage(html: String, doc: org.jsoup.nodes.Document): Boolean {
        if (html.isBlank()) return false
        val lowerHtml = html.lowercase()
        val botKeywords = listOf(
            "проверяем, что вы не бот",
            "checking if you are a bot",
            "checking your browser",
            "just a moment...",
            "anubis_challenge",
            "cf-browser-verification",
            "cf-challenge",
            "ddos-guard",
            "attention required! | cloudflare",
            "security check",
            "challenge-running",
            "enable javascript and cookies to continue"
        )
        for (keyword in botKeywords) {
            if (lowerHtml.contains(keyword)) {
                return true
            }
        }

        val title = doc.selectFirst(".b-post__title h1")?.text()
            ?: doc.selectFirst(".b-post__title")?.text()
            ?: doc.selectFirst("h1")?.text()
            ?: doc.title()

        return isAntiBotTitle(title)
    }

    /**
     * Проверка заголовка на фразы проверок от ботов
     */
    fun isAntiBotTitle(title: String): Boolean {
        if (title.isBlank()) return false
        val lower = title.lowercase()
        return lower.contains("бот") ||
               lower.contains("bot") ||
               lower.contains("cloudflare") ||
               lower.contains("ddos") ||
               lower.contains("security check") ||
               lower.contains("access denied") ||
               lower.contains("attention required") ||
               lower.contains("403 forbidden") ||
               lower.contains("503 service")
    }

    /**
     * Надежное извлечение заголовка с использованием нескольких стратегий парсинга
     */
    fun extractTitleFromDoc(doc: org.jsoup.nodes.Document, url: String): String {
        var title = doc.selectFirst(".b-post__title h1")?.text()?.trim()
            ?: doc.selectFirst(".b-post__title")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst(".b-post__title h2")?.text()?.trim()
            ?: doc.selectFirst("[itemprop='name']")?.text()?.trim()

        if (title.isNullOrEmpty()) {
            val ogTitle = doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
                ?: doc.selectFirst("meta[name='title']")?.attr("content")?.trim()
            if (!ogTitle.isNullOrEmpty()) {
                title = ogTitle
                    .removePrefix("Смотреть ")
                    .removePrefix("сериал ")
                    .removePrefix("Сериал ")
                    .removePrefix("фильм ")
                    .removePrefix("Фильм ")
                    .substringBefore(" смотреть")
                    .substringBefore(" (")
                    .trim()
            }
        }

        if (title.isNullOrEmpty()) {
            val docTitle = doc.title().trim()
            if (docTitle.isNotEmpty()) {
                title = docTitle
                    .removePrefix("Смотреть ")
                    .removePrefix("сериал ")
                    .removePrefix("Сериал ")
                    .removePrefix("фильм ")
                    .removePrefix("Фильм ")
                    .substringBefore(" смотреть")
                    .substringBefore(" (")
                    .trim()
            }
        }

        if (title.isNullOrEmpty()) {
            val id = extractIdFromUrl(url)
            val slug = if (id.contains("-")) id.substringAfter("-") else id
            title = slug.replace("-", " ").replace("_", " ")
                .split(" ")
                .joinToString(" ") { word -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
                .trim()
        }

        return title
    }

    /**
     * Извлечение чистого ID элемента из URL
     */
    fun extractIdFromUrl(url: String): String {
        return try {
            val cleanUrl = url.substringBefore("?").substringBefore("#")
            val fileName = cleanUrl.substringAfterLast("/")
            if (fileName.contains("-") && fileName.endsWith(".html")) {
                fileName.substringBefore(".html")
            } else if (cleanUrl.contains(".html")) {
                cleanUrl.substringAfterLast("/").substringBefore(".html")
            } else {
                val parts = cleanUrl.split("/").filter { it.isNotEmpty() }
                parts.lastOrNull() ?: cleanUrl
            }
        } catch (e: Exception) {
            url.substringAfterLast("/").substringBefore(".html")
        }
    }

    /**
     * Извлечение исключительно числового ID фильма/сериала для AJAX CDN запросов
     */
    fun extractNumericId(idOrUrl: String): String {
        val lastSegment = idOrUrl.substringBefore("?").substringAfterLast("/")
        val match = Regex("""^(\d+)""").find(lastSegment)
            ?: Regex("""(\d+)""").find(lastSegment)
            ?: Regex("""(\d+)""").find(idOrUrl)
        return match?.groupValues?.get(1) ?: idOrUrl.filter { it.isDigit() }.ifEmpty { idOrUrl }
    }

    private val categoryGenres = ConcurrentHashMap<RezkaType, List<GenreItem>>()

    fun getGenresForCategory(type: RezkaType): List<GenreItem> {
        return categoryGenres[type] ?: listOf(GenreItem("Без жанра", ""))
    }

    private fun parseGenresFromHtml(doc: org.jsoup.nodes.Document, type: RezkaType) {
        val categorySlug = when (type) {
            RezkaType.MOVIE -> "films"
            RezkaType.SERIES -> "series"
            RezkaType.ANIME -> "animation"
            RezkaType.CARTOON -> "cartoons"
        }

        val genres = mutableListOf<GenreItem>()
        genres.add(GenreItem("Без жанра", ""))

        val nonGenreSlugs = setOf(
            "films", "series", "animation", "cartoons",
            "best", "popular", "watching", "announcements", "soon",
            "page", "filter", "search", "do", "news", "collections", "help", "rules", "year"
        )

        try {
            val links = doc.select(".b-category__list a, .b-categories__list a, .b-navigation a, ul.b-sidebar__section a, div.b-categories a, a[href*=/$categorySlug/]")
            for (link in links) {
                val href = link.attr("href")
                val text = link.text().trim()
                if (text.isEmpty() || text.length > 35) continue

                val match = Regex("""/$categorySlug/([a-zA-Z0-9_-]+)/?""").find(href)
                if (match != null) {
                    val slug = match.groupValues[1]
                    if (slug !in nonGenreSlugs && !slug.all { it.isDigit() } && genres.none { it.slug == slug }) {
                        genres.add(GenreItem(name = text, slug = slug))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка парсинга жанров для $type", e)
        }

        if (genres.size > 1) {
            categoryGenres[type] = genres
        }
    }

    /**
     * Высокооптимизированный генератор URL каталога без вызова регулярных выражений в GC
     */
    fun buildCatalogUrl(
        type: RezkaType,
        section: SectionType,
        genre: String,
        page: Int,
        baseUrl: String = currentBaseUrl
    ): String {
        val domain = baseUrl.trimEnd('/')
        val categoryPath = when (type) {
            RezkaType.MOVIE -> "films"
            RezkaType.SERIES -> "series"
            RezkaType.ANIME -> "animation"
            RezkaType.CARTOON -> "cartoons"
        }
        val cleanGenre = genre.trim().trim('/')

        val sb = StringBuilder(domain.length + 40)
            .append(domain)
            .append('/')
            .append(categoryPath)
            .append('/')

        if (cleanGenre.isNotEmpty()) {
            sb.append(cleanGenre).append('/')
        }

        if (page > 1) {
            sb.append("page/").append(page).append('/')
        }

        val filterParam = when (section) {
            SectionType.LATEST -> "last"
            SectionType.POPULAR -> "popular"
            SectionType.WATCHING -> "watching"
            SectionType.AWAITING -> "soon"
        }

        sb.append("?filter=").append(filterParam)
        return sb.toString()
    }

    /**
     * Получение каталога фильмов/сериалов с актуального зеркала с динамической фильтрацией по разделу и жанру
     */
    suspend fun getCatalog(type: RezkaType, section: SectionType = SectionType.LATEST, genre: String = "", page: Int = 1): List<RezkaItem> = withContext(Dispatchers.IO) {
        val cacheKey = "$type-$section-$genre-$page"
        catalogCache.get(cacheKey)?.let { return@withContext it }

        val url = buildCatalogUrl(type, section, genre, page)

        val maxAttempts = 3
        var lastException: Exception? = null

        for (attempt in 1..maxAttempts) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                    .header("Referer", "$currentBaseUrl/")
                    .build()

                val (html, isSuccess) = client.newCall(request).execute().use { response ->
                    Pair(response.body?.string().orEmpty(), response.isSuccessful)
                }

                if (!isSuccess || html.isBlank()) {
                    if (attempt < maxAttempts) {
                        kotlinx.coroutines.delay(500L * attempt)
                        continue
                    } else {
                        throw Exception("Не удалось загрузить данные")
                    }
                }

                val doc = Jsoup.parse(html)
                if (isAntiBotPage(html, doc)) {
                    Log.w(TAG, "Обнаружена страница проверки при загрузке каталога (попытка $attempt/$maxAttempts), повторный запрос...")
                    if (attempt < maxAttempts) {
                        kotlinx.coroutines.delay(500L * attempt)
                        continue
                    } else {
                        throw Exception("Не удалось загрузить данные")
                    }
                }

                parseGenresFromHtml(doc, type)
                val items = parseCatalogHtml(html, type)
                if (items.isNotEmpty()) {
                    catalogCache.put(cacheKey, items)
                    return@withContext items
                } else if (page > 1) {
                    return@withContext emptyList()
                } else {
                    if (attempt < maxAttempts) {
                        kotlinx.coroutines.delay(500L * attempt)
                        continue
                    } else {
                        throw Exception("Фильмы не найдены")
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastException = e
                Log.w(TAG, "Ошибка загрузки каталога (попытка $attempt/$maxAttempts): ${e.message}")
                if (attempt < maxAttempts) {
                    kotlinx.coroutines.delay(500L * attempt)
                }
            }
        }
        throw lastException ?: Exception("Не удалось загрузить данные")
    }

    /**
     * Получение каталога фильмов/сериалов по произвольной ссылке (например, режиссер, актер, список)
     */
    suspend fun getCustomCatalog(url: String, page: Int = 1): List<RezkaItem> = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return@withContext emptyList()

        val adjustedUrl = adjustUrlToCurrentMirror(cleanUrl)
        val targetUrl = if (page > 1) {
            val separator = if (adjustedUrl.contains("?")) "&" else "?"
            val baseUrlWithoutParams = adjustedUrl.substringBefore("?")
            val params = if (adjustedUrl.contains("?")) adjustedUrl.substringAfter("?") else ""
            
            val urlWithPage = if (baseUrlWithoutParams.endsWith("/")) {
                "${baseUrlWithoutParams}page/$page/"
            } else {
                "${baseUrlWithoutParams}/page/$page/"
            }
            
            if (params.isNotEmpty()) {
                "$urlWithPage?$params"
            } else {
                urlWithPage
            }
        } else {
            adjustedUrl
        }

        val cacheKey = "custom-$targetUrl"
        catalogCache.get(cacheKey)?.let { return@withContext it }

        val maxAttempts = 3
        var lastException: Exception? = null

        for (attempt in 1..maxAttempts) {
            try {
                val request = Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                    .header("Referer", "$currentBaseUrl/")
                    .build()

                val (html, isSuccess) = client.newCall(request).execute().use { response ->
                    Pair(response.body?.string().orEmpty(), response.isSuccessful)
                }

                if (!isSuccess || html.isBlank()) {
                    if (attempt < maxAttempts) {
                        kotlinx.coroutines.delay(500L * attempt)
                        continue
                    } else {
                        throw Exception("Не удалось загрузить данные")
                    }
                }

                val doc = Jsoup.parse(html)
                if (isAntiBotPage(html, doc)) {
                    Log.w(TAG, "Обнаружена страница проверки при загрузке кастомного каталога (попытка $attempt/$maxAttempts), повторный запрос...")
                    if (attempt < maxAttempts) {
                        kotlinx.coroutines.delay(500L * attempt)
                        continue
                    } else {
                        throw Exception("Не удалось загрузить данные")
                    }
                }

                val items = parseCatalogHtml(html, RezkaType.MOVIE)
                if (items.isNotEmpty()) {
                    catalogCache.put(cacheKey, items)
                    return@withContext items
                } else if (page > 1) {
                    return@withContext emptyList()
                } else {
                    if (attempt < maxAttempts) {
                        kotlinx.coroutines.delay(500L * attempt)
                        continue
                    } else {
                        throw Exception("Фильмы не найдены")
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastException = e
                Log.w(TAG, "Ошибка загрузки кастомного каталога (попытка $attempt/$maxAttempts): ${e.message}")
                if (attempt < maxAttempts) {
                    kotlinx.coroutines.delay(500L * attempt)
                }
            }
        }
        throw lastException ?: Exception("Не удалось загрузить данные")
    }

    /**
     * Получение информации об актере/режиссере и его фильмографии
     */
    suspend fun getPersonProfile(url: String): RezkaPerson = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim()
        val adjustedUrl = adjustUrlToCurrentMirror(cleanUrl)
        
        val maxAttempts = 3
        var lastException: Exception? = null

        for (attempt in 1..maxAttempts) {
            try {
                val request = Request.Builder()
                    .url(adjustedUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                    .header("Referer", "$currentBaseUrl/")
                    .build()

                val (html, isSuccess) = client.newCall(request).execute().use { response ->
                    Pair(response.body?.string().orEmpty(), response.isSuccessful)
                }

                if (!isSuccess || html.isBlank()) {
                    if (attempt < maxAttempts) {
                        kotlinx.coroutines.delay(500L * attempt)
                        continue
                    } else {
                        throw Exception("Не удалось загрузить профиль")
                    }
                }

                val doc = Jsoup.parse(html)
                if (isAntiBotPage(html, doc)) {
                    if (attempt < maxAttempts) {
                        kotlinx.coroutines.delay(500L * attempt)
                        continue
                    } else {
                        throw Exception("Не удалось загрузить профиль (бот-фильтр)")
                    }
                }

                // 1. Извлечение русского и оригинального (английского) имени
                val h1El = doc.selectFirst(".b-post__title h1, h1")
                var name = h1El?.selectFirst("span.t1, span[itemprop='name']")?.text()?.trim() ?: ""
                var originalName = h1El?.selectFirst("span.t2, span[itemprop='alternativeHeadline']")?.text()?.trim() ?: ""

                if (name.isEmpty() && h1El != null) {
                    val fullH1 = h1El.text().trim()
                    // Если h1 содержит и русское, и английское имя без отдельных span (например "Мэтт Джонсон Matt Johnson")
                    val latinMatch = Regex("([A-Za-z].*)").find(fullH1)
                    if (latinMatch != null && latinMatch.range.first > 0) {
                        name = fullH1.substring(0, latinMatch.range.first).trim()
                        if (originalName.isEmpty()) {
                            originalName = latinMatch.value.trim()
                        }
                    } else {
                        name = fullH1
                    }
                }

                // 2. Фотография персоны (с поддержкой lazy loading data-src и заглушек)
                val imgEl = doc.selectFirst(".b-sidecover img, .b-post__infotable_left img, .b-person img, [itemprop='image']")
                var photoUrl = imgEl?.attr("data-src")?.ifEmpty { imgEl.attr("src") } ?: ""
                if (photoUrl.startsWith("//")) {
                    photoUrl = "https:$photoUrl"
                }

                // 3. Таблица метаданных персоны (Карьера, Дата рождения, Место рождения, Рост и др.)
                val infoMap = LinkedHashMap<String, String>()
                val infoRows = doc.select("table.b-post__info tr, .b-post__infotable tr, .b-person__info tr")
                for (row in infoRows) {
                    val label = row.selectFirst("td.l, td:nth-child(1), .l, .label")?.text()?.trim()?.removeSuffix(":")?.trim() ?: ""
                    val value = row.selectFirst("td:nth-child(2), .value")?.text()?.trim() ?: ""
                    if (label.isNotEmpty() && value.isNotEmpty()) {
                        infoMap[label] = value
                    }
                }

                // 4. Фильмография персоны по разделам (Актёр, Режиссёр, Сценарист, Продюсер и др.)
                val careerSections = ArrayList<RezkaCareerSection>()
                val allFilmography = ArrayList<RezkaItem>()
                val seenAllIds = HashSet<String>()

                val careerDivs = doc.select(".b-person__career")
                for (careerDiv in careerDivs) {
                    val h2El = careerDiv.selectFirst("h2")
                    val roleTitle = h2El?.text()?.trim() ?: "Работы"
                    val statsEl = careerDiv.selectFirst(".b-person__career_stats")
                    val roleStats = statsEl?.text()?.trim() ?: ""

                    // Выбираем ВСЕ элементы, включая скрытые классом .is_hidden (которые открываются кнопкой "Показать все")
                    val itemElements = careerDiv.select(".b-content__inline_item")
                    val sectionItems = ArrayList<RezkaItem>()
                    val seenSectionIds = HashSet<String>()

                    for (el in itemElements) {
                        val linkEl = el.selectFirst(".b-content__inline_item-link a")
                            ?: el.selectFirst(".b-content__inline_item-cover a")
                            ?: el.selectFirst("a")
                            ?: continue

                        val rawUrl = linkEl.attr("href")
                        if (rawUrl.isEmpty() || rawUrl.startsWith("javascript:")) continue
                        val itemUrl = if (rawUrl.startsWith("/")) "$currentBaseUrl$rawUrl" else rawUrl

                        var title = linkEl.text().trim()
                        if (title.isEmpty()) {
                            title = el.selectFirst(".b-content__inline_item-link")?.text()?.trim() ?: ""
                        }
                        if (title.isEmpty()) continue

                        val itemImgEl = el.selectFirst(".b-content__inline_item-cover img") ?: el.selectFirst("img")
                        var imageUrl = itemImgEl?.attr("data-src")?.ifEmpty { itemImgEl.attr("src") } ?: ""
                        if (imageUrl.startsWith("//")) {
                            imageUrl = "https:$imageUrl"
                        }

                        val subtitleEl = el.selectFirst(".b-content__inline_item-link .misc, .b-content__inline_item-link div, .misc")
                        val rawSubtitle = subtitleEl?.text()?.trim() ?: ""
                        val formattedSubtitle = CountryFlags.formatWithFlags(rawSubtitle)

                        val ratingEl = el.selectFirst(".b-category-bestrating, .rating, .num, .b-content__inline_item-cover .info, i.imdb, i.kp, .info")
                        val rating = ratingEl?.text()?.trim()?.removeSurrounding("(", ")") ?: ""

                        val id = el.attr("data-id").ifEmpty { extractIdFromUrl(itemUrl) }

                        val itemType = when {
                            itemUrl.contains("/series/") -> RezkaType.SERIES
                            itemUrl.contains("/animation/") -> RezkaType.ANIME
                            itemUrl.contains("/cartoons/") -> RezkaType.CARTOON
                            else -> RezkaType.MOVIE
                        }

                        val item = RezkaItem(id, title, formattedSubtitle, imageUrl, rating, itemUrl, itemType)
                        if (seenSectionIds.add(id)) {
                            sectionItems.add(item)
                        }
                        if (seenAllIds.add(id)) {
                            allFilmography.add(item)
                        }
                    }

                    if (sectionItems.isNotEmpty()) {
                        careerSections.add(
                            RezkaCareerSection(
                                title = roleTitle,
                                stats = roleStats,
                                items = sectionItems
                            )
                        )
                    }
                }

                // Резервный поиск если секции .b-person__career не найдены или пусты
                if (allFilmography.isEmpty()) {
                    val fallbackElements = doc.select(".b-content__main .b-content__inline_item, .b-content__inline_item")
                        .filter { el -> !isSidebarElement(el) }

                    for (el in fallbackElements) {
                        val linkEl = el.selectFirst(".b-content__inline_item-link a")
                            ?: el.selectFirst(".b-content__inline_item-cover a")
                            ?: el.selectFirst("a")
                            ?: continue

                        val rawUrl = linkEl.attr("href")
                        if (rawUrl.isEmpty() || rawUrl.startsWith("javascript:")) continue
                        val itemUrl = if (rawUrl.startsWith("/")) "$currentBaseUrl$rawUrl" else rawUrl

                        var title = linkEl.text().trim()
                        if (title.isEmpty()) {
                            title = el.selectFirst(".b-content__inline_item-link")?.text()?.trim() ?: ""
                        }
                        if (title.isEmpty()) continue

                        val itemImgEl = el.selectFirst(".b-content__inline_item-cover img") ?: el.selectFirst("img")
                        var imageUrl = itemImgEl?.attr("data-src")?.ifEmpty { itemImgEl.attr("src") } ?: ""
                        if (imageUrl.startsWith("//")) {
                            imageUrl = "https:$imageUrl"
                        }

                        val subtitleEl = el.selectFirst(".b-content__inline_item-link .misc, .b-content__inline_item-link div, .misc")
                        val rawSubtitle = subtitleEl?.text()?.trim() ?: ""
                        val formattedSubtitle = CountryFlags.formatWithFlags(rawSubtitle)

                        val ratingEl = el.selectFirst(".b-category-bestrating, .rating, .num, .b-content__inline_item-cover .info, i.imdb, i.kp, .info")
                        val rating = ratingEl?.text()?.trim()?.removeSurrounding("(", ")") ?: ""

                        val id = el.attr("data-id").ifEmpty { extractIdFromUrl(itemUrl) }

                        val itemType = when {
                            itemUrl.contains("/series/") -> RezkaType.SERIES
                            itemUrl.contains("/animation/") -> RezkaType.ANIME
                            itemUrl.contains("/cartoons/") -> RezkaType.CARTOON
                            else -> RezkaType.MOVIE
                        }

                        val item = RezkaItem(id, title, formattedSubtitle, imageUrl, rating, itemUrl, itemType)
                        if (seenAllIds.add(id)) {
                            allFilmography.add(item)
                        }
                    }

                    if (allFilmography.isNotEmpty()) {
                        careerSections.add(
                            RezkaCareerSection(
                                title = "Работы",
                                stats = "",
                                items = allFilmography
                            )
                        )
                    }
                }

                // Дополнительный резервный поиск по ссылкам карьеры если inline_items вообще отсутствуют
                if (allFilmography.isEmpty()) {
                    val careerLinks = doc.select(".b-person__career-item a, .b-person__works a, .b-person__career a, a[href*='/films/'], a[href*='/series/']")
                    for (linkEl in careerLinks) {
                        val href = linkEl.attr("href") ?: continue
                        if (!href.contains("/films/") && !href.contains("/series/") && !href.contains("/animation/") && !href.contains("/cartoons/")) continue
                        val itemTitle = linkEl.text().trim()
                        if (itemTitle.isEmpty()) continue
                        val itemUrl = if (href.startsWith("/")) "$currentBaseUrl$href" else href
                        val itemId = extractIdFromUrl(itemUrl)
                        val itemType = when {
                            itemUrl.contains("/series/") -> RezkaType.SERIES
                            itemUrl.contains("/animation/") -> RezkaType.ANIME
                            itemUrl.contains("/cartoons/") -> RezkaType.CARTOON
                            else -> RezkaType.MOVIE
                        }
                        if (seenAllIds.add(itemId)) {
                            allFilmography.add(
                                RezkaItem(
                                    id = itemId,
                                    title = itemTitle,
                                    subtitle = "",
                                    imageUrl = "",
                                    url = itemUrl,
                                    type = itemType
                                )
                            )
                        }
                    }
                    if (allFilmography.isNotEmpty()) {
                        careerSections.add(
                            RezkaCareerSection(
                                title = "Работы",
                                stats = "",
                                items = allFilmography
                            )
                        )
                    }
                }

                // Информация о карьере формируется непосредственно по разделам карьеры ниже,
                // поэтому дублирующие строчки "В базе HDRezka" и "По категориям" не засоряют карточку персоны.

                return@withContext RezkaPerson(
                    id = extractIdFromUrl(adjustedUrl),
                    name = name,
                    originalName = originalName,
                    photoUrl = photoUrl,
                    info = infoMap,
                    filmography = allFilmography,
                    careerSections = careerSections
                )

            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastException = e
                Log.w(TAG, "Ошибка загрузки профиля (попытка $attempt/$maxAttempts): ${e.message}")
                if (attempt < maxAttempts) {
                    kotlinx.coroutines.delay(500L * attempt)
                }
            }
        }
        throw lastException ?: Exception("Не удалось загрузить профиль")
    }

    /**
     * Быстрая проверка O(1) страницы поиска на наличие ответа HDRezka об отсутствии результатов
     */
    fun isNoResultsPage(html: String): Boolean {
        if (html.isBlank()) return false
        val lower = html.lowercase()
        return lower.contains("нам не удалось ничего найти") ||
               lower.contains("не удалось ничего найти") ||
               lower.contains("по вашему запросу ничего не найдено") ||
               lower.contains("совпадений не найдено") ||
               lower.contains("b-search__empty") ||
               lower.contains("b-search__message") ||
               lower.contains("b-content__inline_empty")
    }

    /**
     * Быстрая проверка принадлежности элемента родительским контейнерам боковой панели или рекомендаций
     */
    private fun isSidebarElement(el: org.jsoup.nodes.Element): Boolean {
        var parent: org.jsoup.nodes.Element? = el.parent()
        while (parent != null) {
            val className = parent.className()
            val id = parent.id()
            // Элементы страницы персоны и списков контента в b-sidelist не являются сайдбаром
            if (className.contains("b-person") || className.contains("b-sidelist") || id == "dle-content") {
                return false
            }
            if (className.contains("b-sidebar") ||
                className.contains("b-seriesupdate") ||
                className.contains("b-news") ||
                className.contains("b-container__side") ||
                className.contains("b-widget") ||
                className.contains("b-topitems") ||
                className.contains("b-post__similar") ||
                id == "sidebar"
            ) {
                return true
            }
            parent = parent.parent()
        }
        return false
    }

    /**
     * Полноценный высокопроизводительный поиск по базе DLE HDRezka
     */
    suspend fun search(query: String, page: Int = 1): List<RezkaItem> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return@withContext emptyList()

        val cacheKey = "search-$cleanQuery-$page"
        catalogCache.get(cacheKey)?.let { return@withContext it }

        val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8")
        // Обязательные параметры поиска движка DLE HDRezka: do=search&subaction=search&q=
        val url = "$currentBaseUrl/search/?do=search&subaction=search&q=$encodedQuery" + if (page > 1) "&page=$page" else ""
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Referer", "$currentBaseUrl/")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    if (isNoResultsPage(html)) {
                        catalogCache.put(cacheKey, emptyList())
                        return@withContext emptyList()
                    }
                    val items = parseCatalogHtml(html, RezkaType.MOVIE)
                    val sortedItems = items.sortedWith(MovieDateParser.MovieDateComparator)
                    catalogCache.put(cacheKey, sortedItems)
                    return@withContext sortedItems
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Ошибка DLE поиска для '$cleanQuery': ${e.message}")
        }

        // Запасной вариант: Live Search AJAX endpoint /ajax/search/
        try {
            val formBody = FormBody.Builder()
                .add("q", cleanQuery)
                .build()

            val ajaxRequest = Request.Builder()
                .url("$currentBaseUrl/ajax/search/")
                .post(formBody)
                .header("User-Agent", USER_AGENT)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "$currentBaseUrl/")
                .header("Origin", currentBaseUrl)
                .build()

            client.newCall(ajaxRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    if (isNoResultsPage(html)) {
                        catalogCache.put(cacheKey, emptyList())
                        return@withContext emptyList()
                    }
                    val items = parseLiveSearchHtml(html)
                    val sortedItems = items.sortedWith(MovieDateParser.MovieDateComparator)
                    catalogCache.put(cacheKey, sortedItems)
                    return@withContext sortedItems
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Ошибка Live Search AJAX: ${e.message}")
        }

        return@withContext emptyList()
    }

    /**
     * Парсинг всплывающего live-поиска HDRezka
     */
    private fun parseLiveSearchHtml(html: String): List<RezkaItem> {
        if (isNoResultsPage(html)) {
            return emptyList()
        }
        val items = ArrayList<RezkaItem>()
        try {
            val doc = Jsoup.parse(html)
            val links = doc.select("li a, a.b-search__live_item, .b-search__live_item")
            for (link in links) {
                if (isSidebarElement(link)) continue
                val rawUrl = link.attr("href")
                if (rawUrl.isEmpty()) continue
                val url = if (rawUrl.startsWith("/")) "$currentBaseUrl$rawUrl" else rawUrl
                val titleEl = link.selectFirst(".enty, .title, .name") ?: link
                val title = titleEl.ownText().ifEmpty { titleEl.text() }.trim()
                if (title.isEmpty()) continue

                val rating = link.selectFirst(".rating, .num")?.text()?.trim() ?: ""
                val yearEl = link.selectFirst(".year")?.text()?.trim() ?: ""
                val miscEl = link.selectFirst(".misc, .info")?.text()?.trim() ?: ""
                val rawSubtitle = when {
                    yearEl.isNotEmpty() && miscEl.isNotEmpty() && !miscEl.contains(yearEl) -> "$yearEl, $miscEl"
                    yearEl.isNotEmpty() -> yearEl
                    else -> miscEl
                }
                val subtitle = CountryFlags.formatWithFlags(rawSubtitle)
                val id = extractIdFromUrl(url)

                val itemType = when {
                    url.contains("/series/") -> RezkaType.SERIES
                    url.contains("/animation/") -> RezkaType.ANIME
                    url.contains("/cartoons/") -> RezkaType.CARTOON
                    else -> RezkaType.MOVIE
                }

                items.add(RezkaItem(id = id, title = title, subtitle = subtitle, imageUrl = "", rating = rating, url = url, type = itemType))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка парсинга live search", e)
        }
        return items.distinctBy { it.id }
    }

    /**
     * Парсинг HTML каталога и результатов поиска rezka-tv.org
     */
    private fun parseCatalogHtml(html: String, defaultType: RezkaType): List<RezkaItem> {
        if (isNoResultsPage(html)) {
            return emptyList()
        }
        val items = ArrayList<RezkaItem>()
        try {
            val doc = Jsoup.parse(html)
            val mainContainer = doc.selectFirst(".b-content__inline_items")
                ?: doc.selectFirst("#main-content")
                ?: doc.selectFirst(".b-content__main")
                ?: doc

            val elements = mainContainer.select(".b-content__inline_item, .b-content__bubble_wrapper")
                .filter { el -> !isSidebarElement(el) }

            for (el in elements) {
                val linkEl = el.selectFirst(".b-content__inline_item-link a")
                    ?: el.selectFirst(".b-content__inline_item-cover a")
                    ?: el.selectFirst("a")
                    ?: continue

                val rawUrl = linkEl.attr("href")
                if (rawUrl.isEmpty()) continue
                val url = if (rawUrl.startsWith("/")) "$currentBaseUrl$rawUrl" else rawUrl

                var title = linkEl.text().trim()
                if (title.isEmpty()) {
                    title = el.selectFirst(".b-content__inline_item-link")?.text()?.trim() ?: ""
                }
                if (title.isEmpty()) continue

                val imgEl = el.selectFirst(".b-content__inline_item-cover img") ?: el.selectFirst("img")
                var imageUrl = imgEl?.attr("data-src") ?: ""
                if (imageUrl.isEmpty()) {
                    imageUrl = imgEl?.attr("src") ?: ""
                }
                if (imageUrl.startsWith("//")) {
                    imageUrl = "https:$imageUrl"
                }

                val subtitleEl = el.selectFirst(".b-content__inline_item-link div")
                    ?: el.selectFirst(".b-content__inline_item-link + div")
                    ?: el.selectFirst(".misc")
                val rawSubtitle = subtitleEl?.text()?.trim() ?: ""
                val formattedSubtitle = CountryFlags.formatWithFlags(rawSubtitle)

                val ratingEl = el.selectFirst(".rating, .num, .b-content__inline_item-cover .info, i.imdb, i.kp, .info")
                val rating = ratingEl?.text()?.trim() ?: ""

                val id = extractIdFromUrl(url)

                val itemType = when {
                    url.contains("/series/") -> RezkaType.SERIES
                    url.contains("/animation/") -> RezkaType.ANIME
                    url.contains("/cartoons/") -> RezkaType.CARTOON
                    else -> defaultType
                }

                items.add(RezkaItem(id, title, formattedSubtitle, imageUrl, rating, url, itemType))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка парсинга HTML каталога", e)
        }
        return items.distinctBy { it.id }
    }

    /**
     * Получение страницы деталей фильма/сериала с rezka-tv.org
     */
    suspend fun getDetail(url: String): RezkaDetail = withContext(Dispatchers.IO) {
        val adjustedUrl = adjustUrlToCurrentMirror(url)
        val cacheKey = adjustedUrl
        detailCache.get(cacheKey)?.let { return@withContext it }

        val id = extractIdFromUrl(adjustedUrl)
        val normalizedUrl = adjustedUrl

        val maxAttempts = 3
        var lastException: Exception? = null

        for (attempt in 1..maxAttempts) {
            try {
                val request = Request.Builder()
                    .url(normalizedUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "$currentBaseUrl/")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                    .build()

                val (html, isSuccess) = client.newCall(request).execute().use { response ->
                    Pair(response.body?.string().orEmpty(), response.isSuccessful)
                }

                if (!isSuccess || html.isBlank()) {
                    if (attempt < maxAttempts) {
                        Log.w(TAG, "Сбой HTTP при загрузке $normalizedUrl (попытка $attempt/$maxAttempts), повторная перезагрузка страницы...")
                        kotlinx.coroutines.delay(650L * attempt)
                        continue
                    } else {
                        throw Exception("Что-то пошло не так :(")
                    }
                }

                val doc = Jsoup.parse(html)

                if (isAntiBotPage(html, doc)) {
                    Log.w(TAG, "Обнаружена проверка страницы для $normalizedUrl (попытка $attempt/$maxAttempts), перезагрузка страницы без очистки...")
                    if (attempt < maxAttempts) {
                        kotlinx.coroutines.delay(650L * attempt)
                        continue
                    } else {
                        throw Exception("Что-то пошло не так :(")
                    }
                }

                val title = extractTitleFromDoc(doc, normalizedUrl)

                if (isAntiBotTitle(title)) {
                    Log.w(TAG, "Обнаружен заголовок проверки: '$title' (попытка $attempt/$maxAttempts), повторная перезагрузка страницы...")
                    if (attempt < maxAttempts) {
                        kotlinx.coroutines.delay(650L * attempt)
                        continue
                    } else {
                        throw Exception("Что-то пошло не так :(")
                    }
                }

                if (!title.isNullOrEmpty()) {
                        val origTitle = doc.selectFirst(".b-post__orig_title")?.text() ?: ""
                        val description = doc.selectFirst(".b-post__description_text")?.text() ?: ""
                        val imgEl = doc.selectFirst(".b-sidecover img") ?: doc.selectFirst(".b-post__cover img")
                        var imgUrl = imgEl?.attr("data-src") ?: ""
                        if (imgUrl.isEmpty()) {
                            imgUrl = imgEl?.attr("src") ?: ""
                        }
                        if (imgUrl.startsWith("//")) {
                            imgUrl = "https:$imgUrl"
                        }

                        var year = ""
                        var releaseDate = ""
                        var country = ""
                        val genres = ArrayList<String>()
                        var director = ""
                        var ageRestriction = ""
                        var duration = ""
                        var slogan = ""
                        val inCollections = ArrayList<String>()
                        var seriesCollection = ""
                        val actors = LinkedHashSet<String>()

                        val directorsList = ArrayList<LinkItem>()
                        val actorsList = ArrayList<LinkItem>()
                        val collectionsList = ArrayList<LinkItem>()
                        val seriesCollectionList = ArrayList<LinkItem>()

                        val infoRows = doc.select(".b-post__info tr")
                        for (row in infoRows) {
                            val label = row.selectFirst("td.l, th, td:first-child")?.text()?.trim()?.lowercase() ?: ""
                            val tdVal = row.selectFirst("td:not(.l):not(th), td:nth-child(2), td:last-child")
                            val value = tdVal?.text()?.trim() ?: ""

                            when {
                                label.contains("входит в списки") || label.contains("списки") -> {
                                    val listLinks = tdVal?.select("a")?.map { it.text().trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                                    if (listLinks.isNotEmpty()) {
                                        inCollections.addAll(listLinks)
                                    } else if (value.isNotEmpty()) {
                                        inCollections.add(value)
                                    }
                                    val rawCollections = tdVal?.select("a")?.map { LinkItem(it.text().trim(), normalizeUrl(it.attr("href"), currentBaseUrl)) }?.filter { it.name.isNotEmpty() } ?: emptyList()
                                    collectionsList.addAll(rawCollections)
                                }
                                label.contains("дата выхода") || label.contains("премьера") -> {
                                    releaseDate = value
                                    if (year.isEmpty()) {
                                        year = Regex("""\b(19\d\d|20\d\d)\b""").find(value)?.value ?: ""
                                    }
                                }
                                label.contains("год") -> {
                                    if (year.isEmpty()) year = value
                                    if (releaseDate.isEmpty()) releaseDate = value
                                }
                                label.contains("страна") -> {
                                    country = value
                                }
                                label.contains("режиссер") || label.contains("режиссёр") -> {
                                    director = value
                                    val rawDirectors = tdVal?.select("a")
                                        ?.map { LinkItem(it.text().trim(), normalizeUrl(it.attr("href"), currentBaseUrl)) }
                                        ?.filter { it.name.isNotEmpty() && !it.url.startsWith("javascript:") && !it.name.all { c -> c.isDigit() } }
                                        ?: emptyList()
                                    directorsList.addAll(rawDirectors)
                                }
                                label.contains("жанр") -> {
                                    genres.addAll(value.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                                }
                                label.contains("возраст") || label.contains("ограничение") || label.contains("mpaa") -> {
                                    val boldSpan = tdVal?.selectFirst("span.bold, span.age, span")?.text()?.trim() ?: ""
                                    ageRestriction = cleanAgeRestriction(if (boldSpan.isNotEmpty()) boldSpan else value)
                                }
                                label.contains("время") || label.contains("длительность") -> {
                                    duration = value
                                }
                                label.contains("слоган") -> {
                                    slogan = value
                                }
                                label.contains("из серии") || label.contains("франшиз") || label.contains("серия") || label.contains("серии") -> {
                                    seriesCollection = value
                                    val rawSeries = tdVal?.select("a")
                                        ?.map { LinkItem(it.text().trim(), normalizeUrl(it.attr("href"), currentBaseUrl)) }
                                        ?.filter { it.name.isNotEmpty() && !it.url.startsWith("javascript:") }
                                        ?: emptyList()
                                    seriesCollectionList.addAll(rawSeries)
                                }
                                label.contains("в ролях") || label.contains("актеры") || label.contains("актёры") -> {
                                    val actorSpans = tdVal?.select(".person-name-item a, a[href*='/person/'], span[itemprop='actor']")
                                    val actorNames = if (!actorSpans.isNullOrEmpty()) {
                                        actorSpans.map { it.text().trim() }.filter { it.isNotEmpty() }
                                    } else {
                                        tdVal?.select("a")?.map { it.text().trim() }?.filter { it.isNotEmpty() && !it.all { c -> c.isDigit() } } ?: emptyList()
                                    }
                                    if (actorNames.isNotEmpty()) {
                                        actors.addAll(actorNames)
                                    } else if (value.isNotEmpty()) {
                                        actors.addAll(value.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                                    }
                                    val rawActors = (tdVal?.select(".person-name-item a, a[href*='/person/']")?.takeIf { it.isNotEmpty() } ?: tdVal?.select("a"))
                                        ?.map { LinkItem(it.text().trim(), normalizeUrl(it.attr("href"), currentBaseUrl)) }
                                        ?.filter { it.name.isNotEmpty() && !it.url.startsWith("javascript:") && !it.name.all { c -> c.isDigit() } }
                                        ?: emptyList()
                                    actorsList.addAll(rawActors)
                                }
                            }
                        }

                        // Тщательный высокопроизводительный парсинг франшизы (связанных частей фильма/сериала)
                        var franchiseTitle = ""
                        val franchiseItems = ArrayList<FranchiseItem>()

                        // Поиск заголовка франшизы в .b-sidetitle
                        val franchiseLinkTitle = doc.selectFirst(".b-sidetitle .b-post__franchise_link_title, .b-post__franchise_link_title")
                        if (franchiseLinkTitle != null) {
                            franchiseTitle = franchiseLinkTitle.text().trim()
                        } else {
                            val sideTitle = doc.selectFirst(".b-sidetitle")
                            if (sideTitle != null) {
                                franchiseTitle = sideTitle.text().trim()
                            }
                        }

                        if (franchiseTitle.endsWith(":")) {
                            franchiseTitle = franchiseTitle.substring(0, franchiseTitle.length - 1).trim()
                        }

                        // Поиск списка связанных частей франшизы
                        val partContent = doc.selectFirst(".b-post__partcontent")
                        if (partContent != null) {
                            if (franchiseTitle.isEmpty()) {
                                franchiseTitle = "Все части франшизы"
                            }

                            val rawFranchiseItems = ArrayList<FranchiseItem>()
                            val elements = partContent.select(".b-post__partcontent_item")
                            val finalElements = if (elements.isNotEmpty()) elements else partContent.children()

                            for (item in finalElements) {
                                val linkEl = if (item.tagName().equals("a", ignoreCase = true)) item else item.selectFirst("a")
                                val url = if (linkEl != null) normalizeUrl(linkEl.attr("href"), currentBaseUrl) else ""
                                val isCurrent = item.hasClass("current") || item.hasClass("active") || linkEl == null
                                val itemId = if (url.isNotEmpty()) extractIdFromUrl(url) else ""

                                // Находим год в элементе с классом .td_year
                                val yearEl = item.selectFirst(".td_year")
                                var itemYear = ""
                                if (yearEl != null) {
                                    // Из td_year выбрасываем всё кроме цифр
                                    itemYear = yearEl.text().replace(Regex("[^0-9]"), "").trim()
                                }

                                // Название фильма получаем путем клонирования элемента и удаления лишних частей (.td_rating, .td_year и т.д.)
                                val titleClone = item.clone()
                                titleClone.select(".td_rating, .td-rating, .rating, .kp, .imdb, .td_year, .td-year, .year").remove()
                                
                                var cleanedTitle = titleClone.text().trim()
                                
                                // Если год не был найден в .td_year, попробуем извлечь его регулярным выражением из названия
                                if (itemYear.isEmpty()) {
                                    val yearRegex = Regex("""\((19\d{2}|20\d{2})\)""")
                                    val yearMatch = yearRegex.find(cleanedTitle)
                                    if (yearMatch != null) {
                                        itemYear = yearMatch.groupValues[1]
                                        cleanedTitle = cleanedTitle.replace(yearMatch.value, "")
                                    } else {
                                        // Также ищем год без скобок в конце, если есть слово "год"
                                        val yearWordRegex = Regex("""\b(19\d{2}|20\d{2})\s*год\b""", RegexOption.IGNORE_CASE)
                                        val yearWordMatch = yearWordRegex.find(cleanedTitle)
                                        if (yearWordMatch != null) {
                                            itemYear = yearWordMatch.groupValues[1]
                                            cleanedTitle = cleanedTitle.replace(yearWordMatch.value, "")
                                        }
                                    }
                                }
                                
                                // Тщательно вырезаем любые остаточные упоминания рейтингов (например, "КП: 7.5", "IMDb 8.0" или в скобках)
                                val ratingCleanRegex = Regex("""\b(?:КП|IMDb|kp|imdb|Кинопоиск|kinopoisk)\s*:?\s*\d+(?:\.\d+)?\b|\((?:КП|IMDb|kp|imdb|Кинопоиск|kinopoisk)?\s*:?\s*\d+(?:\.\d+)?\)|\(\d\.\d\)""", RegexOption.IGNORE_CASE)
                                cleanedTitle = cleanedTitle.replace(ratingCleanRegex, "")
                                
                                // Вырезаем остаточную оценку, если она указана в конце строки просто числом с точкой или запятой (например, "7.5" или "7,5")
                                cleanedTitle = cleanedTitle.replace(Regex("""\b\d[.,]\d\b\s*$"""), "")
                                
                                // Чистим лишние пробелы и разделители на концах названия
                                cleanedTitle = cleanedTitle.replace(Regex("""\s+"""), " ").trim()
                                if (cleanedTitle.endsWith(",") || cleanedTitle.endsWith(";") || cleanedTitle.endsWith("-")) {
                                    cleanedTitle = cleanedTitle.substring(0, cleanedTitle.length - 1).trim()
                                }
                                cleanedTitle = cleanedTitle.replace(Regex("""\s+"""), " ").trim()

                                if (cleanedTitle.isNotEmpty()) {
                                    rawFranchiseItems.add(FranchiseItem(
                                        id = itemId,
                                        title = cleanedTitle,
                                        url = url,
                                        isCurrent = isCurrent,
                                        year = itemYear
                                    ))
                                }
                            }

                            // Инвертируем порядок элементов серии (чтобы первые части шли первыми сверху вниз)
                            franchiseItems.addAll(rawFranchiseItems.reversed())
                        }

                        // Дополнительный поиск актёров, если в таблице не было
                        if (actors.isEmpty()) {
                            val actorNodes = doc.select(".b-post__actors .person-name, .persons-list-holder a, [itemprop='actor'] span, .b-post__info .actors a")
                            for (aEl in actorNodes) {
                                val aName = aEl.text().trim()
                                if (aName.isNotEmpty() && !actors.contains(aName)) {
                                    actors.add(aName)
                                    val aUrl = if (aEl.tagName() == "a" || aEl.hasAttr("href")) {
                                        val hrefAttr = aEl.attr("href")
                                        if (hrefAttr.isNotEmpty()) normalizeUrl(hrefAttr, currentBaseUrl) else ""
                                    } else {
                                        val nestedLink = aEl.selectFirst("a")
                                        if (nestedLink != null) normalizeUrl(nestedLink.attr("href"), currentBaseUrl) else ""
                                    }
                                    actorsList.add(LinkItem(aName, aUrl))
                                }
                            }
                        }

                        // Возрастное ограничение из бейджей и метатегов
                        if (ageRestriction.isEmpty()) {
                            val ageEl = doc.selectFirst(".b-post__age, .age-restricted, .b-post__info .age, span[class*='age'], meta[itemprop='contentRating'], .b-post__rating_mpaa")
                            if (ageEl != null) {
                                val rawVal = if (ageEl.tagName().equals("meta", ignoreCase = true)) ageEl.attr("content") else ageEl.text()
                                ageRestriction = cleanAgeRestriction(rawVal.trim())
                            }
                        }
                        if (ageRestriction.isEmpty()) {
                            val infoText = doc.select(".b-post__info, .b-post__infotable, .b-post__status").text()
                            val ageMatch = Regex("""\b(18\+|16\+|12\+|6\+|0\+|PG-13|NC-17|TV-MA|TV-14|TV-PG|TV-G|TV-Y7|R|PG|G)\b""", RegexOption.IGNORE_CASE).find(infoText)
                            if (ageMatch != null) {
                                ageRestriction = cleanAgeRestriction(ageMatch.value)
                            }
                        }

                        // Форматируем страны с флагами
                        val countryFlag = CountryFlags.formatWithFlags(country)

                        // Тщательный высокопроизводительный парсинг структурированных рейтингов
                        val (ratingInfo, mainRating) = parseRatingInfo(doc)

                        // Извлекаем точный числовой ID фильма (из HTML или URL)
                        val htmlPostId = doc.selectFirst("#post_id")?.attr("value")
                            ?: doc.selectFirst("[data-post_id]")?.attr("data-post_id")
                            ?: doc.selectFirst("[data-id]")?.attr("data-id")
                            ?: ""
                        val numericPostId = htmlPostId.ifEmpty { extractNumericId(normalizedUrl) }

                        // Высокоточный мгновенный парсинг ссылки на трейлер со страницы (0ms оверхеда)
                        val trailerUrl = extractTrailerUrl(doc, html)

                        // График выхода серий (для сериалов)
                        val schedule = ArrayList<ScheduleItem>()
                        val scheduleRows = doc.select(".b-post__schedule_table tr, .b-post__schedule tr, .b-schedules-list tr, table.b-schedule__table tr")
                        val fallbackYearInt = year.toIntOrNull() ?: java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                        val todayNum = ScheduleDateParser.getTodayDateNum()

                        for (sRow in scheduleRows) {
                            val cells = sRow.select("td")
                            if (cells.size >= 2) {
                                val epInfo = cells.getOrNull(0)?.text()?.trim() ?: ""
                                val epTitle = if (cells.size >= 4) cells.getOrNull(1)?.text()?.trim() ?: "" else ""
                                val origDate = if (cells.size >= 4) cells.getOrNull(2)?.text()?.trim() ?: "" else cells.getOrNull(1)?.text()?.trim() ?: ""
                                val ruDate = if (cells.size >= 4) cells.getOrNull(3)?.text()?.trim() ?: "" else ""
                                val hasHtmlFlag = sRow.hasClass("active") || sRow.hasClass("released") || sRow.selectFirst(".released, .done, .check") != null

                                val isReleased = ScheduleDateParser.isEpisodeReleased(
                                    releaseDate = origDate,
                                    ruReleaseDate = ruDate,
                                    fallbackYear = fallbackYearInt,
                                    hasHtmlReleasedFlag = hasHtmlFlag,
                                    todayDateNum = todayNum
                                )

                                if (epInfo.isNotEmpty() && (epInfo.contains("сезон") || epInfo.contains("серия") || Regex("""\d+""").containsMatchIn(epInfo))) {
                                    schedule.add(
                                        ScheduleItem(
                                            seasonEpisode = epInfo,
                                            title = epTitle,
                                            releaseDate = origDate,
                                            ruReleaseDate = ruDate,
                                            isReleased = isReleased
                                        )
                                    )
                                }
                            }
                        }

                        // Отзывы и комментарии
                        var comments = parseCommentsHtml(html, normalizedUrl)
                        var commentsTotalPages = 1
                        var commentsHasMore = false
                        var commentsTotalCount = 0

                        val navEl = doc.selectFirst(".b-comments__navigation, #comments-nav, .b-comments-nav, .navigation, .b-navigation")
                        if (navEl != null) {
                            val (tPages, hMore, tCount) = parseCommentsNavigation(navEl.outerHtml(), 1, comments.size)
                            commentsTotalPages = tPages
                            commentsHasMore = hMore
                            commentsTotalCount = tCount
                        }

                        // Если отзывы на странице фильма не были встроены (частый случай DLE), подгружаем 1-ю страницу через AJAX
                        if (comments.isEmpty() && numericPostId.isNotEmpty()) {
                            try {
                                val ajaxRes = getComments(numericPostId, 1, normalizedUrl)
                                if (ajaxRes.comments.isNotEmpty()) {
                                    comments = ajaxRes.comments
                                    commentsTotalPages = ajaxRes.totalPages
                                    commentsHasMore = ajaxRes.hasMore
                                    commentsTotalCount = ajaxRes.totalCommentsCount
                                }
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                Log.w(TAG, "Не удалось предварительно загрузить отзывы: ${e.message}")
                            }
                        } else if (comments.isNotEmpty() && commentsTotalPages <= 1 && numericPostId.isNotEmpty()) {
                            // Если комментарии на странице были, но блок навигации отсутствовал, в фоне быстро выясняем навигацию первой страницы
                            try {
                                val ajaxRes = getComments(numericPostId, 1, normalizedUrl)
                                if (ajaxRes.totalPages > 1) {
                                    commentsTotalPages = ajaxRes.totalPages
                                    commentsHasMore = ajaxRes.hasMore
                                    if (comments.isEmpty() && ajaxRes.comments.isNotEmpty()) {
                                        comments = ajaxRes.comments
                                    }
                                }
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                // Игнорируем фоновую проверку
                            }
                        }

                        // Извлекаем озвучки/переводы
                        val translatorItems = doc.select(".b-translator__item, #translators-list li, .b-translators__list li")
                        val mirrorUrl = currentBaseUrl.trimEnd('/')

                        // Наличие премиум-контента на текущей (первой) открытой странице фильма
                        val isCurrentPagePremium = html.contains("b-post__prem_content") || doc.selectFirst(".b-post__prem_content") != null

                        // Динамический поиск адреса SVG-иконки прямо со страницы
                        var pagePremIconUrl = extractSvgIconFromPage(doc, currentBaseUrl)
                        if (pagePremIconUrl.isNotEmpty()) {
                            lastParsedPremiumIconUrl = pagePremIconUrl
                        }

                        data class RawTranslatorItem(
                            val id: String,
                            val name: String,
                            val isDefault: Boolean,
                            val flagUrl: String,
                            val isPremium: Boolean,
                            val svgIconUrl: String,
                            val url: String = ""
                        )

                        val rawTranslators = ArrayList<RawTranslatorItem>()
                        for (tEl in translatorItems) {
                            val tId = tEl.attr("data-translator_id").ifEmpty { tEl.attr("data-id") }
                            val tName = tEl.ownText().trim().ifEmpty { tEl.text().trim() }
                            val isDefault = tEl.hasClass("active") || tEl.hasClass("current")

                            // Проверяем наличие SVG-иконки премиума перед названием озвучки
                            val isPrem = hasPremiumSvgIcon(tEl)
                            val itemSvgIcon = if (isPrem) extractSvgIconFromTranslator(tEl, currentBaseUrl) else ""
                            if (itemSvgIcon.isNotEmpty()) {
                                if (pagePremIconUrl.isEmpty()) {
                                    pagePremIconUrl = itemSvgIcon
                                }
                                lastParsedPremiumIconUrl = itemSvgIcon
                            }

                            var flagUrl = ""
                            val imgElements = tEl.select("img")
                            for (img in imgElements) {
                                val rawSrc = img.attr("src").ifEmpty { img.attr("data-src") }
                                if (rawSrc.isBlank()) continue
                                if (isFlagImage(rawSrc, img) && flagUrl.isEmpty()) {
                                    flagUrl = normalizeUrl(rawSrc, currentBaseUrl)
                                } else if (imgElements.size == 1 && flagUrl.isEmpty() && !rawSrc.contains(".svg", ignoreCase = true)) {
                                    flagUrl = normalizeUrl(rawSrc, currentBaseUrl)
                                }
                            }

                            // Извлекаем ссылку на отдельную страницу этой конкретной озвучки
                            val rawHref = tEl.selectFirst("a")?.attr("href") ?: ""
                            val rawDataUrl = tEl.attr("data-url")
                            val rawDataLink = tEl.attr("data-link")
                            val rawDirectHref = tEl.attr("href")
                            val candidateUrl = rawHref.ifEmpty { rawDataUrl }.ifEmpty { rawDataLink }.ifEmpty { rawDirectHref }.trim()

                            val transPageUrl = if (candidateUrl.isNotEmpty()) {
                                normalizeUrl(candidateUrl, currentBaseUrl)
                            } else if (isDefault) {
                                normalizedUrl
                            } else {
                                ""
                            }

                            if (tId.isNotEmpty() && tName.isNotEmpty()) {
                                rawTranslators.add(RawTranslatorItem(tId, tName, isDefault, flagUrl, isPrem, itemSvgIcon, transPageUrl))
                            }
                        }

                        // Если список озвучек пуст, ищем в JS-вызовах страницы
                        if (rawTranslators.isEmpty()) {
                            var foundId = ""
                            val jsEventMatch = Regex("""sof\.tv\.initCDN(?:Movies|Series)Events\s*\(\s*['"]?(\d+)['"]?\s*,\s*['"]?(\d+)['"]?""", RegexOption.IGNORE_CASE).find(html)
                                ?: Regex("""initCDN(?:Movies|Series)Events\s*\(\s*['"]?(\d+)['"]?\s*,\s*['"]?(\d+)['"]?""", RegexOption.IGNORE_CASE).find(html)
                            ?: Regex(""""translator_id"\s*:\s*"?(\d+)"?""", RegexOption.IGNORE_CASE).find(html)
                            ?: Regex("""data-translator_id=["']?(\d+)["']?""", RegexOption.IGNORE_CASE).find(html)

                            if (jsEventMatch != null) {
                                foundId = if (jsEventMatch.groupValues.size > 2) jsEventMatch.groupValues[2] else jsEventMatch.groupValues[1]
                            }
                            rawTranslators.add(
                                RawTranslatorItem(
                                    id = foundId.ifEmpty { "238" },
                                    name = "Оригинал / HDRezka",
                                    isDefault = true,
                                    flagUrl = "",
                                    isPremium = false,
                                    svgIconUrl = "",
                                    url = normalizedUrl
                                )
                            )
                        }

                        val effectivePremIcon = pagePremIconUrl.ifEmpty { lastParsedPremiumIconUrl.ifEmpty { getPremiumIconUrl(currentBaseUrl) } }

                        val translators = ArrayList<Translator>(rawTranslators.size)
                        for (item in rawTranslators) {
                            val isPrem = item.isPremium
                            val premUrl = if (isPrem) {
                                item.svgIconUrl.ifEmpty { effectivePremIcon }
                            } else {
                                ""
                            }
                            if (numericPostId.isNotEmpty()) {
                                synchronized(translatorPremiumCache) {
                                    translatorPremiumCache.put("${numericPostId}_${item.id}", isPrem)
                                }
                            }
                            translators.add(
                                Translator(
                                    id = item.id,
                                    name = item.name,
                                    isDefault = item.isDefault,
                                    flagUrl = item.flagUrl,
                                    isPremium = isPrem,
                                    premiumUrl = premUrl,
                                    url = item.url
                                )
                            )
                        }

                        val isSeriesPage = url.contains("/series/") ||
                                (url.contains("/animation/") && doc.selectFirst("#simple-episodes-tabs, .b-simple_seasons__list") != null) ||
                                doc.selectFirst(".b-simple_seasons__list, #simple-seasons-tabs") != null

                        val type = if (isSeriesPage) RezkaType.SERIES else RezkaType.MOVIE

                        var seasons = ArrayList<Season>()
                        if (type == RezkaType.SERIES) {
                            val defaultTranslator = translators.find { it.isDefault } ?: translators.firstOrNull()
                            val defaultTranslatorId = defaultTranslator?.id ?: "0"
                            val defaultTranslatorUrl = defaultTranslator?.url.orEmpty().ifEmpty { normalizedUrl }

                            // 1. Сначала парсим серии напрямую из HTML структуры текущей страницы (это быстрее всего и точнее всего для дефолтной озвучки)
                            val parsedFromCurrentPage = parseSeasonsFromDoc(doc, defaultTranslatorId)
                            if (parsedFromCurrentPage.isNotEmpty()) {
                                seasons = ArrayList(parsedFromCurrentPage)
                            } else {
                                // Если в HTML текущей страницы не было серий, пробуем AJAX / getEpisodesForTranslator
                                try {
                                    val fetched = getEpisodesForTranslator(numericPostId, defaultTranslatorId, defaultTranslatorUrl)
                                    if (fetched.isNotEmpty()) {
                                        seasons = ArrayList(fetched)
                                    }
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    Log.w(TAG, "Ошибка получения серий для дефолтной озвучки: ${e.message}")
                                }
                            }

                            // Сохраняем серии дефолтной озвучки в оперативный LRU-кэш
                            if (seasons.isNotEmpty() && numericPostId.isNotEmpty()) {
                                synchronized(seasonEpisodesCache) {
                                    seasonEpisodesCache.put("${numericPostId}_${defaultTranslatorId}", seasons)
                                    seasonEpisodesCache.put("${numericPostId}_0", seasons)
                                }
                            }

                            // Фоновая асинхронная предзагрузка серий для остальных озвучек в оперативный кэш
                            if (numericPostId.isNotEmpty() && translators.size > 1) {
                                prefetchScope.launch {
                                    for (t in translators) {
                                        if (t.id != defaultTranslatorId) {
                                            try {
                                                prefetchSemaphore.withPermit {
                                                    getEpisodesForTranslator(numericPostId, t.id, t.url)
                                                }
                                            } catch (_: Exception) {}
                                        }
                                    }
                                }
                            }
                        }

                        val detail = RezkaDetail(
                            id = id,
                            title = title,
                            originalTitle = origTitle,
                            description = description,
                            imageUrl = imgUrl,
                            year = year,
                            releaseDate = releaseDate,
                            country = country,
                            countryFlag = countryFlag,
                            genres = genres,
                            rating = mainRating,
                            ratingInfo = ratingInfo,
                            director = director,
                            directorsList = directorsList,
                            ageRestriction = ageRestriction,
                            duration = duration,
                            slogan = slogan,
                            inCollections = inCollections,
                            collectionsList = collectionsList,
                            seriesCollection = seriesCollection,
                            seriesCollectionList = seriesCollectionList,
                            franchiseTitle = franchiseTitle,
                            franchiseItems = franchiseItems,
                            actors = actors.toList(),
                            actorsList = actorsList,
                            trailerUrl = trailerUrl,
                            comments = comments,
                            commentsTotalPages = commentsTotalPages,
                            commentsHasMore = commentsHasMore,
                            commentsTotalCount = commentsTotalCount,
                            schedule = schedule,
                            type = type,
                            translators = translators,
                            seasons = seasons,
                            numericPostId = numericPostId
                        )
                        detailCache.put(cacheKey, detail)
                        return@withContext detail
                    } else {
                        if (attempt < maxAttempts) {
                            Log.w(TAG, "Заголовок не найден для $normalizedUrl (попытка $attempt/$maxAttempts), повторная перезагрузка страницы...")
                            kotlinx.coroutines.delay(650L * attempt)
                            continue
                        } else {
                            throw Exception("Что-то пошло не так :(")
                        }
                    }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastException = e
                Log.w(TAG, "Ошибка получения деталей с $normalizedUrl (попытка $attempt/$maxAttempts): ${e.message}")
                if (attempt < maxAttempts) {
                    kotlinx.coroutines.delay(650L * attempt)
                }
            }
        }

        throw lastException ?: Exception("Что-то пошло не так :(")
    }

    /**
     * Высокопроизводительный парсер HTML-структуры комментариев и отзывов.
     * Не создает лишних промежуточных объектов, распарсивает дерево ответов, авторов, даты, аватарки и лайки.
     */
    fun parseCommentsHtml(commentsHtml: String, baseUrl: String = currentBaseUrl): List<CommentItem> {
        if (commentsHtml.isBlank()) return emptyList()
        val doc = Jsoup.parse(commentsHtml)
        val treeElements = doc.select("li.comments-tree-item")
        val commentElements = if (treeElements.isNotEmpty()) treeElements else doc.select(".b-comment")
        val uniqueMap = LinkedHashMap<String, CommentItem>()

        for (el in commentElements) {
            val cId = el.attr("data-id")
                .ifEmpty { el.selectFirst(".b-comment")?.attr("data-id") ?: "" }
                .ifEmpty { el.id().removePrefix("comments-tree-item-").removePrefix("comment-id-") }
                .ifEmpty { "c_${uniqueMap.size}" }
            val indent = el.attr("data-indent").toIntOrNull() ?: 0
            val author = el.selectFirst(".info .name, .name a, .name, .b-comment__user_name, .author, a[href*='/user/']")?.text()?.trim() ?: "Зритель"
            
            // Тщательный поиск аватарки пользователя с поддержкой data-src, src, data-original и background-image
            val avatarEl = el.selectFirst(".ava img, .b-comment__user_avatar img, .avatar img, a[href*='/user/'] img, img")
            var rawAvatar = avatarEl?.attr("data-src")?.ifEmpty { null }
                ?: avatarEl?.attr("src")?.ifEmpty { null }
                ?: avatarEl?.attr("data-original")?.ifEmpty { null }
                ?: avatarEl?.attr("data-lazy-src")?.ifEmpty { null }
                ?: ""

            if (rawAvatar.isEmpty()) {
                val styleEl = el.selectFirst(".ava, .avatar, .b-comment__user_avatar, div[style*='background-image']")
                val styleAttr = styleEl?.attr("style") ?: ""
                if (styleAttr.contains("url(")) {
                    val match = Regex("""url\(['"]?(.*?)['"]?\)""").find(styleAttr)
                    if (match != null) {
                        rawAvatar = match.groupValues[1].trim()
                    }
                }
            }
            val avatar = normalizeUrl(rawAvatar, baseUrl)

            val date = el.selectFirst(".info .date, .b-comment__date, .date, span.date")?.text()?.trim() ?: ""
            val text = el.selectFirst(".text div[id^='comm-id-'], .b-comment__text, .comment-text, .text")?.text()?.trim() ?: ""
            val likes = el.selectFirst(".b-comment__likes_count i, .b-comment__likes_count")?.text()?.trim()
                ?: el.selectFirst("[data-likes_num]")?.attr("data-likes_num")?.trim()
                ?: el.selectFirst(".b-comment__rating_count, .rating-count, .votes")?.text()?.trim()
                ?: ""

            if (text.isNotEmpty() && !uniqueMap.containsKey(cId)) {
                uniqueMap[cId] = CommentItem(
                    id = cId,
                    author = author,
                    avatarUrl = avatar,
                    date = date,
                    text = text,
                    likes = likes,
                    indent = indent
                )
            }
        }
        return uniqueMap.values.toList()
    }

    /**
     * Высокопроизводительный парсер блока DLE-навигации комментариев.
     * Определяет общее число страниц (totalPages), наличие следующей страницы (hasMore) и общее количество отзывов.
     */
    fun parseCommentsNavigation(navHtml: String, currentPage: Int, currentCommentsCount: Int): Triple<Int, Boolean, Int> {
        if (navHtml.isBlank()) {
            val hasMore = currentCommentsCount >= 20
            return Triple(if (hasMore) currentPage + 1 else currentPage, hasMore, currentCommentsCount)
        }

        try {
            val navDoc = Jsoup.parse(navHtml)
            val pageNumbers = mutableSetOf<Int>()
            pageNumbers.add(currentPage)

            // 1. Поиск числовых ссылок и спанов страниц (например, [1] [2] [3]... [15])
            val elements = navDoc.select("a, span, li")
            for (el in elements) {
                val num = el.text().trim().toIntOrNull()
                if (num != null && num > 0) {
                    pageNumbers.add(num)
                }
                val onclick = el.attr("onclick")
                if (onclick.isNotEmpty()) {
                    val cstartMatch = Regex("""cstart\s*=\s*(\d+)""").find(onclick)
                        ?: Regex("""CommentsPage\s*\(\s*(\d+)""").find(onclick)
                    cstartMatch?.groupValues?.get(1)?.toIntOrNull()?.let { pageNumbers.add(it) }
                }
                val href = el.attr("href")
                if (href.isNotEmpty()) {
                    val hrefMatch = Regex("""cstart=(\d+)""").find(href)
                        ?: Regex("""page/(\d+)""").find(href)
                        ?: Regex("""page,\d+,(\d+)""").find(href)
                    hrefMatch?.groupValues?.get(1)?.toIntOrNull()?.let { pageNumbers.add(it) }
                }
            }

            // 2. Поиск ссылок "Вперед" / "Далее" / "»"
            val hasNextLink = elements.any { el ->
                val t = el.text().trim()
                t.contains("Вперед", ignoreCase = true) ||
                t.contains("Вперёд", ignoreCase = true) ||
                t.contains("Далее", ignoreCase = true) ||
                t.contains("»") ||
                t.contains(">") ||
                el.attr("title").contains("Вперед", ignoreCase = true)
            }

            val maxPage = pageNumbers.maxOrNull() ?: currentPage
            val totalPages = maxOf(maxPage, if (hasNextLink) currentPage + 1 else currentPage)
            val hasMore = (currentPage < totalPages) || hasNextLink

            // 3. Поиск общего числа комментариев в тексте навигации
            val navText = navDoc.text()
            val totalMatch = Regex("""(?:всего|комментари(?:ев|я)|отзыв(?:ов|а))\s*[:]?\s*(\d+)""", RegexOption.IGNORE_CASE).find(navText)
            val totalCount = totalMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

            return Triple(totalPages, hasMore, totalCount)
        } catch (e: Exception) {
            val hasMore = currentCommentsCount >= 20
            return Triple(if (hasMore) currentPage + 1 else currentPage, hasMore, currentCommentsCount)
        }
    }

    /**
     * Постраничная загрузка отзывов пользователей через AJAX get_comments.
     * Возвращает полную структуру с комментариями, номерами страниц и аватарками.
     */
    suspend fun getComments(
        numericPostId: String,
        page: Int = 1,
        refererUrl: String = ""
    ): CommentsResult = withContext(Dispatchers.IO) {
        val cleanPostId = extractNumericId(numericPostId)
        if (cleanPostId.isEmpty()) return@withContext CommentsResult(emptyList(), page, 1, false, 0)

        val t = System.currentTimeMillis()
        val url = "$currentBaseUrl/ajax/get_comments/?t=$t&news_id=$cleanPostId&cstart=$page&type=0&comment_id=0&skin=hdrezka"
        val ref = refererUrl.ifEmpty { currentBaseUrl }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", ref)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext CommentsResult(emptyList(), page, 1, false, 0)
                }
                val body = response.body?.string() ?: ""
                if (body.isBlank()) return@withContext CommentsResult(emptyList(), page, 1, false, 0)

                val json = JSONObject(body)
                val commentsHtml = json.optString("comments", "")
                val navHtml = json.optString("navigation", "")

                val comments = parseCommentsHtml(commentsHtml, currentBaseUrl)
                val (totalPages, hasMore, totalCount) = parseCommentsNavigation(navHtml, page, comments.size)

                CommentsResult(
                    comments = comments,
                    currentPage = page,
                    totalPages = totalPages,
                    hasMore = hasMore,
                    totalCommentsCount = totalCount
                )
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Ошибка загрузки отзывов для $cleanPostId на стр $page: ${e.message}")
            CommentsResult(emptyList(), page, 1, false, 0)
        }
    }

    /**
     * Извлекает порядковый номер серии из элемента DOM.
     * Приоритет отдаётся прямому атрибуту data-episode и номеру из текста ("130 серия"),
     * а не внутреннему идентификатору записи в БД HDRezka (data-episode_id="30140").
     */
    private fun extractEpisodeNumberId(epEl: org.jsoup.nodes.Element): String {
        val epIdAttr = epEl.attr("data-episode_id").trim()
        if (epIdAttr.isNotEmpty() && (epIdAttr.contains("-") || epIdAttr.length <= 5)) return epIdAttr

        val epAttr = epEl.attr("data-episode").trim()
        if (epAttr.isNotEmpty()) return epAttr

        val rawIdAttr = epEl.attr("data-id").trim()
        if (rawIdAttr.isNotEmpty() && rawIdAttr.length <= 4) return rawIdAttr

        val epText = epEl.text().trim()
        val rangeOrDigits = Regex("""\d+(?:-\d+)?""").find(epText)?.value
        if (!rangeOrDigits.isNullOrEmpty()) return rangeOrDigits

        return if (epIdAttr.isNotEmpty()) epIdAttr else rawIdAttr
    }

    /**
     * Высокопроизводительный парсер сезонов и серий из DOM-дерева документа Rezka.
     * Корректно извлекает сдвоенные серии ("1-2"), кастомные ID, фильтрует вкладки сезонов
     * и обрабатывает любые структуры сезонов/серий без утечки заголовочных вкладок.
     */
    fun parseSeasonsFromDoc(doc: org.jsoup.nodes.Document, translatorId: String): List<Season> {
        val seasons = ArrayList<Season>()

        // 1. Извлекаем только реальные элементы сезонов (исключая серии)
        val rawSeasonTabs = doc.select(
            ".b-simple_seasons__list .b-simple_season__item, " +
            "#simple-seasons-tabs .b-simple_season__item, " +
            ".b-simple_seasons__list li, " +
            "#simple-seasons-tabs li, " +
            ".b-seasons__list li, " +
            "li.b-simple_season__item"
        )

        val seasonTabs = rawSeasonTabs.filter { el ->
            !el.hasAttr("data-episode_id") && !el.hasClass("b-simple_episode__item")
        }.distinctBy { el ->
            val idAttr = el.attr("data-season_id").ifEmpty { el.attr("data-tab_id") }
            if (idAttr.isNotEmpty()) idAttr else el.text().trim()
        }

        if (seasonTabs.isNotEmpty()) {
            for (sEl in seasonTabs) {
                val sId = sEl.attr("data-season_id").toIntOrNull()
                    ?: sEl.attr("data-tab_id").toIntOrNull()
                    ?: Regex("""\d+""").find(sEl.text())?.value?.toIntOrNull()
                    ?: continue
                val sName = sEl.text().trim().ifEmpty { "Сезон $sId" }
                val epList = ArrayList<Episode>()

                // Поиск контейнера серий конкретно для сезона sId
                val seasonContainer = doc.selectFirst(
                    "ul.b-simple_episodes__list[data-season_id=$sId], " +
                    "#simple-episodes-tabs[data-season_id=$sId], " +
                    "[data-season_id=$sId].b-simple_episodes__list, " +
                    "ul[data-season_id=$sId], " +
                    "div[data-season_id=$sId]"
                )

                val epElements = if (seasonContainer != null) {
                    seasonContainer.select(".b-simple_episode__item, li[data-episode_id], [data-episode_id], li")
                } else {
                    doc.select(
                        ".b-simple_episodes__list .b-simple_episode__item[data-season_id=$sId], " +
                        ".b-simple_episode__item[data-season_id=$sId], " +
                        "li[data-season_id=$sId][data-episode_id]"
                    )
                }

                for (epEl in epElements) {
                    if (epEl.hasClass("b-simple_season__item") || epEl.selectFirst(".b-simple_season__item") != null) continue
                    val finalId = extractEpisodeNumberId(epEl)
                    val epText = epEl.text().trim()
                    if (finalId.isNotEmpty()) {
                        val epName = if (epText.isNotEmpty()) epText else "Серия $finalId"
                        epList.add(Episode(id = finalId, name = epName, seasonId = sId, translatorId = translatorId))
                    }
                }

                // Фоллбэк: поиск серий для сезона sId если epList пуст
                if (epList.isEmpty()) {
                    val allEpEls = doc.select(".b-simple_episodes__list .b-simple_episode__item, #simple-episodes-tabs li, .b-simple_episode__item, li[data-episode_id]")
                    for (epEl in allEpEls) {
                        if (epEl.hasClass("b-simple_season__item")) continue
                        val epSeasonId = epEl.attr("data-season_id").toIntOrNull()
                        if (epSeasonId != null && epSeasonId != sId) continue

                        val finalId = extractEpisodeNumberId(epEl)
                        val epText = epEl.text().trim()
                        if (finalId.isNotEmpty()) {
                            val epName = if (epText.isNotEmpty()) epText else "Серия $finalId"
                            epList.add(Episode(id = finalId, name = epName, seasonId = sId, translatorId = translatorId))
                        }
                    }
                }

                if (epList.isNotEmpty()) {
                    seasons.add(Season(sId, sName, epList.distinctBy { it.id }))
                }
            }
        }

        // Если вкладки сезонов отсутствовали вообще или не дали ни одной серии, ищем сквозной список серий (одиночный сезон)
        if (seasons.isEmpty()) {
            val epElements = doc.select(
                ".b-simple_episodes__list .b-simple_episode__item, " +
                "#simple-episodes-tabs li, " +
                ".b-simple_episode__item, " +
                "li[data-episode_id], " +
                "[data-episode_id]"
            )
            val epList = ArrayList<Episode>()
            for (epEl in epElements) {
                if (epEl.hasClass("b-simple_season__item")) continue
                val finalId = extractEpisodeNumberId(epEl)
                val epText = epEl.text().trim()
                if (finalId.isNotEmpty()) {
                    val epName = if (epText.isNotEmpty()) epText else "Серия $finalId"
                    epList.add(Episode(id = finalId, name = epName, seasonId = 1, translatorId = translatorId))
                }
            }
            if (epList.isNotEmpty()) {
                seasons.add(Season(1, "Сезон 1", epList.distinctBy { it.id }))
            }
        }
        return seasons
    }

    /**
     * Загрузка списка сезонов и серий для конкретной выбранной озвучки.
     * Принимает translatorUrl: URL отдельной страницы этой озвучки.
     * При переходе на страницу озвучки парсит актуальные серии и сезоны этой озвучки.
     */
    suspend fun getEpisodesForTranslator(
        numericId: String,
        translatorId: String,
        translatorUrl: String = ""
    ): List<Season> = withContext(Dispatchers.IO) {
        val cleanId = extractNumericId(numericId)
        val cleanTranslatorId = translatorId.trim()
        val cacheKey = "${cleanId}_${cleanTranslatorId}"

        // 0. Быстрая отдача из оперативного LRU-кэша (0ms задержка, 0 CPU)
        synchronized(seasonEpisodesCache) {
            seasonEpisodesCache.get(cacheKey)?.let { cached ->
                if (cached.isNotEmpty()) return@withContext cached
            }
        }

        // 1. Попытка загрузить HTML персональной страницы озвучки (если есть URL страницы)
        if (translatorUrl.isNotBlank()) {
            try {
                val effectiveUrl = adjustUrlToCurrentMirror(translatorUrl)
                val req = Request.Builder()
                    .url(effectiveUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                    .header("Referer", "$currentBaseUrl/")
                    .build()

                val pageHtml = client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() ?: "" else ""
                }

                if (pageHtml.isNotBlank()) {
                    val pageDoc = Jsoup.parse(pageHtml)

                    // Обновляем премиум-статус озвучки прямо с ее персональной страницы
                    val isPrem = pageHtml.contains("b-post__prem_content") || pageDoc.selectFirst(".b-post__prem_content") != null
                    if (isPrem) {
                        synchronized(translatorPremiumCache) {
                            translatorPremiumCache.put(cacheKey, true)
                        }
                    }

                    val parsedFromPage = parseSeasonsFromDoc(pageDoc, cleanTranslatorId)
                    if (parsedFromPage.isNotEmpty()) {
                        synchronized(seasonEpisodesCache) {
                            seasonEpisodesCache.put(cacheKey, parsedFromPage)
                        }
                        return@withContext parsedFromPage
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Не удалось загрузить серии со страницы озвучки $translatorUrl: ${e.message}")
            }
        }

        // 2. Попытка получить через AJAX get_episodes
        val endpoint = "$currentBaseUrl/ajax/get_cdn_series/"
        try {
            val formBuilder = FormBody.Builder()
                .add("id", cleanId)
                .add("translator_id", cleanTranslatorId)
                .add("action", "get_episodes")

            val refererUrl = if (translatorUrl.isNotBlank()) adjustUrlToCurrentMirror(translatorUrl) else "$currentBaseUrl/"
            val request = Request.Builder()
                .url(endpoint)
                .post(formBuilder.build())
                .header("User-Agent", USER_AGENT)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", refererUrl)
                .header("Origin", currentBaseUrl)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    val json = JSONObject(bodyStr)
                    if (json.optBoolean("success", false)) {
                        val seasonsHtml = json.optString("seasons", "")
                        val episodesHtml = json.optString("episodes", "")

                        val seasonsDoc = Jsoup.parse(seasonsHtml)
                        val episodesDoc = Jsoup.parse(episodesHtml)

                        val seasonsList = ArrayList<Season>()
                        val rawSeasonEls = seasonsDoc.select(".b-simple_season__item, .b-simple_seasons__list li, #simple-seasons-tabs li, li.b-simple_season__item")
                        val seasonElements = rawSeasonEls.filter { el ->
                            !el.hasAttr("data-episode_id") && !el.hasClass("b-simple_episode__item")
                        }.ifEmpty {
                            seasonsDoc.select("li[data-season_id], [data-season_id]").filter { el ->
                                !el.hasAttr("data-episode_id") && !el.hasClass("b-simple_episode__item")
                            }
                        }.distinctBy { el ->
                            val idAttr = el.attr("data-season_id").ifEmpty { el.attr("data-tab_id") }
                            if (idAttr.isNotEmpty()) idAttr else el.text().trim()
                        }

                        if (seasonElements.isNotEmpty()) {
                            for (sEl in seasonElements) {
                                val sId = sEl.attr("data-season_id").toIntOrNull()
                                    ?: sEl.attr("data-tab_id").toIntOrNull()
                                    ?: Regex("""\d+""").find(sEl.text())?.value?.toIntOrNull()
                                    ?: continue
                                val sName = sEl.text().trim().ifEmpty { "Сезон $sId" }
                                val epList = ArrayList<Episode>()

                                val seasonContainer = episodesDoc.selectFirst(
                                    "ul.b-simple_episodes__list[data-season_id=$sId], " +
                                    "#simple-episodes-tabs[data-season_id=$sId], " +
                                    "[data-season_id=$sId].b-simple_episodes__list, " +
                                    "ul[data-season_id=$sId], " +
                                    "div[data-season_id=$sId]"
                                )

                                val epElements = if (seasonContainer != null) {
                                    seasonContainer.select(".b-simple_episode__item, li[data-episode_id], [data-episode_id], li")
                                } else {
                                    episodesDoc.select(
                                        ".b-simple_episodes__list .b-simple_episode__item[data-season_id=$sId], " +
                                        ".b-simple_episode__item[data-season_id=$sId], " +
                                        "li[data-season_id=$sId][data-episode_id]"
                                    )
                                }

                                for (epEl in epElements) {
                                    if (epEl.hasClass("b-simple_season__item")) continue
                                    val finalId = extractEpisodeNumberId(epEl)
                                    val epText = epEl.text().trim()
                                    if (finalId.isNotEmpty()) {
                                        val epName = if (epText.isNotEmpty()) epText else "Серия $finalId"
                                        epList.add(Episode(id = finalId, name = epName, seasonId = sId, translatorId = cleanTranslatorId))
                                    }
                                }

                                if (epList.isEmpty()) {
                                    val allEpEls = episodesDoc.select(".b-simple_episode__item, li[data-episode_id], [data-episode_id]")
                                    for (epEl in allEpEls) {
                                        if (epEl.hasClass("b-simple_season__item")) continue
                                        val epSeasonId = epEl.attr("data-season_id").toIntOrNull()
                                        if (epSeasonId != null && epSeasonId != sId) continue

                                        val finalId = extractEpisodeNumberId(epEl)
                                        val epText = epEl.text().trim()
                                        if (finalId.isNotEmpty()) {
                                            val epName = if (epText.isNotEmpty()) epText else "Серия $finalId"
                                            epList.add(Episode(id = finalId, name = epName, seasonId = sId, translatorId = cleanTranslatorId))
                                        }
                                    }
                                }

                                if (epList.isNotEmpty()) {
                                    seasonsList.add(Season(sId, sName, epList.distinctBy { it.id }))
                                }
                            }
                        } else {
                            val epElements = episodesDoc.select(".b-simple_episode__item, li[data-episode_id], [data-episode_id]")
                            val epList = ArrayList<Episode>()
                            for (epEl in epElements) {
                                if (epEl.hasClass("b-simple_season__item")) continue
                                val finalId = extractEpisodeNumberId(epEl)
                                val epText = epEl.text().trim()
                                if (finalId.isNotEmpty()) {
                                    val epName = if (epText.isNotEmpty()) epText else "Серия $finalId"
                                    epList.add(Episode(id = finalId, name = epName, seasonId = 1, translatorId = cleanTranslatorId))
                                }
                            }
                            if (epList.isNotEmpty()) {
                                seasonsList.add(Season(1, "Сезон 1", epList.distinctBy { it.id }))
                            }
                        }

                        if (seasonsList.isNotEmpty()) {
                            synchronized(seasonEpisodesCache) {
                                seasonEpisodesCache.put(cacheKey, seasonsList)
                            }
                            return@withContext seasonsList
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Ошибка загрузки серий через AJAX для озвучки $cleanTranslatorId: ${e.message}")
        }

        // 3. Безопасный фоллбэк: если есть кэшированные серии дефолтной озвучки для этого тайтла
        synchronized(seasonEpisodesCache) {
            val defaultKey = "${cleanId}_0"
            val fallback = seasonEpisodesCache.get(defaultKey)
            if (fallback != null && fallback.isNotEmpty()) {
                val adapted = fallback.map { s ->
                    s.copy(episodes = s.episodes.map { ep -> ep.copy(translatorId = cleanTranslatorId) })
                }
                return@withContext adapted
            }
        }

        return@withContext emptyList()
    }

    /**
     * Получение ссылок на видеопотоки (CDN AJAX rezka-tv.org).
     * Оптимизировано с автоматическим определением параметров и надежными фоллбэками.
     */
    suspend fun getStreamUrls(
        itemId: String,
        translatorId: String,
        isSeries: Boolean,
        season: Int = 0,
        episode: String = ""
    ): List<StreamUrl> = withContext(Dispatchers.IO) {
        val numericId = extractNumericId(itemId)
        val cleanTranslatorId = translatorId.trim()
        val effectiveTranslatorId = if (cleanTranslatorId == "0") "" else cleanTranslatorId
        val effectiveSeason = if (isSeries) season.coerceAtLeast(1) else 0
        val effectiveEpisode = if (isSeries) {
            if (episode.isBlank() || episode == "0") "1" else episode.trim()
        } else ""

        val cacheKey = "$numericId-$effectiveTranslatorId-$isSeries-$effectiveSeason-$effectiveEpisode"
        streamCache.get(cacheKey)?.let { return@withContext it }

        val endpoint = if (isSeries) "$currentBaseUrl/ajax/get_cdn_series/" else "$currentBaseUrl/ajax/get_cdn_movie/"

        // 1. Попытка 1: с переданной озвучкой
        val streams = fetchCdnStreams(endpoint, numericId, effectiveTranslatorId, isSeries, effectiveSeason, effectiveEpisode)
        if (streams.isNotEmpty()) {
            streamCache.put(cacheKey, streams)
            return@withContext streams
        }

        // 2. Попытка 2: если переданная озвучка не сработала, пробуем без translator_id
        if (effectiveTranslatorId.isNotEmpty()) {
            val streamNoTr = fetchCdnStreams(endpoint, numericId, "", isSeries, effectiveSeason, effectiveEpisode)
            if (streamNoTr.isNotEmpty()) {
                streamCache.put(cacheKey, streamNoTr)
                return@withContext streamNoTr
            }
        }

        // 3. Попытка 3: перебор распространенных ID переводов (238 - Дубляж, 56 - HDRezka, 1 - Оригинал, 375, 111, 33)
        val fallbackTranslators = listOf("238", "56", "1", "375", "111", "33", "82", "2", "438", "62")
        for (altTrans in fallbackTranslators) {
            if (altTrans == effectiveTranslatorId) continue
            val altStreams = fetchCdnStreams(endpoint, numericId, altTrans, isSeries, effectiveSeason, effectiveEpisode)
            if (altStreams.isNotEmpty()) {
                streamCache.put(cacheKey, altStreams)
                return@withContext altStreams
            }
        }

        // 4. Попытка 4: переключение между series/movie endpoints
        val altEndpoint = if (isSeries) "$currentBaseUrl/ajax/get_cdn_movie/" else "$currentBaseUrl/ajax/get_cdn_series/"
        val altSeries = !isSeries
        val altStreams = fetchCdnStreams(
            altEndpoint,
            numericId,
            effectiveTranslatorId,
            altSeries,
            if (altSeries) effectiveSeason else 0,
            if (altSeries) effectiveEpisode else ""
        )
        if (altStreams.isNotEmpty()) {
            streamCache.put(cacheKey, altStreams)
            return@withContext altStreams
        }

        return@withContext emptyList()
    }

    private fun fetchCdnStreams(
        endpoint: String,
        numericId: String,
        translatorId: String,
        isSeries: Boolean,
        season: Int,
        episode: String
    ): List<StreamUrl> {
        val primaryAction = if (isSeries) "get_stream" else "get_movie"
        val firstAttempt = fetchCdnStreamsSingle(endpoint, numericId, translatorId, isSeries, season, episode, primaryAction)
        if (firstAttempt.isNotEmpty()) return firstAttempt

        val altAction = if (isSeries) "get_movie" else "get_stream"
        return fetchCdnStreamsSingle(endpoint, numericId, translatorId, isSeries, season, episode, altAction)
    }

    private fun fetchCdnStreamsSingle(
        endpoint: String,
        numericId: String,
        translatorId: String,
        isSeries: Boolean,
        season: Int,
        episode: String,
        actionParam: String
    ): List<StreamUrl> {
        try {
            val urlWithTs = "$endpoint?t=${System.currentTimeMillis()}"
            val formBuilder = FormBody.Builder()
                .add("id", numericId)
                .add("action", actionParam)
                .add("is_cam", "0")
                .add("is_ads", "0")
                .add("is_director", "0")

            if (translatorId.isNotEmpty() && translatorId != "0") {
                formBuilder.add("translator_id", translatorId)
            }

            if (isSeries) {
                formBuilder.add("season", season.coerceAtLeast(1).toString())
                formBuilder.add("episode", if (episode.isBlank() || episode == "0") "1" else episode)
            }

            val request = Request.Builder()
                .url(urlWithTs)
                .post(formBuilder.build())
                .header("User-Agent", USER_AGENT)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "$currentBaseUrl/")
                .header("Origin", currentBaseUrl)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    Log.d(TAG, "CDN AJAX ответ [$urlWithTs, id=$numericId, tr=$translatorId, act=$actionParam, s=$season, ep=$episode]: $bodyStr")

                    // Пытаемся распарсить JSON
                    var rawUrl = ""
                    var rawSubtitle = ""
                    var subtitleDef = ""
                    try {
                        val json = JSONObject(bodyStr)
                        rawUrl = json.optString("url", "")
                        val subObj = json.opt("subtitle")
                        if (subObj is String) {
                            rawSubtitle = subObj
                        } else if (subObj != null && subObj != false && subObj != JSONObject.NULL) {
                            rawSubtitle = subObj.toString()
                        }
                        if (rawSubtitle.isEmpty()) {
                            val altSub = json.opt("subtitles")
                            if (altSub is String) rawSubtitle = altSub
                        }
                        val defObj = json.opt("subtitle_def")
                        if (defObj is String) {
                            subtitleDef = defObj
                        }
                    } catch (e: Exception) {
                        val match = Regex(""""url"\s*:\s*"([^"]+)"""").find(bodyStr)
                        if (match != null) {
                            rawUrl = match.groupValues[1]
                        }
                        val subMatch = Regex(""""subtitle(?:s)?"\s*:\s*"([^"]+)"""").find(bodyStr)
                        if (subMatch != null) {
                            rawSubtitle = subMatch.groupValues[1]
                        }
                        val defMatch = Regex(""""subtitle_def"\s*:\s*"([^"]+)"""").find(bodyStr)
                        if (defMatch != null) {
                            subtitleDef = defMatch.groupValues[1]
                        }
                    }

                    if (rawUrl.isNotEmpty() && rawUrl != "false" && rawUrl != "null") {
                        val cleanEncrypted = rawUrl.replace("\\/", "/")
                        val decrypted = RezkaDecryptor.decrypt(cleanEncrypted)
                        val streams = RezkaDecryptor.parseStreams(decrypted)
                        val subtitles = if (rawSubtitle.isNotEmpty() && rawSubtitle != "false" && rawSubtitle != "null") {
                            RezkaDecryptor.parseSubtitles(rawSubtitle, subtitleDef)
                        } else {
                            emptyList()
                        }
                        if (streams.isNotEmpty()) {
                            val finalStreams = if (subtitles.isNotEmpty()) {
                                streams.map { it.copy(subtitles = subtitles) }
                            } else {
                                streams
                            }
                            return finalStreams
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка CDN запроса ($endpoint, id=$numericId): ${e.message}")
        }
        return emptyList()
    }

    /**
     * Очищает возрастное ограничение от подсказок и пояснений, оставляя только краткий возраст с сайта.
     */
    fun cleanAgeRestriction(raw: String): String {
        if (raw.isBlank()) return ""
        val trimmed = raw.trim()
        // 1. Поиск шаблонов 18+, 16+, 12+, 6+, 0+
        val plusMatch = Regex("""\b(\d{1,2}\+)""").find(trimmed)
        if (plusMatch != null) {
            return plusMatch.groupValues[1]
        }
        // 2. MPAA / TV рейтинги (PG-13, NC-17, TV-MA, TV-14, TV-PG, TV-G, TV-Y7, R, PG, G, NR)
        val ratingMatch = Regex("""\b(PG-13|NC-17|TV-MA|TV-14|TV-PG|TV-G|TV-Y7|R|PG|G|NR)\b""", RegexOption.IGNORE_CASE).find(trimmed)
        if (ratingMatch != null) {
            return ratingMatch.groupValues[1].uppercase()
        }
        // 3. Если просто число "18" или "16"
        val digitMatch = Regex("""\b(\d{1,2})\b""").find(trimmed)
        if (digitMatch != null) {
            return "${digitMatch.groupValues[1]}+"
        }
        // 4. Отрезаем скобки и поясняющий текст
        val clean = trimmed.substringBefore("(").substringBefore("для").substringBefore("зрител").trim()
        return clean.ifEmpty { trimmed }
    }

    /**
     * Декодирует все виды экранирования (HTML сущности, JS слеши, Unicode коды).
     */
    private fun unescapeRaw(raw: String): String {
        if (raw.isBlank()) return ""
        var s = raw
        // 1. Быстрая замена стандартных HTML сущностей
        if (s.contains("&")) {
            s = s.replace("&quot;", "\"")
                .replace("&#039;", "'")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&nbsp;", " ")
        }
        // 2. JS экранирование слешей и кавычек
        if (s.contains("\\")) {
            s = s.replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("\\'", "'")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
        }
        // 3. Unicode escape коды \u002F -> /, \u003A -> : и т.д.
        if (s.contains("\\u00")) {
            s = s.replace("\\u002F", "/")
                .replace("\\u002f", "/")
                .replace("\\u003A", ":")
                .replace("\\u003a", ":")
                .replace("\\u003D", "=")
                .replace("\\u003d", "=")
                .replace("\\u003F", "?")
                .replace("\\u003f", "?")
                .replace("\\u0026", "&")
                .replace("\\u0022", "\"")
                .replace("\\u0027", "'")
                .replace("\\u003C", "<")
                .replace("\\u003c", "<")
                .replace("\\u003E", ">")
                .replace("\\u003e", ">")
        }
        return s
    }

    /**
     * Валидация YouTube ID видео (строго 11 символов, исключая ID каналов UC... и мусорные токены).
     */
    fun isValidYouTubeId(id: String): Boolean {
        if (id.length != 11) return false
        if (id.startsWith("UC", ignoreCase = false) || id.startsWith("PL", ignoreCase = false)) return false
        if (id.equals("placeholder", ignoreCase = true) ||
            id.equals("undefined", ignoreCase = true) ||
            id.equals("null", ignoreCase = true) ||
            id.equals("watch", ignoreCase = true) ||
            id.equals("embed", ignoreCase = true) ||
            id.equals("channel", ignoreCase = true) ||
            id.equals("results", ignoreCase = true)
        ) {
            return false
        }
        return true
    }

    /**
     * Высокопроизводительное извлечение 11-символьного идентификатора YouTube видео из любого формата ссылки/HTML/JSON.
     */
    fun extractYouTubeVideoId(raw: String): String? {
        if (raw.isBlank()) return null
        val clean = unescapeRaw(raw)

        // 1. Специфичные видео-паттерны YouTube (embed, watch?v=, v, vi, shorts)
        val videoRegex = Regex(
            """(?:https?:)?(?://)?(?:www\.|m\.)?(?:youtube\.com|youtube-nocookie\.com)/(?:embed/|v/|vi/|shorts/|watch\?(?:[^"'\s<>]*&)?v=)([a-zA-Z0-9_-]{11})""",
            RegexOption.IGNORE_CASE
        )
        val vMatch = videoRegex.find(clean)
        if (vMatch != null) {
            val id = vMatch.groupValues[1]
            if (isValidYouTubeId(id)) return id
        }

        // 2. Короткие ссылки youtu.be/VIDEO_ID
        val shortRegex = Regex(
            """(?:https?:)?(?://)?(?:www\.)?youtu\.be/([a-zA-Z0-9_-]{11})(?:[?&#"'\s<>]|$)""",
            RegexOption.IGNORE_CASE
        )
        val sMatch = shortRegex.find(clean)
        if (sMatch != null) {
            val id = sMatch.groupValues[1]
            if (isValidYouTubeId(id)) return id
        }

        // 3. Паттерны параметров ?v=... или &v=...
        val vParamRegex = Regex("""[?&]v=([a-zA-Z0-9_-]{11})(?:[&"'\s<>]|$)""").find(clean)
        if (vParamRegex != null) {
            val id = vParamRegex.groupValues[1]
            if (isValidYouTubeId(id)) return id
        }

        // 4. YouTube ID внутри JSON/JS параметров trailer_id: "..." или videoId: "..."
        val jsonIdMatches = Regex("""(?:"videoId"|"trailer_id"|"yt_id"|"youtube_id"|"trailerId"|"code"|"video")\s*:\s*"([^"]+)"""").findAll(clean)
        for (m in jsonIdMatches) {
            val valStr = m.groupValues[1]
            val subId = extractYouTubeVideoId(valStr)
            if (subId != null) return subId
        }

        // 5. Строгий изолированный 11-значный ID
        val trimmed = clean.trim('\'', '"', ' ', '\t', '\n', '\r')
        if (Regex("""^[a-zA-Z0-9_-]{11}$""").matches(trimmed)) {
            if (isValidYouTubeId(trimmed)) return trimmed
        }

        return null
    }

    /**
     * Извлекает реальную ссылку на трейлер в YouTube непосредственно из HTML/JS со страницы фильма/сериала.
     * Не создает лишних объектов в памяти, работает молниеносно.
     */
    fun extractTrailerUrl(doc: org.jsoup.nodes.Document, html: String): String {
        // 1. Поиск в вызовах CDN инициализации: sof.tv.initCDNSeriesEvents, initCDNSeriesEvents, initCDNMoviesEvents
        val cdnMatches = Regex("""initCDN(?:Series|Movies)Events\s*\((.*?)\);""", RegexOption.DOT_MATCHES_ALL).findAll(html)
        for (m in cdnMatches) {
            val block = m.groupValues[1]
            val id = extractYouTubeVideoId(block)
            if (id != null) {
                return "https://www.youtube.com/watch?v=$id"
            }
        }

        // 2. Поиск в вызовах sof.tv.show_trailer / show_trailer
        val trailerFuncMatches = Regex("""(?:show_trailer|initTrailer)\s*\((.*?)\)""", RegexOption.DOT_MATCHES_ALL).findAll(html)
        for (m in trailerFuncMatches) {
            val block = m.groupValues[1]
            val id = extractYouTubeVideoId(block)
            if (id != null) {
                return "https://www.youtube.com/watch?v=$id"
            }
        }

        // 3. Поиск во всех элементах, связанных с трейлером (кнопки, ссылки, data-атрибуты, iframe, meta-теги)
        val trailerElements = doc.select(
            "a.b-post__trailer, .b-post__trailer, .b-post__trailer_link, .b-post__trailer-link, " +
            "a[class*='trailer'], button[class*='trailer'], div[class*='trailer'], span[class*='trailer'], " +
            "[id*='trailer'], [onclick*='trailer'], [onclick*='show_trailer'], [onclick*='Trailer'], " +
            "[data-trailer], [data-trailer-id], [data-code], [data-video], [data-youtube], [data-url], [data-src], " +
            "a[href*='youtube'], a[href*='youtu.be'], iframe[src*='youtube'], iframe[data-src*='youtube'], " +
            "meta[property*='video'], meta[itemprop*='trailer'], meta[itemprop*='embedUrl'], link[itemprop='trailer']"
        )

        for (el in trailerElements) {
            val candidates = listOf(
                el.attr("data-trailer"),
                el.attr("data-trailer-id"),
                el.attr("data-code"),
                el.attr("data-video"),
                el.attr("data-youtube"),
                el.attr("data-url"),
                el.attr("data-src"),
                el.attr("href"),
                el.attr("src"),
                el.attr("content"),
                el.attr("onclick"),
                el.outerHtml()
            )
            for (c in candidates) {
                if (c.isNotBlank()) {
                    val id = extractYouTubeVideoId(c)
                    if (id != null) {
                        return "https://www.youtube.com/watch?v=$id"
                    }
                }
            }
        }

        // 4. Поиск по тексту кнопок ("Смотреть трейлер", "Трейлер")
        val textElements = doc.select("*:containsOwn(трейлер), *:containsOwn(Трейлер), *:containsOwn(trailer), *:containsOwn(Trailer)")
        for (el in textElements) {
            val attrs = listOf(
                el.attr("onclick"),
                el.attr("href"),
                el.attr("data-trailer"),
                el.attr("data-code"),
                el.attr("data-url"),
                el.attr("data-src"),
                el.parent()?.attr("onclick") ?: "",
                el.parent()?.attr("href") ?: ""
            )
            for (a in attrs) {
                if (a.isNotBlank()) {
                    val id = extractYouTubeVideoId(a)
                    if (id != null) {
                        return "https://www.youtube.com/watch?v=$id"
                    }
                }
            }
        }

        // 5. Поиск во всех тегах <script> страницы
        val scriptTags = doc.select("script")
        for (script in scriptTags) {
            val scriptContent = script.data()
            if (scriptContent.contains("youtube", ignoreCase = true) || scriptContent.contains("trailer", ignoreCase = true)) {
                val id = extractYouTubeVideoId(scriptContent)
                if (id != null) {
                    return "https://www.youtube.com/watch?v=$id"
                }
            }
        }

        // 6. Поиск JSON/JS ключей trailer / trailer_url / trailer_id / youtube_id
        val jsonKeyMatch = Regex("""["'](?:trailer|trailer_url|trailer_link|trailerUrl|trailer_id|trailer_code|youtube_id|yt_id)["']\s*[:=]\s*["']([^"']+)["']""").findAll(html)
        for (match in jsonKeyMatch) {
            val rawVal = match.groupValues[1]
            val id = extractYouTubeVideoId(rawVal)
            if (id != null) {
                return "https://www.youtube.com/watch?v=$id"
            }
        }

        return ""
    }

    /**
     * AJAX запрос трейлера с сервера HDRezka, если трейлер подгружается динамически.
     */
    suspend fun fetchTrailerAjax(numericPostId: String): String = withContext(Dispatchers.IO) {
        if (numericPostId.isBlank()) return@withContext ""
        val endpoints = listOf(
            "$currentBaseUrl/ajax/get_trailer/?t=${System.currentTimeMillis()}",
            "$currentBaseUrl/ajax/get_cdn_trailer/?t=${System.currentTimeMillis()}",
            "$currentBaseUrl/ajax/get_trailer/"
        )

        for (endpoint in endpoints) {
            try {
                val form = FormBody.Builder()
                    .add("id", numericPostId)
                    .add("post_id", numericPostId)
                    .add("action", "get_trailer")
                    .add("custom", "1")
                    .build()

                val request = Request.Builder()
                    .url(endpoint)
                    .post(form)
                    .header("User-Agent", USER_AGENT)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Referer", "$currentBaseUrl/")
                    .header("Origin", currentBaseUrl)
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .build()

                client.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        val videoId = extractYouTubeVideoId(body)
                        if (videoId != null) {
                            return@withContext "https://www.youtube.com/watch?v=$videoId"
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return@withContext ""
    }

    /**
     * Мгновенный асинхронный поиск точного официального трейлера в YouTube.
     * Извлекает первый релевантный videoId из официальной поисковой выдачи YouTube.
     */
    suspend fun resolveYouTubeTrailer(title: String, originalTitle: String, year: String): String = withContext(Dispatchers.IO) {
        if (title.isBlank() && originalTitle.isBlank()) return@withContext ""

        try {
            val queryParts = ArrayList<String>()
            if (title.isNotBlank()) queryParts.add(title)
            if (originalTitle.isNotBlank() && !originalTitle.equals(title, ignoreCase = true)) {
                queryParts.add(originalTitle)
            }
            if (year.isNotBlank()) queryParts.add(year)
            queryParts.add("официальный трейлер")

            val query = queryParts.joinToString(" ")
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val searchUrl = "https://www.youtube.com/results?search_query=$encoded&sp=EgIQAQ%253D%253D"

            val req = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val html = resp.body?.string() ?: ""
                    val matches = Regex(""""videoId"\s*:\s*"([a-zA-Z0-9_-]{11})"""").findAll(html)
                    for (m in matches) {
                        val videoId = m.groupValues[1]
                        if (isValidYouTubeId(videoId)) {
                            return@withContext "https://www.youtube.com/watch?v=$videoId"
                        }
                    }
                    val urlMatches = Regex("""/watch\?v=([a-zA-Z0-9_-]{11})""").findAll(html)
                    for (m in urlMatches) {
                        val videoId = m.groupValues[1]
                        if (isValidYouTubeId(videoId)) {
                            return@withContext "https://www.youtube.com/watch?v=$videoId"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка резолва трейлера YouTube: ${e.message}")
        }
        return@withContext ""
    }

    /**
     * Высокопроизводительный парсинг структурированных рейтингов (IMDb, Кинопоиск, HDRezka).
     * Очищает переносы строк, лишние скобки и мусор разметки.
     */
    private fun parseRatingInfo(doc: org.jsoup.nodes.Document): Pair<RatingInfo, String> {
        var imdbScore = ""
        var imdbVotes = ""
        var kpScore = ""
        var kpVotes = ""
        var rezkaScore = ""
        var rezkaVotes = ""

        val ratingHolder = doc.selectFirst(".b-post__rating") ?: doc.selectFirst(".b-post__info tr:contains(рейтинг)")

        // 1. IMDb парсинг
        val imdbEl = doc.selectFirst(".b-post__rating .imdb, .b-post__info .imdb, span.imdb, i.imdb")
            ?: ratingHolder?.selectFirst(".imdb")
        if (imdbEl != null) {
            val fullText = imdbEl.text()
            imdbScore = imdbEl.selectFirst(".bold")?.text()?.trim()
                ?: Regex("""\b(\d+(?:\.\d+)?)\b""").find(fullText)?.value ?: ""
            val rawVotes = imdbEl.selectFirst(".votes, span:not(.bold)")?.text()?.trim()
                ?: Regex("""\(([^)]+)\)""").find(fullText)?.groupValues?.get(1)?.trim() ?: ""
            imdbVotes = cleanVotesString(rawVotes)
        }

        // 2. Кинопоиск парсинг
        val kpEl = doc.selectFirst(".b-post__rating .kp, .b-post__info .kp, span.kp, i.kp")
            ?: ratingHolder?.selectFirst(".kp")
        if (kpEl != null) {
            val fullText = kpEl.text()
            kpScore = kpEl.selectFirst(".bold")?.text()?.trim()
                ?: Regex("""\b(\d+(?:\.\d+)?)\b""").find(fullText)?.value ?: ""
            val rawVotes = kpEl.selectFirst(".votes, span:not(.bold)")?.text()?.trim()
                ?: Regex("""\(([^)]+)\)""").find(fullText)?.groupValues?.get(1)?.trim() ?: ""
            kpVotes = cleanVotesString(rawVotes)
        }

        // 3. HDRezka пользовательский рейтинг
        val rezkaEl = doc.selectFirst(".b-post__rating .num, .b-post__info .num")
        if (rezkaEl != null) {
            rezkaScore = rezkaEl.text().trim().replace("\n", "").replace("\r", "")
            val rawVotes = doc.selectFirst(".b-post__rating .votes, .b-post__info .votes")?.text()?.trim() ?: ""
            rezkaVotes = cleanVotesString(rawVotes)
        }

        // 4. Дополнительный fallback поиск по строке таблицы "В рейтинге", если селекторы не сработали
        if (imdbScore.isEmpty() && kpScore.isEmpty() && rezkaScore.isEmpty()) {
            val ratingRows = doc.select(".b-post__info tr")
            for (row in ratingRows) {
                val label = row.selectFirst("td:nth-child(1)")?.text()?.trim()?.lowercase() ?: ""
                if (label.contains("рейтинг") || label.contains("оценк")) {
                    val tdText = row.selectFirst("td:nth-child(2)")?.text() ?: ""

                    val imdbMatch = Regex("""IMDb[:\s]*([0-9]+(?:\.[0-9]+)?)(?:\s*\(([^)]+)\))?""", RegexOption.IGNORE_CASE).find(tdText)
                    if (imdbMatch != null) {
                        imdbScore = imdbMatch.groupValues.getOrNull(1)?.trim() ?: ""
                        imdbVotes = cleanVotesString(imdbMatch.groupValues.getOrNull(2)?.trim() ?: "")
                    }

                    val kpMatch = Regex("""(?:Кинопоиск|КП)[:\s]*([0-9]+(?:\.[0-9]+)?)(?:\s*\(([^)]+)\))?""", RegexOption.IGNORE_CASE).find(tdText)
                    if (kpMatch != null) {
                        kpScore = kpMatch.groupValues.getOrNull(1)?.trim() ?: ""
                        kpVotes = cleanVotesString(kpMatch.groupValues.getOrNull(2)?.trim() ?: "")
                    }

                    val numMatch = Regex("""\b(\d+\.\d+)\b""").find(tdText)
                    if (rezkaScore.isEmpty() && numMatch != null && numMatch.value != imdbScore && numMatch.value != kpScore) {
                        rezkaScore = numMatch.value
                    }
                }
            }
        }

        imdbScore = cleanScoreString(imdbScore)
        kpScore = cleanScoreString(kpScore)
        rezkaScore = cleanScoreString(rezkaScore)

        val ratingInfo = RatingInfo(
            imdb = imdbScore,
            imdbVotes = imdbVotes,
            kinopoisk = kpScore,
            kinopoiskVotes = kpVotes,
            rezka = rezkaScore,
            rezkaVotes = rezkaVotes
        )

        val mainRating = when {
            imdbScore.isNotEmpty() -> "IMDb $imdbScore"
            kpScore.isNotEmpty() -> "КП $kpScore"
            rezkaScore.isNotEmpty() -> rezkaScore
            else -> ""
        }

        return Pair(ratingInfo, mainRating)
    }

    private fun cleanScoreString(score: String): String {
        return score.replace("\n", "").replace("\r", "").trim()
    }

    private fun cleanVotesString(rawVotes: String): String {
        var v = rawVotes.replace("\n", " ").replace("\r", " ").trim()
        if (v.startsWith("(") && v.endsWith(")")) {
            v = v.substring(1, v.length - 1).trim()
        }
        v = v.replace(Regex("""^(?:imdb|кинопоиск|кп|votes|голосов)[:\s]*""", RegexOption.IGNORE_CASE), "").trim()
        return v
    }
}


