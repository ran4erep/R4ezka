package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.RezkaApplication
import com.example.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed interface CatalogState {
    object Loading : CatalogState
    data class Success(val items: List<RezkaItem>) : CatalogState
    data class Error(val message: String) : CatalogState
}

sealed interface DetailState {
    object Idle : DetailState
    object Loading : DetailState
    data class Success(val detail: RezkaDetail) : DetailState
    data class Error(val message: String) : DetailState
}

data class SeriesProgressCalculation(
    val absoluteEpisodeIndex: Int,
    val watchedEpisodesCount: Int,
    val totalEpisodesCount: Int,
    val totalProgressFraction: Float
)

data class MovieCommentsState(
    val comments: List<CommentItem> = emptyList(),
    val currentPage: Int = 1,
    val totalPages: Int = 1,
    val totalCount: Int = 0,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val numericPostId: String = ""
)

class RezkaViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as RezkaApplication).repository

    // Reactive database flows
    val favorites: StateFlow<List<FavoriteEntity>> = repository.favorites
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val watchHistory: StateFlow<List<WatchHistoryEntity>> = repository.watchHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val aggregatedWatchHistory: StateFlow<List<AggregatedHistoryItem>> = repository.watchHistory
        .map { list ->
            list.groupBy { it.itemId }.map { (itemId, items) ->
                val latest = items.maxByOrNull { it.timestamp } ?: items.first()
                val isSeries = latest.season > 0 || items.any { it.season > 0 }

                val totalProgressFraction: Float
                val watchedEpisodesCount: Int
                val totalEpisodesCount: Int

                if (!isSeries) {
                    totalProgressFraction = if (latest.durationMs > 0) {
                        (latest.progressMs.toFloat() / latest.durationMs.toFloat()).coerceIn(0f, 1f)
                    } else 0f
                    watchedEpisodesCount = if (totalProgressFraction >= 0.85f) 1 else 0
                    totalEpisodesCount = 1
                } else {
                    val calc = calculateSeriesProgress(latest, items)
                    totalProgressFraction = calc.totalProgressFraction
                    watchedEpisodesCount = calc.watchedEpisodesCount
                    totalEpisodesCount = calc.totalEpisodesCount
                }

                AggregatedHistoryItem(
                    itemId = itemId,
                    title = latest.title,
                    imageUrl = latest.imageUrl,
                    url = latest.url,
                    latestSeason = latest.season,
                    latestEpisode = latest.episode,
                    latestTranslatorName = latest.translatorName,
                    isSeries = isSeries,
                    totalProgressFraction = totalProgressFraction,
                    watchedEpisodesCount = watchedEpisodesCount,
                    totalEpisodesCount = totalEpisodesCount,
                    latestHistoryId = latest.id,
                    timestamp = latest.timestamp
                )
            }.sortedByDescending { it.timestamp }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // UI States
    private val _catalogState = MutableStateFlow<CatalogState>(CatalogState.Loading)
    val catalogState: StateFlow<CatalogState> = _catalogState.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _isEndReached = MutableStateFlow(false)
    val isEndReached: StateFlow<Boolean> = _isEndReached.asStateFlow()

    private val _detailState = MutableStateFlow<DetailState>(DetailState.Idle)
    val detailState: StateFlow<DetailState> = _detailState.asStateFlow()

    private val _commentsState = MutableStateFlow(MovieCommentsState())
    val commentsState: StateFlow<MovieCommentsState> = _commentsState.asStateFlow()
    private var commentsJob: Job? = null

    // Current filter selections
    private val _currentType = MutableStateFlow(RezkaType.MOVIE)
    val currentType: StateFlow<RezkaType> = _currentType.asStateFlow()

    private val _currentSection = MutableStateFlow(SectionType.LATEST)
    val currentSection: StateFlow<SectionType> = _currentSection.asStateFlow()

    private val _currentGenre = MutableStateFlow("")
    val currentGenre: StateFlow<String> = _currentGenre.asStateFlow()

    private val _genresList = MutableStateFlow<List<GenreItem>>(listOf(GenreItem("Без жанра", "")))
    val genresList: StateFlow<List<GenreItem>> = _genresList.asStateFlow()

    var currentCatalogPage = 1
        private set
    var searchQuery = ""
        private set

    // Loaded states with O(1) lookup and insertion-order preservation (zero duplicate keys)
    private val loadedMap = LinkedHashMap<String, RezkaItem>()
    private var catalogJob: Job? = null
    private var paginationJob: Job? = null
    private var searchJob: Job? = null

    init {
        // Load default catalog (Movies) on startup
        loadCatalog(RezkaType.MOVIE, SectionType.LATEST, "", forceRefresh = true)
    }

    /**
     * Loads catalog items for the specified type, section, genre and resets pagination
     */
    fun loadCatalog(
        type: RezkaType = _currentType.value,
        section: SectionType = _currentSection.value,
        genre: String = _currentGenre.value,
        forceRefresh: Boolean = false
    ) {
        paginationJob?.cancel()
        catalogJob?.cancel()
        searchJob?.cancel()

        // If category changed, reset active genre to "Без жанра"
        val actualGenre = if (type != _currentType.value) "" else genre

        _currentType.value = type
        _currentSection.value = section
        _currentGenre.value = actualGenre
        searchQuery = ""
        currentCatalogPage = 1
        _isEndReached.value = false
        _isLoadingMore.value = false

        // Update genres list immediately from service cache if available for this category
        val cachedGenres = RezkaService.getGenresForCategory(type)
        if (cachedGenres.isNotEmpty()) {
            _genresList.value = cachedGenres
        }

        if (forceRefresh || loadedMap.isEmpty()) {
            loadedMap.clear()
            _catalogState.value = CatalogState.Loading
        }

        catalogJob = viewModelScope.launch {
            try {
                val items = RezkaService.getCatalog(type, section, actualGenre, 1)
                loadedMap.clear()
                items.forEach { loadedMap[it.id] = it }
                _catalogState.value = CatalogState.Success(loadedMap.values.toList())

                // Update genres list after parsing page HTML
                val dynamicGenres = RezkaService.getGenresForCategory(type)
                if (dynamicGenres.isNotEmpty()) {
                    _genresList.value = dynamicGenres
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _catalogState.value = CatalogState.Error(e.message ?: "Неизвестная ошибка")
            }
        }
    }

    /**
     * Loads next page smoothly without UI flickering or CPU bottlenecks
     */
    fun loadNextPage() {
        if (_isLoadingMore.value || _isEndReached.value || searchQuery.isNotBlank() || _catalogState.value is CatalogState.Loading) {
            return
        }

        val nextPage = currentCatalogPage + 1
        _isLoadingMore.value = true

        paginationJob?.cancel()
        paginationJob = viewModelScope.launch {
            try {
                val items = RezkaService.getCatalog(_currentType.value, _currentSection.value, _currentGenre.value, nextPage)
                val newUniqueItems = items.filterNot { loadedMap.containsKey(it.id) }
                if (newUniqueItems.isEmpty()) {
                    _isEndReached.value = true
                } else {
                    newUniqueItems.forEach { loadedMap[it.id] = it }
                    currentCatalogPage = nextPage
                    _catalogState.value = CatalogState.Success(loadedMap.values.toList())
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Keep current items intact on pagination error
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    /**
     * Triggers search query with debounce
     */
    fun onSearchQueryChanged(query: String) {
        searchQuery = query
        searchJob?.cancel()
        paginationJob?.cancel()
        _isLoadingMore.value = false

        if (query.isBlank()) {
            loadCatalog(_currentType.value, forceRefresh = true)
            return
        }

        searchJob = viewModelScope.launch {
            delay(400) // Debounce for 400ms to reduce CPU load and network requests
            _catalogState.value = CatalogState.Loading
            try {
                val results = RezkaService.search(query).distinctBy { it.id }
                _catalogState.value = CatalogState.Success(results)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _catalogState.value = CatalogState.Error(e.message ?: "Ошибка поиска")
            }
        }
    }

    // Кэш страниц комментариев для текущего фильма (O(1) доступ в памяти без лишних запросов и нагрузки на CPU)
    private val commentsPageCache = HashMap<Int, CommentsResult>()

    /**
     * Loads detailed info of selected item
     */
    fun loadDetail(url: String) {
        _detailState.value = DetailState.Loading
        _commentsState.value = MovieCommentsState(isLoading = true)
        commentsPageCache.clear()
        viewModelScope.launch {
            try {
                val detail = RezkaService.getDetail(url)
                _detailState.value = DetailState.Success(detail)

                val initialComments = detail.comments
                val initialTotalPages = maxOf(1, detail.commentsTotalPages)
                val initialHasMore = detail.commentsHasMore
                val initialTotalCount = detail.commentsTotalCount

                if (initialComments.isNotEmpty()) {
                    commentsPageCache[1] = CommentsResult(
                        comments = initialComments,
                        currentPage = 1,
                        totalPages = initialTotalPages,
                        hasMore = initialHasMore,
                        totalCommentsCount = initialTotalCount
                    )
                }

                _commentsState.value = MovieCommentsState(
                    comments = initialComments,
                    currentPage = 1,
                    totalPages = initialTotalPages,
                    totalCount = initialTotalCount,
                    isLoading = false,
                    isLoadingMore = false,
                    hasMore = initialHasMore,
                    numericPostId = detail.numericPostId
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _detailState.value = DetailState.Error("Что-то пошло не так :(")
                _commentsState.value = MovieCommentsState(isLoading = false)
            }
        }
    }

    fun clearDetail() {
        _detailState.value = DetailState.Idle
        _commentsState.value = MovieCommentsState()
        commentsPageCache.clear()
        commentsJob?.cancel()
    }

    /**
     * Очищает сбойный кэш/куки и повторно загружает детали фильма с сети
     */
    fun clearCacheAndReloadDetail(url: String) {
        RezkaService.clearAllCacheAndSession()
        commentsPageCache.clear()
        loadDetail(url)
    }

    /**
     * Очищает сбойный кэш/куки и повторно загружает каталог с сети
     */
    fun clearCacheAndReloadCatalog() {
        RezkaService.clearAllCacheAndSession()
        commentsPageCache.clear()
        loadCatalog(_currentType.value, forceRefresh = true)
    }

    /**
     * Полный сброс всех кэшей и сессионных данных
     */
    fun clearAllCacheAndSession() {
        RezkaService.clearAllCacheAndSession()
        loadedMap.clear()
        commentsPageCache.clear()
        loadCatalog(_currentType.value, forceRefresh = true)
    }

    /**
     * Загрузка определенной страницы отзывов (пагинация 1, 2, 3...)
     * Использует быстрый кэш в памяти: при переключении назад не тратит трафик и ресурсы процессора.
     */
    fun loadCommentsPage(page: Int, forceRefresh: Boolean = false) {
        val current = _commentsState.value
        val postId = current.numericPostId
        if (postId.isEmpty() || page < 1) return
        if (current.isLoading || current.isLoadingMore) return
        if (!forceRefresh && page == current.currentPage && current.comments.isNotEmpty()) return

        // 1. Проверяем кэш в памяти: если страница уже загружалась, выдаем моментально
        if (!forceRefresh && commentsPageCache.containsKey(page)) {
            val cached = commentsPageCache[page]
            if (cached != null) {
                _commentsState.value = current.copy(
                    comments = cached.comments,
                    currentPage = page,
                    totalPages = maxOf(current.totalPages, cached.totalPages, page),
                    totalCount = if (cached.totalCommentsCount > 0) cached.totalCommentsCount else current.totalCount,
                    isLoading = false,
                    isLoadingMore = false,
                    hasMore = cached.hasMore
                )
                return
            }
        }

        // 2. Иначе запрашиваем с сервера
        _commentsState.value = current.copy(isLoading = true)
        commentsJob?.cancel()
        commentsJob = viewModelScope.launch {
            try {
                val result = RezkaService.getComments(postId, page)
                if (result.comments.isNotEmpty()) {
                    commentsPageCache[page] = result
                }
                _commentsState.value = current.copy(
                    comments = if (result.comments.isNotEmpty()) result.comments else current.comments,
                    currentPage = page,
                    totalPages = maxOf(current.totalPages, result.totalPages, page),
                    totalCount = if (result.totalCommentsCount > 0) result.totalCommentsCount else current.totalCount,
                    isLoading = false,
                    isLoadingMore = false,
                    hasMore = result.hasMore
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _commentsState.value = _commentsState.value.copy(isLoading = false)
            }
        }
    }

    /**
     * Подгрузка следующей страницы отзывов в общий список (режим "Загрузить ещё")
     */
    fun appendNextCommentsPage() {
        val current = _commentsState.value
        val postId = current.numericPostId
        if (postId.isEmpty() || current.isLoading || current.isLoadingMore || !current.hasMore) return

        val nextPage = current.currentPage + 1
        _commentsState.value = current.copy(isLoadingMore = true)
        commentsJob?.cancel()
        commentsJob = viewModelScope.launch {
            try {
                val result = RezkaService.getComments(postId, nextPage)
                if (result.comments.isNotEmpty()) {
                    commentsPageCache[nextPage] = result
                }
                val combined = (current.comments + result.comments).distinctBy { it.id }
                _commentsState.value = current.copy(
                    comments = combined,
                    currentPage = nextPage,
                    totalPages = maxOf(current.totalPages, result.totalPages, nextPage),
                    totalCount = if (result.totalCommentsCount > 0) result.totalCommentsCount else current.totalCount,
                    isLoading = false,
                    isLoadingMore = false,
                    hasMore = result.hasMore
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _commentsState.value = _commentsState.value.copy(isLoadingMore = false)
            }
        }
    }

    /**
     * Favorite management
     */
    fun toggleFavorite(item: RezkaItem, isFav: Boolean) {
        viewModelScope.launch {
            if (isFav) {
                repository.removeFavorite(item.id)
                FirebaseSyncManager.onFavoriteRemoved(item.id)
            } else {
                val entity = FavoriteEntity(
                    id = item.id,
                    title = item.title,
                    subtitle = item.subtitle,
                    imageUrl = item.imageUrl,
                    rating = item.rating,
                    url = item.url,
                    type = item.type.name
                )
                repository.insertFavoriteEntity(entity)
                FirebaseSyncManager.onFavoriteAdded(entity)
            }
        }
    }

    /**
     * Saves watching progress to local database and cloud Firebase RTDB engine
     */
    fun saveWatchProgress(
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
        val entity = WatchHistoryEntity(
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
        viewModelScope.launch {
            repository.insertHistoryEntity(entity)
            FirebaseSyncManager.onWatchProgress(entity)
        }
    }

    fun flushWatchProgress() {
        FirebaseSyncManager.flushPendingProgress()
    }

    fun deleteHistoryByItemId(itemId: String) {
        viewModelScope.launch {
            repository.deleteHistoryByItemId(itemId)
            FirebaseSyncManager.onHistoryDeletedByItemId(itemId)
        }
    }

    /**
     * Fetches stream URLs for playback
     */
    suspend fun getStreamUrls(
        itemId: String,
        translatorId: String,
        isSeries: Boolean,
        season: Int = 0,
        episode: String = ""
    ): List<StreamUrl> {
        return RezkaService.getStreamUrls(itemId, translatorId, isSeries, season, episode)
    }

    suspend fun getEpisodesForTranslator(
        numericId: String,
        translatorId: String
    ): List<Season> {
        return RezkaService.getEpisodesForTranslator(numericId, translatorId)
    }

    suspend fun getSavedProgress(itemId: String): WatchHistoryEntity? {
        return repository.getWatchHistoryForMovie(itemId)
    }

    suspend fun getSavedProgressForEpisode(itemId: String, season: Int, episode: String): WatchHistoryEntity? {
        return repository.getWatchHistoryForEpisode(itemId, season, episode)
    }

    fun deleteHistory(id: String) {
        viewModelScope.launch {
            repository.deleteHistory(id)
            FirebaseSyncManager.onHistoryDeleted(id)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearAllHistory()
            FirebaseSyncManager.onAllHistoryCleared()
        }
    }

    val isLoggedIn: StateFlow<Boolean> = FirebaseSyncManager.isLoggedIn
    val currentUser: StateFlow<String?> = FirebaseSyncManager.currentUser
    val currentUserAvatar: StateFlow<String?> = FirebaseSyncManager.currentUserAvatar
    val isSyncing: StateFlow<Boolean> = FirebaseSyncManager.isSyncing

    fun register(loginName: String, loginPass: String, avatar: String? = null, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val res = FirebaseSyncManager.register(loginName, loginPass, avatar, repository)
            if (res.isSuccess) {
                onResult(true, "Регистрация успешна")
            } else {
                onResult(false, res.exceptionOrNull()?.message ?: "Ошибка регистрации")
            }
        }
    }

    fun updateAvatar(avatar: String?, onResult: ((Boolean, String) -> Unit)? = null) {
        viewModelScope.launch {
            val res = FirebaseSyncManager.updateAvatar(avatar)
            if (res.isSuccess) {
                onResult?.invoke(true, "Аватар обновлен")
            } else {
                onResult?.invoke(false, res.exceptionOrNull()?.message ?: "Ошибка обновления аватара")
            }
        }
    }

    fun login(loginName: String, loginPass: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val res = FirebaseSyncManager.login(loginName, loginPass, repository)
            if (res.isSuccess) {
                onResult(true, res.getOrNull() ?: "Вход выполнен")
            } else {
                onResult(false, res.exceptionOrNull()?.message ?: "Ошибка авторизации")
            }
        }
    }

    fun logout() {
        FirebaseSyncManager.logout()
    }

    fun syncCloudData(onFinished: (() -> Unit)? = null) {
        viewModelScope.launch {
            FirebaseSyncManager.syncAll(repository)
            onFinished?.invoke()
        }
    }

    // Mirror management for dynamic mirror switching
    val currentMirror: StateFlow<String> = RezkaService.currentMirror
    val presetMirrors: List<String> = RezkaService.PRESET_MIRRORS

    // Quality settings for video playback
    val defaultQuality: StateFlow<String> = RezkaService.defaultQuality

    // Auto next episode setting
    val autoNextEpisode: StateFlow<Boolean> = RezkaService.autoNextEpisode

    // Subtitle and Player settings
    val preferredSubtitleLang: StateFlow<String> = RezkaService.preferredSubtitleLang
    val subtitleTextScale: StateFlow<Float> = RezkaService.subtitleTextScale
    val defaultResizeMode: StateFlow<String> = RezkaService.defaultResizeMode

    fun setDefaultQuality(quality: String) {
        RezkaService.setDefaultQuality(quality)
        FirebaseSyncManager.onSettingsUpdated(quality = quality)
    }

    fun setAutoNextEpisode(enabled: Boolean) {
        RezkaService.setAutoNextEpisode(enabled)
        FirebaseSyncManager.onSettingsUpdated(autoNextEpisode = enabled)
    }

    fun setPreferredSubtitleLang(lang: String) {
        RezkaService.setPreferredSubtitleLang(lang)
        FirebaseSyncManager.onSettingsUpdated(preferredSubtitleLang = lang)
    }

    fun setSubtitleTextScale(scale: Float) {
        RezkaService.setSubtitleTextScale(scale)
        FirebaseSyncManager.onSettingsUpdated(subtitleTextScale = scale)
    }

    fun setDefaultResizeMode(mode: String) {
        RezkaService.setDefaultResizeMode(mode)
        FirebaseSyncManager.onSettingsUpdated(resizeMode = mode)
    }

    fun setMirror(newUrl: String): Boolean {
        val success = RezkaService.setMirror(newUrl)
        if (success) {
            loadCatalog(_currentType.value, forceRefresh = true)
            FirebaseSyncManager.onSettingsUpdated(mirror = RezkaService.currentMirror.value)
        }
        return success
    }

    fun resetMirrorToDefault(): String {
        val defaultUrl = RezkaService.resetMirrorToDefault()
        loadCatalog(_currentType.value, forceRefresh = true)
        FirebaseSyncManager.onSettingsUpdated(mirror = defaultUrl)
        return defaultUrl
    }

    suspend fun testMirror(url: String): Result<Long> {
        return RezkaService.testMirror(url)
    }

    companion object {
        /**
         * Высокопроизводительный движок вычисления прогресса сериала по последней просмотренной серии.
         * Учитывает сквозной номер серии из общего числа, прогресс текущей серии и структуру сезонов.
         */
        fun calculateSeriesProgress(
            latest: WatchHistoryEntity,
            items: List<WatchHistoryEntity>
        ): SeriesProgressCalculation {
            val latestSeason = latest.season.coerceAtLeast(1)
            val latestEpNumber = latest.episode.filter { it.isDigit() }.toIntOrNull() ?: 1
            val currentEpProgress = if (latest.durationMs > 0) {
                (latest.progressMs.toFloat() / latest.durationMs.toFloat()).coerceIn(0f, 1f)
            } else 0f

            val storedTotalEpisodes = items.maxOfOrNull { it.totalEpisodes } ?: 0
            val storedTotalSeasons = items.maxOfOrNull { it.totalSeasons } ?: 0

            val absoluteEpisodeIndex: Int
            val estimatedTotalEpisodes: Int

            if (latest.episodeIndex > 0) {
                // Прямой точный сохраненный сквозной индекс
                absoluteEpisodeIndex = latest.episodeIndex
                estimatedTotalEpisodes = if (storedTotalEpisodes > 0) {
                    maxOf(storedTotalEpisodes, absoluteEpisodeIndex)
                } else {
                    absoluteEpisodeIndex
                }
            } else {
                // Интеллектуальный расчет для старых записей или при отсутствии сохраненного сквозного индекса
                if (storedTotalEpisodes > 0 && storedTotalSeasons > 0) {
                    val epsPerSeason = (storedTotalEpisodes.toDouble() / storedTotalSeasons.toDouble()).coerceAtLeast(1.0)
                    val priorEpisodes = ((latestSeason - 1) * epsPerSeason).toInt()
                    absoluteEpisodeIndex = (priorEpisodes + latestEpNumber).coerceIn(1, storedTotalEpisodes)
                    estimatedTotalEpisodes = storedTotalEpisodes
                } else if (storedTotalEpisodes > 0) {
                    val maxEpSeen = items.mapNotNull { it.episode.filter { c -> c.isDigit() }.toIntOrNull() }.maxOrNull()?.coerceAtLeast(1) ?: latestEpNumber
                    val epsPerSeason = maxOf(maxEpSeen, latestEpNumber)
                    val priorEpisodes = (latestSeason - 1) * epsPerSeason
                    absoluteEpisodeIndex = (priorEpisodes + latestEpNumber).coerceIn(1, storedTotalEpisodes)
                    estimatedTotalEpisodes = storedTotalEpisodes
                } else {
                    val maxEpSeen = items.mapNotNull { it.episode.filter { c -> c.isDigit() }.toIntOrNull() }.maxOrNull()?.coerceAtLeast(1) ?: latestEpNumber
                    val maxSeasonSeen = maxOf(latestSeason, items.maxOfOrNull { it.season } ?: 1)
                    val epsPerSeason = maxOf(maxEpSeen, latestEpNumber)
                    val priorEpisodes = (latestSeason - 1) * epsPerSeason
                    absoluteEpisodeIndex = priorEpisodes + latestEpNumber
                    estimatedTotalEpisodes = maxOf(maxSeasonSeen * epsPerSeason, absoluteEpisodeIndex, items.size)
                }
            }

            // Количество завершенных серий до текущей
            val priorCompletedCount = (absoluteEpisodeIndex - 1).coerceAtLeast(0)

            // Засчитываем текущую серию как просмотренную, если посмотрели >= 85%
            val watchedEpisodesCount = if (currentEpProgress >= 0.85f) {
                absoluteEpisodeIndex
            } else {
                priorCompletedCount
            }

            val totalEpisodesCount = maxOf(estimatedTotalEpisodes, absoluteEpisodeIndex, 1)

            // Суммарный прогресс сериала в диапазоне от 0.0 до 1.0
            val totalProgressFraction = ((priorCompletedCount.toFloat() + currentEpProgress) / totalEpisodesCount.toFloat()).coerceIn(0f, 1f)

            return SeriesProgressCalculation(
                absoluteEpisodeIndex = absoluteEpisodeIndex,
                watchedEpisodesCount = watchedEpisodesCount,
                totalEpisodesCount = totalEpisodesCount,
                totalProgressFraction = totalProgressFraction
            )
        }
    }
}

