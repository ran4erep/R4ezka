package com.example.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.UpdateManager
import com.example.data.UpdateState
import com.example.ui.haptics.HapticEngine
import com.example.ui.haptics.HapticType
import com.example.ui.theme.*
import com.example.ui.tv.requestFocusSafe
import com.example.ui.tv.tvFocusCursor
import kotlinx.coroutines.launch

/**
 * Высокопроизводительная плашка уведомления о наличии обновления приложения.
 *
 * Архитектурные особенности:
 * 1. На мобильных устройствах (isTvMode = false):
 *    - Полностью сохраняет оригинальное сенсорное поведение и размеры без изменений.
 * 2. На телевизорах (isTvMode = true):
 *    - Располагается ИСКЛЮЧИТЕЛЬНО в основном окне (не налезает на боковое меню).
 *    - При нажатии на кнопку обновления в сайдбаре курсор переходит на плашку
 *      (на кнопку действия actionFocusRequester) без немедленного запуска скачивания.
 *    - Пользователь с пульта D-Pad может:
 *      * стрелкой влево перейти к кнопке "Подробнее" и развернуть/свернуть описание,
 *      * нажать "Обновить", чтобы начать скачивание,
 *      * стрелкой вправо перейти к кнопке "Закрыть", чтобы закрыть плашку,
 *      * стрелкой влево с плашки вернуться обратно в боковое меню.
 * 3. Нулевой оверхед на CPU при отсутствии обновлений (мгновенный возврат при UpdateState.Idle).
 */
