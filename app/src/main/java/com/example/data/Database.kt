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

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE watch_history ADD COLUMN episodeIndex INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE watch_history ADD COLUMN totalSeasons INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(entities = [FavoriteEntity::class, WatchHistoryEntity::class], version = 4, exportSchema = false)
abstract class RezkaDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun watchHistoryDao(): WatchHistoryDao

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
                    .addMigrations(MIGRATION_3_4)
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
