package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.RezkaApplication
import com.example.data.*
import java.text.Collator
import java.util.Locale
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
    data class Checking(
        val checkedCount: Int = 0,
        val totalCount: Int = 0,
        val currentMirrorHost: String = "",
        val lastCheckedMirrorHost: String = "",
        val lastCheckedStatus: String = "",
        val lastCheckedSuccess: Boolean? = null
    ) : MirrorAuditUiState
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
    val repository = (application as RezkaApplication).repository
    val isOnline: StateFlow<Boolean> = NetworkMonitor.isOnline

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

    // Сохранение последнего выбранного / сфокусированного фильма в ТВ-каталоге для возврата фокуса
    @Volatile
    private var tvCatalogFocusedItemId: String? = null
    @Volatile
    private var tvCatalogFocusedIndex: Int? = null

    fun setTvCatalogFocusedItem(itemId: String?, index: Int?) {
        tvCatalogFocusedItemId = itemId
        tvCatalogFocusedIndex = index
    }

    fun getTvCatalogFocusedItemId(): String? = tvCatalogFocusedItemId
    fun getTvCatalogFocusedIndex(): Int? = tvCatalogFocusedIndex

    fun clearTvCatalogFocusedItem() {
        tvCatalogFocusedItemId = null
        tvCatalogFocusedIndex = null
    }

    private val _catalogScrollResetEvent = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val catalogScrollResetEvent: SharedFlow<Unit> = _catalogScrollResetEvent.asSharedFlow()

    fun requestCatalogScrollToTop() {
        clearTvCatalogFocusedItem()
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

    val offlineMedia: StateFlow<List<OfflineMediaEntity>> = repository.offlineMedia
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalOfflineSizeBytes: StateFlow<Long> = repository.getTotalOfflineSizeBytes()
        .map { it ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val offlineCount: StateFlow<Int> = repository.getOfflineCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _isCheckingSeriesUpdates = MutableStateFlow(false)
    val isCheckingSeriesUpdates: StateFlow<Boolean> = _isCheckingSeriesUpdates.asStateFlow()

    private val autoWatchedPersistedSet = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun markItemAutoWatchedIfNeeded(itemId: String, entity: WatchHistoryEntity) {
        if (autoWatchedPersistedSet.add(itemId)) {
            viewModelScope.launch(Dispatchers.IO) {
                repository.setHistoryWatched(itemId, true)
                val updated = entity.copy(isFullyWatched = true)
                FirebaseSyncManager.onWatchProgress(updated)
                FirebaseSyncManager.flushWatchProgress()
            }
        }
    }

    val aggregatedWatchHistory: StateFlow<List<AggregatedHistoryItem>> = repository.watchHistory
        .map { list ->
            val now = System.currentTimeMillis()
            val filtered = list.filter {
                !it.id.startsWith("offline_") &&
                !it.itemId.startsWith("offline_") &&
                it.url.isNotBlank() &&
                !it.url.startsWith("file://")
            }
            filtered.groupBy { it.itemId }.map { (itemId, items) ->
                val latest = resolveLatestWatchHistory(items)
                val isSeries = latest.season > 0 || items.any { it.season > 0 }
                val isManualWatched = latest.isFullyWatched

                val totalProgressFraction: Float
                val watchedEpisodesCount: Int
                val totalEpisodesCount: Int
                val isFullyWatched: Boolean
                val currentEpisodeIndex: Int

                if (!isSeries) {
                    val isCompletedToEnd = latest.durationMs > 0L && (
                        latest.progressMs >= latest.durationMs ||
                        (latest.durationMs - latest.progressMs) <= 20_000L ||
                        (latest.progressMs.toDouble() / latest.durationMs.toDouble()) >= 0.98
                    )
                    val isAutoWatched = isAutoWatchedRuleMet(latest.progressMs, latest.durationMs, latest.timestamp, now)
                    isFullyWatched = isManualWatched || isCompletedToEnd || isAutoWatched
                    currentEpisodeIndex = 0

                    if (isFullyWatched) {
                        totalProgressFraction = 1.0f
                        watchedEpisodesCount = 1
                        totalEpisodesCount = 1
                        if (!latest.isFullyWatched) {
                            markItemAutoWatchedIfNeeded(itemId, latest)
                        }
                    } else {
                        val exactFraction = if (latest.durationMs > 0) {
                            (latest.progressMs.toDouble() / latest.durationMs.toDouble()).coerceIn(0.0, 0.99).toFloat()
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
                    currentEpisodeIndex = calc.absoluteEpisodeIndex
                    if (isFullyWatched && !latest.isFullyWatched) {
                        markItemAutoWatchedIfNeeded(itemId, latest)
                    }
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
                    isFullyWatched = isFullyWatched,
                    currentEpisodeIndex = currentEpisodeIndex
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

    // Default catalog settings
    val defaultCatalogType: StateFlow<RezkaType> = RezkaService.defaultCatalogType
    val defaultCatalogSection: StateFlow<SectionType> = RezkaService.defaultCatalogSection

    // Current filter selections
    private val _currentType = MutableStateFlow(RezkaService.defaultCatalogType.value)
    val currentType: StateFlow<RezkaType> = _currentType.asStateFlow()

    private val _currentSection = MutableStateFlow(RezkaService.defaultCatalogSection.value)
    val currentSection: StateFlow<SectionType> = _currentSection.asStateFlow()

    private val _currentGenre = MutableStateFlow("")
    val currentGenre: StateFlow<String> = _currentGenre.asStateFlow()

    private val _genresList = MutableStateFlow<List<GenreItem>>(listOf(GenreItem("Без жанра", "")))
    val genresList: StateFlow<List<GenreItem>> = _genresList.asStateFlow()

    private val _currentYear = MutableStateFlow("")
    val currentYear: StateFlow<String> = _currentYear.asStateFlow()

    private val _yearsList = MutableStateFlow<List<YearItem>>(listOf(YearItem("Все года", "")))
    val yearsList: StateFlow<List<YearItem>> = _yearsList.asStateFlow()

    private val _currentCountry = MutableStateFlow("")
    val currentCountry: StateFlow<String> = _currentCountry.asStateFlow()

    private val _countriesList = MutableStateFlow<List<CountryItem>>(CountryFilterList.defaultCountries)
    val countriesList: StateFlow<List<CountryItem>> = _countriesList.asStateFlow()

    private var countryPrefetchJob: Job? = null

    fun setCountry(countryQuery: String) {
        if (_currentCountry.value != countryQuery) {
            _currentCountry.value = countryQuery
            requestCatalogScrollToTop()
            if (countryQuery.isNotEmpty()) {
                prefetchForCountryFilter(countryQuery)
            }
        }
    }

    fun prefetchForCountryFilter(countryQuery: String = _currentCountry.value) {
        if (countryQuery.isBlank() || _isEndReached.value) return
        countryPrefetchJob?.cancel()
        countryPrefetchJob = viewModelScope.launch {
            var matching = loadedMap.values.count { it.matchesCountry(countryQuery) }
            if (matching >= 24) return@launch
            _isLoadingMore.value = true
            try {
                var consecutiveEmptyPages = 0
                while (matching < 24 && !_isEndReached.value && consecutiveEmptyPages < 8) {
                    val nextPage = currentCatalogPage + 1
                    val items = RezkaService.getCatalog(_currentType.value, _currentSection.value, _currentGenre.value, _currentYear.value, nextPage)
                    val newUniqueItems = items.filterNot { loadedMap.containsKey(it.id) }
                    if (newUniqueItems.isEmpty() || items.size < 32) {
                        _isEndReached.value = true
                    }
                    if (newUniqueItems.isNotEmpty()) {
                        val addedMatching = newUniqueItems.count { it.matchesCountry(countryQuery) }
                        if (addedMatching > 0) {
                            consecutiveEmptyPages = 0
                        } else {
                            consecutiveEmptyPages++
                        }
                        val dynamicCountries = mutableSetOf<String>()
                        for (item in newUniqueItems) {
                            dynamicCountries.addAll(CountryFlags.extractCountries(item.subtitle))
                        }
                        if (dynamicCountries.isNotEmpty()) {
                            updateDynamicCountries(dynamicCountries)
                        }

                        newUniqueItems.forEach { loadedMap[it.id] = it }
                        currentCatalogPage = nextPage
                        matching += addedMatching
                        _catalogState.value = CatalogState.Success(loadedMap.values.toList())
                    } else {
                        break
                    }
                    delay(50)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    fun updateDynamicCountries(parsedCountries: Set<String>) {
        if (parsedCountries.isEmpty()) return
        val currentList = _countriesList.value
        val existingCleanQueries = currentList.map { CountryFlags.cleanCountryName(it.query) }.toSet()
        val newItems = ArrayList<CountryItem>()
        for (country in parsedCountries) {
            val q = country.trim()
            val clean = CountryFlags.cleanCountryName(q)
            if (clean.isNotEmpty() && !existingCleanQueries.contains(clean)) {
                val flag = CountryFlags.getFlag(q, fallbackToDefault = false)
                if (flag.isNotEmpty()) {
                    val capitalized = q.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                    newItems.add(CountryItem("$flag $capitalized", capitalized))
                }
            }
        }
        if (newItems.isNotEmpty()) {
            val ruLocale = Locale.forLanguageTag("ru")
            val collator = Collator.getInstance(ruLocale).apply {
                strength = Collator.PRIMARY
            }
            val rest = (currentList.drop(1) + newItems)
                .distinctBy { CountryFlags.cleanCountryName(it.query) }
                .sortedWith { a, b ->
                    collator.compare(
                        CountryFlags.stripFlags(a.name).trim(),
                        CountryFlags.stripFlags(b.name).trim()
                    )
                }
            _countriesList.value = listOf(currentList.first()) + rest
        }
    }


    private val _collectionsState = MutableStateFlow<CollectionsState>(CollectionsState.Loading)
    val collectionsState: StateFlow<CollectionsState> = _collectionsState.asStateFlow()

    private var allCollections = listOf<CollectionItem>()

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

    private val _installedPlayers = MutableStateFlow<List<ExternalPlayerApp>>(
        ExternalPlayerManager.getCachedPlayers() ?: emptyList()
    )
    val installedPlayers: StateFlow<List<ExternalPlayerApp>> = _installedPlayers.asStateFlow()

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
        loadInstalledPlayers()
        // Привязываем провайдер и коллбэк синхронизации истории поиска с Firebase
        FirebaseSyncManager.searchHistoryProvider = {
            _searchHistory.value
        }
        FirebaseSyncManager.onSearchHistorySynced = { syncedList ->
            _searchHistory.value = syncedList
            searchHistoryPrefs.edit().putString("recent_queries", syncedList.joinToString("\u0000")).apply()
        }

        viewModelScope.launch {
            NetworkMonitor.isOnline.collect { online ->
                if (!online) {
                    loadOfflineCatalog()
                }
            }
        }

        if (!NetworkMonitor.isOnline.value) {
            loadOfflineCatalog()
        } else if (!RezkaService.isFirstLaunchAuditDone()) {
            startMirrorAudit(isFirstLaunch = true)
        } else {
            // Load default catalog on startup
            loadCatalog(RezkaService.defaultCatalogType.value, RezkaService.defaultCatalogSection.value, "", forceRefresh = true)
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
     * Сброс всех фильтров каталога к значениям по умолчанию
     */
    fun resetCatalogFilters() {
        _currentCountry.value = ""
        loadCatalog(
            type = RezkaService.defaultCatalogType.value,
            section = RezkaService.defaultCatalogSection.value,
            genre = "",
            year = "",
            forceRefresh = true
        )
    }

    /**
     * Loads catalog items for the specified type, section, genre, year and resets pagination
     */
    fun loadCatalog(
        type: RezkaType = _currentType.value,
        section: SectionType = _currentSection.value,
        genre: String = _currentGenre.value,
        year: String = _currentYear.value,
        forceRefresh: Boolean = false
    ) {
        paginationJob?.cancel()
        catalogJob?.cancel()
        searchJob?.cancel()

        if (type == RezkaType.COLLECTIONS) {
            _currentType.value = RezkaType.COLLECTIONS
            searchQuery = ""
            currentCatalogPage = 1
            loadCollections(forceRefresh = forceRefresh)
            return
        }

        // If category changed, reset active genre and year to default
        val actualGenre = if (type != _currentType.value) "" else genre
        val actualYear = if (type != _currentType.value) "" else year
        val actualCountry = if (type != _currentType.value) "" else _currentCountry.value

        val isFilterChanged = type != _currentType.value || section != _currentSection.value || actualGenre != _currentGenre.value || actualYear != _currentYear.value
        if (forceRefresh || isFilterChanged) {
            requestCatalogScrollToTop()
        }

        _currentType.value = type
        _currentSection.value = section
        _currentGenre.value = actualGenre
        _currentYear.value = actualYear
        _currentCountry.value = actualCountry
        searchQuery = ""
        currentCatalogPage = 1
        _isEndReached.value = false
        _isLoadingMore.value = false

        // Update genres and years list immediately from service cache if available for this category
        val cachedGenres = RezkaService.getGenresForCategory(type)
        if (cachedGenres.isNotEmpty()) {
            _genresList.value = cachedGenres
        }
        val cachedYears = RezkaService.getYearsForCategory(type)
        if (cachedYears.isNotEmpty()) {
            _yearsList.value = cachedYears
        }

        if (!NetworkMonitor.isOnline.value) {
            loadOfflineCatalog()
            return
        }

        if (forceRefresh || loadedMap.isEmpty()) {
            loadedMap.clear()
            _catalogState.value = CatalogState.Loading
        }

        catalogJob = viewModelScope.launch {
            try {
                val items = RezkaService.getCatalog(type, section, actualGenre, actualYear, 1)
                loadedMap.clear()
                items.forEach { loadedMap[it.id] = it }
                _isEndReached.value = items.size < 32
                _catalogState.value = CatalogState.Success(loadedMap.values.toList())

                // Update dynamic genres and years list after parsing page HTML
                val dynamicGenres = RezkaService.getGenresForCategory(type)
                if (dynamicGenres.isNotEmpty()) {
                    _genresList.value = dynamicGenres
                }
                val dynamicYears = RezkaService.getYearsForCategory(type)
                if (dynamicYears.isNotEmpty()) {
                    _yearsList.value = dynamicYears
                }

                // Dynamically extract countries from parsed catalog cards
                val dynamicCountries = mutableSetOf<String>()
                for (item in items) {
                    dynamicCountries.addAll(CountryFlags.extractCountries(item.subtitle))
                }
                if (dynamicCountries.isNotEmpty()) {
                    updateDynamicCountries(dynamicCountries)
                }

                if (_currentCountry.value.isNotEmpty()) {
                    prefetchForCountryFilter(_currentCountry.value)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                NetworkMonitor.handleNetworkException(e)
                if (!NetworkMonitor.isOnline.value) {
                    loadOfflineCatalog()
                } else {
                    _catalogState.value = CatalogState.Error(e.message ?: "Неизвестная ошибка")
                }
            }
        }
    }

    /**
     * Загружает локальный оффлайн каталог из сохраненных файлов с поддержкой фильтров и поиска
     */
    fun loadOfflineCatalog(forceRefresh: Boolean = false) {
        paginationJob?.cancel()
        catalogJob?.cancel()
        searchJob?.cancel()
        _isEndReached.value = true
        _isLoadingMore.value = false

        if (forceRefresh || _catalogState.value !is CatalogState.Success) {
            _catalogState.value = CatalogState.Loading
        }

        catalogJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val allEntities = repository.getAllOfflineMediaList()
                val query = searchQuery.trim()

                if (allEntities.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        _catalogState.value = CatalogState.Success(emptyList())
                    }
                    return@launch
                }

                // Фильтрация по категории, поиску, жанру, году, стране
                val filteredByCategory = allEntities.filter { entity ->
                    // 1. Категория
                    val matchType = when (_currentType.value) {
                        RezkaType.MOVIE -> entity.type.equals("MOVIE", ignoreCase = true) || entity.type.contains("Фильм", ignoreCase = true)
                        RezkaType.SERIES -> entity.type.equals("SERIES", ignoreCase = true) || entity.type.contains("Сериал", ignoreCase = true)
                        RezkaType.ANIME -> entity.type.equals("ANIME", ignoreCase = true) || entity.type.contains("Аниме", ignoreCase = true)
                        RezkaType.CARTOON -> entity.type.equals("CARTOON", ignoreCase = true) || entity.type.contains("Мульт", ignoreCase = true)
                        RezkaType.COLLECTIONS -> true
                    }
                    if (!matchType) return@filter false

                    // 2. Поиск
                    if (query.isNotEmpty()) {
                        val matchSearch = entity.title.contains(query, ignoreCase = true) ||
                                entity.description.contains(query, ignoreCase = true) ||
                                entity.translatorName.contains(query, ignoreCase = true) ||
                                entity.genres.contains(query, ignoreCase = true)
                        if (!matchSearch) return@filter false
                    }

                    // 3. Жанр
                    if (_currentGenre.value.isNotEmpty()) {
                        if (!entity.genres.contains(_currentGenre.value, ignoreCase = true)) return@filter false
                    }

                    // 4. Год
                    if (_currentYear.value.isNotEmpty()) {
                        if (entity.year != _currentYear.value) return@filter false
                    }

                    // 5. Страна
                    if (_currentCountry.value.isNotEmpty()) {
                        if (!entity.country.contains(_currentCountry.value, ignoreCase = true)) return@filter false
                    }

                    true
                }

                // Фолбэк: если категория отфильтровала все элементы, но оффлайн записи существуют и пользователь не вводил специфичный поиск/фильтр,
                // отображаем все оффлайн элементы, чтобы оффлайн библиотека не выглядела пустой.
                val finalEntities = if (filteredByCategory.isEmpty() && query.isEmpty() && _currentGenre.value.isEmpty() && _currentYear.value.isEmpty() && _currentCountry.value.isEmpty()) {
                    allEntities
                } else {
                    filteredByCategory
                }

                // Группировка по itemId (для сериалов с несколькими сериями)
                val grouped = finalEntities.groupBy { if (it.itemId.isNotBlank()) it.itemId else it.id }
                val items = grouped.map { (itemId, list) ->
                    val first = list.first()
                    val isSeries = first.type.equals("SERIES", ignoreCase = true) || list.size > 1 || first.season > 0
                    val count = list.size
                    val totalSize = list.sumOf { entity ->
                        if (entity.fileSizeBytes > 0) entity.fileSizeBytes
                        else {
                            val f = java.io.File(entity.videoPath)
                            if (f.exists()) f.length() else 0L
                        }
                    }
                    val sizeFormatted = DownloadHelper.formatFileSize(totalSize)
                    val sub = if (isSeries) {
                        "Скачано: $count сер. • $sizeFormatted"
                    } else {
                        listOfNotNull(
                            first.year.ifEmpty { null },
                            first.quality.ifEmpty { null },
                            sizeFormatted
                        ).joinToString(" • ")
                    }

                    val imageUri = if (first.localPosterPath.isNotEmpty() && java.io.File(first.localPosterPath).exists()) {
                        "file://${first.localPosterPath}"
                    } else {
                        first.imageUrl
                    }

                    val rezkaType = try {
                        RezkaType.valueOf(first.type.uppercase())
                    } catch (_: Exception) {
                        if (isSeries) RezkaType.SERIES else RezkaType.MOVIE
                    }

                    RezkaItem(
                        id = itemId,
                        title = first.title,
                        subtitle = sub,
                        imageUrl = imageUri,
                        rating = "Оффлайн",
                        url = "",
                        type = rezkaType
                    )
                }

                // Обновляем фильтры (жанры, года, страны) на основе сохраненных элементов
                val offlineGenres = allEntities.flatMap { it.genres.split(",") }
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .map { GenreItem(it, it) }
                _genresList.value = listOf(GenreItem("Все жанры", "")) + offlineGenres

                val offlineYears = allEntities.map { it.year }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .sortedDescending()
                    .map { YearItem(it, it) }
                _yearsList.value = listOf(YearItem("Все года", "")) + offlineYears

                val offlineCountries = allEntities.map { it.country }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .map { CountryItem(it, it) }
                _countriesList.value = listOf(CountryItem("Все страны", "")) + offlineCountries

                withContext(Dispatchers.Main) {
                    _catalogState.value = CatalogState.Success(items)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _catalogState.value = CatalogState.Success(emptyList())
                }
            }
        }
    }

    /**
     * Загрузка подборок без пагинации (все страницы объединяются в единый список)
     */
    fun loadCollections(forceRefresh: Boolean = false) {
        paginationJob?.cancel()
        catalogJob?.cancel()
        searchJob?.cancel()
        _isEndReached.value = true
        _isLoadingMore.value = false

        if (!forceRefresh && allCollections.isNotEmpty()) {
            filterCollections(searchQuery)
            return
        }

        _collectionsState.value = CollectionsState.Loading
        catalogJob = viewModelScope.launch {
            try {
                // Быстрая мгновенная отдача первой страницы
                val firstPage = RezkaService.fetchCollectionsPage(1)
                allCollections = firstPage.items
                filterCollections(searchQuery)

                // Фоновая параллельная догрузка остальных страниц (без пагинации в UI)
                if (firstPage.totalPages > 1) {
                    val fullList = RezkaService.getCollections(fetchAll = true)
                    allCollections = fullList
                    filterCollections(searchQuery)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _collectionsState.value = CollectionsState.Error(e.message ?: "Ошибка загрузки подборок")
            }
        }
    }

    private fun filterCollections(query: String) {
        val q = query.trim()
        val filtered = if (q.isEmpty()) {
            allCollections
        } else {
            allCollections.filter { it.title.contains(q, ignoreCase = true) }
        }
        _collectionsState.value = CollectionsState.Success(filtered)
    }

    /**
     * Loads next page smoothly without UI flickering or CPU bottlenecks
     */
    fun loadNextPage() {
        if (_currentType.value == RezkaType.COLLECTIONS || _isLoadingMore.value || _isEndReached.value || searchQuery.isNotBlank() || _catalogState.value is CatalogState.Loading) {
            return
        }

        val country = _currentCountry.value
        if (country.isNotEmpty()) {
            paginationJob?.cancel()
            paginationJob = viewModelScope.launch {
                _isLoadingMore.value = true
                try {
                    var found = 0
                    var pagesScanned = 0
                    while (found == 0 && !_isEndReached.value && pagesScanned < 6) {
                        pagesScanned++
                        val nextPage = currentCatalogPage + 1
                        val items = RezkaService.getCatalog(_currentType.value, _currentSection.value, _currentGenre.value, _currentYear.value, nextPage)
                        val newUniqueItems = items.filterNot { loadedMap.containsKey(it.id) }
                        if (newUniqueItems.isEmpty() || items.size < 32) {
                            _isEndReached.value = true
                        }
                        if (newUniqueItems.isNotEmpty()) {
                            val addedMatching = newUniqueItems.count { it.matchesCountry(country) }
                            found += addedMatching

                            val dynamicCountries = mutableSetOf<String>()
                            for (item in newUniqueItems) {
                                dynamicCountries.addAll(CountryFlags.extractCountries(item.subtitle))
                            }
                            if (dynamicCountries.isNotEmpty()) {
                                updateDynamicCountries(dynamicCountries)
                            }

                            newUniqueItems.forEach { loadedMap[it.id] = it }
                            currentCatalogPage = nextPage
                            _catalogState.value = CatalogState.Success(loadedMap.values.toList())
                        } else {
                            break
                        }
                        if (found == 0 && !_isEndReached.value) {
                            delay(50)
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _isEndReached.value = true
                } finally {
                    _isLoadingMore.value = false
                }
            }
            return
        }

        val nextPage = currentCatalogPage + 1
        _isLoadingMore.value = true

        paginationJob?.cancel()
        paginationJob = viewModelScope.launch {
            try {
                val items = RezkaService.getCatalog(_currentType.value, _currentSection.value, _currentGenre.value, _currentYear.value, nextPage)
                val newUniqueItems = items.filterNot { loadedMap.containsKey(it.id) }
                if (newUniqueItems.isEmpty() || items.size < 32) {
                    _isEndReached.value = true
                }
                if (newUniqueItems.isNotEmpty()) {
                    val dynamicCountries = mutableSetOf<String>()
                    for (item in newUniqueItems) {
                        dynamicCountries.addAll(CountryFlags.extractCountries(item.subtitle))
                    }
                    if (dynamicCountries.isNotEmpty()) {
                        updateDynamicCountries(dynamicCountries)
                    }

                    newUniqueItems.forEach { loadedMap[it.id] = it }
                    currentCatalogPage = nextPage
                    _catalogState.value = CatalogState.Success(loadedMap.values.toList())
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _isEndReached.value = true
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

        if (_currentType.value == RezkaType.COLLECTIONS) {
            filterCollections(query)
            return
        }

        if (!NetworkMonitor.isOnline.value) {
            loadOfflineCatalog()
            return
        }

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
                _isEndReached.value = true
                _catalogState.value = CatalogState.Success(results)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                NetworkMonitor.handleNetworkException(e)
                if (!NetworkMonitor.isOnline.value) {
                    loadOfflineCatalog()
                } else {
                    _catalogState.value = CatalogState.Error(e.message ?: "Ошибка поиска")
                }
            }
        }
    }

    fun hasOfflineMedia(itemId: String): Boolean {
        if (itemId.isBlank()) return false
        val cleanNum = itemId.filter { it.isDigit() }
        val list = offlineMedia.value
        return list.any {
            it.itemId == itemId || (cleanNum.isNotEmpty() && (it.itemId == cleanNum || it.itemId.startsWith("$cleanNum-") || it.itemId.filter { c -> c.isDigit() } == cleanNum))
        }
    }

    // Кэш страниц комментариев для текущего фильма (O(1) доступ в памяти без лишних запросов и нагрузки на CPU)
    private val commentsPageCache = HashMap<Int, CommentsResult>()

    /**
     * Loads detailed info of selected item
     */
    fun loadDetail(url: String, fallbackItem: RezkaItem? = null) {
        commitSearchQuery()
        _detailState.value = DetailState.Loading
        _commentsState.value = MovieCommentsState(isLoading = true)
        commentsPageCache.clear()
        viewModelScope.launch {
            val offlineItemId = fallbackItem?.id ?: extractIdFromUrl(url)
            val offlineEntities = if (offlineItemId.isNotEmpty()) {
                val direct = repository.getOfflineMediaByItemId(offlineItemId)
                if (direct.isNotEmpty()) {
                    direct
                } else {
                    val cleanNum = offlineItemId.filter { it.isDigit() }
                    repository.getAllOfflineMediaList().filter {
                        it.itemId == offlineItemId || (cleanNum.isNotEmpty() && (it.itemId == cleanNum || it.itemId.startsWith("$cleanNum-") || it.itemId.filter { c -> c.isDigit() } == cleanNum))
                    }
                }
            } else emptyList()

            val isOfflineRequest = !NetworkMonitor.isOnline.value || url.isBlank() || fallbackItem?.rating == "Оффлайн" || fallbackItem?.url.isNullOrBlank()
            if (isOfflineRequest) {
                if (offlineEntities.isNotEmpty()) {
                    val offlineDetail = buildOfflineDetail(offlineEntities, fallbackItem)
                    _detailState.value = DetailState.Success(offlineDetail)
                    _commentsState.value = MovieCommentsState(isLoading = false)
                    return@launch
                }
            }

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
                NetworkMonitor.handleNetworkException(e)
                if (offlineEntities.isNotEmpty()) {
                    val offlineDetail = buildOfflineDetail(offlineEntities, fallbackItem)
                    _detailState.value = DetailState.Success(offlineDetail)
                    _commentsState.value = MovieCommentsState(isLoading = false)
                } else {
                    val errorMsg = if (!NetworkMonitor.isOnline.value) {
                        "Этот фильм не сохранён в оффлайн библиотеке. Подключитесь к интернету для просмотра онлайн."
                    } else {
                        "Что-то пошло не так :("
                    }
                    _detailState.value = DetailState.Error(errorMsg)
                    _commentsState.value = MovieCommentsState(isLoading = false)
                }
            }
        }
    }

    private fun extractIdFromUrl(url: String): String {
        if (url.isBlank()) return ""
        val match = Regex("""/(\d+)-""").find(url)
        return match?.groupValues?.get(1) ?: url.substringAfterLast("/").substringBefore(".html")
    }

    private fun buildOfflineDetail(
        entities: List<OfflineMediaEntity>,
        fallbackItem: RezkaItem?
    ): RezkaDetail {
        val first = entities.first()
        val isSeries = first.type.equals("SERIES", ignoreCase = true) || entities.size > 1 || first.season > 0

        // 1. Пытаемся восстановить полную информацию о фильме/сериале из сохранённого JSON
        var savedDetail: RezkaDetail? = null
        val jsonSource = first.detailJson.ifBlank {
            try {
                val offlineDir = DownloadHelper.getOfflineDirectory(getApplication())
                val metaFile = java.io.File(offlineDir, "metadata_${DownloadHelper.sanitizeFilename(first.itemId)}.json")
                if (metaFile.exists()) metaFile.readText() else ""
            } catch (_: Exception) {
                ""
            }
        }
        if (jsonSource.isNotBlank()) {
            savedDetail = RezkaDetailJsonEngine.fromJson(jsonSource)
        }

        val posterUrl = if (first.localPosterPath.isNotEmpty() && java.io.File(first.localPosterPath).exists()) {
            "file://${first.localPosterPath}"
        } else {
            savedDetail?.imageUrl?.ifEmpty { null } ?: first.imageUrl.ifEmpty { fallbackItem?.imageUrl ?: "" }
        }

        if (savedDetail != null) {
            // Восстанавливаем озвучки с сохранением флагов и настроек
            val translators = if (savedDetail.translators.isNotEmpty()) {
                savedDetail.translators
            } else {
                entities.map { it.translatorName }
                    .distinct()
                    .filter { it.isNotBlank() }
                    .mapIndexed { idx, name ->
                        val transId = entities.firstOrNull { it.translatorName == name }?.translatorId?.ifEmpty { idx.toString() } ?: idx.toString()
                        Translator(id = transId, name = name, isDefault = idx == 0)
                    }.ifEmpty {
                        listOf(Translator("0", "Оффлайн", isDefault = true))
                    }
            }

            // Сезоны для сериала — ТОЛЬКО реально скачанные серии из оффлайн библиотеки
            val seasons = if (isSeries) {
                val downloadedGroup = entities.groupBy { it.season }
                downloadedGroup.map { (sNum, eps) ->
                    val origSeason = savedDetail.seasons.find { it.id == sNum }
                    val seasonName = origSeason?.name?.ifEmpty { null } ?: "Сезон $sNum"
                    val episodeList = eps.map { epEntity ->
                        val origEp = origSeason?.episodes?.find { it.id == epEntity.episode }
                        val epName = origEp?.name?.ifEmpty { null } ?: "Серия ${epEntity.episode}"
                        Episode(
                            id = epEntity.episode,
                            name = epName,
                            seasonId = sNum,
                            translatorId = epEntity.translatorId
                        )
                    }.sortedBy { it.id.toIntOrNull() ?: 0 }

                    Season(
                        id = sNum,
                        name = seasonName,
                        episodes = episodeList
                    )
                }.sortedBy { it.id }
            } else emptyList()

            return savedDetail.copy(
                imageUrl = posterUrl,
                translators = translators,
                seasons = seasons,
                rating = "Оффлайн",
                isReleased = true
            )
        }

        // Резервный фоллбек для старых записей без JSON метаданных
        val genresList = if (first.genres.isNotBlank()) first.genres.split(", ").map { it.trim() } else emptyList()
        val translators = entities.map { it.translatorName }
            .distinct()
            .filter { it.isNotBlank() }
            .mapIndexed { idx, name ->
                val transId = entities.firstOrNull { it.translatorName == name }?.translatorId?.ifEmpty { idx.toString() } ?: idx.toString()
                Translator(id = transId, name = name, isDefault = idx == 0)
            }.ifEmpty {
                listOf(Translator("0", "Оффлайн", isDefault = true))
            }

        val seasons = if (isSeries) {
            entities.groupBy { it.season }.map { (sNum, eps) ->
                Season(
                    id = sNum,
                    name = "Сезон $sNum",
                    episodes = eps.map { epEntity ->
                        Episode(
                            id = epEntity.episode,
                            name = "Серия ${epEntity.episode}",
                            seasonId = sNum,
                            translatorId = epEntity.translatorId
                        )
                    }
                )
            }.sortedBy { it.id }
        } else emptyList()

        val rezkaType = try {
            RezkaType.valueOf(first.type)
        } catch (_: Exception) {
            fallbackItem?.type ?: RezkaType.MOVIE
        }

        return RezkaDetail(
            id = first.itemId,
            title = first.title,
            originalTitle = "",
            description = first.description.ifEmpty { "Сохранено в оффлайн библиотеке для автономного просмотра." },
            imageUrl = posterUrl,
            year = first.year,
            releaseDate = first.year,
            country = first.country,
            countryFlag = CountryFlags.getFlag(first.country),
            genres = genresList,
            rating = "Оффлайн",
            ratingInfo = RatingInfo(),
            type = rezkaType,
            translators = translators,
            seasons = seasons,
            numericPostId = first.itemId,
            isReleased = true
        )
    }

    fun deleteOfflineMedia(entity: OfflineMediaEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            DownloadHelper.deleteOfflineMedia(getApplication(), entity, repository)
            if (!NetworkMonitor.isOnline.value) {
                loadOfflineCatalog()
            }
        }
    }

    fun clearOfflineLibrary() {
        viewModelScope.launch(Dispatchers.IO) {
            DownloadHelper.clearAllOffline(getApplication(), repository)
            if (!NetworkMonitor.isOnline.value) {
                loadOfflineCatalog()
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
    private data class HistorySeriesCandidate(
        val itemId: String,
        val title: String,
        val url: String,
        val translatorId: String,
        val latest: WatchHistoryEntity,
        val allItems: List<WatchHistoryEntity>,
        val calculatedAbsoluteEpisodeIndex: Int,
        val calculatedWatchedEpisodesCount: Int,
        val currentTotalEpisodes: Int,
        val isFullyWatched: Boolean
    )

    private val lastHistoryCheckTimestamps = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Высокопроизводительный движок проверки и пересчёта количества серий и прогресса для всех сериалов в истории.
     * Запускается при каждом входе в окно истории:
     * 1. Считывает историю напрямую из локальной базы данных SQLite без задержек StateFlow.
     * 2. Выполняет легковесные точечные AJAX-запросы к HDRezka с ограничением параллелизма (Semaphore=2).
     * 3. При обнаружении новых серий мгновенно сохраняет точное число серий в базе,
     *    сбрасывает статус "полностью просмотрено", если вышли новые серии,
     *    что автоматически и реактивно обновляет счётчик (например, "32 из 33 серий") и процент прогресса (например, "97%").
     */
    fun checkHistorySeriesUpdates(force: Boolean = false) {
        checkHistoryJob?.cancel()
        checkHistoryJob = viewModelScope.launch(Dispatchers.IO) {
            val allHistory = repository.getAllHistoryList()
            if (allHistory.isEmpty()) return@launch

            val now = System.currentTimeMillis()
            val grouped = allHistory.groupBy { it.itemId }
            val seriesCandidates = grouped.mapNotNull { (itemId, items) ->
                val latest = resolveLatestWatchHistory(items)
                val isSeries = latest.season > 0 ||
                               latest.episode.isNotBlank() ||
                               items.any { it.season > 0 || it.episode.isNotBlank() } ||
                               latest.url.contains("/series/") ||
                               latest.url.contains("/animation/")
                if (!isSeries) return@mapNotNull null

                val bestUrl = items.firstOrNull { it.url.isNotBlank() }?.url ?: latest.url
                val bestTranslatorId = items.firstOrNull { it.translatorId.isNotBlank() }?.translatorId ?: latest.translatorId
                val calc = calculateSeriesProgress(latest, items, now)

                HistorySeriesCandidate(
                    itemId = itemId,
                    title = latest.title,
                    url = bestUrl,
                    translatorId = bestTranslatorId,
                    latest = latest,
                    allItems = items,
                    calculatedAbsoluteEpisodeIndex = calc.absoluteEpisodeIndex,
                    calculatedWatchedEpisodesCount = calc.watchedEpisodesCount,
                    currentTotalEpisodes = calc.totalEpisodesCount,
                    isFullyWatched = latest.isFullyWatched || calc.isFullyWatched
                )
            }

            if (seriesCandidates.isEmpty()) return@launch

            _isCheckingSeriesUpdates.value = true
            try {
                val semaphore = Semaphore(2)
                seriesCandidates.map { candidate ->
                    async {
                        val lastCheck = lastHistoryCheckTimestamps[candidate.itemId] ?: 0L
                        // Защита от спама: если force = false, то 10 секунд; если force = true, то 3 секунды
                        val minInterval = if (force) 3_000L else 10_000L
                        if (now - lastCheck < minInterval) return@async

                        semaphore.withPermit {
                            try {
                                lastHistoryCheckTimestamps[candidate.itemId] = now

                                val targetUrl = RezkaService.adjustUrlToCurrentMirror(candidate.url, RezkaType.SERIES, candidate.itemId)
                                val numericPostId = RezkaService.extractNumericId(targetUrl).ifEmpty { RezkaService.extractNumericId(candidate.itemId) }
                                val transId = candidate.translatorId

                                var newTotalEpisodes = 0
                                var newTotalSeasons = 0

                                // 1. Сверхбыстрый точечный AJAX-запрос к API HDRezka
                                if (numericPostId.isNotBlank()) {
                                    try {
                                        val ajaxRes = SeriesUpdateEngine.fetchSeriesLatestEpisodeAjax(numericPostId, transId)
                                        if (ajaxRes.isSuccess && ajaxRes.totalEpisodes > 0) {
                                            newTotalEpisodes = ajaxRes.totalEpisodes
                                            newTotalSeasons = ajaxRes.totalSeasons
                                        }
                                    } catch (_: Exception) {}
                                }

                                // 2. Сканирование страницы если AJAX не дал результата
                                if (newTotalEpisodes == 0) {
                                    try {
                                        val scanRes = SeriesUpdateEngine.fetchSeriesLatestEpisode(targetUrl)
                                        if (scanRes.isSuccess && scanRes.totalEpisodes > 0) {
                                            newTotalEpisodes = scanRes.totalEpisodes
                                            newTotalSeasons = scanRes.totalSeasons
                                        }
                                    } catch (_: Exception) {}
                                }

                                // 3. Fallback: RezkaService.getDetail
                                if (newTotalEpisodes == 0) {
                                    try {
                                        val detail = RezkaService.getDetail(targetUrl)
                                        if (detail.seasons.isNotEmpty()) {
                                            newTotalEpisodes = detail.seasons.sumOf { it.episodes.size }
                                            newTotalSeasons = detail.seasons.maxOfOrNull { it.id } ?: detail.seasons.size
                                        }
                                    } catch (_: Exception) {}
                                }

                                if (newTotalEpisodes > 0) {
                                    val finalSeasons = maxOf(newTotalSeasons, 1)
                                    val currentKnownTotal = candidate.currentTotalEpisodes

                                    // Обновляем точное количество серий во всей истории сериала в базе данных,
                                    // только если реально найдены новые серии или если общее количество серий не было известно
                                    if (newTotalEpisodes > currentKnownTotal || currentKnownTotal <= 0) {
                                        repository.updateExactTotalEpisodes(candidate.itemId, newTotalEpisodes, finalSeasons)
                                    }

                                    // Если вышли новые серии, которых не было раньше, сбрасываем статус "полностью просмотрено":
                                    if (newTotalEpisodes > currentKnownTotal && currentKnownTotal > 0) {
                                        autoWatchedPersistedSet.remove(candidate.itemId)
                                        repository.setHistoryWatched(candidate.itemId, false)
                                        val updated = candidate.latest.copy(
                                            isFullyWatched = false,
                                            totalEpisodes = newTotalEpisodes,
                                            totalSeasons = finalSeasons
                                        )
                                        FirebaseSyncManager.onWatchProgress(updated)
                                        FirebaseSyncManager.flushWatchProgress()
                                    }
                                }
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                            }
                        }
                    }
                }.awaitAll()
            } finally {
                _isCheckingSeriesUpdates.value = false
            }
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
        totalSeasons: Int = 0,
        isFullyWatched: Boolean = false,
        isOfflineStream: Boolean = false
    ) {
        // Просмотр в оффлайн режиме из оффлайн библиотеки никак не должен отмечаться в истории
        if (isOfflineStream || !NetworkMonitor.isOnline.value || url.isBlank() || url.startsWith("file://") || itemId.startsWith("offline_")) {
            return
        }
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
            isFullyWatched = isFullyWatched,
            timestamp = System.currentTimeMillis()
        )
        viewModelScope.launch {
            if (isFullyWatched) {
                repository.setHistoryWatched(itemId, true)
            } else {
                repository.setHistoryWatched(itemId, false)
            }
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
     * Fetches stream URLs for playback (with offline local fallback)
     */
    suspend fun getStreamUrls(
        itemId: String,
        translatorId: String,
        isSeries: Boolean,
        season: Int = 0,
        episode: String = ""
    ): List<StreamUrl> {
        val cleanNum = itemId.filter { it.isDigit() }
        val offlineList = repository.getOfflineMediaByItemId(itemId).ifEmpty {
            repository.getAllOfflineMediaList().filter {
                it.itemId == itemId || (cleanNum.isNotEmpty() && (it.itemId == cleanNum || it.itemId.startsWith("$cleanNum-") || it.itemId.filter { c -> c.isDigit() } == cleanNum))
            }
        }

        val matchingOffline = if (isSeries) {
            val cleanEp = episode.filter { it.isDigit() }
            offlineList.find {
                it.season == season && (it.episode == episode || (cleanEp.isNotEmpty() && it.episode.filter { c -> c.isDigit() } == cleanEp)) &&
                DownloadHelper.resolveOfflineVideoFile(it, getApplication()) != null
            }
                ?: offlineList.find { it.season == season && DownloadHelper.resolveOfflineVideoFile(it, getApplication()) != null }
                ?: offlineList.firstOrNull { DownloadHelper.resolveOfflineVideoFile(it, getApplication()) != null }
        } else {
            offlineList.find { DownloadHelper.resolveOfflineVideoFile(it, getApplication()) != null }
        }

        if (matchingOffline != null) {
            val resolvedFile = DownloadHelper.resolveOfflineVideoFile(matchingOffline, getApplication())
            if (resolvedFile != null && resolvedFile.exists()) {
                if (!NetworkMonitor.isOnline.value || itemId.startsWith("offline_") || offlineList.isNotEmpty()) {
                    return listOf(
                        StreamUrl(
                            quality = matchingOffline.quality.ifEmpty { "1080p" },
                            url = "file://${resolvedFile.absolutePath}",
                            directMp4Url = "file://${resolvedFile.absolutePath}"
                        )
                    )
                }
            }
        }

        return try {
            RezkaService.getStreamUrls(itemId, translatorId, isSeries, season, episode)
        } catch (e: Exception) {
            if (matchingOffline != null) {
                val resolvedFile = DownloadHelper.resolveOfflineVideoFile(matchingOffline, getApplication())
                if (resolvedFile != null && resolvedFile.exists()) {
                    return listOf(
                        StreamUrl(
                            quality = matchingOffline.quality.ifEmpty { "1080p" },
                            url = "file://${resolvedFile.absolutePath}",
                            directMp4Url = "file://${resolvedFile.absolutePath}"
                        )
                    )
                }
            }
            NetworkMonitor.handleNetworkException(e)
            throw e
        }
    }

    suspend fun getEpisodesForTranslator(
        numericId: String,
        translatorId: String,
        translatorUrl: String = ""
    ): List<Season> {
        val cleanNum = numericId.filter { it.isDigit() }
        val offlineList = repository.getOfflineMediaByItemId(numericId).ifEmpty {
            repository.getAllOfflineMediaList().filter {
                it.itemId == numericId || (cleanNum.isNotEmpty() && (it.itemId == cleanNum || it.itemId.startsWith("$cleanNum-") || it.itemId.filter { c -> c.isDigit() } == cleanNum))
            }
        }

        if (!NetworkMonitor.isOnline.value || offlineList.isNotEmpty()) {
            if (offlineList.isNotEmpty()) {
                val filtered = offlineList.filter { it.translatorId == translatorId || translatorId.isBlank() }
                    .ifEmpty { offlineList }
                return filtered.groupBy { it.season }.map { (sNum, eps) ->
                    Season(
                        id = sNum,
                        name = "Сезон $sNum",
                        episodes = eps.map { epEntity ->
                            Episode(
                                id = epEntity.episode,
                                name = "Серия ${epEntity.episode}",
                                seasonId = sNum,
                                translatorId = epEntity.translatorId
                            )
                        }.sortedBy { it.id.toIntOrNull() ?: 0 }
                    )
                }.sortedBy { it.id }
            }
        }
        return try {
            RezkaService.getEpisodesForTranslator(numericId, translatorId, translatorUrl)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            NetworkMonitor.handleNetworkException(e)
            val offlineList = repository.getOfflineMediaByItemId(numericId)
            if (offlineList.isNotEmpty()) {
                offlineList.groupBy { it.season }.map { (sNum, eps) ->
                    Season(
                        id = sNum,
                        name = "Сезон $sNum",
                        episodes = eps.map { epEntity ->
                            Episode(
                                id = epEntity.episode,
                                name = "Серия ${epEntity.episode}",
                                seasonId = sNum,
                                translatorId = epEntity.translatorId
                            )
                        }
                    )
                }.sortedBy { it.id }
            } else {
                throw e
            }
        }
    }

    suspend fun getSavedProgress(itemId: String): WatchHistoryEntity? {
        val allItems = repository.getAllWatchHistoryForMovie(itemId)
        if (allItems.isEmpty()) return repository.getWatchHistoryForMovie(itemId)
        return resolveLatestWatchHistory(allItems)
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

    // Player Selection (локальная настройка, без синхронизации с облаком)
    val selectedPlayer: StateFlow<String> = RezkaService.selectedPlayer

    fun clearDnsCache() {
        SafeDns.clearCache()
    }

    fun setSelectedPlayer(playerKey: String) {
        RezkaService.setSelectedPlayer(playerKey)
    }

    fun loadInstalledPlayers(forceRefresh: Boolean = false) {
        val current = _installedPlayers.value
        if (!forceRefresh && current.isNotEmpty()) return
        viewModelScope.launch {
            val list = ExternalPlayerManager.getInstalledVideoPlayers(getApplication(), forceRefresh)
            _installedPlayers.value = list
        }
    }

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

    fun setDefaultCatalogType(type: RezkaType) {
        if (type == RezkaType.COLLECTIONS) return
        val previous = RezkaService.defaultCatalogType.value
        RezkaService.setDefaultCatalogType(type)
        if (_currentType.value == previous && _currentGenre.value.isEmpty() && _currentYear.value.isEmpty() && _currentCountry.value.isEmpty() && searchQuery.isEmpty()) {
            loadCatalog(type = type, section = _currentSection.value, forceRefresh = true)
        }
        FirebaseSyncManager.onSettingsUpdated(defaultCatalogType = type.name)
    }

    fun setDefaultCatalogSection(section: SectionType) {
        val previous = RezkaService.defaultCatalogSection.value
        RezkaService.setDefaultCatalogSection(section)
        if (_currentSection.value == previous && _currentGenre.value.isEmpty() && _currentYear.value.isEmpty() && _currentCountry.value.isEmpty() && searchQuery.isEmpty()) {
            loadCatalog(type = _currentType.value, section = section, forceRefresh = true)
        }
        FirebaseSyncManager.onSettingsUpdated(defaultCatalogSection = section.name)
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
     * Высокопроизводительный асинхронный аудит зеркал с проверкой видеопотока.
     * 1. Проверяет зеркала по порядку как в настройках, начиная с основного (PRIMARY_MIRROR).
     * 2. Проверяет зеркала асинхронно пачками по 5 штук.
     * 3. Для каждого зеркала проверяет каталог и отклик видеопотока.
     * 4. Если поток успешен — сразу выбирает это зеркало и прерывает аудит.
     * 5. Если ни у одного зеркала поток не грузится — выбирает первое зеркало с рабочим каталогом.
     */
    fun startMirrorAudit(isFirstLaunch: Boolean = false) {
        auditJob?.cancel()
        isFirstLaunchAuditSession = isFirstLaunch

        val mirrors = buildList {
            add(RezkaService.PRIMARY_MIRROR)
            for (m in RezkaService.PRESET_MIRRORS) {
                if (!contains(m)) add(m)
            }
        }
        val totalCount = mirrors.size
        var checkedCount = 0

        val primaryHost = RezkaService.PRIMARY_MIRROR.removePrefix("https://").removePrefix("http://").trimEnd('/')
        _mirrorAuditState.value = MirrorAuditUiState.Checking(
            checkedCount = 0,
            totalCount = totalCount,
            currentMirrorHost = primaryHost,
            lastCheckedStatus = "Подготовка к проверке..."
        )

        auditJob = viewModelScope.launch(Dispatchers.IO) {
            val chunks = mirrors.chunked(5)
            var selectedWorkingMirror: String? = null
            var selectedCatalogItems: List<RezkaItem>? = null
            var firstCatalogOnlyMirror: String? = null
            var firstCatalogOnlyItems: List<RezkaItem>? = null

            for (chunk in chunks) {
                if (!isActive) break

                val firstHostInChunk = chunk.firstOrNull()?.removePrefix("https://")?.removePrefix("http://")?.trimEnd('/').orEmpty()
                withContext(Dispatchers.Main) {
                    _mirrorAuditState.value = MirrorAuditUiState.Checking(
                        checkedCount = checkedCount,
                        totalCount = totalCount,
                        currentMirrorHost = firstHostInChunk,
                        lastCheckedStatus = "Проверка доступности..."
                    )
                }

                // Параллельно асинхронно проверяем пачку из 5 зеркал
                val chunkDeferreds = chunk.map { mirrorUrl ->
                    async(Dispatchers.IO) {
                        val checkRes = RezkaService.testMirrorWithStreamCheck(mirrorUrl)
                        val host = mirrorUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')
                        withContext(Dispatchers.Main) {
                            checkedCount++
                            val statusMsg = when {
                                checkRes.streamSuccess -> "$host: успешно (поток работает)"
                                checkRes.catalogSuccess -> "$host: каталог OK (поток недоступен)"
                                else -> "$host: недоступно"
                            }
                            _mirrorAuditState.value = MirrorAuditUiState.Checking(
                                checkedCount = checkedCount,
                                totalCount = totalCount,
                                currentMirrorHost = host,
                                lastCheckedMirrorHost = host,
                                lastCheckedStatus = statusMsg,
                                lastCheckedSuccess = checkRes.streamSuccess || checkRes.catalogSuccess
                            )
                        }
                        Pair(mirrorUrl, checkRes)
                    }
                }

                val chunkResults = chunkDeferreds.awaitAll()
                if (!isActive) return@launch

                // Анализируем результаты пачки в строгом порядке следования в списке (начиная с основного)
                for (m in chunk) {
                    val result = chunkResults.firstOrNull { it.first == m }?.second ?: continue
                    if (result.streamSuccess && selectedWorkingMirror == null) {
                        selectedWorkingMirror = m
                        selectedCatalogItems = result.catalogItems
                        break
                    }
                    if (result.catalogSuccess && firstCatalogOnlyMirror == null) {
                        firstCatalogOnlyMirror = m
                        firstCatalogOnlyItems = result.catalogItems
                    }
                }

                // Если найдено приоритетное зеркало с работающим потоком — завершаем поиск
                if (selectedWorkingMirror != null) {
                    break
                }
            }

            if (!isActive) return@launch

            val targetMirror = selectedWorkingMirror ?: firstCatalogOnlyMirror
            val targetItems = selectedCatalogItems ?: firstCatalogOnlyItems

            if (targetMirror != null && targetItems != null) {
                delay(300)
                withContext(Dispatchers.Main) {
                    RezkaService.setMirror(targetMirror)
                    RezkaService.markFirstLaunchAuditDone()
                    FirebaseSyncManager.onSettingsUpdated(mirror = targetMirror)

                    loadedMap.clear()
                    targetItems.forEach { loadedMap[it.id] = it }
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
                        "Не найдено ни одного работающего зеркала... :(\nПопробуйте указать работающий адрес зеркала вручную в настройках."
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
            loadCatalog(RezkaService.defaultCatalogType.value, RezkaService.defaultCatalogSection.value, "", forceRefresh = true)
        }
    }

    fun dismissMirrorAudit() {
        auditJob?.cancel()
        _mirrorAuditState.value = MirrorAuditUiState.Idle
    }

    companion object {
        fun parseEpisodeNumber(epStr: String): Int {
            return Regex("""\d+""").find(epStr)?.value?.toIntOrNull() ?: 1
        }

        fun getSeriesOrderWeight(item: WatchHistoryEntity): Long {
            val s = item.season.coerceAtLeast(1)
            val epNum = parseEpisodeNumber(item.episode).coerceAtLeast(1)
            return s.toLong() * 100_000L + epNum.toLong()
        }

        /**
         * Высокопроизводительный движок вычисления наиболее актуальной серии просмотра.
         * Честно и динамически отражает то, что пользователь смотрел в последний раз (last-watched).
         * Если пользователь ушёл смотреть прошлые серии — последняя просмотренная серия мгновенно становится актуальной.
         */
        fun resolveLatestWatchHistory(items: List<WatchHistoryEntity>): WatchHistoryEntity {
            if (items.isEmpty()) throw NoSuchElementException("History items list is empty")
            if (items.size == 1) return items[0]

            return items.maxWithOrNull(
                compareBy<WatchHistoryEntity> { it.timestamp }
                    .thenBy { it.progressMs }
            ) ?: items.first()
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
         * Учитывает фактическое распределение серий по сезонам, сквозной номер серии из общего числа,
         * прогресс текущей серии, правило 24ч + <5% и ручные отметки.
         */
        fun calculateSeriesProgress(
            latest: WatchHistoryEntity,
            items: List<WatchHistoryEntity>,
            now: Long = System.currentTimeMillis()
        ): SeriesProgressCalculation {
            val latestSeason = latest.season.coerceAtLeast(1)
            val rawEpNum = parseEpisodeNumber(latest.episode)
            val latestEpNumber = if (rawEpNum in 1..2500) rawEpNum else 1

            val rawStoredTotalEpisodes = items.mapNotNull { it.totalEpisodes.takeIf { ep -> ep in 1..2500 } }.maxOrNull() ?: 0
            val storedTotalEpisodes = if (rawStoredTotalEpisodes > 2500) 0 else rawStoredTotalEpisodes

            // Точный сквозной порядковый номер серии (файла)
            val absoluteEpisodeIndex = if (latest.episodeIndex > 0) {
                latest.episodeIndex
            } else {
                val priorEpisodesFromHistory = if (latestSeason > 1) {
                    (1 until latestSeason).sumOf { s ->
                        items.filter { it.season == s }.map { it.episode }.distinct().size
                    }
                } else 0
                (priorEpisodesFromHistory + latestEpNumber).coerceAtLeast(1)
            }.coerceAtLeast(1)

            val totalEpisodesCount = maxOf(storedTotalEpisodes, latest.totalEpisodes, absoluteEpisodeIndex, 1)
            val isLastEpisode = absoluteEpisodeIndex >= totalEpisodesCount

            // Завершена ли серия до конца (окончание видео / последние 20 секунд / >= 98%):
            val isCompletedToEnd = latest.durationMs > 0L && (
                latest.progressMs >= latest.durationMs ||
                (latest.durationMs - latest.progressMs) <= 20_000L ||
                (latest.progressMs.toDouble() / latest.durationMs.toDouble()) >= 0.98
            )

            // Правило автозавершения: осталось менее 5% и прошло не менее 24 часов:
            val isLatestEpAutoWatched = isAutoWatchedRuleMet(latest.progressMs, latest.durationMs, latest.timestamp, now)

            // Полностью завершенным сериал считается при ручной отметке "Просмотрено" или если последняя серия завершена
            val isFullyWatched = latest.isFullyWatched || (isLastEpisode && (isCompletedToEnd || isLatestEpAutoWatched))

            val watchedEpisodesCount: Int
            val totalProgressFraction: Float

            if (isFullyWatched) {
                watchedEpisodesCount = totalEpisodesCount
                totalProgressFraction = 1.0f
            } else {
                val currentEpProgress = if (latest.durationMs > 0L) {
                    (latest.progressMs.toDouble() / latest.durationMs.toDouble()).coerceIn(0.0, 1.0)
                } else 0.0

                val isCurrentEpFinished = currentEpProgress >= 0.95 || isCompletedToEnd

                watchedEpisodesCount = if (isCurrentEpFinished) {
                    absoluteEpisodeIndex.coerceIn(0, totalEpisodesCount)
                } else {
                    (absoluteEpisodeIndex - 1).coerceIn(0, totalEpisodesCount - 1)
                }

                val rawFraction = ((absoluteEpisodeIndex - 1).toDouble() + currentEpProgress) / totalEpisodesCount.toDouble()
                totalProgressFraction = rawFraction.coerceIn(0.0, 0.99).toFloat()
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

    override fun onCleared() {
        super.onCleared()
        FirebaseSyncManager.searchHistoryProvider = null
        FirebaseSyncManager.onSearchHistorySynced = null
        catalogJob?.cancel()
        paginationJob?.cancel()
        searchJob?.cancel()
        auditJob?.cancel()
        countryPrefetchJob?.cancel()
        checkHistoryJob?.cancel()
        commentsJob?.cancel()
    }
}