@Composable
fun UpdateBanner(
    isBottomBarVisible: Boolean = false,
    isTvMode: Boolean = false,
    actionFocusRequester: FocusRequester? = null,
    onNavigateLeftToSidebar: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val updateState by UpdateManager.updateState.collectAsState()
    if (updateState is UpdateState.Idle) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isExpanded by remember { mutableStateOf(false) }

    val detailsButtonRequester = remember { FocusRequester() }
    val effectiveActionRequester = actionFocusRequester ?: remember { FocusRequester() }
    val closeButtonRequester = remember { FocusRequester() }

    // По нажатию кнопки "Назад" на пульте, если описание развёрнуто — сворачиваем его
    if (isTvMode && isExpanded) {
        BackHandler {
            isExpanded = false
            effectiveActionRequester.requestFocusSafe()
        }
    }

    val bottomPadding = when {
        isTvMode -> 16.dp
        isBottomBarVisible -> 88.dp
        else -> 16.dp
    }

    LaunchedEffect(updateState) {
        val state = updateState
        if (state is UpdateState.ReadyToInstall) {
            UpdateManager.installApk(context, state.apkFile)
        }
    }

    val outerModifier = if (isTvMode) {
        modifier
            .fillMaxWidth()
            .widthIn(max = 760.dp)
            .padding(horizontal = 20.dp)
            .padding(bottom = bottomPadding)
    } else {
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = bottomPadding)
            .navigationBarsPadding()
    }

    Box(modifier = outerModifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("update_banner"),
            shape = if (isTvMode) RoundedCornerShape(14.dp) else MaterialTheme.shapes.medium,
            color = CinemaDark,
            border = BorderStroke(1.dp, CinemaPrimary.copy(alpha = if (isTvMode) 0.65f else 0.5f)),
            shadowElevation = if (isTvMode) 8.dp else 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (isTvMode) 16.dp else 14.dp, vertical = if (isTvMode) 10.dp else 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Информационный блок слева (иконка, версия, описание, прогресс)
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.SystemUpdate,
                            contentDescription = "Обновление",
                            tint = CinemaPrimary,
                            modifier = Modifier.size(if (isTvMode) 26.dp else 24.dp)
                        )
                        Spacer(modifier = Modifier.width(if (isTvMode) 12.dp else 10.dp))
                        Column {
                            when (val state = updateState) {
                                is UpdateState.UpdateAvailable -> {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "Обновление: ${state.latestVersion}",
                                            color = CinemaTextWhite,
                                            fontSize = if (isTvMode) 14.sp else 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        if (!state.changelog.isNullOrBlank()) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            TvFocusableIconButton(
                                                isTvMode = isTvMode,
                                                focusRequester = detailsButtonRequester,
                                                onNavigateLeft = onNavigateLeftToSidebar,
                                                onNavigateRight = { effectiveActionRequester.requestFocusSafe() },
                                                onClick = {
                                                    HapticEngine.get().perform(HapticType.GENTLE_TICK)
                                                    isExpanded = !isExpanded
                                                },
                                                contentDescription = if (isExpanded) "Скрыть описание" else "Развернуть описание",
                                                modifier = Modifier.size(if (isTvMode) 28.dp else 24.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                                    contentDescription = null,
                                                    tint = CinemaPrimary,
                                                    modifier = Modifier.size(if (isTvMode) 18.dp else 16.dp)
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
                                        fontSize = if (isTvMode) 14.sp else 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    LinearProgressIndicator(
                                        progress = { if (state.progress >= 0f) state.progress else 0f },
                                        modifier = Modifier.fillMaxWidth(0.9f),
                                        color = CinemaPrimary,
                                        trackColor = CinemaTextGray.copy(alpha = 0.3f),
                                        drawStopIndicator = {}
                                    )
                                }
                                is UpdateState.ReadyToInstall -> {
                                    Text(
                                        text = "Готово к установке",
                                        color = CinemaTextWhite,
                                        fontSize = if (isTvMode) 14.sp else 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                is UpdateState.Error -> {
                                    Text(
                                        text = state.message,
                                        color = MaterialTheme.colorScheme.error,
                                        fontSize = if (isTvMode) 12.sp else 11.sp,
                                        maxLines = 1
                                    )
                                }
                                else -> {}
                            }
                        }
                    }

                    // Блок кнопок действий справа
                    val hasChangelog = (updateState as? UpdateState.UpdateAvailable)?.changelog?.isNotBlank() == true

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when (val state = updateState) {
                            is UpdateState.UpdateAvailable -> {
                                TvActionButton(
                                    text = "Обновить",
                                    isTvMode = isTvMode,
                                    focusRequester = effectiveActionRequester,
                                    onNavigateLeft = {
                                        if (hasChangelog) {
                                            detailsButtonRequester.requestFocusSafe()
                                        } else {
                                            onNavigateLeftToSidebar?.invoke()
                                        }
                                    },
                                    onNavigateRight = {
                                        closeButtonRequester.requestFocusSafe()
                                    },
                                    onClick = {
                                        HapticEngine.get().perform(HapticType.SOFT_CLICK)
                                        scope.launch {
                                            UpdateManager.startDownload(context, state.downloadUrl)
                                        }
                                    }
                                )
                            }
                            is UpdateState.ReadyToInstall -> {
                                TvActionButton(
                                    text = "Установить",
                                    isTvMode = isTvMode,
                                    focusRequester = effectiveActionRequester,
                                    onNavigateLeft = onNavigateLeftToSidebar,
                                    onNavigateRight = null,
                                    onClick = {
                                        HapticEngine.get().perform(HapticType.CONFIRM)
                                        UpdateManager.installApk(context, state.apkFile)
                                    }
                                )
                            }
                            is UpdateState.Error -> {
                                if (isTvMode) {
                                    TvActionButton(
                                        text = "ОК",
                                        isTvMode = true,
                                        focusRequester = effectiveActionRequester,
                                        onNavigateLeft = onNavigateLeftToSidebar,
                                        onNavigateRight = null,
                                        onClick = {
                                            HapticEngine.get().perform(HapticType.GENTLE_TICK)
                                            UpdateManager.dismissUpdate()
                                            onNavigateLeftToSidebar?.invoke()
                                        }
                                    )
                                } else {
                                    TextButton(
                                        onClick = {
                                            HapticEngine.get().perform(HapticType.GENTLE_TICK)
                                            UpdateManager.dismissUpdate()
                                        },
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text("ОК", color = CinemaPrimary, fontSize = 11.sp)
                                    }
                                }
                            }
                            else -> {}
                        }

                        if (updateState is UpdateState.UpdateAvailable) {
                            Spacer(modifier = Modifier.width(if (isTvMode) 8.dp else 6.dp))
                            TvFocusableIconButton(
                                isTvMode = isTvMode,
                                focusRequester = closeButtonRequester,
                                onNavigateLeft = { effectiveActionRequester.requestFocusSafe() },
                                onNavigateRight = null,
                                onClick = {
                                    HapticEngine.get().perform(HapticType.GENTLE_TICK)
                                    UpdateManager.dismissUpdate()
                                    onNavigateLeftToSidebar?.invoke()
                                },
                                contentDescription = "Закрыть плашку",
                                modifier = Modifier.size(if (isTvMode) 32.dp else 28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                    tint = CinemaTextGray,
                                    modifier = Modifier.size(if (isTvMode) 18.dp else 16.dp)
                                )
                            }
                        }
                    }
                }

                // Раскрывающийся чейнджлог
                AnimatedVisibility(
                    visible = isExpanded && updateState is UpdateState.UpdateAvailable,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    val availableState = updateState as? UpdateState.UpdateAvailable
                    val changelog = availableState?.changelog
                    if (!changelog.isNullOrBlank()) {
                        val configuration = LocalConfiguration.current
                        val maxHeightDp = if (isTvMode) 220.dp else (configuration.screenHeightDp * 0.5f).dp
                        val scrollState = rememberScrollState()

                        Column {
                            Spacer(modifier = Modifier.height(10.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = if (isTvMode) 120.dp else 180.dp, max = maxHeightDp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(CinemaBlack.copy(alpha = 0.5f))
                                    .border(1.dp, CinemaCard.copy(alpha = 0.8f), RoundedCornerShape(10.dp))
                                    .padding(4.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .verticalScroll(scrollState)
                                        .padding(start = 10.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
                                ) {
                                    Text(
                                        text = "История изменений:",
                                        color = CinemaPrimary,
                                        fontSize = if (isTvMode) 12.sp else 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(bottom = 6.dp)
                                    )
                                    Text(
                                        text = changelog,
                                        color = CinemaTextWhite.copy(alpha = 0.9f),
                                        fontSize = if (isTvMode) 13.sp else 12.sp,
                                        lineHeight = if (isTvMode) 18.sp else 17.sp
                                    )
                                }

                                // Видимый скроллбар
                                val isScrollable = scrollState.maxValue > 0
                                if (isScrollable) {
                                    val viewPortHeightPx = scrollState.viewportSize.toFloat()
                                    val totalHeightPx = (scrollState.maxValue + scrollState.viewportSize).toFloat()
                                    val thumbFraction = if (totalHeightPx > 0f) (viewPortHeightPx / totalHeightPx).coerceIn(0.12f, 0.9f) else 1f
                                    val scrollFraction = if (scrollState.maxValue > 0) scrollState.value.toFloat() / scrollState.maxValue.toFloat() else 0f

                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.CenterEnd)
                                            .matchParentSize()
                                            .padding(vertical = 4.dp, horizontal = 2.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.CenterEnd)
                                                .fillMaxHeight()
                                                .width(4.dp)
                                                .background(CinemaMuted.copy(alpha = 0.25f), CircleShape)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .fillMaxHeight(thumbFraction)
                                                    .align(
                                                        androidx.compose.ui.BiasAlignment(
                                                            horizontalBias = 0f,
                                                            verticalBias = (scrollFraction * 2f) - 1f
                                                        )
                                                    )
                                                    .background(CinemaPrimary, CircleShape)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Кнопка действия (Обновить / Установить / ОК) с поддержкой ТВ-фокуса и навигации D-Pad.
 */
@Composable
private fun TvActionButton(
    text: String,
    isTvMode: Boolean,
    focusRequester: FocusRequester? = null,
    onNavigateLeft: (() -> Unit)?,
    onNavigateRight: (() -> Unit)?,
    onClick: () -> Unit
) {
    if (isTvMode) {
        var isFocused by remember { mutableStateOf(false) }
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isFocused) CinemaPrimary else CinemaPrimary.copy(alpha = 0.85f)
            ),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            modifier = Modifier
                .height(34.dp)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { isFocused = it.isFocused }
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.nativeKeyEvent.keyCode) {
                            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                                if (onNavigateLeft != null) {
                                    onNavigateLeft()
                                    true
                                } else false
                            }
                            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                                if (onNavigateRight != null) {
                                    onNavigateRight()
                                    true
                                } else false
                            }
                            else -> false
                        }
                    } else false
                }
                .tvFocusCursor(
                    isFocused = isFocused,
                    focusedBorderColor = CinemaPrimary,
                    shape = RoundedCornerShape(8.dp),
                    focusedBorderWidth = 2.dp
                )
        ) {
            Text(
                text = text,
                color = CinemaTextWhite,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    } else {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = CinemaPrimary),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier = Modifier.height(32.dp)
        ) {
            Text(text, color = CinemaTextWhite, fontSize = 11.sp)
        }
    }
}

/**
 * Иконка-кнопка (Подробнее / Закрыть) с адаптивным фокусом на ТВ.
 */
@Composable
private fun TvFocusableIconButton(
    isTvMode: Boolean,
    focusRequester: FocusRequester? = null,
    onNavigateLeft: (() -> Unit)?,
    onNavigateRight: (() -> Unit)?,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    if (isTvMode) {
        var isFocused by remember { mutableStateOf(false) }
        IconButton(
            onClick = onClick,
            modifier = modifier
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { isFocused = it.isFocused }
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.nativeKeyEvent.keyCode) {
                            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                                if (onNavigateLeft != null) {
                                    onNavigateLeft()
                                    true
                                } else false
                            }
                            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                                if (onNavigateRight != null) {
                                    onNavigateRight()
                                    true
                                } else false
                            }
                            else -> false
                        }
                    } else false
                }
                .tvFocusCursor(
                    isFocused = isFocused,
                    focusedBorderColor = CinemaPrimary,
                    shape = CircleShape,
                    focusedBorderWidth = 2.dp
                )
        ) {
            content()
        }
    } else {
        IconButton(
            onClick = onClick,
            modifier = modifier
        ) {
            content()
        }
    }
}
