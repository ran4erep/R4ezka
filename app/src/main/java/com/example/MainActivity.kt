package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
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
import com.example.ui.RezkaViewModel
import com.example.ui.components.AppHeader
import com.example.ui.components.AuthDialog
import com.example.ui.screens.CatalogScreen
import com.example.ui.screens.FavoritesScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.DetailScreen
import com.example.ui.screens.SettingsScreen
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainContent()
            }
        }
    }
}

@Composable
fun MainContent() {
    val viewModel: RezkaViewModel = viewModel()
    var currentTab by remember { mutableStateOf(NavTab.FEED) }
    var selectedItem by remember { mutableStateOf<RezkaItem?>(null) }
    var isSettingsOpen by remember { mutableStateOf(false) }

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
    BackHandler(enabled = isSettingsOpen || selectedItem != null) {
        if (selectedItem != null) {
            selectedItem = null
        } else if (isSettingsOpen) {
            isSettingsOpen = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CinemaBlack)
    ) {
        if (isTvMode && !isSettingsOpen && selectedItem == null) {
            TvMainScreen(
                viewModel = viewModel,
                onNavigateToDetail = { selectedItem = it },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    // Header bar (R4ezka, account/login, settings) persistent on every tab
                    AnimatedVisibility(
                        visible = selectedItem == null && !isSettingsOpen,
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
                        visible = selectedItem == null && !isSettingsOpen,
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
                                onNavigateToDetail = { selectedItem = it },
                                onNavigateToSettings = { isSettingsOpen = true }
                            )
                        }
                        NavTab.FAVORITES -> {
                            FavoritesScreen(
                                viewModel = viewModel,
                                onNavigateToDetail = { selectedItem = it }
                            )
                        }
                        NavTab.HISTORY -> {
                            HistoryScreen(
                                viewModel = viewModel,
                                onNavigateToDetail = { selectedItem = it }
                            )
                        }
                    }
                }
            }
        }

        // Animated Fullscreen Settings Screen overlay
        AnimatedVisibility(
            visible = isSettingsOpen && selectedItem == null,
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it }),
            modifier = Modifier.fillMaxSize()
        ) {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { isSettingsOpen = false }
            )
        }

        // Animated Fullscreen Details Screen overlay
        AnimatedVisibility(
            visible = selectedItem != null,
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it }),
            modifier = Modifier.fillMaxSize()
        ) {
            selectedItem?.let { item ->
                DetailScreen(
                    viewModel = viewModel,
                    item = item,
                    onBack = { selectedItem = null }
                )
            }
        }
    }
}
