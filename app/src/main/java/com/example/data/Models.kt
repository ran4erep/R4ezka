package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable
import java.text.Collator
import java.util.Locale

data class LinkItem(val name: String, val url: String) : Serializable

enum class RezkaType {
    MOVIE, SERIES, ANIME, CARTOON, COLLECTIONS
}

data class YearItem(val name: String, val year: String) : Serializable

data class CollectionItem(
    val id: String,
    val title: String,
    val url: String,
    val imageUrl: String,
    val count: String = ""
) : Serializable

sealed interface CollectionsState : Serializable {
    object Loading : CollectionsState
    data class Success(val items: List<CollectionItem>) : CollectionsState
    data class Error(val message: String) : CollectionsState
}

enum class SectionType {
    LATEST, POPULAR, WATCHING, AWAITING;

    fun getDisplayName(): String {
        return when (this) {
            LATEST -> "Новые поступления"
            POPULAR -> "Популярные"
            WATCHING -> "Сейчас смотрят"
            AWAITING -> "В ожидании"
        }
    }
}

data class GenreItem(val name: String, val slug: String) : Serializable

data class CountryItem(
    val name: String,
    val query: String,
    val searchKey: String = "${CountryFlags.stripFlags(name).trim().lowercase()} ${CountryFlags.cleanCountryName(query).lowercase()} ${name.lowercase()} ${query.lowercase()}"
) : Serializable

object CountryFilterList {
    val defaultCountries: List<CountryItem> by lazy {
        val ruLocale = Locale.forLanguageTag("ru")
        val collator = Collator.getInstance(ruLocale).apply {
            strength = Collator.PRIMARY
        }
        val map = java.util.TreeMap<String, String>(collator)

        // Кинематографические алиасы и приоритетные формы
        map["США"] = "США"
        map["Южная Корея"] = "Южная Корея"
        map["Северная Корея"] = "Северная Корея"
        map["Великобритания"] = "Великобритания"
        map["Англия"] = "Англия"
        map["Шотландия"] = "Шотландия"
        map["Уэльс"] = "Уэльс"
        map["Гонконг"] = "Гонконг"
        map["СССР"] = "СССР"
        map["Югославия"] = "Югославия"
        map["Чехословакия"] = "Чехословакия"
        map["ГДР"] = "ГДР"

        for (c in Locale.getISOCountries()) {
            val loc = Locale("", c)
            var name = loc.getDisplayCountry(ruLocale) ?: continue
            if (name.isBlank()) continue
            name = name.replace(" (САР)", "").replace(" САР", "").trim()
            if (c == "US" || c == "KR" || c == "KP" || c == "HK") continue
            if (!map.containsKey(name)) {
                map[name] = name
            }
        }

        val list = ArrayList<CountryItem>(map.size + 1)
        list.add(CountryItem("Все страны", ""))
        for ((name, query) in map) {
            val flag = CountryFlags.getFlag(name, fallbackToDefault = true)
            list.add(CountryItem("$flag $name", query))
        }
        list
    }
}

fun RezkaItem.matchesCountry(countryQuery: String): Boolean {
    if (countryQuery.isBlank()) return true
    val filterCode = CountryFlags.getCanonicalCountryCode(countryQuery)
    if (filterCode.isEmpty()) return true
    return CountryFlags.isMatchingCountryCodes(countryCodes, filterCode)
}

fun RezkaItem.matchesType(type: RezkaType?): Boolean {
    if (type == null) return true
    if (this.type == type) return true
    return when (type) {
        RezkaType.MOVIE -> url.contains("/films/") || (!url.contains("/series/") && !url.contains("/animation/") && !url.contains("/cartoons/"))
        RezkaType.SERIES -> url.contains("/series/")
        RezkaType.ANIME -> url.contains("/animation/")
        RezkaType.CARTOON -> url.contains("/cartoons/")
        RezkaType.COLLECTIONS -> false
    }
}

