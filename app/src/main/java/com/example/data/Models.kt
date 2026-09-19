package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable

data class LinkItem(val name: String, val url: String) : Serializable

enum class RezkaType {
    MOVIE, SERIES, ANIME, CARTOON
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

data class RezkaItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String,
    val rating: String = "",
    val url: String,
    val type: RezkaType,
    val releaseDateNum: Int = MovieDateParser.extractReleaseDateNum(subtitle, url),
    val numericId: Long = MovieDateParser.extractNumericId(id, url)
) : Serializable

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
    val isReleased: Boolean = true
) : Serializable

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
    val url: String = ""
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
    val quality: String, // e.g. "1080p Ultra", "1080p", "720p", "480p", "360p"
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
    val timestamp: Long
) : Serializable

sealed interface ScreenState : Serializable {
    data class Detail(
        val item: RezkaItem,
        val initialTranslatorId: String? = null
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
