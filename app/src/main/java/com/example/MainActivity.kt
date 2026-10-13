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
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import com.example.ui.effects.SnowfallOverlay
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CloudOff
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
import com.example.data.NavTab
import com.example.ui.RezkaViewModel
import com.example.ui.MirrorAuditUiState
import com.example.ui.components.AppHeader
import com.example.ui.components.AuthDialog
import com.example.ui.components.MirrorAuditOverlay
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
import com.example.ui.tv.LocalCardClickBoundsTracker
import com.example.BuildConfig
import com.example.ui.components.UpdateBanner
import com.example.data.UpdateManager
import com.example.data.UpdateState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import com.example.ui.theme.CinemaMuted
import com.example.ui.theme.CinemaCard
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import com.example.data.SeriesUpdateScheduler
import com.example.ui.haptics.HapticEngine
import com.example.ui.haptics.HapticType
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: RezkaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            SeriesUpdateScheduler.schedulePeriodicCheck(this)
            viewModel.triggerManualSeriesCheck(this)
        } catch (_: Exception) {}
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        try {
            viewModel.handleIncomingIntent(intent)
        } catch (_: Exception) {}

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

private val MovieCardExpandEasing = CubicBezierEasing(0.06f, 0.72f, 0.12f, 1.0f)
private val MovieCardCollapseEasing = CubicBezierEasing(0.36f, 0.0f, 0.75f, 0.15f)
private val DetailScaleEasing = MovieCardExpandEasing