private val YEAR_RANGE_REGEX = Regex("""\b(19\d\d|20\d\d)\s*[-–—]\s*(19\d\d|20\d\d)\b""")
private val ONGOING_YEAR_REGEX = Regex("""\b(19\d\d|20\d\d)\s*[-–—]\s*(?:\.\.\.|…|по\s*наст|наст|\s*$)""")

fun RezkaItem.matchesYear(yearQuery: String): Boolean {
    if (yearQuery.isBlank()) return true
    val targetYear = yearQuery.trim().toIntOrNull() ?: return true

    if (releaseDateNum > 0) {
        val y = releaseDateNum / 10000
        if (y == targetYear) return true
    }

    if (subtitle.isNotEmpty()) {
        val sub = subtitle
        if (sub.contains(yearQuery)) return true

        val rangeMatch = YEAR_RANGE_REGEX.find(sub)
        if (rangeMatch != null) {
            val startY = rangeMatch.groupValues[1].toIntOrNull() ?: 0
            val endY = rangeMatch.groupValues[2].toIntOrNull() ?: 0
            if (startY in 1890..2040 && endY in 1890..2040 && targetYear in startY..endY) {
                return true
            }
        }

        val ongoingMatch = ONGOING_YEAR_REGEX.find(sub)
        if (ongoingMatch != null) {
            val startY = ongoingMatch.groupValues[1].toIntOrNull() ?: 0
            val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            if (startY in 1890..2040 && targetYear in startY..currentYear) {
                return true
            }
        }
    }

    return false
}

fun RezkaItem.matchesGenre(genreSlug: String, genreName: String = ""): Boolean {
    if (genreSlug.isBlank() && genreName.isBlank()) return true

    if (genreSlug.isNotBlank()) {
        if (url.contains("/$genreSlug/", ignoreCase = true) || url.contains("/$genreSlug.html", ignoreCase = true)) {
            return true
        }
    }

    if (subtitle.isNotEmpty()) {
        if (genreName.isNotBlank() && subtitle.contains(genreName, ignoreCase = true)) {
            return true
        }
        if (genreSlug.isNotBlank() && subtitle.contains(genreSlug, ignoreCase = true)) {
            return true
        }
    }

    return false
}


data class RezkaItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String,
    val rating: String = "",
    val url: String,
    val type: RezkaType,
    val releaseDateNum: Int = MovieDateParser.extractReleaseDateNum(subtitle, url),
    val numericId: Long = MovieDateParser.extractNumericId(id, url),
    val countryCodes: Set<String> = CountryFlags.extractCanonicalCountryCodes(subtitle)
) : Serializable

/**
 * Высокопроизводительное O(1) определение, является ли карточка контентом с сериями
 * (сериалы, аниме-сериалы, мультсериалы, многосерийные фильмы)
 * или же полнометражным фильмом (фильмы, полнометражные аниме, полнометражные мультфильмы).
 */
fun RezkaItem.isSeriesContent(): Boolean {
    if (type == RezkaType.SERIES) return true
    if (type == RezkaType.COLLECTIONS) return false

    val r = rating.lowercase()
    val sub = subtitle.lowercase()

    // 1. Проверка явных признаков серий в рейтинге/плашке ("1 сезон 2 серия", "12 серий", "2 сезон")
    val hasSeriesBadge = r.contains("сезон") || r.contains("сез") || r.contains("сер") || r.contains("эпизод")

    // 2. Многосерийные фильмы (категория MOVIE, но с сериями)
    if (type == RezkaType.MOVIE) {
        return hasSeriesBadge || sub.contains("многосерийный")
    }

    // 3. Аниме и Мультфильмы: могут быть как полнометражными (фильмы), так и сериалами!
    val hasSeriesKeywords = sub.contains("сериал") || sub.contains("сериалы") ||
            sub.contains("мультсериал") || sub.contains("аниме-сериал")
    if (hasSeriesKeywords || hasSeriesBadge) {
        return true
    }

    val hasMovieKeywords = sub.contains("фильм") || sub.contains("фильмы") ||
            sub.contains("полнометраж") || sub.contains("movie")
    if (hasMovieKeywords) {
        return false
    }

    // Числовой рейтинг без упоминания серий (например, "8.4", "7.9") на карточке аниме/мультфильма указывает на фильм
    if (r.isNotEmpty() && r.matches(Regex("""^\s*\d+([\.,]\d+)?\s*$"""))) {
        return false
    }

    // Проверка явных путей в URL
    if (url.contains("/series/")) return true
    if (url.contains("/films/")) return false

    // По умолчанию: если нет признаков серий — считаем полнометражным фильмом
    return false
}

