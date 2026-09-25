package com.example.data

import android.util.Base64

object RezkaDecryptor {
    private val trashChars = charArrayOf('@', '#', '!', '^', '$')

    // Pre-calculated trash codes (combinations of 1, 2, 3 chars, with and without padding)
    // Cached once at runtime for O(1) initialization and zero CPU allocations during playback.
    private val trashCodesSet: List<String> by lazy {
        val list = LinkedHashSet<String>(600)

        // 1. Raw trash sequences and multi-character delimiters
        list.add("$$!!@$$")
        list.add("//_//")
        list.add("_//")
        list.add("||")
        list.add("@@")
        list.add("##")
        list.add("!!")
        list.add("^^")
        list.add("$$")
        list.add("@#^$!")
        list.add("!@#$^")
        list.add("#h")

        // 2. Length 1 base64 combinations
        for (c in trashChars) {
            val s = c.toString()
            val b64Padded = Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val b64Unpadded = b64Padded.replace("=", "")
            list.add(b64Padded)
            list.add(b64Unpadded)
        }

        // 3. Length 2 base64 combinations (e.g. "@#", "!$", etc.)
        for (c1 in trashChars) {
            for (c2 in trashChars) {
                val s = "$c1$c2"
                val b64Padded = Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val b64Unpadded = b64Padded.replace("=", "")
                list.add(b64Padded)
                list.add(b64Unpadded)
                list.add(b64Padded.replace('+', '-').replace('/', '_'))
                list.add(b64Unpadded.replace('+', '-').replace('/', '_'))
            }
        }

        // 4. Length 3 base64 combinations (e.g. "@#!", "$$$", etc.)
        for (c1 in trashChars) {
            for (c2 in trashChars) {
                for (c3 in trashChars) {
                    val s = "$c1$c2$c3"
                    val b64Padded = Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    val b64Unpadded = b64Padded.replace("=", "")
                    list.add(b64Padded)
                    list.add(b64Unpadded)
                    list.add(b64Padded.replace('+', '-').replace('/', '_'))
                    list.add(b64Unpadded.replace('+', '-').replace('/', '_'))
                }
            }
        }

        // Sort descending by length so longer sequences are stripped first
        list.sortedByDescending { it.length }
    }

    /**
     * Decrypts HDRezka encrypted stream URL string.
     * Removes trash garbage tokens and decodes Base64 into cleartext playlist definition.
     */
    fun decrypt(encryptedData: String): String {
        if (encryptedData.isEmpty()) return ""

        var data = encryptedData.trim()

        // If already plaintext stream definition
        if (data.startsWith("[") || data.startsWith("http://") || data.startsWith("https://")) {
            return data
        }

        // Strip known HDRezka obfuscation prefixes
        while (data.startsWith("#h") || data.startsWith("//") || data.startsWith("#") || data.startsWith("$") || data.startsWith("!") || data.startsWith("@") || data.startsWith("^") || data.startsWith("_") || data.startsWith("~")) {
            data = when {
                data.startsWith("#h") -> data.substring(2)
                data.startsWith("//") -> data.substring(2)
                else -> data.substring(1)
            }
        }

        // Unescape escaped slashes if coming from raw JSON
        data = data.replace("\\/", "/")

        // Iterative removal of trash codes in descending length order
        var previousLength: Int
        var iterations = 0
        do {
            previousLength = data.length
            for (i in trashCodesSet.indices) {
                val code = trashCodesSet[i]
                if (data.contains(code)) {
                    data = data.replace(code, "")
                }
            }
            iterations++
        } while (data.length != previousLength && iterations < 3)

        // Decode cleaned Base64 string
        return tryDecodeBase64(data)
    }

