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

enum class NavTab {
    FEED, FAVORITES, HISTORY
}

class MainActivity : ComponentActivity() {
    private val viewModel: RezkaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    val isLoggedIn by viewModel.isLoggedIn.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val currentUserAvatar by viewModel.currentUserAvatar.collectAsState()
    var showAuthDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val tvModePrefString by viewModel.tvModePreference.collectAsState()
    val isTvMode = remember(context, tvModePrefString) {
        val pref = when (tvModePrefString) {
            "force_tv" -> TvModePreference.FORCE_TV
            "force_mobile" -> TvModePreference.FORCE_MOBILE
            else -> TvModePreference.AUTO
        }
        TvDetector.shouldShowTvInterface(context, pref)
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
                                modifier = Modifier.fillMaxSize()
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
                                isTvMode = true
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
                                isTvMode = true
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
                                }
                            )
                        }
                        is ScreenState.ThematicList -> {
                            ThematicListScreen(
                                title = topState.title,
                                url = topState.url,
                                onBack = { popBackStack() },
                                onNavigateToDetail = { item ->
                                    pushToStack(ScreenState.Detail(item))
                                }
                            )
                        }
                        is ScreenState.PersonProfile -> {
                            PersonProfileScreen(
                                name = topState.name,
                                url = topState.url,
                                onBack = { popBackStack() },
                                onNavigateToDetail = { item ->
                                    pushToStack(ScreenState.Detail(item))
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