data class RatingInfo(
    val imdb: String = "",
    val imdbVotes: String = "",
    val kinopoisk: String = "",
    val kinopoiskVotes: String = "",
    val rezka: String = "",
    val rezkaVotes: String = ""
) : Serializable

data class CommentItem(
    val id: String,
    val author: String,
    val avatarUrl: String = "",
    val date: String = "",
    val text: String,
    val likes: String = "",
    val indent: Int = 0
) : Serializable

data class ScheduleItem(
    val seasonEpisode: String, // "1 сезон 5 серия"
    val title: String = "",    // Название серии
    val releaseDate: String,   // Дата выхода (оригинал)
    val ruReleaseDate: String = "", // Дата выхода (рус)
    val isReleased: Boolean = false
) : Serializable

data class CommentsResult(
    val comments: List<CommentItem> = emptyList(),
    val currentPage: Int = 1,
    val totalPages: Int = 1,
    val hasMore: Boolean = false,
    val totalCommentsCount: Int = 0
) : Serializable

data class RezkaDetail(
    val id: String,
    val title: String,
    val originalTitle: String = "",
    val description: String = "",
    val imageUrl: String,
    val year: String = "",
    val releaseDate: String = "",       // Точная дата выхода / премьеры
    val country: String = "",
    val countryFlag: String = "",       // Страны с эмодзи-флагами
    val genres: List<String> = emptyList(),
    val rating: String = "",
    val ratingInfo: RatingInfo = RatingInfo(),
    val director: String = "",          // Режиссёр
    val directorsList: List<LinkItem> = emptyList(),
    val ageRestriction: String = "",    // 18+, 16+, 12+
    val duration: String = "",          // Хронометраж / Длительность
    val slogan: String = "",            // Слоган фильма
    val inCollections: List<String> = emptyList(), // Входит в списки
    val collectionsList: List<LinkItem> = emptyList(),
    val seriesCollection: String = "",  // Из серии (франшиза)
    val seriesCollectionList: List<LinkItem> = emptyList(),
    val franchiseTitle: String = "",    // Название франшизы/саги
    val franchiseItems: List<FranchiseItem> = emptyList(), // Части франшизы/саги
    val actors: List<String> = emptyList(), // В главных ролях
    val actorsList: List<LinkItem> = emptyList(),
    val trailerUrl: String = "",        // Ссылка на YouTube трейлер
    val comments: List<CommentItem> = emptyList(), // Отзывы
    val commentsTotalPages: Int = 1,    // Количество страниц отзывов
    val commentsHasMore: Boolean = false, // Есть ли следующая страница отзывов
    val commentsTotalCount: Int = 0,    // Всего отзывов
    val schedule: List<ScheduleItem> = emptyList(), // График выхода серий
    val type: RezkaType,
    val translators: List<Translator> = emptyList(),
    val seasons: List<Season> = emptyList(),
    val numericPostId: String = "",
    val isReleased: Boolean = true,
    val defaultCam: Int = 0,
    val defaultAds: Int = 0,
    val defaultDirector: Int = 0
) : Serializable {
    /**
     * Показывает, имеет ли релиз серии/эпизоды:
     * - Сериалы, аниме-сериалы, мультсериалы (как вышедшие, так и ожидающие релиза с расписанием)
     * - Многосерийные фильмы
     * Возвращает false для любых полнометражных фильмов, полнометражных аниме и полнометражных мультфильмов.
     */
    val hasEpisodes: Boolean
        get() {
            if (type == RezkaType.SERIES) return true
            if (seasons.isNotEmpty() && seasons.any { it.episodes.isNotEmpty() }) return true
            if (schedule.isNotEmpty()) return true
            // Для аниме и мультиков проверяем наличие сериальных жанров / коллекций
            val isExplicitSeries = genres.any { g ->
                val lower = g.lowercase()
                lower.contains("сериал") || lower.contains("мультсериал") || lower.contains("аниме-сериал")
            } || inCollections.any { c ->
                val lower = c.lowercase()
                lower.contains("сериал") || lower.contains("мультсериал") || lower.contains("аниме-сериал")
            }
            if (isExplicitSeries) return true
            return false
        }

    /**
     * Является ли контент многосерийным фильмом (категория MOVIE/ANIME/CARTOON, но с наличием серий/частей).
     */
    val isMultiSeriesMovie: Boolean
        get() = (type == RezkaType.MOVIE || type == RezkaType.ANIME || type == RezkaType.CARTOON) &&
                seasons.isNotEmpty() && seasons.any { it.episodes.isNotEmpty() } &&
                !genres.any { it.contains("сериал", ignoreCase = true) }
}

