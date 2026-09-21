package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.RezkaItem
import com.example.data.ScreenState
import com.example.ui.RezkaViewModel
import com.example.ui.components.AppHeader
import com.example.ui.components.AuthDialog
import com.example.ui.screens.CatalogScreen
import com.example.ui.screens.FavoritesScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.DetailScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.ThematicListScreen
import com.example.ui.screens.PersonProfileScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.CinemaBlack
import com.example.ui.theme.CinemaDark
import com.example.ui.theme.CinemaPrimary
import com.example.ui.theme.CinemaTextGray
import com.example.ui.theme.CinemaTextWhite
import com.example.ui.tv.TvDetector
import com.example.ui.tv.TvMainScreen
import com.example.ui.tv.TvModePreference
import com.example.ui.tv.LocalTvShowCursor
import com.example.BuildConfig
import com.example.data.UpdateManager
import com.example.data.UpdateState
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import com.example.data.SeriesUpdateScheduler
import kotlinx.coroutines.launch

enum class NavTab {
    FEED, FAVORITES, HISTORY
}

class MainActivity : ComponentActivity() {
    private val viewModel: RezkaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SeriesUpdateScheduler.schedulePeriodicCheck(this)
        viewModel.triggerManualSeriesCheck(this)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        viewModel.handleIncomingIntent(intent)