    private fun tryDecodeBase64(rawBase64: String): String {
        var clean = rawBase64.trim()
        if (clean.isEmpty()) return ""

        // If it starts with [ or http, it's already decoded
        if (clean.startsWith("[") || clean.startsWith("http")) {
            return clean
        }

        // Remove any residual non-base64 characters
        val sb = StringBuilder(clean.length)
        for (i in 0 until clean.length) {
            val c = clean[i]
            if ((c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') || c == '+' || c == '/' || c == '=' || c == '-' || c == '_') {
                sb.append(c)
            }
        }
        clean = sb.toString()

        // Normalize URL-safe Base64
        clean = clean.replace('-', '+').replace('_', '/')

        // Add correct Base64 padding
        val remainder = clean.length % 4
        if (remainder == 2) {
            clean += "=="
        } else if (remainder == 3) {
            clean += "="
        } else if (remainder == 1) {
            clean = clean.dropLast(1)
        }

        return try {
            val decodedBytes = Base64.decode(clean, Base64.DEFAULT)
            val decoded = String(decodedBytes, Charsets.UTF_8).trim()

            // Handle nested encryption (if base64 contained another layer)
            if (decoded.startsWith("#h") || (decoded.startsWith("aHR0c") && !decoded.contains(" "))) {
                decrypt(decoded)
            } else if (decoded.contains("http") || decoded.contains(".mp4") || decoded.contains(".m3u8") || decoded.contains("[")) {
                decoded
            } else {
                // If decoding didn't produce URL-like string, return original or cleaned
                if (rawBase64.contains("http")) rawBase64 else decoded
            }
        } catch (e: Exception) {
            if (rawBase64.contains("http")) rawBase64 else ""
        }
    }

    private val qualityRegex = Regex("""\[([^\]]+)\]([^\[]*)""")
    private val urlRegex = Regex("""https?://[^\s,"'<>]+""")

    /**
     * Parses the decrypted string into a list of StreamUrls with mirror links and direct MP4 fallback.
     * Accurately extracts all free resolutions (1080p, 720p, 480p, 360p) whether delimited by " or ", ",",
     * or newline, and sorts them from highest resolution down to lowest resolution.
     * 1080p Ultra is excluded completely as it requires a paid account.
     */
    fun parseStreams(decodedStr: String): List<StreamUrl> {
        if (decodedStr.isBlank()) return emptyList()

        val streamsMap = LinkedHashMap<String, StreamUrl>()

        // 1. Primary parser: match all [quality]url blocks
        val matches = qualityRegex.findAll(decodedStr).toList()
        if (matches.isNotEmpty()) {
            for (match in matches) {
                val qualityRaw = match.groupValues[1].trim()
                // Skip premium 1080p Ultra streams completely
                if (qualityRaw.contains("ultra", ignoreCase = true)) {
                    continue
                }
                val urlsPart = match.groupValues[2]

                val urls = urlRegex.findAll(urlsPart)
                    .map { it.value.trim() }
                    .filter { it.isNotEmpty() && !it.contains("ultra", ignoreCase = true) }
                    .distinct()
                    .toList()

                if (urls.isNotEmpty()) {
                    val primaryUrl = urls.first()
                    val backupUrls = if (urls.size > 1) urls.drop(1) else emptyList()

                    val directMp4 = when {
                        primaryUrl.contains(":hls:manifest.m3u8") -> primaryUrl.substringBefore(":hls:manifest.m3u8")
                        primaryUrl.endsWith(".mp4") || primaryUrl.contains(".mp4?") -> primaryUrl.substringBefore(":")
                        else -> ""
                    }

                    val normalizedQuality = normalizeQualityLabel(qualityRaw)
                    if (!normalizedQuality.contains("ultra", ignoreCase = true)) {
                        streamsMap[normalizedQuality] = StreamUrl(
                            quality = normalizedQuality,
                            url = primaryUrl,
                            backupUrls = backupUrls,
                            directMp4Url = directMp4
                        )
                    }
                }
            }
        }

        // 2. Fallback parser: if no [...] tags were present, find all URLs and deduce quality
        if (streamsMap.isEmpty()) {
            val allUrls = urlRegex.findAll(decodedStr)
                .map { it.value.trim() }
                .filter { it.isNotEmpty() && !it.contains("ultra", ignoreCase = true) }
                .distinct()
                .toList()

            for (url in allUrls) {
                val quality = deduceQualityFromUrl(url)
                if (quality.contains("ultra", ignoreCase = true)) continue

                val directMp4 = when {
                    url.contains(":hls:manifest.m3u8") -> url.substringBefore(":hls:manifest.m3u8")
                    url.endsWith(".mp4") || url.contains(".mp4?") -> url.substringBefore(":")
                    else -> ""
                }
                streamsMap[quality] = StreamUrl(
                    quality = quality,
                    url = url,
                    backupUrls = emptyList(),
                    directMp4Url = directMp4
                )
            }
        }

        // 3. Sort streams descending by quality (highest resolution first: 1080p -> 720p -> 480p -> 360p)
        return streamsMap.values
            .filterNot { it.quality.contains("ultra", ignoreCase = true) || it.url.contains("ultra", ignoreCase = true) }
            .sortedWith { a, b ->
                val weightA = getQualityWeight(a.quality)
                val weightB = getQualityWeight(b.quality)
                weightB.compareTo(weightA)
            }
    }

    private fun normalizeQualityLabel(raw: String): String {
        val lower = raw.lowercase()
        return when {
            lower.contains("2160") || lower.contains("4k") -> "4K (2160p)"
            lower.contains("1440") || lower.contains("2k") -> "2K (1440p)"
            lower.contains("1080") || lower.contains("fhd") || lower.contains("full hd") -> "1080p"
            lower.contains("720") || lower.contains("hd") -> "720p"
            lower.contains("480") || lower.contains("sd") -> "480p"
            lower.contains("360") -> "360p"
            lower.contains("240") -> "240p"
            else -> raw
        }
    }

    private fun deduceQualityFromUrl(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains("2160") || lower.contains("4k") -> "4K (2160p)"
            lower.contains("1440") || lower.contains("2k") -> "2K (1440p)"
            lower.contains("1080") -> "1080p"
            lower.contains("720") -> "720p"
            lower.contains("480") -> "480p"
            lower.contains("360") -> "360p"
            lower.contains("240") -> "240p"
            else -> "Auto"
        }
    }