data class FranchiseItem(
    val id: String,
    val title: String,
    val url: String,
    val isCurrent: Boolean = false,
    val year: String = ""
) : Serializable

data class Translator(
    val id: String,
    val name: String,
    val isDefault: Boolean = false,
    val flagUrl: String = "",
    val isPremium: Boolean = false,
    val premiumUrl: String = "",
    val url: String = "",
    val isCam: Boolean = false,
    val isAds: Boolean = false,
    val isDirector: Boolean = false,
    val cam: Int = 0,
    val ads: Int = 0,
    val director: Int = 0
) : Serializable

data class Season(
    val id: Int,
    val name: String,
    val episodes: List<Episode> = emptyList()
) : Serializable

data class Episode(
    val id: String,
    val name: String,
    val seasonId: Int = 1,
    val translatorId: String = ""
) : Serializable

data class SubtitleTrack(
    val language: String, // e.g. "ru", "en", "uk"
    val title: String,    // e.g. "Русский", "English", "Украинский"
    val url: String,      // Direct URL to WebVTT or SRT file
    val isDefault: Boolean = false
) : Serializable

data class StreamUrl(
    val quality: String, // e.g. "1080p", "720p", "480p", "360p"
    val url: String,
    val backupUrls: List<String> = emptyList(),
    val directMp4Url: String = "",
    val subtitles: List<SubtitleTrack> = emptyList()
) : Serializable

// Room Entities for local database
@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String,
    val rating: String,
    val url: String,
    val type: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "watch_history")
data class WatchHistoryEntity(
    @PrimaryKey val id: String, // item_id + season + episode
    val itemId: String,
    val title: String,
    val imageUrl: String,
    val subtitle: String, // Season 1, Episode 2 etc.
    val url: String = "",
    val translatorId: String = "",
    val translatorName: String = "",
    val season: Int = 0,
    val episode: String = "",
    val progressMs: Long = 0L,
    val durationMs: Long = 0L,
    val totalEpisodes: Int = 0,
    val episodeIndex: Int = 0,
    val totalSeasons: Int = 0,
    val isFullyWatched: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "series_subscriptions")