        setContent {
            MyApplicationTheme {
                MainContent(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.handleIncomingIntent(intent)
    }
}

@Composable
fun MainContent(viewModel: RezkaViewModel = viewModel()) {
    var currentTab by remember { mutableStateOf(NavTab.FEED) }
    val navigationStack = remember { mutableStateListOf<ScreenState>() }
    var isSettingsOpen by remember { mutableStateOf(false) }

    // Безопасный метод извлечения экрана из стека (защита от дребезга кнопок, двойных кликов и гонок состояний)
    val popBackStack = {
        if (navigationStack.isNotEmpty()) {
            navigationStack.removeAt(navigationStack.lastIndex)
        }
    }

    // Безопасный метод добавления экрана в стек с автоматической дедупликацией последовательных переходов
    val pushToStack = { state: ScreenState ->
        val currentTop = navigationStack.lastOrNull()
        val isDuplicate = when {
            state is ScreenState.Detail && currentTop is ScreenState.Detail -> state.item.id == currentTop.item.id
            state is ScreenState.ThematicList && currentTop is ScreenState.ThematicList -> state.url == currentTop.url
            state is ScreenState.PersonProfile && currentTop is ScreenState.PersonProfile -> state.url == currentTop.url
            else -> false
        }
        if (!isDuplicate) {
            navigationStack.add(state)
        }
    }

    val pendingDeepLink by viewModel.pendingDeepLink.collectAsState()

    LaunchedEffect(pendingDeepLink) {
        val link = pendingDeepLink ?: return@LaunchedEffect
        val top = navigationStack.lastOrNull() as? ScreenState.Detail
        if (top?.item?.id == link.item.id) {
            if (top.initialTranslatorId != link.translatorId) {
                popBackStack()
                pushToStack(ScreenState.Detail(item = link.item, initialTranslatorId = link.translatorId))
            }
        } else {
            pushToStack(ScreenState.Detail(item = link.item, initialTranslatorId = link.translatorId))
        }
        viewModel.consumePendingDeepLink()
    }

    LaunchedEffect(Unit) {
        UpdateManager.checkForUpdates(BuildConfig.VERSION_NAME)
    }

    val isLoggedIn by viewModel.isLoggedIn.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val currentUserAvatar by viewModel.currentUserAvatar.collectAsState()
    var showAuthDialog by remember { mutableStateOf(false) }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    val context = LocalContext.current
    val tvModePrefString by viewModel.tvModePreference.collectAsState()
    val isPlayerActive by viewModel.isPlayerActive.collectAsState()

    val targetIsTvMode = remember(context, tvModePrefString, isLandscape) {
        val pref = when (tvModePrefString) {
            "force_tv" -> TvModePreference.FORCE_TV
            "force_mobile" -> TvModePreference.FORCE_MOBILE
            else -> TvModePreference.AUTO
        }
        when (pref) {
            TvModePreference.FORCE_TV -> true
            TvModePreference.FORCE_MOBILE -> false
            TvModePreference.AUTO -> TvDetector.isRunningOnTv(context) || isLandscape
        }
    }

    var isTvMode by remember { mutableStateOf(targetIsTvMode) }

    LaunchedEffect(targetIsTvMode, isPlayerActive) {
        if (!isPlayerActive) {
            isTvMode = targetIsTvMode
        }
    }

    val showTvCursor = remember(context, tvModePrefString, isLandscape) {
        when (tvModePrefString) {
            "force_tv" -> true
            "force_mobile" -> false
            else -> TvDetector.isRunningOnTv(context)
        }
    }

    if (showAuthDialog) {
        AuthDialog(
            viewModel = viewModel,
            onDismiss = { showAuthDialog = false }
        )
    }

    // System Back Press Handler inside Compose
    BackHandler(enabled = isSettingsOpen || navigationStack.isNotEmpty()) {
        if (navigationStack.isNotEmpty()) {
            popBackStack()
        } else if (isSettingsOpen) {
            isSettingsOpen = false
        }
    }

    CompositionLocalProvider(LocalTvShowCursor provides showTvCursor) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CinemaBlack)
        ) {
        if (isTvMode) {
            // Режим Android TV: строго изолированный рендеринг активного экрана
            // Никаких скрытых мобильных Scaffold, фоновых сеток каталога или полей ввода!
            when {
                navigationStack.isNotEmpty() -> {
                    when (val topState = navigationStack.last()) {
                        is ScreenState.Detail -> {
                            DetailScreen(
                                viewModel = viewModel,
                                item = topState.item,
                                initialTranslatorId = topState.initialTranslatorId,
                                onBack = { popBackStack() },
                                onNavigateToThematic = { name, url ->
                                    if (url.contains("/person/")) {
                                        pushToStack(ScreenState.PersonProfile(name, url))
                                    } else {
                                        pushToStack(ScreenState.ThematicList(name, url))
                                    }
                                },
                                onNavigateToDetail = { targetItem ->
                                    pushToStack(ScreenState.Detail(targetItem))
                                },
                                modifier = Modifier.fillMaxSize(),
                                isTvMode = true
                            )
                        }
                        is ScreenState.ThematicList -> {
                            ThematicListScreen(
                                title = topState.title,
                                url = topState.url,
                                onBack = { popBackStack() },
                                onNavigateToDetail = { item ->
                                    pushToStack(ScreenState.Detail(item))
                                },
                                modifier = Modifier.fillMaxSize(),
                                isTvMode = true,
                                viewModel = viewModel
                            )
                        }
                        is ScreenState.PersonProfile -> {
                            PersonProfileScreen(
                                name = topState.name,
                                url = topState.url,
                                onBack = { popBackStack() },
                                onNavigateToDetail = { item ->
                                    pushToStack(ScreenState.Detail(item))
                                },
                                modifier = Modifier.fillMaxSize(),
                                isTvMode = true,
                                viewModel = viewModel
                            )
                        }
                    }
                }
                isSettingsOpen -> {
                    SettingsScreen(
                        viewModel = viewModel,
                        onBack = { isSettingsOpen = false },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                else -> {
                    TvMainScreen(
                        viewModel = viewModel,
                        onNavigateToDetail = { item ->
                            pushToStack(ScreenState.Detail(item))
                        },
                        isTopScreen = navigationStack.isEmpty() && !isSettingsOpen,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        } else {
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    // Header bar (R4ezka, account/login, settings) persistent on every tab
                    AnimatedVisibility(
                        visible = navigationStack.isEmpty() && !isSettingsOpen,
                        enter = slideInVertically { -it },
                        exit = slideOutVertically { -it }
                    ) {
                        AppHeader(
                            isLoggedIn = isLoggedIn,
                            currentUser = currentUser,
                            currentUserAvatar = currentUserAvatar,
                            onAuthClick = { showAuthDialog = true },
                            onSettingsClick = { isSettingsOpen = true }
                        )
                    }
                },
                bottomBar = {
                    // Hide bottom bar when viewing movie details/player or settings to give 100% immersive focus
                    AnimatedVisibility(
                        visible = navigationStack.isEmpty() && !isSettingsOpen,
                        enter = slideInVertically { it },
                        exit = slideOutVertically { it }
                    ) {
                        NavigationBar(
                            containerColor = CinemaDark,
                            tonalElevation = 0.dp,
                            windowInsets = WindowInsets.navigationBars,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("bottom_nav_bar")
                        ) {
                            // 1. Feed / Catalog tab
                            NavigationBarItem(
                                selected = currentTab == NavTab.FEED,
                                onClick = { currentTab = NavTab.FEED },
                                icon = { Icon(Icons.Default.Movie, contentDescription = "Каталог") },
                                label = { Text("Каталог", fontSize = 11.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = CinemaPrimary,
                                    selectedTextColor = CinemaPrimary,
                                    unselectedIconColor = CinemaTextGray,
                                    unselectedTextColor = CinemaTextGray,
                                    indicatorColor = CinemaPrimary.copy(alpha = 0.1f)
                                ),
                                modifier = Modifier.testTag("nav_feed")
                            )

                            // 2. Favorites tab
                            NavigationBarItem(
                                selected = currentTab == NavTab.FAVORITES,
                                onClick = { currentTab = NavTab.FAVORITES },
                                icon = { Icon(Icons.Default.Bookmark, contentDescription = "Избранное") },
                                label = { Text("Избранное", fontSize = 11.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = CinemaPrimary,
                                    selectedTextColor = CinemaPrimary,
                                    unselectedIconColor = CinemaTextGray,
                                    unselectedTextColor = CinemaTextGray,
                                    indicatorColor = CinemaPrimary.copy(alpha = 0.1f)
                                ),
                                modifier = Modifier.testTag("nav_favorites")
                            )

                            // 3. History tab
                            NavigationBarItem(
                                selected = currentTab == NavTab.HISTORY,
                                onClick = { currentTab = NavTab.HISTORY },
                                icon = { Icon(Icons.Default.History, contentDescription = "История") },
                                label = { Text("История", fontSize = 11.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = CinemaPrimary,
                                    selectedTextColor = CinemaPrimary,
                                    unselectedIconColor = CinemaTextGray,
                                    unselectedTextColor = CinemaTextGray,
                                    indicatorColor = CinemaPrimary.copy(alpha = 0.1f)
                                ),
                                modifier = Modifier.testTag("nav_history")
                            )
                        }
                    }
                },
                containerColor = CinemaBlack
            ) { paddingValues ->
                // Crossfade content transition for ultra-smooth shifts
                Crossfade(
                    targetState = currentTab,
                    label = "nav_transition",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            top = paddingValues.calculateTopPadding(),
                            bottom = paddingValues.calculateBottomPadding()
                        )
                ) { tab ->
                    when (tab) {
                        NavTab.FEED -> {
                            CatalogScreen(
                                viewModel = viewModel,
                                onNavigateToDetail = { item ->
                                    pushToStack(ScreenState.Detail(item))
                                },
                                onNavigateToSettings = { isSettingsOpen = true },
                                isTopScreen = navigationStack.isEmpty() && !isSettingsOpen
                            )
                        }
                        NavTab.FAVORITES -> {
                            FavoritesScreen(
                                viewModel = viewModel,
                                onNavigateToDetail = { item ->
                                    pushToStack(ScreenState.Detail(item))
                                }
                            )
                        }
                        NavTab.HISTORY -> {
                            HistoryScreen(
                                viewModel = viewModel,
                                onNavigateToDetail = { item ->
                                    pushToStack(ScreenState.Detail(item))
                                }
                            )
                        }
                    }
                }
            }

            // Animated Fullscreen Settings Screen overlay for Mobile
            AnimatedVisibility(
                visible = isSettingsOpen && navigationStack.isEmpty(),
                enter = slideInHorizontally(initialOffsetX = { it }),
                exit = slideOutHorizontally(targetOffsetX = { it }),
                modifier = Modifier.fillMaxSize()
            ) {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = { isSettingsOpen = false }
                )
            }

            // Animated Fullscreen stack screens overlay for Mobile
            AnimatedContent(
                targetState = navigationStack.lastOrNull(),
                transitionSpec = {
                    if (targetState != null && initialState == null) {
                        // Первый экран поверх каталога: только slideIn справа, без slideOut старого экрана (его нет)
                        slideInHorizontally(animationSpec = tween(300)) { it } togetherWith ExitTransition.None
                    } else if (targetState == null && initialState != null) {
                        // Возврат на каталог (стек пуст): только slideOut вправо, без slideIn нового экрана (его нет)
                        EnterTransition.None togetherWith slideOutHorizontally(animationSpec = tween(300)) { it }
                    } else if (targetState != null && initialState != null) {
                        // Переход между экранами внутри стека (например, Detail -> PersonProfile)
                        // Слайд нового экрана справа налево, уход старого влево
                        slideInHorizontally(animationSpec = tween(300)) { it } togetherWith slideOutHorizontally(animationSpec = tween(300)) { -it }
                    } else {
                        EnterTransition.None togetherWith ExitTransition.None
                    }
                },
                label = "stack_transition",
                modifier = Modifier.fillMaxSize()
            ) { topState ->
                if (topState != null) {
                    when (topState) {
                        is ScreenState.Detail -> {
                            DetailScreen(
                                viewModel = viewModel,
                                item = topState.item,
                                initialTranslatorId = topState.initialTranslatorId,
                                onBack = { popBackStack() },
                                onNavigateToThematic = { name, url ->
                                    if (url.contains("/person/")) {
                                        pushToStack(ScreenState.PersonProfile(name, url))
                                    } else {
                                        pushToStack(ScreenState.ThematicList(name, url))
                                    }
                                },
                                onNavigateToDetail = { targetItem ->
                                    pushToStack(ScreenState.Detail(targetItem))
                                },
                                isTvMode = false
                            )
                        }
                        is ScreenState.ThematicList -> {
                            ThematicListScreen(
                                title = topState.title,
                                url = topState.url,
                                onBack = { popBackStack() },
                                onNavigateToDetail = { item ->
                                    pushToStack(ScreenState.Detail(item))
                                },
                                viewModel = viewModel
                            )
                        }
                        is ScreenState.PersonProfile -> {
                            PersonProfileScreen(
                                name = topState.name,
                                url = topState.url,
                                onBack = { popBackStack() },
                                onNavigateToDetail = { item ->
                                    pushToStack(ScreenState.Detail(item))
                                },
                                viewModel = viewModel
                            )
                        }
                    }
                }
            }
            } // Close else mobile branch
            if (!isPlayerActive) {
                UpdateBanner(
                    isBottomBarVisible = navigationStack.isEmpty() && !isSettingsOpen && !isTvMode,
                    modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                )
            }
        }
    }
}

@Composable
fun UpdateBanner(
    isBottomBarVisible: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updateState by UpdateManager.updateState.collectAsState()
    var isExpanded by remember { mutableStateOf(false) }

    if (updateState is UpdateState.Idle) return

    val bottomPadding = if (isBottomBarVisible) 88.dp else 16.dp

    LaunchedEffect(updateState) {
        val state = updateState
        if (state is UpdateState.ReadyToInstall) {
            UpdateManager.installApk(context, state.apkFile)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = bottomPadding)
            .navigationBarsPadding()
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("update_banner"),
            shape = MaterialTheme.shapes.medium,
            color = CinemaDark,
            border = androidx.compose.foundation.BorderStroke(1.dp, CinemaPrimary.copy(alpha = 0.5f)),
            shadowElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.SystemUpdate,
                            contentDescription = "Обновление",
                            tint = CinemaPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            when (val state = updateState) {
                                is UpdateState.UpdateAvailable -> {
                                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Text(
                                            text = "Обновление: ${state.latestVersion}",
                                            color = CinemaTextWhite,
                                            fontSize = 13.sp,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                        )
                                        if (!state.changelog.isNullOrBlank()) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            IconButton(
                                                onClick = { isExpanded = !isExpanded },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                                    contentDescription = "Подробнее",
                                                    tint = CinemaPrimary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                                is UpdateState.Downloading -> {
                                    Text(
                                        text = if (state.progress >= 0f) {
                                            "Скачивание: ${(state.progress * 100).toInt()}%"
                                        } else {
                                            "Скачивание..."
                                        },
                                        color = CinemaTextWhite,
                                        fontSize = 13.sp,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    LinearProgressIndicator(
                                        progress = { if (state.progress >= 0f) state.progress else 0f },
                                        modifier = Modifier.fillMaxWidth(0.9f),
                                        color = CinemaPrimary,
                                        trackColor = CinemaTextGray.copy(alpha = 0.3f)
                                    )
                                }
                                is UpdateState.ReadyToInstall -> {
                                    Text(
                                        text = "Готово к установке",
                                        color = CinemaTextWhite,
                                        fontSize = 13.sp,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                    )
                                }
                                is UpdateState.Error -> {
                                    Text(
                                        text = state.message,
                                        color = MaterialTheme.colorScheme.error,
                                        fontSize = 11.sp,
                                        maxLines = 1
                                    )
                                }
                                else -> {}
                            }
                        }
                    }

                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        when (val state = updateState) {
                            is UpdateState.UpdateAvailable -> {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            UpdateManager.startDownload(context, state.downloadUrl)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = CinemaPrimary),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("Обновить", color = CinemaTextWhite, fontSize = 11.sp)
                                }
                            }
                            is UpdateState.ReadyToInstall -> {
                                Button(
                                    onClick = {
                                        UpdateManager.installApk(context, state.apkFile)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = CinemaPrimary),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("Установить", color = CinemaTextWhite, fontSize = 11.sp)
                                }
                            }
                            is UpdateState.Error -> {
                                TextButton(
                                    onClick = { UpdateManager.dismissUpdate() },
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("ОК", color = CinemaPrimary, fontSize = 11.sp)
                                }
                            }
                            else -> {}
                        }

                        if (updateState is UpdateState.UpdateAvailable) {
                            Spacer(modifier = Modifier.width(6.dp))
                            IconButton(
                                onClick = { UpdateManager.dismissUpdate() },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Закрыть",
                                    tint = CinemaTextGray,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                if (isExpanded && updateState is UpdateState.UpdateAvailable) {
                    val changelog = (updateState as UpdateState.UpdateAvailable).changelog
                    if (!changelog.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        androidx.compose.foundation.lazy.LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 120.dp)
                                .background(CinemaBlack.copy(alpha = 0.3f), MaterialTheme.shapes.small)
                                .padding(8.dp)
                        ) {
                            item {
                                Text(
                                    text = changelog,
                                    color = CinemaTextGray,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