    fun getQualityWeight(quality: String): Int {
        val lower = quality.lowercase()
        return when {
            lower.contains("4k") || lower.contains("2160") -> 2160
            lower.contains("2k") || lower.contains("1440") -> 1440
            lower.contains("1080") -> 1080
            lower.contains("720") -> 720
            lower.contains("480") -> 480
            lower.contains("360") -> 360
            lower.contains("240") -> 240
            lower.contains("auto") -> 1
            else -> {
                val match = Regex("""(\d+)""").find(quality)
                match?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
        }
    }

    private val subtitleBracketRegex = Regex("""\[([^\]]+)\]\s*(https?://[^\s,"'<>]+)""")
    private val subtitleUrlRegex = Regex("""https?://[^\s,"'<>]+\.(?:vtt|srt)[^\s,"'<>]*""")

    /**
     * Parses the decrypted or raw subtitle string into a list of SubtitleTracks.
     * Supports formats like:
     * "[Русский]https://stream.hdrezka.ac/.../ru.vtt,[English]https://stream.hdrezka.ac/.../en.vtt"
     * and standalone URLs or encrypted strings.
     */
    fun parseSubtitles(rawSubtitleStr: String, defaultLang: String = ""): List<SubtitleTrack> {
        if (rawSubtitleStr.isBlank() || rawSubtitleStr == "false" || rawSubtitleStr == "null") {
            return emptyList()
        }

        // Clean and decrypt if encrypted
        val clean = rawSubtitleStr.replace("\\/", "/").trim()
        val decoded = if (clean.startsWith("[") || clean.startsWith("http")) {
            clean
        } else {
            decrypt(clean)
        }

        if (decoded.isBlank()) return emptyList()

        val list = ArrayList<SubtitleTrack>()
        val seenUrls = HashSet<String>()

        // 1. First attempt: match [Title]URL pairs
        val bracketMatches = subtitleBracketRegex.findAll(decoded).toList()
        if (bracketMatches.isNotEmpty()) {
            for (m in bracketMatches) {
                val title = m.groupValues[1].trim()
                val url = m.groupValues[2].trim()
                if (url.isNotEmpty() && seenUrls.add(url)) {
                    val lang = deduceSubtitleLanguage(title, url)
                    val isDef = if (defaultLang.isNotEmpty()) {
                        lang.equals(defaultLang, ignoreCase = true) || title.contains(defaultLang, ignoreCase = true)
                    } else {
                        lang == "ru"
                    }
                    list.add(
                        SubtitleTrack(
                            language = lang,
                            title = title,
                            url = url,
                            isDefault = isDef
                        )
                    )
                }
            }
        }

        // 2. Fallback: match any .vtt or .srt URLs
        if (list.isEmpty()) {
            val urlMatches = subtitleUrlRegex.findAll(decoded).toList()
            for (m in urlMatches) {
                val url = m.value.trim()
                if (url.isNotEmpty() && seenUrls.add(url)) {
                    val deducedName = deduceSubtitleTitleFromUrl(url)
                    val lang = deduceSubtitleLanguage(deducedName, url)
                    val isDef = if (defaultLang.isNotEmpty()) {
                        lang.equals(defaultLang, ignoreCase = true)
                    } else {
                        lang == "ru"
                    }
                    list.add(
                        SubtitleTrack(
                            language = lang,
                            title = deducedName,
                            url = url,
                            isDefault = isDef
                        )
                    )
                }
            }
        }

        // If no default was set, set first track (preferring Russian or first) as default
        if (list.isNotEmpty() && list.none { it.isDefault }) {
            val ruIdx = list.indexOfFirst { it.language == "ru" }
            val defIdx = if (ruIdx >= 0) ruIdx else 0
            list[defIdx] = list[defIdx].copy(isDefault = true)
        }

        return list
    }

    private fun deduceSubtitleLanguage(title: String, url: String): String {
        val lowerT = title.lowercase()
        val lowerU = url.lowercase()
        return when {
            lowerT.contains("рус") || lowerT.contains("ru") || lowerU.contains("/ru.") || lowerU.contains("subtitles/ru") -> "ru"
            lowerT.contains("укр") || lowerT.contains("uk") || lowerT.contains("ua") || lowerU.contains("/uk.") || lowerU.contains("/ua.") -> "uk"
            lowerT.contains("англ") || lowerT.contains("eng") || lowerT.contains("en") || lowerU.contains("/en.") -> "en"
            lowerT.contains("нем") || lowerT.contains("de") || lowerT.contains("ger") -> "de"
            lowerT.contains("фр") || lowerT.contains("fr") || lowerT.contains("fre") -> "fr"
            lowerT.contains("исп") || lowerT.contains("es") || lowerT.contains("spa") -> "es"
            lowerT.contains("ит") || lowerT.contains("it") || lowerT.contains("ita") -> "it"
            lowerT.contains("яп") || lowerT.contains("ja") || lowerT.contains("jp") -> "ja"
            lowerT.contains("кор") || lowerT.contains("ko") || lowerT.contains("kor") -> "ko"
            lowerT.contains("кит") || lowerT.contains("zh") || lowerT.contains("chi") -> "zh"
            lowerT.contains("пол") || lowerT.contains("pl") || lowerT.contains("pol") -> "pl"
            lowerT.contains("тур") || lowerT.contains("tr") || lowerT.contains("tur") -> "tr"
            else -> {
                val codeMatch = Regex("""^[a-zA-Z]{2,3}$""").find(title.trim())
                codeMatch?.value?.lowercase() ?: "und"
            }
        }
    }

    private fun deduceSubtitleTitleFromUrl(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains("/ru.") || lower.contains("subtitles/ru") -> "Русский"
            lower.contains("/uk.") || lower.contains("/ua.") || lower.contains("subtitles/uk") -> "Украинский"
            lower.contains("/en.") || lower.contains("subtitles/en") -> "English"
            lower.contains("/de.") -> "Немецкий"
            lower.contains("/fr.") -> "Французский"
            lower.contains("/es.") -> "Испанский"
            lower.contains("/it.") -> "Итальянский"
            else -> {
                val fileName = url.substringAfterLast('/').substringBeforeLast('.')
                if (fileName.length in 2..15) fileName.replaceFirstChar { it.uppercase() } else "Субтитры"
            }
        }
    }
}
