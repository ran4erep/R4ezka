package com.example.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY timestamp DESC")
    fun getAllFavorites(): Flow<List<FavoriteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(favorite: FavoriteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorites(favorites: List<FavoriteEntity>)

    @Query("DELETE FROM favorites WHERE id = :id")
    suspend fun deleteFavoriteById(id: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE id = :id LIMIT 1)")
    suspend fun isFavorite(id: String): Boolean

    @Query("SELECT * FROM favorites")
    suspend fun getAllFavoritesList(): List<FavoriteEntity>
}

@Dao
interface WatchHistoryDao {
    @Query("SELECT * FROM watch_history ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<WatchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: WatchHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistoryList(historyList: List<WatchHistoryEntity>)

    @Query("SELECT * FROM watch_history WHERE itemId = :itemId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getHistoryByItemId(itemId: String): WatchHistoryEntity?

    @Query("SELECT * FROM watch_history WHERE itemId = :itemId AND season = :season AND episode = :episode ORDER BY timestamp DESC LIMIT 1")
    suspend fun getHistoryByEpisode(itemId: String, season: Int, episode: String): WatchHistoryEntity?

    @Query("DELETE FROM watch_history WHERE id = :id")
    suspend fun deleteHistoryById(id: String)

    @Query("DELETE FROM watch_history WHERE itemId = :itemId")
    suspend fun deleteHistoryByItemId(itemId: String)

    @Query("DELETE FROM watch_history")
    suspend fun clearAllHistory()

    @Query("SELECT * FROM watch_history")
    suspend fun getAllHistoryList(): List<WatchHistoryEntity>
}

@Dao
interface SeriesSubscriptionDao {
    @Query("SELECT * FROM series_subscriptions ORDER BY subscribedAt DESC")
    fun getAllSubscriptions(): Flow<List<SeriesSubscriptionEntity>>

    @Query("SELECT * FROM series_subscriptions")
    suspend fun getAllSubscriptionsList(): List<SeriesSubscriptionEntity>

    @Query("SELECT * FROM series_subscriptions WHERE id = :id LIMIT 1")
    suspend fun getSubscriptionById(id: String): SeriesSubscriptionEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM series_subscriptions WHERE id = :id LIMIT 1)")
    suspend fun isSubscribed(id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM series_subscriptions WHERE id = :id LIMIT 1)")
    fun isSubscribedFlow(id: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubscription(subscription: SeriesSubscriptionEntity)

    @Query("DELETE FROM series_subscriptions WHERE id = :id")
    suspend fun deleteSubscriptionById(id: String)

    @Query("UPDATE series_subscriptions SET lastKnownSeason = :season, lastKnownEpisode = :episode, lastEpisodeName = :episodeName, lastCheckedAt = :checkedAt, hasUnseenUpdate = :hasUpdate WHERE id = :id")
    suspend fun updateEpisodeProgress(id: String, season: Int, episode: Int, episodeName: String, checkedAt: Long, hasUpdate: Boolean)

    @Query("UPDATE series_subscriptions SET lastCheckedAt = :checkedAt WHERE id = :id")
    suspend fun updateCheckedTime(id: String, checkedAt: Long)

    @Query("UPDATE series_subscriptions SET hasUnseenUpdate = 0 WHERE id = :id")
    suspend fun markSeen(id: String)
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE watch_history ADD COLUMN episodeIndex INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE watch_history ADD COLUMN totalSeasons INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `series_subscriptions` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `title` TEXT NOT NULL,
                `imageUrl` TEXT NOT NULL,
                `url` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `lastKnownSeason` INTEGER NOT NULL,
                `lastKnownEpisode` INTEGER NOT NULL,
                `lastEpisodeName` TEXT NOT NULL,
                `subscribedAt` INTEGER NOT NULL,
                `lastCheckedAt` INTEGER NOT NULL,
                `hasUnseenUpdate` INTEGER NOT NULL,
                `lastNotifiedSeason` INTEGER NOT NULL,
                `lastNotifiedEpisode` INTEGER NOT NULL
            )
        """.trimIndent())
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE series_subscriptions ADD COLUMN numericPostId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE series_subscriptions ADD COLUMN translatorId TEXT NOT NULL DEFAULT ''")
    }
}

@Database(entities = [FavoriteEntity::class, WatchHistoryEntity::class, SeriesSubscriptionEntity::class], version = 6, exportSchema = false)
abstract class RezkaDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun seriesSubscriptionDao(): SeriesSubscriptionDao

    companion object {
        @Volatile
        private var INSTANCE: RezkaDatabase? = null

        fun getDatabase(context: Context): RezkaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RezkaDatabase::class.java,
                    "rezka_database"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class RezkaRepository(private val db: RezkaDatabase) {
    val favorites: Flow<List<FavoriteEntity>> = db.favoriteDao().getAllFavorites()
    val watchHistory: Flow<List<WatchHistoryEntity>> = db.watchHistoryDao().getAllHistory()
    val subscriptions: Flow<List<SeriesSubscriptionEntity>> = db.seriesSubscriptionDao().getAllSubscriptions()

    fun isSubscribedFlow(id: String): Flow<Boolean> = db.seriesSubscriptionDao().isSubscribedFlow(id)
    suspend fun isSubscribed(id: String): Boolean = db.seriesSubscriptionDao().isSubscribed(id)
    suspend fun getSubscription(id: String): SeriesSubscriptionEntity? = db.seriesSubscriptionDao().getSubscriptionById(id)
    suspend fun addSubscription(subscription: SeriesSubscriptionEntity) = db.seriesSubscriptionDao().insertSubscription(subscription)
    suspend fun removeSubscription(id: String) = db.seriesSubscriptionDao().deleteSubscriptionById(id)
    suspend fun getAllSubscriptionsList(): List<SeriesSubscriptionEntity> = db.seriesSubscriptionDao().getAllSubscriptionsList()
    suspend fun updateSubscriptionProgress(id: String, season: Int, episode: Int, episodeName: String, checkedAt: Long, hasUpdate: Boolean) {
        db.seriesSubscriptionDao().updateEpisodeProgress(id, season, episode, episodeName, checkedAt, hasUpdate)
    }
    suspend fun updateSubscriptionCheckedTime(id: String, checkedAt: Long) {
        db.seriesSubscriptionDao().updateCheckedTime(id, checkedAt)
    }
    suspend fun markSubscriptionSeen(id: String) {
        db.seriesSubscriptionDao().markSeen(id)
    }

    suspend fun isFavorite(id: String): Boolean = db.favoriteDao().isFavorite(id)

    suspend fun addFavorite(item: RezkaItem) {
        db.favoriteDao().insertFavorite(
            FavoriteEntity(
                id = item.id,
                title = item.title,
                subtitle = item.subtitle,
                imageUrl = item.imageUrl,
                rating = item.rating,
                url = item.url,
                type = item.type.name
            )
        )
    }

    suspend fun removeFavorite(id: String) {
        db.favoriteDao().deleteFavoriteById(id)
    }

    suspend fun saveWatchProgress(
        itemId: String,
        title: String,
        imageUrl: String,
        subtitle: String,
        url: String = "",
        translatorId: String = "",
        translatorName: String = "",
        season: Int = 0,
        episode: String = "",
        progressMs: Long = 0L,
        durationMs: Long = 0L,
        totalEpisodes: Int = 0,
        episodeIndex: Int = 0,
        totalSeasons: Int = 0
    ) {
        val id = "${itemId}_${season}_${episode}"
        db.watchHistoryDao().insertHistory(
            WatchHistoryEntity(
                id = id,
                itemId = itemId,
                title = title,
                imageUrl = imageUrl,
                subtitle = subtitle,
                url = url,
                translatorId = translatorId,
                translatorName = translatorName,
                season = season,
                episode = episode,
                progressMs = progressMs,
                durationMs = durationMs,
                totalEpisodes = totalEpisodes,
                episodeIndex = episodeIndex,
                totalSeasons = totalSeasons,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    suspend fun getWatchHistoryForMovie(itemId: String): WatchHistoryEntity? {
        return db.watchHistoryDao().getHistoryByItemId(itemId)
    }

    suspend fun getWatchHistoryForEpisode(itemId: String, season: Int, episode: String): WatchHistoryEntity? {
        return db.watchHistoryDao().getHistoryByEpisode(itemId, season, episode)
    }

    suspend fun deleteHistory(id: String) {
        db.watchHistoryDao().deleteHistoryById(id)
    }

    suspend fun deleteHistoryByItemId(itemId: String) {
        db.watchHistoryDao().deleteHistoryByItemId(itemId)
    }

    suspend fun clearAllHistory() {
        db.watchHistoryDao().clearAllHistory()
    }

    suspend fun getAllFavoritesList(): List<FavoriteEntity> {
        return db.favoriteDao().getAllFavoritesList()
    }

    suspend fun insertFavorites(favorites: List<FavoriteEntity>) {
        db.favoriteDao().insertFavorites(favorites)
    }

    suspend fun insertFavoriteEntity(entity: FavoriteEntity) {
        db.favoriteDao().insertFavorite(entity)
    }

    suspend fun getAllHistoryList(): List<WatchHistoryEntity> {
        return db.watchHistoryDao().getAllHistoryList()
    }

    suspend fun insertHistoryList(historyList: List<WatchHistoryEntity>) {
        db.watchHistoryDao().insertHistoryList(historyList)
    }

    suspend fun insertHistoryEntity(entity: WatchHistoryEntity) {
        db.watchHistoryDao().insertHistory(entity)
    }
}