data class SeriesSubscriptionEntity(
    @PrimaryKey val id: String, // itemId сериала
    val title: String,
    val imageUrl: String,
    val url: String,
    val type: String,
    val numericPostId: String = "",
    val translatorId: String = "",
    val lastKnownSeason: Int,
    val lastKnownEpisode: Int,
    val lastEpisodeName: String,
    val subscribedAt: Long = System.currentTimeMillis(),
    val lastCheckedAt: Long = 0L,
    val hasUnseenUpdate: Boolean = false,
    val lastNotifiedSeason: Int = 0,
    val lastNotifiedEpisode: Int = 0
) : Serializable

@Entity(tableName = "offline_media")
data class OfflineMediaEntity(
    @PrimaryKey val id: String, // itemId_season_episode_quality
    val itemId: String,
    val title: String,
    val subtitle: String = "",
    val imageUrl: String = "",
    val localPosterPath: String = "",
    val videoPath: String,
    val type: String = "MOVIE", // MOVIE, SERIES, ANIME, CARTOON
    val genres: String = "",
    val year: String = "",
    val country: String = "",
    val season: Int = 0,
    val episode: String = "",
    val translatorId: String = "",
    val translatorName: String = "",
    val quality: String = "",
    val fileSizeBytes: Long = 0L,
    val durationMs: Long = 0L,
    val downloadId: Long = -1L,
    val downloadStatus: Int = 0, // 0 = downloading/pending, 1 = completed, 2 = failed
    val createdAt: Long = System.currentTimeMillis(),
    val description: String = "",
    val detailJson: String = ""
) : Serializable

fun SeriesSubscriptionEntity.isMovie(): Boolean {
    val typeUpper = type.trim().uppercase()
    if (typeUpper == "MOVIE") return true
    if (url.contains("/films/")) return true
    if (lastEpisodeName.contains("фильм", ignoreCase = true)) return true
    if (url.contains("/series/")) return false
    if (lastKnownSeason > 1 || lastKnownEpisode > 1) return false
    return typeUpper != "SERIES"
}

fun SeriesSubscriptionEntity.isMultiSeriesMovie(): Boolean {
    val typeUpper = type.trim().uppercase()
    val isMovieType = typeUpper == "MOVIE" || url.contains("/films/") || lastEpisodeName.contains("фильм", ignoreCase = true)
    return isMovieType && (lastKnownEpisode > 1 || lastEpisodeName.contains("сери", ignoreCase = true) || lastEpisodeName.contains("част", ignoreCase = true))
}

data class AggregatedHistoryItem(
    val itemId: String,
    val title: String,
    val imageUrl: String,
    val url: String,
    val latestSeason: Int,
    val latestEpisode: String,
    val latestTranslatorName: String,
    val isSeries: Boolean,
    val totalProgressFraction: Float,
    val watchedEpisodesCount: Int,
    val totalEpisodesCount: Int,
    val latestHistoryId: String,
    val timestamp: Long,
    val isFullyWatched: Boolean = false,
    val currentEpisodeIndex: Int = 0
) : Serializable

sealed interface ScreenState : Serializable {
    data class Detail(
        val item: RezkaItem,
        val initialTranslatorId: String? = null,
        val fromHistory: Boolean = false
    ) : ScreenState
    data class ThematicList(val title: String, val url: String) : ScreenState
    data class PersonProfile(val name: String, val url: String) : ScreenState
}

data class ParsedRezkaLink(
    val item: RezkaItem,
    val translatorId: String? = null
) : Serializable

data class RezkaCareerSection(
    val title: String,
    val stats: String = "",
    val items: List<RezkaItem> = emptyList()
) : Serializable

data class RezkaPerson(
    val id: String,
    val name: String,
    val originalName: String = "",
    val photoUrl: String = "",
    val info: Map<String, String> = emptyMap(),
    val filmography: List<RezkaItem> = emptyList(),
    val careerSections: List<RezkaCareerSection> = emptyList()
) : Serializable

data class MirrorAuditCheckResult(
    val mirror: String,
    val catalogSuccess: Boolean,
    val streamSuccess: Boolean,
    val catalogItems: List<RezkaItem>? = null,
    val errorMessage: String? = null
) : Serializable
