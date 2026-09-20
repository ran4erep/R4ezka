package com.example

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.data.FirebaseSyncManager
import com.example.data.RezkaDatabase
import com.example.data.RezkaRepository
import com.example.data.RezkaService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RezkaApplication : Application(), ImageLoaderFactory {
    val database by lazy { RezkaDatabase.getDatabase(this) }
    val repository by lazy { RezkaRepository(database) }

    override fun onCreate() {
        super.onCreate()
        RezkaService.init(this)
        FirebaseSyncManager.init(this, repository)
        com.example.data.SeriesUpdateEngine.createNotificationChannel(this)
        
        // Мгновенное создание и инициализация текстового лог-файла проверок сериалов
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            com.example.data.SeriesUpdateLogger.init(this@RezkaApplication)
        }
    }

    /**
     * Высокопроизводительный загрузчик изображений Coil с кэшированием в памяти и на диске,
     * а также поддержкой заголовков User-Agent/Referer и обхода защиты HDRezka.
     */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient { RezkaService.imageOkHttpClient }
            .components {
                add(SvgDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024) // 100 MB
                    .build()
            }
            .crossfade(true)
            .respectCacheHeaders(false)
            .build()
    }
}
