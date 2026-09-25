package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.RezkaApplication
import com.example.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

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

sealed interface MirrorAuditUiState {
    object Idle : MirrorAuditUiState
    object Checking : MirrorAuditUiState
    data class Failed(val message: String) : MirrorAuditUiState
}

data class SeriesProgressCalculation(
    val absoluteEpisodeIndex: Int,
    val watchedEpisodesCount: Int,
    val totalEpisodesCount: Int,
    val totalProgressFraction: Float,
    val isFullyWatched: Boolean = false
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

data class ScrollPosition(val index: Int = 0, val offset: Int = 0)

class RezkaViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as RezkaApplication).repository

    // Store for saving scroll positions across screen orientation changes and UI mode switches
    private val scrollPositions = java.util.concurrent.ConcurrentHashMap<String, ScrollPosition>()

    fun getScrollPosition(key: String): ScrollPosition {
        return scrollPositions[key] ?: ScrollPosition()
    }

    fun saveScrollPosition(key: String, index: Int, offset: Int) {
        if (key.isNotEmpty() && index >= 0 && offset >= 0) {
            scrollPositions[key] = ScrollPosition(index, offset)
        }
    }

    fun resetScrollPosition(key: String) {
        scrollPositions.remove(key)
    }

    private val _catalogScrollResetEvent = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val catalogScrollResetEvent: SharedFlow<Unit> = _catalogScrollResetEvent.asSharedFlow()

    fun requestCatalogScrollToTop() {
        resetScrollPosition("catalog")
        _catalogScrollResetEvent.tryEmit(Unit)
    }

    // Reactive database flows
    val favorites: StateFlow<List<FavoriteEntity>> = repository.favorites
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val watchHistory: StateFlow<List<WatchHistoryEntity>> = repository.watchHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val subscriptions: StateFlow<List<SeriesSubscriptionEntity>> = repository.subscriptions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isCheckingSeriesUpdates = MutableStateFlow(false)
    val isCheckingSeriesUpdates: StateFlow<Boolean> = _isCheckingSeriesUpdates.asStateFlow()

    val aggregatedWatchHistory: StateFlow<List<AggregatedHistoryItem>> = repository.watchHistory
        .map { list ->
            val now = System.currentTimeMillis()
            list.groupBy { it.itemId }.map { (itemId, items) ->
                val latest = items.maxByOrNull { it.timestamp } ?: items.first()
                val isSeries = latest.season > 0 || items.any { it.season > 0 }
                val isManualWatched = latest.isFullyWatched

                val totalProgressFraction: Float
                val watchedEpisodesCount: Int
                val totalEpisodesCount: Int
                val isFullyWatched: Boolean

                if (!isSeries) {
                    val isAutoWatched = isAutoWatchedRuleMet(latest.progressMs, latest.durationMs, latest.timestamp, now)
                    isFullyWatched = isManualWatched || isAutoWatched

                    if (isFullyWatched) {
                        totalProgressFraction = 1.0f
                        watchedEpisodesCount = 1
                        totalEpisodesCount = 1
                    } else {
                        val exactFraction = if (latest.durationMs > 0) {
                            (latest.progressMs.toDouble() / latest.durationMs.toDouble()).coerceIn(0.0, 1.0).toFloat()
                        } else 0f
                        totalProgressFraction = exactFraction
                        watchedEpisodesCount = 0
                        totalEpisodesCount = 1
                    }
                } else {
                    val calc = calculateSeriesProgress(latest, items, now)
                    totalProgressFraction = calc.totalProgressFraction
                    watchedEpisodesCount = calc.watchedEpisodesCount
                    totalEpisodesCount = calc.totalEpisodesCount
                    isFullyWatched = calc.isFullyWatched
                }

                val rawEpDigit = latest.episode.filter { it.isDigit() }.toIntOrNull() ?: 0
                val safeLatestEpisode = if (rawEpDigit > 2500) "1" else latest.episode

                AggregatedHistoryItem(
                    itemId = itemId,
                    title = latest.title,
                    imageUrl = latest.imageUrl,
                    url = latest.url,
                    latestSeason = latest.season,
                    latestEpisode = safeLatestEpisode,
                    latestTranslatorName = latest.translatorName,
                    isSeries = isSeries,
                    totalProgressFraction = totalProgressFraction,
                    watchedEpisodesCount = watchedEpisodesCount,
                    totalEpisodesCount = totalEpisodesCount,
                    latestHistoryId = latest.id,
                    timestamp = latest.timestamp,
                    isFullyWatched = isFullyWatched
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

    // Search History persistence (последние 5 запросов поиска)
    private val searchHistoryPrefs by lazy {
        getApplication<Application>().getSharedPreferences("rezka_search_history_prefs", Context.MODE_PRIVATE)
    }
    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    // Deep Link & Share URL navigation state
    private val _pendingDeepLink = MutableStateFlow<ParsedRezkaLink?>(null)
    val pendingDeepLink: StateFlow<ParsedRezkaLink?> = _pendingDeepLink.asStateFlow()

    private val _isPlayerActive = MutableStateFlow(false)
    val isPlayerActive: StateFlow<Boolean> = _isPlayerActive.asStateFlow()

    fun setPlayerActive(active: Boolean) {
        _isPlayerActive.value = active
    }

    fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val parsed = RezkaService.parseIntent(intent)
        if (parsed != null) {
            _pendingDeepLink.value = parsed
        }
    }

    fun consumePendingDeepLink() {
        _pendingDeepLink.value = null
    }

    var currentCatalogPage = 1
        private set
    var searchQuery = ""
        private set

    // Loaded states with O(1) lookup and insertion-order preservation (zero duplicate keys)
    private val loadedMap = LinkedHashMap<String, RezkaItem>()
    private var catalogJob: Job? = null
    private var paginationJob: Job? = null
    private var searchJob: Job? = null

    private val _mirrorAuditState = MutableStateFlow<MirrorAuditUiState>(MirrorAuditUiState.Idle)
    val mirrorAuditState: StateFlow<MirrorAuditUiState> = _mirrorAuditState.asStateFlow()

    private var auditJob: Job? = null
    private var isFirstLaunchAuditSession = false

    init {
        RezkaService.clearCache()
        loadSearchHistory()
        // Привязываем провайдер и коллбэк синхронизации истории поиска с Firebase
        FirebaseSyncManager.searchHistoryProvider = {
            _searchHistory.value
        }
        FirebaseSyncManager.onSearchHistorySynced = { syncedList ->
            _searchHistory.value = syncedList
            searchHistoryPrefs.edit().putString("recent_queries", syncedList.joinToString("\u0000")).apply()
        }

        if (!RezkaService.isFirstLaunchAuditDone()) {
            startMirrorAudit(isFirstLaunch = true)
        } else {
            // Load default catalog (Movies) on startup
            loadCatalog(RezkaType.MOVIE, SectionType.LATEST, "", forceRefresh = true)
        }
    }

    private fun loadSearchHistory() {
        val raw = searchHistoryPrefs.getString("recent_queries", "") ?: ""
        if (raw.isNotBlank()) {
            _searchHistory.value = raw.split("\u0000").filter { it.isNotBlank() }.take(15)
        }
    }

    // Запоминаем последний зафиксированный запрос, чтобы ровно ОДНО действие (Enter, скролл или выбор фильма)
    // добавило его в историю поиска без повторных аллокаций и лишних сетевых вызовов.
    private var lastCommittedQuery: String = ""

    fun addSearchQueryToHistory(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return
        val current = _searchHistory.value.toMutableList()
        // Удаляем только точные совпадения, чтобы похожие запросы (например, "Веном" и "Веном 2") не перезаписывали друг друга
        current.removeAll { it.equals(trimmed, ignoreCase = true) }
        current.add(0, trimmed)
        val updated = current.take(15)
        _searchHistory.value = updated
        searchHistoryPrefs.edit().putString("recent_queries", updated.joinToString("\u0000")).apply()
        FirebaseSyncManager.onSearchHistoryUpdated(updated)
    }

    /**
     * Фиксирует поисковый запрос в историю ровно один раз при наступлении одного из событий:
     * - Нажатие ввода на клавиатуре (Enter / Search)
     * - Начало скролла выдачи
     * - Выбор фильма из результатов
     */
    fun commitSearchQuery(query: String = searchQuery) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return
        if (trimmed.equals(lastCommittedQuery, ignoreCase = true)) return
        lastCommittedQuery = trimmed
        addSearchQueryToHistory(trimmed)
    }

    fun removeSearchQueryFromHistory(query: String) {
        val current = _searchHistory.value.toMutableList()
        current.removeAll { it.equals(query, ignoreCase = true) }
        _searchHistory.value = current
        searchHistoryPrefs.edit().putString("recent_queries", current.joinToString("\u0000")).apply()
        FirebaseSyncManager.onSearchHistoryUpdated(current)
    }

    fun clearSearchHistory() {
        _searchHistory.value = emptyList()
        lastCommittedQuery = ""
        searchHistoryPrefs.edit().remove("recent_queries").apply()
        FirebaseSyncManager.onSearchHistoryCleared()
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

        val isFilterChanged = type != _currentType.value || section != _currentSection.value || actualGenre != _currentGenre.value
        if (forceRefresh || isFilterChanged) {
            requestCatalogScrollToTop()
        }

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
        if (searchQuery != query) {
            requestCatalogScrollToTop()
        }
        searchQuery = query
        searchJob?.cancel()
        paginationJob?.cancel()
        _isLoadingMore.value = false

        if (query.isBlank()) {
            lastCommittedQuery = ""
            loadCatalog(_currentType.value, forceRefresh = true)
            return
        }

        searchJob = viewModelScope.launch {
            delay(400) // Debounce for 400ms to reduce CPU load and network requests
            _catalogState.value = CatalogState.Loading
            try {
                val results = RezkaService.search(query)
                    .distinctBy { it.id }
                    .sortedWith(MovieDateParser.MovieDateComparator)
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
        commitSearchQuery()
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

                if (repository.isFavorite(detail.id)) {
                    val currentFav = favorites.value.find { it.id == detail.id }
                    if (currentFav != null) {
                        val latestBadge = if (detail.type == RezkaType.SERIES || detail.type == RezkaType.ANIME || detail.type == RezkaType.CARTOON) {
                            val maxSeason = detail.seasons.maxOfOrNull { it.id } ?: 0
                            val lastSeasonObj = detail.seasons.find { it.id == maxSeason }
                            val maxEp = lastSeasonObj?.episodes?.maxOfOrNull { it.id.toIntOrNull() ?: 0 } ?: 0
                            if (maxSeason > 0 && maxEp > 0) "$maxSeason сезон $maxEp серия" else detail.rating
                        } else {
                            detail.rating.ifEmpty { currentFav.rating }
                        }
                        if (latestBadge.isNotEmpty() && latestBadge != currentFav.rating) {
                            val updated = currentFav.copy(rating = latestBadge)
                            repository.insertFavoriteEntity(updated)
                            FirebaseSyncManager.onFavoriteAdded(updated)
                        }
                    }
                }
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

    fun removeFavorite(itemId: String) {
        viewModelScope.launch {
            repository.removeFavorite(itemId)
            FirebaseSyncManager.onFavoriteRemoved(itemId)
        }
    }

    private var refreshFavoritesJob: Job? = null

    /**
     * Подтягивает с сайта актуальные сезоны и серии для всех сериалов в избранном.
     */
    fun refreshFavoritesInfo() {
        val currentFavorites = favorites.value
        if (currentFavorites.isEmpty()) return

        refreshFavoritesJob?.cancel()
        refreshFavoritesJob = viewModelScope.launch(Dispatchers.IO) {
            val semaphore = Semaphore(3)
            currentFavorites.map { fav ->
                async {
                    semaphore.withPermit {
                        try {
                            val updatedRating = RezkaService.fetchLatestRatingForUrl(fav.url, fav.type)
                            if (!updatedRating.isNullOrBlank() && updatedRating != fav.rating) {
                                val updatedEntity = fav.copy(rating = updatedRating)
                                repository.insertFavoriteEntity(updatedEntity)
                                FirebaseSyncManager.onFavoriteAdded(updatedEntity)
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                        }
                    }
                }
            }.awaitAll()
        }
    }

    /**
     * Series episodes subscription management
     */
    fun isSubscribedFlow(id: String): Flow<Boolean> = repository.isSubscribedFlow(id)

    fun toggleSubscription(
        item: RezkaItem,
        detail: RezkaDetail?,
        selectedTranslatorId: String? = null,
        isSubscribed: Boolean,
        onResult: ((isSubscribedNow: Boolean, season: Int, episode: Int) -> Unit)? = null
    ) {
        viewModelScope.launch {
            if (isSubscribed) {
                repository.removeSubscription(item.id)
                FirebaseSyncManager.onSubscriptionRemoved(item.id)
                onResult?.invoke(false, 0, 0)
            } else {
                val isMovie = (detail?.type ?: item.type) == RezkaType.MOVIE
                val isUnreleased = if (detail != null) {
                    !detail.isReleased
                } else {
                    val subText = item.subtitle.lowercase()
                    subText.contains("скоро") || subText.contains("в ожидании") || subText.contains("ожидается")
                }

                var maxSeason = 1
                var maxEpisode = 1
                var lastEpName = "Серия 1"

                if (isMovie) {
                    if (isUnreleased) {
                        maxSeason = 0
                        maxEpisode = 0
                        lastEpName = "Ожидается выход фильма"
                    } else {
                        maxSeason = 1
                        maxEpisode = 1
                        lastEpName = "Фильм вышел"
                    }
                } else {
                    if (isUnreleased) {
                        maxSeason = 0
                        maxEpisode = 0
                        lastEpName = "Сериал еще не вышел"
                    } else {
                        val effectiveSeasons = detail?.seasons.orEmpty()
                        if (effectiveSeasons.isNotEmpty()) {
                            maxSeason = effectiveSeasons.maxOfOrNull { it.id } ?: 1
                            val seasonObj = effectiveSeasons.find { it.id == maxSeason }
                            val eps = seasonObj?.episodes.orEmpty()
                            if (eps.isNotEmpty()) {
                                val parsedMax = eps.mapNotNull { ep ->
                                    Regex("""\d+""").find(ep.id)?.value?.toIntOrNull()
                                        ?: Regex("""\d+""").find(ep.name)?.value?.toIntOrNull()
                                }.maxOrNull() ?: eps.size
                                maxEpisode = parsedMax
                                lastEpName = eps.lastOrNull()?.name ?: "Серия $maxEpisode"
                            }
                        }
                    }
                }

                val subTitle = detail?.title?.takeIf { it.isNotBlank() } ?: item.title
                val subImageUrl = detail?.imageUrl?.takeIf { it.isNotBlank() } ?: item.imageUrl
                val subUrl = item.url
                val subType = (detail?.type ?: item.type).name

                // Быстрые идентификаторы для прямого точечного AJAX-запроса get_episodes
                val numericId = detail?.numericPostId?.takeIf { it.isNotBlank() }
                    ?: RezkaService.extractNumericId(item.url).takeIf { it.isNotBlank() }
                    ?: RezkaService.extractNumericId(item.id)

                val transId = selectedTranslatorId?.takeIf { it.isNotBlank() }
                    ?: detail?.translators?.firstOrNull()?.id
                    ?: ""

                val subscription = SeriesSubscriptionEntity(
                    id = item.id,
                    title = subTitle,
                    imageUrl = subImageUrl,
                    url = subUrl,
                    type = subType,
                    numericPostId = numericId,
                    translatorId = transId,
                    lastKnownSeason = maxSeason,
                    lastKnownEpisode = maxEpisode,
                    lastEpisodeName = lastEpName,
                    subscribedAt = System.currentTimeMillis(),
                    lastCheckedAt = System.currentTimeMillis(),
                    hasUnseenUpdate = false
                )
                repository.addSubscription(subscription)
                FirebaseSyncManager.onSubscriptionAdded(subscription)
                onResult?.invoke(true, maxSeason, maxEpisode)
            }
        }
    }

    fun removeSubscription(id: String) {
        viewModelScope.launch {
            repository.removeSubscription(id)
            FirebaseSyncManager.onSubscriptionRemoved(id)
        }
    }

    fun markSubscriptionSeen(id: String) {
        viewModelScope.launch {
            val sub = repository.getSubscription(id)
            repository.markSubscriptionSeen(id)
            if (sub != null) {
                FirebaseSyncManager.onSubscriptionProgressUpdated(
                    id = id,
                    season = sub.lastKnownSeason,
                    episode = sub.lastKnownEpisode,
                    episodeName = sub.lastEpisodeName,
                    hasUpdate = false
                )
            }
        }
    }

    private val _logContent = MutableStateFlow("")
    val logContent: StateFlow<String> = _logContent.asStateFlow()

    fun refreshLogContent(context: Context) {
        viewModelScope.launch {
            _logContent.value = SeriesUpdateLogger.readLogContent(context)
        }
    }

    fun clearLog(context: Context, onResult: ((String) -> Unit)? = null) {
        viewModelScope.launch {
            SeriesUpdateLogger.clearLog(context)
            _logContent.value = SeriesUpdateLogger.readLogContent(context)
            onResult?.invoke("Журнал проверок очищен")
        }
    }

    fun exportLogToDownloads(context: Context, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val res = SeriesUpdateLogger.exportToPublicDownloads(context)
            onResult(res)
        }
    }

    fun shareLogFile(context: Context) {
        SeriesUpdateLogger.shareLogFile(context)
    }

    fun getLogFilePath(context: Context): String {
        return SeriesUpdateLogger.getLogFile(context).absolutePath
    }

    fun triggerManualSeriesCheck(context: Context) {
        viewModelScope.launch {
            if (_isCheckingSeriesUpdates.value) return@launch
            _isCheckingSeriesUpdates.value = true
            try {
                SeriesUpdateEngine.checkAllSubscriptions(context, repository)
                refreshLogContent(context)
            } finally {
                _isCheckingSeriesUpdates.value = false
            }
        }
    }

    /**
     * Точечная фоновая проверка обновлений для конкретного сериала при его запуске/просмотре.
     */
    fun checkSeriesUpdateOnLaunch(context: Context, itemId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val sub = repository.getSubscription(itemId) ?: return@launch
            SeriesUpdateEngine.checkSingleSubscriptionUpdate(context, repository, sub)
        }
    }

    private var checkHistoryJob: Job? = null
    private val lastHistoryCheckTimestamps = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Проверяет появление новых серий у всех сериалов в истории просмотров.
     * При обнаружении новых серий мгновенно обновляет totalEpisodes в базе данных для пересчёта прогресса.
     */
    fun checkHistorySeriesUpdates() {
        val seriesItems = aggregatedWatchHistory.value.filter { it.isSeries && it.url.isNotBlank() }
        if (seriesItems.isEmpty()) return

        checkHistoryJob?.cancel()
        checkHistoryJob = viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val semaphore = Semaphore(2)

            seriesItems.map { historyItem ->
                async {
                    val lastCheck = lastHistoryCheckTimestamps[historyItem.itemId] ?: 0L
                    if (now - lastCheck < 30_000L) return@async

                    semaphore.withPermit {
                        try {
                            lastHistoryCheckTimestamps[historyItem.itemId] = now

                            val targetUrl = RezkaService.adjustUrlToCurrentMirror(historyItem.url, RezkaType.SERIES, historyItem.itemId)
                            var newTotalEpisodes = 0
                            var newTotalSeasons = 0

                            try {
                                val detail = RezkaService.getDetail(targetUrl)
                                if (detail.seasons.isNotEmpty()) {
                                    newTotalEpisodes = detail.seasons.sumOf { it.episodes.size }
                                    newTotalSeasons = detail.seasons.maxOfOrNull { it.id } ?: detail.seasons.size
                                }
                            } catch (_: Exception) {}

                            if (newTotalEpisodes == 0) {
                                val scanRes = SeriesUpdateEngine.fetchSeriesLatestEpisode(targetUrl)
                                if (scanRes.isSuccess && (scanRes.latestSeason > 0 || scanRes.latestEpisode > 0)) {
                                    val latestS = scanRes.latestSeason.coerceAtLeast(1)
                                    val latestE = scanRes.latestEpisode.coerceAtLeast(1)
                                    newTotalSeasons = maxOf(historyItem.latestSeason, latestS)
                                    val epsPerSeason = maxOf(latestE, if (historyItem.latestSeason > 0) historyItem.totalEpisodesCount / historyItem.latestSeason else 1, 1)
                                    newTotalEpisodes = maxOf((latestS - 1) * epsPerSeason + latestE, historyItem.totalEpisodesCount)
                                }
                            }

                            if (newTotalEpisodes > 0 && newTotalEpisodes > historyItem.totalEpisodesCount) {
                                val finalSeasons = maxOf(newTotalSeasons, 1)
                                repository.updateHistoryTotalEpisodes(historyItem.itemId, newTotalEpisodes, finalSeasons)
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                        }
                    }
                }
            }.awaitAll()
        }
    }

    /**
     * Высокоточный детектор типа подписки: фильм или сериал.
     */
    fun isMovieSubscription(sub: SeriesSubscriptionEntity): Boolean = sub.isMovie()

    /**
     * Тестирование подписок: четко разграничивает логику фильмов и сериалов.
     * Для фильма: переводит в статус ожидания с тестовым маркером [TEST] для последующей проверки фоновым чекером через 10 секунд.
     * Для сериала: понижает номер последней серии на -1 для последующего сканирования обновлений через 10 секунд.
     */
    fun simulatePreviousEpisodeForTest(
        id: String,
        context: Context? = null,
        onResult: ((String) -> Unit)? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val sub = repository.getSubscription(id) ?: return@launch
            val now = System.currentTimeMillis()
            val isMovie = sub.isMovie()

            if (isMovie) {
                // Тест фильма: переводим в статус ожидания релиза с тестовым маркером
                val targetSeason = 0
                val targetEpisode = 0
                val epName = "Ожидается выход фильма [TEST]"

                repository.updateSubscriptionProgress(
                    id = sub.id,
                    season = targetSeason,
                    episode = targetEpisode,
                    episodeName = epName,
                    checkedAt = now,
                    hasUpdate = false
                )
                FirebaseSyncManager.onSubscriptionProgressUpdated(
                    id = sub.id,
                    season = targetSeason,
                    episode = targetEpisode,
                    episodeName = epName,
                    hasUpdate = false
                )

                val msg = "Фильм '${sub.title}' переведён в статус ожидания для теста."
                withContext(Dispatchers.Main) {
                    onResult?.invoke(msg)
                }
                return@launch
            }

            // Сериал: понижение номера серии для теста фонового сканера
            var targetSeason = sub.lastKnownSeason
            var targetEpisode = sub.lastKnownEpisode

            if (targetEpisode > 1) {
                targetEpisode -= 1
            } else if (targetEpisode == 1) {
                if (targetSeason > 1) {
                    targetSeason -= 1
                    targetEpisode = 1
                } else {
                    targetEpisode = 0
                }
            } else if (targetSeason > 0) {
                targetSeason = 0
                targetEpisode = 0
            }

            val epName = if (targetSeason == 0 && targetEpisode == 0) "Ожидает выхода" else "Серия $targetEpisode"

            repository.updateSubscriptionProgress(
                id = sub.id,
                season = targetSeason,
                episode = targetEpisode,
                episodeName = epName,
                checkedAt = now,
                hasUpdate = false
            )
            FirebaseSyncManager.onSubscriptionProgressUpdated(
                id = sub.id,
                season = targetSeason,
                episode = targetEpisode,
                episodeName = epName,
                hasUpdate = false
            )

            val msg = if (targetSeason == 0 && targetEpisode == 0) {
                "Статус сериала '${sub.title}' изменён на 'Ожидается' (0/0)"
            } else {
                "'${sub.title}': установлена серия S${targetSeason}E${targetEpisode}."
            }
            withContext(Dispatchers.Main) {
                onResult?.invoke(msg)
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

    fun toggleHistoryWatched(itemId: String) {
        viewModelScope.launch {
            repository.toggleHistoryWatched(itemId)
            // Синхронизируем обновленные локальные элементы с Firebase
            val updatedEntities = repository.getAllHistoryList().filter { it.itemId == itemId }
            for (entity in updatedEntities) {
                FirebaseSyncManager.onWatchProgress(entity)
            }
            FirebaseSyncManager.flushWatchProgress()
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
        translatorId: String,
        translatorUrl: String = ""
    ): List<Season> {
        return RezkaService.getEpisodesForTranslator(numericId, translatorId, translatorUrl)
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
    val currentUserRegisteredAt: StateFlow<Long?> = FirebaseSyncManager.currentUserRegisteredAt
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
    val tvModePreference: StateFlow<String> = RezkaService.tvModePreference
    val cardGridMode: StateFlow<String> = RezkaService.cardGridMode

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

    fun setTvModePreference(mode: String) {
        RezkaService.setTvModePreference(mode)
        FirebaseSyncManager.onSettingsUpdated(tvMode = mode)
    }

    fun setCardGridMode(mode: String) {
        RezkaService.setCardGridMode(mode)
        FirebaseSyncManager.onSettingsUpdated(cardGridMode = mode)
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

    /**
     * Аудит доступности встроенных зеркал.
     * Проверяет встроенные зеркала только до того момента, как не найдёт доступное зеркало,
     * с которым каталог успешно загрузился.
     */
    fun startMirrorAudit(isFirstLaunch: Boolean = false) {
        auditJob?.cancel()
        isFirstLaunchAuditSession = isFirstLaunch
        _mirrorAuditState.value = MirrorAuditUiState.Checking

        auditJob = viewModelScope.launch(Dispatchers.IO) {
            val mirrors = RezkaService.PRESET_MIRRORS
            var foundWorkingMirror: String? = null
            var firstPageItems: List<RezkaItem>? = null

            for (mirror in mirrors) {
                if (!isActive) break

                val testRes = RezkaService.testMirrorWithCatalog(mirror)
                if (testRes.isSuccess) {
                    val items = testRes.getOrNull()
                    if (!items.isNullOrEmpty()) {
                        foundWorkingMirror = mirror
                        firstPageItems = items
                        break
                    }
                }
            }

            if (!isActive) return@launch

            if (foundWorkingMirror != null && firstPageItems != null) {
                withContext(Dispatchers.Main) {
                    RezkaService.setMirror(foundWorkingMirror)
                    RezkaService.markFirstLaunchAuditDone()
                    FirebaseSyncManager.onSettingsUpdated(mirror = foundWorkingMirror)

                    loadedMap.clear()
                    firstPageItems.forEach { loadedMap[it.id] = it }
                    _catalogState.value = CatalogState.Success(loadedMap.values.toList())

                    val dynamicGenres = RezkaService.getGenresForCategory(_currentType.value)
                    if (dynamicGenres.isNotEmpty()) {
                        _genresList.value = dynamicGenres
                    }

                    _mirrorAuditState.value = MirrorAuditUiState.Idle
                }
            } else {
                withContext(Dispatchers.Main) {
                    if (isFirstLaunch) {
                        RezkaService.markFirstLaunchAuditDone()
                    }
                    _mirrorAuditState.value = MirrorAuditUiState.Failed(
                        "Не найдено ни одного работающего зеркала... :(\nПопробуйте указать вручную в настройках приложения"
                    )
                }
            }
        }
    }

    /**
     * Прерывает аудит зеркал.
     * При первом запуске прерывает процесс и открывает каталог с основным зеркалом, как обычно.
     */
    fun cancelMirrorAudit() {
        auditJob?.cancel()
        val wasFirstLaunch = isFirstLaunchAuditSession
        _mirrorAuditState.value = MirrorAuditUiState.Idle

        if (wasFirstLaunch) {
            RezkaService.markFirstLaunchAuditDone()
            RezkaService.resetMirrorToDefault()
            loadCatalog(RezkaType.MOVIE, SectionType.LATEST, "", forceRefresh = true)
        }
    }

    fun dismissMirrorAudit() {
        auditJob?.cancel()
        _mirrorAuditState.value = MirrorAuditUiState.Idle
    }

    companion object {
        private fun parseEpisodeNumber(epStr: String): Int {
            return Regex("""\d+""").findAll(epStr).mapNotNull { it.value.toIntOrNull() }.maxOrNull() ?: 1
        }

        /**
         * Проверяет, осталось ли менее 5% хронометража и прошло ли более 24 часов (суток) с момента просмотра.
         */
        fun isAutoWatchedRuleMet(
            progressMs: Long,
            durationMs: Long,
            timestamp: Long,
            now: Long = System.currentTimeMillis()
        ): Boolean {
            if (durationMs <= 0L) return false
            val remainingMs = durationMs - progressMs
            val remainingFraction = remainingMs.toFloat() / durationMs.toFloat()
            val isLessThan5PercentRemaining = remainingFraction in 0f..0.05f || progressMs >= durationMs
            val is24HoursPassed = (now - timestamp) >= 24 * 60 * 60 * 1000L // 86,400,000 ms
            return isLessThan5PercentRemaining && is24HoursPassed
        }

        /**
         * Высокопроизводительный движок вычисления прогресса сериала по последней просмотренной серии.
         * Учитывает сквозной номер серии из общего числа, прогресс текущей серии, правило 24ч + <5% и ручные отметки.
         */
        fun calculateSeriesProgress(
            latest: WatchHistoryEntity,
            items: List<WatchHistoryEntity>,
            now: Long = System.currentTimeMillis()
        ): SeriesProgressCalculation {
            val isManualWatched = latest.isFullyWatched
            val latestSeason = latest.season.coerceAtLeast(1)
            val rawEpNum = parseEpisodeNumber(latest.episode)
            val latestEpNumber = if (rawEpNum > 2500) 1 else rawEpNum

            val rawStoredTotalEpisodes = items.mapNotNull { it.totalEpisodes.takeIf { ep -> ep in 1..2500 } }.maxOrNull() ?: 0
            val storedTotalEpisodes = if (rawStoredTotalEpisodes > 2500) 0 else rawStoredTotalEpisodes
            val storedTotalSeasons = items.mapNotNull { it.totalSeasons.takeIf { s -> s in 1..100 } }.maxOrNull() ?: 0

            val rawEpIndex = if (latest.episodeIndex in 1..2500) latest.episodeIndex else 0

            val absoluteEpisodeIndex: Int
            val estimatedTotalEpisodes: Int

            if (rawEpIndex > 0) {
                absoluteEpisodeIndex = maxOf(rawEpIndex, latestEpNumber)
                estimatedTotalEpisodes = if (storedTotalEpisodes > 0) {
                    maxOf(storedTotalEpisodes, absoluteEpisodeIndex)
                } else {
                    absoluteEpisodeIndex
                }
            } else {
                if (storedTotalEpisodes > 0 && storedTotalSeasons > 0) {
                    val epsPerSeason = (storedTotalEpisodes.toDouble() / storedTotalSeasons.toDouble()).coerceAtLeast(1.0)
                    val priorEpisodes = ((latestSeason - 1) * epsPerSeason).toInt()
                    absoluteEpisodeIndex = maxOf(1, priorEpisodes + latestEpNumber)
                    estimatedTotalEpisodes = maxOf(storedTotalEpisodes, absoluteEpisodeIndex)
                } else if (storedTotalEpisodes > 0) {
                    val maxEpSeen = items.mapNotNull { parseEpisodeNumber(it.episode).takeIf { e -> e in 1..2500 } }.maxOrNull()?.coerceAtLeast(1) ?: latestEpNumber
                    val epsPerSeason = maxOf(maxEpSeen, latestEpNumber)
                    val priorEpisodes = (latestSeason - 1) * epsPerSeason
                    absoluteEpisodeIndex = maxOf(1, priorEpisodes + latestEpNumber)
                    estimatedTotalEpisodes = maxOf(storedTotalEpisodes, absoluteEpisodeIndex)
                } else {
                    val maxEpSeen = items.mapNotNull { parseEpisodeNumber(it.episode).takeIf { e -> e in 1..2500 } }.maxOrNull()?.coerceAtLeast(1) ?: latestEpNumber
                    val maxSeasonSeen = maxOf(latestSeason, items.mapNotNull { it.season.takeIf { s -> s in 1..100 } }.maxOrNull() ?: 1)
                    val epsPerSeason = maxOf(maxEpSeen, latestEpNumber)
                    val priorEpisodes = (latestSeason - 1) * epsPerSeason
                    absoluteEpisodeIndex = priorEpisodes + latestEpNumber
                    estimatedTotalEpisodes = maxOf(maxSeasonSeen * epsPerSeason, absoluteEpisodeIndex, items.size)
                }
            }

            val distinctEpisodesCount = items.map { "${it.season}_${it.episode}" }.distinct().size
            val totalEpisodesCount = maxOf(estimatedTotalEpisodes, absoluteEpisodeIndex, distinctEpisodesCount, 1)

            val isLatestEpAutoWatched = isAutoWatchedRuleMet(latest.progressMs, latest.durationMs, latest.timestamp, now)
            val isFullyWatched = isManualWatched || (absoluteEpisodeIndex >= totalEpisodesCount && isLatestEpAutoWatched)

            val watchedEpisodesCount: Int
            val totalProgressFraction: Float

            if (isFullyWatched) {
                watchedEpisodesCount = totalEpisodesCount
                totalProgressFraction = 1.0f
            } else {
                val priorCompletedCount = (absoluteEpisodeIndex - 1).coerceAtLeast(0)
                val currentEpCompleted = if (isLatestEpAutoWatched) 1 else 0
                watchedEpisodesCount = maxOf(priorCompletedCount + currentEpCompleted, 1).coerceAtMost(totalEpisodesCount)

                val currentEpProgress = if (latest.durationMs > 0) {
                    (latest.progressMs.toDouble() / latest.durationMs.toDouble()).coerceIn(0.0, 1.0)
                } else 0.0
                val exactFraction = ((priorCompletedCount.toDouble() + currentEpProgress) / totalEpisodesCount.toDouble()).coerceIn(0.0, 1.0).toFloat()
                totalProgressFraction = exactFraction
            }

            return SeriesProgressCalculation(
                absoluteEpisodeIndex = absoluteEpisodeIndex,
                watchedEpisodesCount = watchedEpisodesCount,
                totalEpisodesCount = totalEpisodesCount,
                totalProgressFraction = totalProgressFraction,
                isFullyWatched = isFullyWatched
            )
        }
    }
}

