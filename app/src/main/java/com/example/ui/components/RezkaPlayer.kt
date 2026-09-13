package com.example.ui.components

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.os.Build
import android.util.Log
import android.util.Rational
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationEndReason
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.R
import com.example.data.RezkaService
import com.example.data.StreamUrl
import com.example.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class SeekSide { NONE, LEFT, RIGHT }

/**
 * Режимы масштабирования видео (ExoPlayer AspectRatioFrameLayout)
 */
enum class VideoResizeMode(val mode: Int, val title: String, val shortLabel: String) {
    FIT(AspectRatioFrameLayout.RESIZE_MODE_FIT, "По размеру видео (оригинал)", "100%"),
    ZOOM(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, "Заполнение экрана (без полос)", "Заполнить"),
    FILL(AspectRatioFrameLayout.RESIZE_MODE_FILL, "Растянуть на весь экран", "Растянуть")
}

@OptIn(UnstableApi::class)
@SuppressLint("SourceLockedOrientationActivity")
@Composable
fun RezkaPlayer(
    title: String,
    subtitle: String,
    streams: List<StreamUrl>,
    initialQualityIndex: Int = 0,
    startPositionMs: Long = 0L,
    isSeries: Boolean = false,
    hasPreviousEpisode: Boolean = false,
    hasNextEpisode: Boolean = false,
    autoNextEpisode: Boolean = true,
    onPreviousEpisode: (() -> Unit)? = null,
    onNextEpisode: (() -> Unit)? = null,
    onBack: () -> Unit,
    onProgressUpdate: (positionMs: Long, durationMs: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (streams.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(CinemaBlack),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = null,
                    tint = CinemaPrimary,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("Ссылки на видео не найдены", color = CinemaTextWhite, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = CinemaPrimary)) {
                    Text("Назад")
                }
            }
        }
        return
    }

    val context = LocalContext.current
    val view = LocalView.current
    val activity = context as? Activity
    val window = activity?.window

    // Floating (PiP) Window state
    var isFloating by remember { mutableStateOf(false) }

    // Lock Screen state: touches are blocked until user holds lock icon for 2 seconds
    var isScreenLocked by remember { mutableStateOf(false) }
    var showLockOverlay by remember { mutableStateOf(false) }
    var lockOverlayInteractionKey by remember { mutableIntStateOf(0) }
    var unlockHoldProgress by remember { mutableFloatStateOf(0f) }
    var isHoldingUnlock by remember { mutableStateOf(false) }
    var screenNotificationMessage by remember { mutableStateOf<String?>(null) }

    // Video Resize (Stretch) mode: FIT -> ZOOM -> FILL
    var currentResizeMode by remember { mutableStateOf(VideoResizeMode.FIT) }

    // Playback speed state (supports 1.5x, 2.0x, etc.)
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }

    // Lock orientation & fullscreen in full mode; return to normal in floating mode
    DisposableEffect(activity, window, isFloating) {
        val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        
        if (isFloating) {
            // Floating mini-player mode: allow normal orientation and show status/nav bars
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        } else {
            // Fullscreen player: lock to sensor landscape & hide bars
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
            }
        }

        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        onDispose {
            activity?.requestedOrientation = originalOrientation
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    var preferredQualityName by remember { mutableStateOf<String?>(null) }
    var selectedStreamIndex by remember { mutableStateOf(initialQualityIndex.coerceIn(0, streams.lastIndex)) }
    val currentStream = streams.getOrElse(selectedStreamIndex) { streams.first() }

    // Error and Fallback tracking state
    var playerErrorMessage by remember { mutableStateOf<String?>(null) }
    var triedDirectMp4 by remember { mutableStateOf(false) }
    var backupAttemptIndex by remember { mutableStateOf(0) }
    var currentPlayingUrl by remember { mutableStateOf("") }

    // Auto-play next episode with circular timer state (zero CPU overhead engine)
    var showAutoNextCountdown by remember { mutableStateOf(false) }
    var isAutoNextDismissed by remember { mutableStateOf(false) }
    val autoNextProgressAnim = remember { Animatable(1f) }
    val remainingSeconds by remember {
        derivedStateOf {
            ceil(autoNextProgressAnim.value * 5f).toInt().coerceIn(1, 5)
        }
    }

    val activeMirror = RezkaService.currentBaseUrl

    // High-performance hardware-accelerated ExoPlayer instance
    val exoPlayer = remember {
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setEnableDecoderFallback(true)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000, // minBufferMs
                50_000, // maxBufferMs
                2_500,  // bufferForPlaybackMs
                5_000   // bufferForPlaybackAfterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(RezkaService.USER_AGENT)
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to "$activeMirror/",
                    "Accept" to "*/*"
                )
            )
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)

        val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredVideoMimeType(MimeTypes.VIDEO_H264)
                    .setForceHighestSupportedBitrate(true)
            )
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(httpDataSourceFactory)

        ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build().apply {
                playWhenReady = true
            }
    }

    // Helper to build proper MediaItem with correct container MIME type
    fun buildMediaItem(rawUrl: String): MediaItem {
        val uri = rawUrl.trim()
        val builder = MediaItem.Builder().setUri(uri)
        if (uri.contains(".m3u8") || uri.contains(":hls:manifest.m3u8")) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        } else if (uri.endsWith(".mp4") || uri.contains(".mp4?")) {
            builder.setMimeType(MimeTypes.APPLICATION_MP4)
        }
        return builder.build()
    }

    // Player States
    var isPlaying by remember { mutableStateOf(true) }
    var playbackState by remember { mutableIntStateOf(Player.STATE_IDLE) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var totalDuration by remember { mutableLongStateOf(0L) }
    var isBuffering by remember { mutableStateOf(false) }

    // Function to load and play given URL safely
    fun playStreamUrl(urlToPlay: String, targetStartPos: Long? = null) {
        if (urlToPlay.isEmpty()) return
        currentPlayingUrl = urlToPlay
        playerErrorMessage = null
        val startFrom = targetStartPos ?: startPositionMs
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        try {
            exoPlayer.setMediaItem(buildMediaItem(urlToPlay))
            exoPlayer.prepare()
            if (startFrom > 0) {
                exoPlayer.seekTo(startFrom)
                currentPosition = startFrom
            } else {
                exoPlayer.seekTo(0L)
                currentPosition = 0L
            }
            if (playbackSpeed != 1.0f) {
                exoPlayer.setPlaybackSpeed(playbackSpeed)
            }
            exoPlayer.play()
        } catch (e: Exception) {
            Log.e("RezkaPlayer", "Error loading media item: $urlToPlay", e)
        }
    }

    // Load stream when streams, subtitle or startPositionMs changes (e.g. episode switch)
    LaunchedEffect(streams, subtitle, startPositionMs) {
        showAutoNextCountdown = false
        isAutoNextDismissed = false
        val targetIndex = if (preferredQualityName != null) {
            val match = streams.indexOfFirst { it.quality.equals(preferredQualityName, ignoreCase = true) }
            if (match >= 0) match else initialQualityIndex.coerceIn(0, streams.lastIndex)
        } else {
            initialQualityIndex.coerceIn(0, streams.lastIndex)
        }
        selectedStreamIndex = targetIndex
        triedDirectMp4 = false
        backupAttemptIndex = 0
        playStreamUrl(streams[selectedStreamIndex].url, targetStartPos = startPositionMs)
    }

    // High-performance hardware-accelerated 5-second countdown for auto-playing next episode
    LaunchedEffect(showAutoNextCountdown) {
        if (showAutoNextCountdown) {
            autoNextProgressAnim.snapTo(1f)
            try {
                val animResult = autoNextProgressAnim.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 5000, easing = LinearEasing)
                )
                if (animResult.endReason == AnimationEndReason.Finished) {
                    showAutoNextCountdown = false
                    onNextEpisode?.invoke()
                }
            } catch (e: CancellationException) {
                // Countdown cancelled by user interaction
            }
        }
    }

    // Register Player listeners with robust automatic fallback on network/codec error
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing && showAutoNextCountdown) {
                    showAutoNextCountdown = false
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    totalDuration = exoPlayer.duration.coerceAtLeast(0L)
                    playerErrorMessage = null
                    if (playbackSpeed != 1.0f) {
                        exoPlayer.setPlaybackSpeed(playbackSpeed)
                    }
                } else if (state == Player.STATE_ENDED) {
                    if (isSeries && hasNextEpisode && autoNextEpisode && !isAutoNextDismissed && onNextEpisode != null) {
                        showAutoNextCountdown = true
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w("RezkaPlayer", "Player error: code=${error.errorCode}, name=${error.errorCodeName}, msg=${error.message}")

                // Auto-fallback 1: If HLS manifest failed and direct MP4 exists, try direct MP4
                if (!triedDirectMp4 && currentStream.directMp4Url.isNotEmpty() && currentStream.directMp4Url != currentPlayingUrl) {
                    Log.d("RezkaPlayer", "Auto-switching to direct MP4 fallback: ${currentStream.directMp4Url}")
                    triedDirectMp4 = true
                    playStreamUrl(currentStream.directMp4Url, targetStartPos = exoPlayer.currentPosition)
                    return
                }

                // Auto-fallback 2: If backup CDN mirror links exist, try next mirror
                if (backupAttemptIndex < currentStream.backupUrls.size) {
                    val backupUrl = currentStream.backupUrls[backupAttemptIndex]
                    backupAttemptIndex++
                    Log.d("RezkaPlayer", "Auto-switching to backup CDN link: $backupUrl")
                    playStreamUrl(backupUrl, targetStartPos = exoPlayer.currentPosition)
                    return
                }

                // Auto-fallback 3: If alternate quality stream exists in the list, auto-try next stream
                if (selectedStreamIndex + 1 < streams.size) {
                    val nextIndex = selectedStreamIndex + 1
                    Log.i("RezkaPlayer", "Auto-switching to next stream quality: ${streams[nextIndex].quality}")
                    selectedStreamIndex = nextIndex
                    playStreamUrl(streams[nextIndex].url, targetStartPos = exoPlayer.currentPosition)
                    return
                }

                // Auto-fallback error message
                val errorDesc = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Сервер потока вернул ошибку (403/404). Видеосервер недоступен или заблокирован провайдером."
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "Не удалось подключиться к видеосерверу CDN."
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "Не удалось распознать формат видеопотока."
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "Аппаратный декодер не поддерживает данный профиль видео."
                    else -> error.localizedMessage ?: "Сбой воспроизведения видео"
                }
                playerErrorMessage = errorDesc
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    // Release player on dispose & report final progress
    DisposableEffect(exoPlayer) {
        onDispose {
            try {
                if (exoPlayer.currentPosition > 0) {
                    onProgressUpdate(exoPlayer.currentPosition, exoPlayer.duration)
                }
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                exoPlayer.release()
            } catch (e: Exception) {
                Log.e("RezkaPlayer", "Error releasing ExoPlayer", e)
            }
        }
    }

    // Optimized Progress Poller
    var lastSavedPosMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isPlaying, playbackState) {
        while (isPlaying && playbackState == Player.STATE_READY) {
            currentPosition = exoPlayer.currentPosition
            totalDuration = exoPlayer.duration

            if (kotlin.math.abs(currentPosition - lastSavedPosMs) >= 10_000L) {
                lastSavedPosMs = currentPosition
                onProgressUpdate(currentPosition, totalDuration)
            }
            delay(500)
        }
    }

    // Controller Visibility State & Interaction Key
    var showControls by remember { mutableStateOf(true) }
    var controlsInteractionKey by remember { mutableIntStateOf(0) }

    // Multi-tap continuous seek accumulation state
    var activeSeekSide by remember { mutableStateOf(SeekSide.NONE) }
    var accumulatedSeekSeconds by remember { mutableIntStateOf(0) }

    LaunchedEffect(showControls, isPlaying, controlsInteractionKey, isScreenLocked) {
        if (showControls && isPlaying && playerErrorMessage == null && !isScreenLocked) {
            delay(4000)
            showControls = false
        }
    }

    // Auto-dismiss lock overlay indicator after inactivity
    LaunchedEffect(showLockOverlay, lockOverlayInteractionKey, isHoldingUnlock) {
        if (showLockOverlay && !isHoldingUnlock) {
            delay(3500)
            showLockOverlay = false
        }
    }

    // Auto-dismiss on-screen banner notifications (e.g. "Масштаб: Заполнение", "Экран разблокирован")
    LaunchedEffect(screenNotificationMessage) {
        if (screenNotificationMessage != null) {
            delay(2000)
            screenNotificationMessage = null
        }
    }

    // Floating Window Geometry state
    val density = LocalDensity.current
    var floatingWidthDp by remember { mutableFloatStateOf(260f) }
    var floatingOffsetX by remember { mutableFloatStateOf(32f) }
    var floatingOffsetY by remember { mutableFloatStateOf(96f) }
    var showFloatingControls by remember { mutableStateOf(false) }

    // Auto-hide floating mini-player overlay buttons
    LaunchedEffect(showFloatingControls) {
        if (showFloatingControls) {
            delay(3500)
            showFloatingControls = false
        }
    }

    // Root layout using BoxWithConstraints for responsive screen bounding
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .testTag("rezka_player_container")
    ) {
        val maxAvailableWidthDp = maxWidth.value
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val screenHeightPx = with(density) { maxHeight.toPx() }

        // --- FLOATING MODE VIEW ---
        if (isFloating) {
            val cardWidthDp = floatingWidthDp.dp
            val cardHeightDp = (floatingWidthDp * 9f / 16f).dp
            val cardWidthPx = with(density) { cardWidthDp.toPx() }
            val cardHeightPx = with(density) { cardHeightDp.toPx() }

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            floatingOffsetX.roundToInt().coerceIn(0, max(0, (screenWidthPx - cardWidthPx).toInt())),
                            floatingOffsetY.roundToInt().coerceIn(0, max(0, (screenHeightPx - cardHeightPx).toInt()))
                        )
                    }
                    .size(width = cardWidthDp, height = cardHeightDp)
                    .shadow(16.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black)
                    .border(BorderStroke(1.5.dp, CinemaPrimary), RoundedCornerShape(14.dp))
                    .pointerInput(Unit) {
                        // Multi-touch pinch-to-zoom (resize) & smooth drag-to-move
                        detectTransformGestures { _, pan, zoom, _ ->
                            if (zoom != 1f) {
                                val newWidth = (floatingWidthDp * zoom).coerceIn(180f, min(380f, maxAvailableWidthDp))
                                floatingWidthDp = newWidth
                            }
                            if (pan.x != 0f || pan.y != 0f) {
                                val currentWidthPx = with(density) { floatingWidthDp.dp.toPx() }
                                val currentHeightPx = with(density) { (floatingWidthDp * 9f / 16f).dp.toPx() }
                                floatingOffsetX = (floatingOffsetX + pan.x).coerceIn(0f, max(0f, screenWidthPx - currentWidthPx))
                                floatingOffsetY = (floatingOffsetY + pan.y).coerceIn(0f, max(0f, screenHeightPx - currentHeightPx))
                            }
                        }
                    }
                    .testTag("floating_player_window")
            ) {
                // Media3 Player TextureView
                AndroidView(
                    factory = { ctx ->
                        (LayoutInflater.from(ctx).inflate(R.layout.item_player_view, null) as PlayerView).apply {
                            player = exoPlayer
                            useController = false
                            resizeMode = currentResizeMode.mode
                        }
                    },
                    update = { playerView ->
                        playerView.player = exoPlayer
                        playerView.resizeMode = currentResizeMode.mode
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            showFloatingControls = !showFloatingControls
                        }
                )

                // Floating mini-player compact overlay controls
                AnimatedVisibility(
                    visible = showFloatingControls,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(6.dp)
                    ) {
                        // Top bar: Expand to Fullscreen (Left), PiP & Close (Right)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    isFloating = false
                                },
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                    .testTag("floating_expand_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Fullscreen,
                                    contentDescription = "Развернуть на весь экран",
                                    tint = CinemaTextWhite,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                // System PiP button (if supported on Android 8.0+)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null) {
                                    IconButton(
                                        onClick = {
                                            try {
                                                val params = PictureInPictureParams.Builder()
                                                    .setAspectRatio(Rational(16, 9))
                                                    .build()
                                                activity.enterPictureInPictureMode(params)
                                            } catch (e: Exception) {
                                                Log.e("RezkaPlayer", "Error entering PiP", e)
                                            }
                                        },
                                        modifier = Modifier
                                            .size(32.dp)
                                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PictureInPicture,
                                            contentDescription = "Системный PiP",
                                            tint = CinemaPrimary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = onBack,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                        .testTag("floating_close_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Закрыть",
                                        tint = CinemaTextWhite,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        // Center: Play / Pause Button
                        IconButton(
                            onClick = {
                                if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .align(Alignment.Center)
                                .background(CinemaPrimary, CircleShape)
                                .testTag("floating_play_pause")
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Пауза/Воспроизведение",
                                tint = CinemaTextWhite,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Bottom Right: Resize Drag Handle
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(28.dp)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(topStart = 8.dp))
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        val delta = dragAmount.x
                                        val newW = (floatingWidthDp + delta / density.density).coerceIn(180f, min(380f, maxAvailableWidthDp))
                                        floatingWidthDp = newW
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInFull,
                                contentDescription = "Растянуть плеер",
                                tint = CinemaPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            return@BoxWithConstraints
        }

        // --- FULLSCREEN IMMERSIVE PLAYER VIEW ---
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Media3 Player View (using TextureView via item_player_view layout to avoid SurfaceView EGL errors)
            AndroidView(
                factory = { ctx ->
                    (LayoutInflater.from(ctx).inflate(R.layout.item_player_view, null) as PlayerView).apply {
                        player = exoPlayer
                        useController = false
                        resizeMode = currentResizeMode.mode
                    }
                },
                update = { playerView ->
                    playerView.player = exoPlayer
                    playerView.resizeMode = currentResizeMode.mode
                },
                onRelease = { playerView ->
                    playerView.player = null
                },
                modifier = Modifier.fillMaxSize()
            )

            // Visual Continuous Multi-Tap Seek Indicators (Left / Right)
            AnimatedVisibility(
                visible = activeSeekSide == SeekSide.LEFT && !isScreenLocked,
                enter = fadeIn(animationSpec = tween(100)) + scaleIn(initialScale = 0.8f),
                exit = fadeOut(animationSpec = tween(250)),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 56.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.8f))
                        .border(1.5.dp, CinemaPrimary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FastRewind,
                            contentDescription = "Перемотка назад",
                            tint = CinemaPrimary,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "-$accumulatedSeekSeconds сек",
                            color = CinemaTextWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = activeSeekSide == SeekSide.RIGHT && !isScreenLocked,
                enter = fadeIn(animationSpec = tween(100)) + scaleIn(initialScale = 0.8f),
                exit = fadeOut(animationSpec = tween(250)),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 56.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.8f))
                        .border(1.5.dp, CinemaPrimary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FastForward,
                            contentDescription = "Перемотка вперед",
                            tint = CinemaPrimary,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "+$accumulatedSeekSeconds сек",
                            color = CinemaTextWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Central On-screen Notification Banner (e.g. Video Scaling Mode, Screen Lock / Unlock)
            AnimatedVisibility(
                visible = screenNotificationMessage != null,
                enter = fadeIn() + scaleIn(initialScale = 0.85f),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 70.dp)
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.82f),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, CinemaPrimary.copy(alpha = 0.6f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = when {
                                screenNotificationMessage?.contains("разблокирован") == true -> Icons.Default.LockOpen
                                screenNotificationMessage?.contains("заблокирован") == true -> Icons.Default.Lock
                                currentResizeMode == VideoResizeMode.FIT -> Icons.Default.FitScreen
                                currentResizeMode == VideoResizeMode.ZOOM -> Icons.Default.Crop
                                else -> Icons.Default.ZoomOutMap
                            },
                            contentDescription = null,
                            tint = CinemaPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = screenNotificationMessage ?: "",
                            color = CinemaTextWhite,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Gesture detector overlay:
            // When screen is locked: single-tap wakes up unlock hold indicator, but blocks all playback/seek gestures!
            // When screen is unlocked: enables double-tap seek and single-tap controls toggle
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(isScreenLocked) {
                        if (isScreenLocked) {
                            detectTapGestures(
                                onTap = {
                                    showLockOverlay = true
                                    lockOverlayInteractionKey++
                                }
                            )
                        } else {
                            coroutineScope {
                                var lastTapTime = 0L
                                var lastTapIsLeft = false
                                var singleTapJob: kotlinx.coroutines.Job? = null
                                var dismissJob: kotlinx.coroutines.Job? = null
                                var currentSide = SeekSide.NONE
                                var accumulatedSec = 0

                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val isLeft = down.position.x < size.width / 2f

                                    val up = waitForUpOrCancellation()
                                    if (up != null) {
                                        val now = System.currentTimeMillis()
                                        val delta = now - lastTapTime
                                        val tappedSide = if (isLeft) SeekSide.LEFT else SeekSide.RIGHT

                                        if (currentSide != SeekSide.NONE && currentSide == tappedSide) {
                                            // 3rd, 4th, 5th, ... continuous taps while badge is active
                                            singleTapJob?.cancel()
                                            accumulatedSec += 10
                                            accumulatedSeekSeconds = accumulatedSec

                                            val seekDelta = 10_000L
                                            val currentPos = exoPlayer.currentPosition
                                            val newPos = if (isLeft) {
                                                (currentPos - seekDelta).coerceAtLeast(0L)
                                            } else {
                                                val dur = exoPlayer.duration.coerceAtLeast(0L)
                                                if (dur > 0) (currentPos + seekDelta).coerceAtMost(dur) else (currentPos + seekDelta)
                                            }
                                            exoPlayer.seekTo(newPos)
                                            currentPosition = newPos

                                            lastTapTime = now
                                            lastTapIsLeft = isLeft

                                            dismissJob?.cancel()
                                            dismissJob = launch {
                                                delay(850)
                                                currentSide = SeekSide.NONE
                                                accumulatedSec = 0
                                                activeSeekSide = SeekSide.NONE
                                                accumulatedSeekSeconds = 0
                                            }
                                        } else if (delta < 380 && lastTapIsLeft == isLeft) {
                                            // 2nd tap: activate double-tap seek!
                                            singleTapJob?.cancel()
                                            currentSide = tappedSide
                                            accumulatedSec = 10
                                            activeSeekSide = tappedSide
                                            accumulatedSeekSeconds = 10

                                            val seekDelta = 10_000L
                                            val currentPos = exoPlayer.currentPosition
                                            val newPos = if (isLeft) {
                                                (currentPos - seekDelta).coerceAtLeast(0L)
                                            } else {
                                                val dur = exoPlayer.duration.coerceAtLeast(0L)
                                                if (dur > 0) (currentPos + seekDelta).coerceAtMost(dur) else (currentPos + seekDelta)
                                            }
                                            exoPlayer.seekTo(newPos)
                                            currentPosition = newPos

                                            lastTapTime = now
                                            lastTapIsLeft = isLeft

                                            dismissJob?.cancel()
                                            dismissJob = launch {
                                                delay(850)
                                                currentSide = SeekSide.NONE
                                                accumulatedSec = 0
                                                activeSeekSide = SeekSide.NONE
                                                accumulatedSeekSeconds = 0
                                            }
                                        } else {
                                            // 1st tap: wait briefly in case of second tap, then toggle controls
                                            lastTapTime = now
                                            lastTapIsLeft = isLeft
                                            singleTapJob?.cancel()
                                            singleTapJob = launch {
                                                delay(260)
                                                if (currentSide == SeekSide.NONE) {
                                                    showControls = !showControls
                                                    controlsInteractionKey++
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
            )

            // Buffering Indicator
            if (isBuffering && playerErrorMessage == null) {
                CircularProgressIndicator(
                    color = CinemaPrimary,
                    modifier = Modifier
                        .size(60.dp)
                        .align(Alignment.Center)
                )
            }

            // Error Banner Overlay
            playerErrorMessage?.let { errMsg ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.85f))
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CinemaDark),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.widthIn(max = 480.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudOff,
                                contentDescription = null,
                                tint = CinemaPrimary,
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Не удалось запустить видео",
                                color = CinemaTextWhite,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = errMsg,
                                color = CinemaTextGray,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = {
                                        playerErrorMessage = null
                                        playStreamUrl(currentStream.url)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = CinemaPrimary)
                                ) {
                                    Text("Повторить")
                                }

                                if (streams.size > 1) {
                                    Button(
                                        onClick = { showQualityDialog = true },
                                        colors = ButtonDefaults.buttonColors(containerColor = CinemaSecondary)
                                    ) {
                                        Text("Качество (${currentStream.quality})")
                                    }
                                }

                                if (currentStream.directMp4Url.isNotEmpty() && currentPlayingUrl != currentStream.directMp4Url) {
                                    Button(
                                        onClick = {
                                            triedDirectMp4 = true
                                            playStreamUrl(currentStream.directMp4Url)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = CinemaSecondary)
                                    ) {
                                        Text("Прямой MP4")
                                    }
                                }

                                OutlinedButton(
                                    onClick = onBack,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CinemaTextWhite)
                                ) {
                                    Text("Назад")
                                }
                            }
                        }
                    }
                }
            }

            // ---- LOCKED SCREEN OVERLAY (Appears in Top-Right when touched during screen lock) ----
            if (isScreenLocked) {
                AnimatedVisibility(
                    visible = showLockOverlay || isHoldingUnlock,
                    enter = fadeIn() + scaleIn(initialScale = 0.8f),
                    exit = fadeOut() + scaleOut(targetScale = 0.8f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 24.dp, end = 24.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.testTag("player_locked_indicator")
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.8f))
                                .border(
                                    BorderStroke(
                                        2.dp,
                                        if (isHoldingUnlock) CinemaPrimary else Color.White.copy(alpha = 0.5f)
                                    ),
                                    CircleShape
                                )
                                .pointerInput(Unit) {
                                    coroutineScope {
                                        while (true) {
                                            awaitEachGesture {
                                                awaitFirstDown(requireUnconsumed = false)
                                                isHoldingUnlock = true
                                                val startTime = System.currentTimeMillis()
                                                val holdDurationMs = 2000L
                                                var unlockedSuccessfully = false

                                                val holdJob = launch {
                                                    while (true) {
                                                        val elapsed = System.currentTimeMillis() - startTime
                                                        val prog = (elapsed.toFloat() / holdDurationMs).coerceIn(0f, 1f)
                                                        unlockHoldProgress = prog
                                                        if (elapsed >= holdDurationMs) {
                                                            unlockedSuccessfully = true
                                                            isScreenLocked = false
                                                            isHoldingUnlock = false
                                                            unlockHoldProgress = 0f
                                                            showLockOverlay = false
                                                            showControls = true
                                                            screenNotificationMessage = "Экран разблокирован"
                                                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                                            break
                                                        }
                                                        delay(16) // ~60fps smooth progress ring
                                                    }
                                                }

                                                waitForUpOrCancellation()
                                                holdJob.cancel()
                                                if (!unlockedSuccessfully) {
                                                    isHoldingUnlock = false
                                                    unlockHoldProgress = 0f
                                                    lockOverlayInteractionKey++
                                                }
                                            }
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            // 2-second hold circular progress ring
                            if (isHoldingUnlock) {
                                CircularProgressIndicator(
                                    progress = { unlockHoldProgress },
                                    color = CinemaPrimary,
                                    strokeWidth = 3.5.dp,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            Icon(
                                imageVector = if (isHoldingUnlock && unlockHoldProgress > 0.85f) Icons.Default.LockOpen else Icons.Default.Lock,
                                contentDescription = "Разблокировать касания",
                                tint = if (isHoldingUnlock) CinemaPrimary else CinemaTextWhite,
                                modifier = Modifier.size(30.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Surface(
                            color = Color.Black.copy(alpha = 0.8f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, if (isHoldingUnlock) CinemaPrimary.copy(alpha = 0.5f) else Color.Transparent)
                        ) {
                            val secondsLeft = (2f - (unlockHoldProgress * 2f)).coerceAtLeast(0.1f)
                            Text(
                                text = if (isHoldingUnlock) "Держите ещё ${String.format("%.1f", secondsLeft)} сек..." else "Удерживайте 2 сек.",
                                color = if (isHoldingUnlock) CinemaPrimary else CinemaTextWhite,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // ---- MAIN FULLSCREEN CONTROLS OVERLAY (Only visible when unlocked) ----
            AnimatedVisibility(
                visible = showControls && playerErrorMessage == null && !isScreenLocked,
                enter = fadeIn() + slideInVertically { it / 10 },
                exit = fadeOut() + slideOutVertically { it / 10 },
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(WindowInsets.safeDrawing.asPaddingValues())
                ) {
                    // ---- TOP BAR ----
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                                .testTag("player_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Назад",
                                tint = CinemaTextWhite
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                color = CinemaTextWhite,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            if (subtitle.isNotEmpty()) {
                                Text(
                                    text = subtitle,
                                    color = CinemaTextGray,
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                            }
                        }

                        // Top Right Actions: Floating mini-player button & Screen Lock button
                        // (Duplicate quality badge was removed per user request)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 1. Floating mini-player button (PiP with drag & resize)
                            IconButton(
                                onClick = {
                                    isFloating = true
                                    showControls = false
                                },
                                modifier = Modifier
                                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                                    .testTag("player_pip_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PictureInPictureAlt,
                                    contentDescription = "Сделать плеер плавающим",
                                    tint = CinemaTextWhite
                                )
                            }

                            // 2. Lock screen button (locks touches until held 2 seconds)
                            IconButton(
                                onClick = {
                                    isScreenLocked = true
                                    showControls = false
                                    showLockOverlay = true
                                    screenNotificationMessage = "Экран заблокирован. Удерживайте 2 сек. для разблокировки."
                                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                },
                                modifier = Modifier
                                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                                    .testTag("player_lock_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LockOpen,
                                    contentDescription = "Заблокировать касания",
                                    tint = CinemaTextWhite
                                )
                            }
                        }
                    }

                    // ---- CENTER CONTROLS (Previous / Play-Pause / Next) ----
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(40.dp)
                    ) {
                        if (isSeries) {
                            IconButton(
                                onClick = {
                                    controlsInteractionKey++
                                    onPreviousEpisode?.invoke()
                                },
                                enabled = hasPreviousEpisode,
                                modifier = Modifier
                                    .size(56.dp)
                                    .background(
                                        if (hasPreviousEpisode) Color.Black.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.2f),
                                        CircleShape
                                    )
                                    .testTag("player_prev_episode_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SkipPrevious,
                                    contentDescription = "Предыдущая серия",
                                    tint = if (hasPreviousEpisode) CinemaTextWhite else CinemaTextGray.copy(alpha = 0.4f),
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }

                        // Play / Pause Button
                        IconButton(
                            onClick = {
                                controlsInteractionKey++
                                if (isPlaying) {
                                    exoPlayer.pause()
                                } else {
                                    exoPlayer.play()
                                }
                            },
                            modifier = Modifier
                                .size(72.dp)
                                .background(CinemaPrimary, CircleShape)
                                .testTag("player_play_pause_button")
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Воспроизведение/Пауза",
                                tint = CinemaTextWhite,
                                modifier = Modifier.size(40.dp)
                            )
                        }

                        if (isSeries) {
                            IconButton(
                                onClick = {
                                    controlsInteractionKey++
                                    onNextEpisode?.invoke()
                                },
                                enabled = hasNextEpisode,
                                modifier = Modifier
                                    .size(56.dp)
                                    .background(
                                        if (hasNextEpisode) Color.Black.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.2f),
                                        CircleShape
                                    )
                                    .testTag("player_next_episode_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SkipNext,
                                    contentDescription = "Следующая серия",
                                    tint = if (hasNextEpisode) CinemaTextWhite else CinemaTextGray.copy(alpha = 0.4f),
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }

                    // ---- BOTTOM BAR (Time, Slider, Quality, Speed, Stretch/Resize) ----
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        // Time Labels
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formatTime(currentPosition),
                                color = CinemaTextWhite,
                                fontSize = 14.sp
                            )
                            Text(
                                text = formatTime(totalDuration),
                                color = CinemaTextWhite,
                                fontSize = 14.sp
                            )
                        }

                        // Seek Slider
                        Slider(
                            value = currentPosition.toFloat(),
                            onValueChange = {
                                controlsInteractionKey++
                                currentPosition = it.toLong()
                                exoPlayer.seekTo(currentPosition)
                            },
                            valueRange = 0f..(totalDuration.toFloat().coerceAtLeast(1f)),
                            colors = SliderDefaults.colors(
                                thumbColor = CinemaPrimary,
                                activeTrackColor = CinemaPrimary,
                                inactiveTrackColor = CinemaSecondary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(24.dp)
                                .testTag("player_seek_slider")
                        )

                        // Secondary Bottom Controls
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Left side: Quality and Speed buttons
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                // Quality button
                                Button(
                                    onClick = {
                                        controlsInteractionKey++
                                        showQualityDialog = true
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.5f)),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .height(32.dp)
                                        .testTag("player_quality_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = null,
                                        tint = CinemaPrimary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = streams[selectedStreamIndex].quality,
                                        color = CinemaTextWhite,
                                        fontSize = 12.sp
                                    )
                                }

                                // Speed button (supports 1.5x and 2x)
                                val speedDisplay = if (playbackSpeed % 1.0f == 0f) "${playbackSpeed.toInt()}x" else "${playbackSpeed}x"
                                Button(
                                    onClick = {
                                        controlsInteractionKey++
                                        showSpeedDialog = true
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.5f)),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .height(32.dp)
                                        .testTag("player_speed_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Speed,
                                        contentDescription = null,
                                        tint = CinemaPrimary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = speedDisplay,
                                        color = CinemaTextWhite,
                                        fontSize = 12.sp
                                    )
                                }
                            }

                            // Right side: Video Stretch / Resize Mode button (Right bottom corner)
                            Button(
                                onClick = {
                                    controlsInteractionKey++
                                    currentResizeMode = when (currentResizeMode) {
                                        VideoResizeMode.FIT -> VideoResizeMode.ZOOM
                                        VideoResizeMode.ZOOM -> VideoResizeMode.FILL
                                        VideoResizeMode.FILL -> VideoResizeMode.FIT
                                    }
                                    screenNotificationMessage = "Масштаб: ${currentResizeMode.title}"
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.5f)),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .height(32.dp)
                                    .testTag("player_resize_button")
                            ) {
                                Icon(
                                    imageVector = when (currentResizeMode) {
                                        VideoResizeMode.FIT -> Icons.Default.FitScreen
                                        VideoResizeMode.ZOOM -> Icons.Default.Crop
                                        VideoResizeMode.FILL -> Icons.Default.ZoomOutMap
                                    },
                                    contentDescription = "Растягивание видео",
                                    tint = CinemaPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = currentResizeMode.shortLabel,
                                    color = CinemaTextWhite,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // ==========================================
        // AUTO-PLAY NEXT EPISODE COUNTDOWN OVERLAY
        // ==========================================
        AnimatedVisibility(
            visible = showAutoNextCountdown,
            enter = fadeIn(animationSpec = tween(200)) + scaleIn(initialScale = 0.9f),
            exit = fadeOut(animationSpec = tween(200)) + scaleOut(targetScale = 0.9f),
            modifier = Modifier
                .align(Alignment.Center)
                .padding(16.dp)
        ) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = CinemaDark.copy(alpha = 0.96f)),
                border = BorderStroke(1.5.dp, CinemaPrimary.copy(alpha = 0.6f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
                modifier = Modifier
                    .widthIn(max = 380.dp)
                    .fillMaxWidth(0.9f)
                    .testTag("auto_next_episode_countdown_dialog")
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Следующая серия",
                        color = CinemaTextWhite,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Автоматический запуск через $remainingSeconds сек",
                        color = CinemaTextGray,
                        fontSize = 13.sp
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // Круговой таймер на 5 секунд
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(76.dp)
                    ) {
                        CircularProgressIndicator(
                            progress = { autoNextProgressAnim.value },
                            modifier = Modifier.fillMaxSize(),
                            color = CinemaPrimary,
                            trackColor = Color.White.copy(alpha = 0.12f),
                            strokeWidth = 5.dp
                        )
                        Text(
                            text = "$remainingSeconds",
                            color = CinemaTextWhite,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Black
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                showAutoNextCountdown = false
                                isAutoNextDismissed = true
                            },
                            border = BorderStroke(1.dp, CinemaMuted),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .testTag("auto_next_cancel_button")
                        ) {
                            Text("Отмена", color = CinemaTextWhite, fontSize = 14.sp)
                        }

                        Button(
                            onClick = {
                                showAutoNextCountdown = false
                                onNextEpisode?.invoke()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CinemaPrimary),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .testTag("auto_next_play_now_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Включить", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }

    // Quality Selection Dialog
    if (showQualityDialog) {
        AlertDialog(
            onDismissRequest = { showQualityDialog = false },
            title = { Text("Качество видео", color = CinemaTextWhite) },
            containerColor = CinemaDark,
            text = {
                Column {
                    streams.forEachIndexed { index, stream ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedStreamIndex = index
                                    preferredQualityName = stream.quality
                                    playStreamUrl(stream.url, targetStartPos = exoPlayer.currentPosition)
                                    showQualityDialog = false
                                }
                                .padding(vertical = 12.dp, horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stream.quality,
                                color = if (index == selectedStreamIndex) CinemaPrimary else CinemaTextWhite,
                                fontWeight = if (index == selectedStreamIndex) FontWeight.Bold else FontWeight.Normal
                            )
                            if (index == selectedStreamIndex) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = CinemaPrimary
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQualityDialog = false }) {
                    Text("Закрыть", color = CinemaPrimary)
                }
            }
        )
    }

    // Speed Selection Dialog (with 1.5x, 2x, etc.)
    if (showSpeedDialog) {
        val availableSpeeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        tint = CinemaPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Скорость воспроизведения", color = CinemaTextWhite)
                }
            },
            containerColor = CinemaDark,
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    availableSpeeds.forEach { speed ->
                        val isSelected = speed == playbackSpeed
                        val label = when (speed) {
                            1.0f -> "1.0x (Обычная)"
                            1.5f -> "1.5x (Быстрая)"
                            2.0f -> "2.0x (Двойная)"
                            else -> "${speed}x"
                        }

                        Surface(
                            color = if (isSelected) CinemaPrimary.copy(alpha = 0.15f) else Color.Transparent,
                            shape = RoundedCornerShape(8.dp),
                            border = if (isSelected) BorderStroke(1.dp, CinemaPrimary.copy(alpha = 0.4f)) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    playbackSpeed = speed
                                    exoPlayer.setPlaybackSpeed(speed)
                                    showSpeedDialog = false
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp, horizontal = 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSelected) CinemaPrimary else CinemaTextWhite,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 14.sp
                                )
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = CinemaPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSpeedDialog = false }) {
                    Text("Закрыть", color = CinemaPrimary)
                }
            }
        )
    }
}

// Utility to format milliseconds into HH:MM:SS or MM:SS
private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600

    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