@Composable
fun MainContent(viewModel: RezkaViewModel = viewModel()) {
    val currentTab by viewModel.currentNavTab.collectAsState()
    val navigationStack by viewModel.navigationStack.collectAsState()
    val isSettingsOpen by viewModel.isSettingsOpen.collectAsState()

    // Безопасный метод извлечения экрана из стека (сохраняется в ViewModel при переворотах телефона)
    val popBackStack = {
        viewModel.popScreen()
    }

    // Безопасный метод добавления экрана в стек с автоматической дедупликацией
    val pushToStack = { state: ScreenState ->
        viewModel.pushScreen(state)
    }

    // Предзагрузка фильма с эффектом подпрыгивания карточки перед переходом
    val onNavigateToDetailWithBounce: (RezkaItem) -> Unit = { item ->
        viewModel.selectMovieWithPreload(item) {
            pushToStack(ScreenState.Detail(item))
        }
    }

    // Переход на экран фильма специально из истории просмотров
    val onNavigateToDetailFromHistory: (RezkaItem) -> Unit = { item ->
        viewModel.selectMovieWithPreload(item) {
            pushToStack(ScreenState.Detail(item = item, fromHistory = true))
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

    val isLoggedIn by viewModel.isLoggedIn.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val currentUserAvatar by viewModel.currentUserAvatar.collectAsState()
    val mirrorAuditState by viewModel.mirrorAuditState.collectAsState()
    var showAuthDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        UpdateManager.checkForUpdates(BuildConfig.VERSION_NAME)
    }

    LaunchedEffect(isOnline) {
        if (isOnline) {
            UpdateManager.checkForUpdates(BuildConfig.VERSION_NAME)
        }
    }

    LaunchedEffect(currentTab, navigationStack.size) {
        if (currentTab == NavTab.HISTORY && navigationStack.isEmpty()) {
            viewModel.checkHistorySeriesUpdates(force = true)
        }
    }

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

    val showTvCursor = remember(context, tvModePrefString, isLandscape, isTvMode) {
        when (tvModePrefString) {
            "force_tv" -> true
            "force_mobile" -> false
            else -> isTvMode
        }
    }

    if (showAuthDialog) {
        AuthDialog(
            viewModel = viewModel,
            isTvMode = isTvMode,
            onDismiss = { showAuthDialog = false }
        )
    }

    // System Back Press Handler inside Compose
    BackHandler(enabled = (mirrorAuditState !is MirrorAuditUiState.Idle) || isSettingsOpen || navigationStack.isNotEmpty() || currentTab != NavTab.FEED) {
        if (mirrorAuditState !is MirrorAuditUiState.Idle) {
            viewModel.cancelMirrorAudit()
        } else if (navigationStack.isNotEmpty()) {
            popBackStack()
        } else if (isSettingsOpen) {
            viewModel.setSettingsOpen(false)
        } else if (currentTab != NavTab.FEED) {
            viewModel.setNavTab(NavTab.FEED)
        }
    }

    var lastClickedCardBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }

    CompositionLocalProvider(
        LocalTvShowCursor provides showTvCursor,
        LocalCardClickBoundsTracker provides { bounds ->
            lastClickedCardBounds = bounds
        }
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(CinemaBlack)
        ) {
            val screenWidthPx = constraints.maxWidth.toFloat()
            val screenHeightPx = constraints.maxHeight.toFloat()

            if (isTvMode) {
                // Режим Android TV: кинематографичная анимация перетекания карточки в фильм с аппаратным GPU-ускорением
                val tvActiveTarget: Any = if (navigationStack.isNotEmpty()) navigationStack.last() else "main"

                AnimatedContent(
                    targetState = tvActiveTarget,
                    transitionSpec = {
                        val bounds = lastClickedCardBounds
                        val tvOrigin = if (bounds != null && screenWidthPx > 0f && screenHeightPx > 0f) {
                            TransformOrigin(
                                (bounds.center.x / screenWidthPx).coerceIn(0.02f, 0.98f),
                                (bounds.center.y / screenHeightPx).coerceIn(0.02f, 0.98f)
                            )
                        } else {
                            TransformOrigin(0.5f, 0.42f)
                        }
                        val tvStartScale = if (bounds != null && screenWidthPx > 0f) {
                            (bounds.width / screenWidthPx).coerceIn(0.08f, 0.85f)
                        } else {
                            0.26f
                        }

                        if (targetState is ScreenState.Detail) {
                            // Карточка фильма на ТВ увеличивается РОВНО ИЗ ТОГО МЕСТА ГДЕ ОНА НАХОДИТСЯ:
                            // Каталог остается статичным под ней (ExitTransition.None)
                            (scaleIn(
                                initialScale = tvStartScale,
                                transformOrigin = tvOrigin,
                                animationSpec = tween(520, easing = MovieCardExpandEasing)
                            ) + fadeIn(
                                animationSpec = tween(320, easing = LinearOutSlowInEasing)
                            )).togetherWith(ExitTransition.None)
                        } else if (initialState is ScreenState.Detail && targetState == "main") {
                            // Страница фильма плавно схлопывается обратно ровно в то место, где была карточка
                            EnterTransition.None.togetherWith(
                                scaleOut(
                                    targetScale = tvStartScale,
                                    transformOrigin = tvOrigin,
                                    animationSpec = tween(420, easing = MovieCardCollapseEasing)
                                ) + fadeOut(
                                    animationSpec = tween(300, easing = FastOutLinearInEasing)
                                )
                            )
                        } else if (targetState is ScreenState && initialState is ScreenState) {
                        // Переходы между карточками/подэкранами (Detail -> PersonProfile / ThematicList)
                        (slideInHorizontally(animationSpec = tween(320, easing = FastOutSlowInEasing)) { it / 3 } + scaleIn(initialScale = 0.94f) + fadeIn(animationSpec = tween(280)))
                            .togetherWith(slideOutHorizontally(animationSpec = tween(300, easing = FastOutSlowInEasing)) { -it / 3 } + scaleOut(targetScale = 0.96f) + fadeOut(animationSpec = tween(240)))
                    } else if (targetState == "settings") {
                        (slideInHorizontally(tween(300)) { it } + fadeIn(tween(250)))
                            .togetherWith(slideOutHorizontally(tween(280)) { -it / 4 } + fadeOut(tween(200)))
                    } else {
                        (fadeIn(tween(260))).togetherWith(fadeOut(tween(220)))
                    }
                },
                label = "tv_screen_transition",
                modifier = Modifier.fillMaxSize()
            ) { activeTarget ->
                val tvCornerRadius by transition.animateDp(
                    transitionSpec = {
                        tween(durationMillis = 480, easing = MovieCardExpandEasing)
                    },
                    label = "tv_card_morph_radius"
                ) { enterExitState ->
                    if (enterExitState == EnterExitState.Visible) 0.dp else 14.dp
                }

                when (activeTarget) {
                    "settings" -> {
                        SettingsScreen(
                            viewModel = viewModel,
                            onBack = { viewModel.setSettingsOpen(false) },
                            onNavigateToDetail = { item ->
                                viewModel.setSettingsOpen(false)
                                onNavigateToDetailWithBounce(item)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    is ScreenState.Detail -> {
                        DetailScreen(
                            viewModel = viewModel,
                            item = activeTarget.item,
                            initialTranslatorId = activeTarget.initialTranslatorId,
                            fromHistory = activeTarget.fromHistory,
                            onBack = { popBackStack() },
                            onNavigateToThematic = { name, url ->
                                if (url.contains("/person/")) {
                                    pushToStack(ScreenState.PersonProfile(name, url))
                                } else {
                                    pushToStack(ScreenState.ThematicList(name, url))
                                }
                            },
                            onNavigateToDetail = onNavigateToDetailWithBounce,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val rPx = tvCornerRadius.toPx()
                                    if (rPx > 0.5f) {
                                        clip = true
                                        shape = RoundedCornerShape(rPx)
                                    }
                                },
                            isTvMode = true
                        )
                    }
                    is ScreenState.ThematicList -> {
                        ThematicListScreen(
                            title = activeTarget.title,
                            url = activeTarget.url,
                            onBack = { popBackStack() },
                            onNavigateToDetail = onNavigateToDetailWithBounce,
                            modifier = Modifier.fillMaxSize(),
                            isTvMode = true,
                            viewModel = viewModel
                        )
                    }
                    is ScreenState.PersonProfile -> {
                        PersonProfileScreen(
                            name = activeTarget.name,
                            url = activeTarget.url,
                            onBack = { popBackStack() },
                            onNavigateToDetail = onNavigateToDetailWithBounce,
                            modifier = Modifier.fillMaxSize(),
                            isTvMode = true,
                            viewModel = viewModel
                        )
                    }
                    else -> {
                        TvMainScreen(
                            viewModel = viewModel,
                            onNavigateToDetail = onNavigateToDetailWithBounce,
                            onNavigateToDetailFromHistory = onNavigateToDetailFromHistory,
                            onNavigateToThematic = { title, url ->
                                pushToStack(ScreenState.ThematicList(title, url))
                            },
                            isTopScreen = navigationStack.isEmpty() && !isSettingsOpen,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        } else {
            val isStackActive = navigationStack.isNotEmpty()
            val bgCatalogScale by animateFloatAsState(
                targetValue = if (isStackActive) 0.92f else 1.0f,
                animationSpec = tween(480, easing = FastOutSlowInEasing),
                label = "bg_catalog_scale"
            )
            val bgCatalogAlpha by animateFloatAsState(
                targetValue = if (isStackActive) 0.0f else 1.0f,
                animationSpec = tween(420, delayMillis = 40, easing = FastOutSlowInEasing),
                label = "bg_catalog_alpha"
            )

            Scaffold(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = bgCatalogScale
                        scaleY = bgCatalogScale
                        alpha = bgCatalogAlpha
                    },
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
                            onSettingsClick = { viewModel.setSettingsOpen(true) }
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
                        val barHeight = if (isLandscape) 58.dp else 80.dp
                        NavigationBar(
                            containerColor = CinemaDark,
                            tonalElevation = 0.dp,
                            windowInsets = WindowInsets.navigationBars,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(barHeight)
                                .testTag("bottom_nav_bar")
                        ) {
                            val feedLabel = if (isOnline) "Каталог" else "Оффлайн"
                            val feedIcon = if (isOnline) Icons.Default.Movie else Icons.Default.CloudOff

                            // 1. Feed / Catalog tab
                            NavigationBarItem(
                                selected = currentTab == NavTab.FEED,
                                onClick = {
                                    HapticEngine.get().perform(HapticType.GENTLE_TICK)
                                    viewModel.setNavTab(NavTab.FEED)
                                },
                                icon = { Icon(feedIcon, contentDescription = feedLabel) },
                                label = { Text(feedLabel, fontSize = 11.sp) },
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
                                onClick = {
                                    HapticEngine.get().perform(HapticType.GENTLE_TICK)
                                    viewModel.setNavTab(NavTab.FAVORITES)
                                },
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
                                onClick = {
                                    HapticEngine.get().perform(HapticType.GENTLE_TICK)
                                    viewModel.setNavTab(NavTab.HISTORY)
                                },
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
                                onNavigateToDetail = onNavigateToDetailWithBounce,
                                onNavigateToThematic = { title, url ->
                                    pushToStack(ScreenState.ThematicList(title, url))
                                },
                                onNavigateToSettings = { viewModel.setSettingsOpen(true) },
                                isTopScreen = navigationStack.isEmpty() && !isSettingsOpen
                            )
                        }
                        NavTab.FAVORITES -> {
                            FavoritesScreen(
                                viewModel = viewModel,
                                onNavigateToDetail = onNavigateToDetailWithBounce
                            )
                        }
                        NavTab.HISTORY -> {
                            HistoryScreen(
                                viewModel = viewModel,
                                onNavigateToDetail = onNavigateToDetailFromHistory
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
                    onBack = { viewModel.setSettingsOpen(false) },
                    onNavigateToDetail = { item ->
                        viewModel.setSettingsOpen(false)
                        onNavigateToDetailWithBounce(item)
                    }
                )
            }

            // Animated Fullscreen stack screens overlay for Mobile
            AnimatedContent(
                targetState = navigationStack.lastOrNull(),
                transitionSpec = {
                    val bounds = lastClickedCardBounds
                    val mobileOrigin = if (bounds != null && screenWidthPx > 0f && screenHeightPx > 0f) {
                        TransformOrigin(
                            (bounds.center.x / screenWidthPx).coerceIn(0.02f, 0.98f),
                            (bounds.center.y / screenHeightPx).coerceIn(0.02f, 0.98f)
                        )
                    } else {
                        TransformOrigin(0.5f, 0.36f)
                    }
                    val mobileStartScale = if (bounds != null && screenWidthPx > 0f) {
                        (bounds.width / screenWidthPx).coerceIn(0.12f, 0.85f)
                    } else {
                        0.38f
                    }

                    if (targetState != null && initialState == null) {
                        // Первый экран поверх каталога: плавное перетекание карточки в страницу фильма
                        if (targetState is ScreenState.Detail) {
                            (scaleIn(
                                initialScale = mobileStartScale,
                                transformOrigin = mobileOrigin,
                                animationSpec = tween(520, easing = MovieCardExpandEasing)
                            ) + fadeIn(
                                animationSpec = tween(320, easing = LinearOutSlowInEasing)
                            )).togetherWith(ExitTransition.None)
                        } else {
                            (slideInHorizontally(animationSpec = tween(300)) { it } + fadeIn(tween(250)))
                                .togetherWith(ExitTransition.None)
                        }
                    } else if (targetState == null && initialState != null) {
                        // Возврат на каталог (стек пуст): страница фильма схлопывается обратно в карточку и растворяется
                        if (initialState is ScreenState.Detail) {
                            EnterTransition.None.togetherWith(
                                scaleOut(
                                    targetScale = mobileStartScale,
                                    transformOrigin = mobileOrigin,
                                    animationSpec = tween(420, easing = MovieCardCollapseEasing)
                                ) + fadeOut(
                                    animationSpec = tween(300, easing = FastOutLinearInEasing)
                                )
                            )
                        } else {
                            EnterTransition.None.togetherWith(
                                slideOutHorizontally(animationSpec = tween(300)) { it } + fadeOut(tween(200))
                            )
                        }
                    } else if (targetState != null && initialState != null) {
                        // Переход между экранами внутри стека (например, Detail -> PersonProfile)
                        (slideInHorizontally(animationSpec = tween(320, easing = FastOutSlowInEasing)) { it / 3 } + scaleIn(initialScale = 0.94f) + fadeIn(animationSpec = tween(280)))
                            .togetherWith(slideOutHorizontally(animationSpec = tween(300, easing = FastOutSlowInEasing)) { -it / 3 } + scaleOut(targetScale = 0.96f) + fadeOut(animationSpec = tween(240)))
                    } else {
                        EnterTransition.None togetherWith ExitTransition.None
                    }
                },
                label = "stack_transition",
                modifier = Modifier.fillMaxSize()
            ) { topState ->
                val mobileCornerRadius by transition.animateDp(
                    transitionSpec = {
                        tween(durationMillis = 480, easing = MovieCardExpandEasing)
                    },
                    label = "mobile_card_morph_radius"
                ) { enterExitState ->
                    if (enterExitState == EnterExitState.Visible) 0.dp else 14.dp
                }

                if (topState != null) {
                    when (topState) {
                        is ScreenState.Detail -> {
                            DetailScreen(
                                viewModel = viewModel,
                                item = topState.item,
                                initialTranslatorId = topState.initialTranslatorId,
                                fromHistory = topState.fromHistory,
                                onBack = { popBackStack() },
                                onNavigateToThematic = { name, url ->
                                    if (url.contains("/person/")) {
                                        pushToStack(ScreenState.PersonProfile(name, url))
                                    } else {
                                        pushToStack(ScreenState.ThematicList(name, url))
                                    }
                                },
                                onNavigateToDetail = onNavigateToDetailWithBounce,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        val rPx = mobileCornerRadius.toPx()
                                        if (rPx > 0.5f) {
                                            clip = true
                                            shape = RoundedCornerShape(rPx)
                                        }
                                    },
                                isTvMode = false
                            )
                        }
                        is ScreenState.ThematicList -> {
                            ThematicListScreen(
                                title = topState.title,
                                url = topState.url,
                                onBack = { popBackStack() },
                                onNavigateToDetail = onNavigateToDetailWithBounce,
                                viewModel = viewModel
                            )
                        }
                        is ScreenState.PersonProfile -> {
                            PersonProfileScreen(
                                name = topState.name,
                                url = topState.url,
                                onBack = { popBackStack() },
                                onNavigateToDetail = onNavigateToDetailWithBounce,
                                viewModel = viewModel
                            )
                        }
                    }
                }
            }
            } // Close else mobile branch

            // Высокопроизводительный движок снега (автоматически засыпает при открытии видео)
            SnowfallOverlay(
                isPlayerActive = isPlayerActive,
                isTvMode = isTvMode,
                modifier = Modifier.fillMaxSize()
            )

            if (!isPlayerActive && !isTvMode) {
                UpdateBanner(
                    isBottomBarVisible = navigationStack.isEmpty() && !isSettingsOpen,
                    isTvMode = false,
                    modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                )
            }

            if (mirrorAuditState !is MirrorAuditUiState.Idle) {
                MirrorAuditOverlay(
                    state = mirrorAuditState,
                    onCancel = { viewModel.cancelMirrorAudit() },
                    onOpenSettings = {
                        viewModel.dismissMirrorAudit()
                        viewModel.setSettingsOpen(true)
                    }
                )
            }
        }
    }
}

