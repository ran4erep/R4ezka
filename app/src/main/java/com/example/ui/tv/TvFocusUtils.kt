package com.example.ui.tv

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.ui.theme.CinemaPrimary
import kotlinx.coroutines.launch

/**
 * Мерцающая/пульсирующая светящаяся рамочка вокруг выбранного элемента (ТВ-курсор).
 *
 * Оптимизация производительности:
 * 1. Анимация мерцания (InfiniteTransition) активируется СТРОГО на сфокусированном элементе.
 *    Когда фокуса нет — рендерится 0 анимаций, нагрузка на процессор = 0%.
 * 2. Двойной неоновый контур: внешняя пульсирующая рамка + внутренний яркий белый акцент
 *    гарантируют идеальную видимость курсора на любых фильмах, постерах и темных фонах.
 */
@Composable
fun Modifier.tvPulsingFocusBorder(
    isFocused: Boolean,
    focusedBorderColor: Color = CinemaPrimary,
    shape: Shape = RoundedCornerShape(12.dp),
    baseBorderWidth: Dp = 2.5.dp
): Modifier {
    if (!isFocused) return this

    val infiniteTransition = rememberInfiniteTransition(label = "tv_cursor_pulse")

    // Плавное синусоидальное мерцание яркости (альфы) рамочки
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursor_pulse_alpha"
    )

    // Динамическая пульсация толщины светового контура
    val pulseWidthExtra by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursor_pulse_width"
    )

    val currentWidth = baseBorderWidth + pulseWidthExtra.dp

    return this
        // Внешний мерцающий неоновый контур
        .border(
            width = currentWidth,
            color = focusedBorderColor.copy(alpha = pulseAlpha),
            shape = shape
        )
        // Внутренний контрастный белый световой блик для идеального выделения
        .border(
            width = 1.dp,
            color = Color.White.copy(alpha = pulseAlpha * 0.85f),
            shape = shape
        )
}

/**
 * Автономный модификатор мерцающего ТВ-курсора для любого Composable элемента.
 */
fun Modifier.tvFocusCursor(
    isFocused: Boolean,
    focusedBorderColor: Color = CinemaPrimary,
    shape: Shape = RoundedCornerShape(12.dp),
    focusedBorderWidth: Dp = 2.5.dp
): Modifier = composed {
    this.tvPulsingFocusBorder(
        isFocused = isFocused,
        focusedBorderColor = focusedBorderColor,
        shape = shape,
        baseBorderWidth = focusedBorderWidth
    )
}

/**
 * Высокопроизводительный модификатор фокуса для ТВ-интерфейса и пульта.
 * Объединяет:
 * 1. Аппаратное плавное увеличение (scale) через graphicsLayer без relayout.
 * 2. Мерцающий неоновый курсор-рамочку вокруг выбранного элемента.
 * 3. Автоматическую прокрутку списка к выбранному элементу (bringIntoView).
 * 4. Мгновенную обработку нажатий ОК (DPAD_CENTER / ENTER) с тактильным микровсплеском.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.tvFocusableItem(
    onClick: () -> Unit,
    onFocused: (() -> Unit)? = null,
    scaleFactor: Float = 1.06f,
    focusedBorderColor: Color = CinemaPrimary,
    focusedBorderWidth: Dp = 2.5.dp,
    shape: Shape = RoundedCornerShape(12.dp),
    focusRequester: FocusRequester? = null,
    lazyListState: LazyListState? = null,
    targetViewportY: Float = 220f
): Modifier = composed {
    var isFocused by remember { mutableStateOf(false) }
    var isPressed by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    var itemCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var scrollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> (scaleFactor * 0.96f).coerceAtLeast(1.0f)
            isFocused -> scaleFactor
            else -> 1.0f
        },
        animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
        label = "tv_focus_scale"
    )

    this
        .zIndex(if (isFocused) 5f else 1f)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .onGloballyPositioned { itemCoordinates = it }
        .bringIntoViewRequester(bringIntoViewRequester)
        .then(
            if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
        )
        .onFocusChanged { focusState ->
            isFocused = focusState.isFocused
            if (focusState.isFocused) {
                onFocused?.invoke()
                scrollJob?.cancel()
                scrollJob = coroutineScope.launch {
                    try {
                        if (lazyListState != null) {
                            if (itemCoordinates?.isAttached == true) {
                                val bounds = try { itemCoordinates?.boundsInWindow() } catch (_: Throwable) { null }
                                if (bounds != null) {
                                    val delta = bounds.top - targetViewportY
                                    if (kotlin.math.abs(delta) > 20f) {
                                        lazyListState.animateScrollBy(
                                            value = delta,
                                            animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing)
                                        )
                                    }
                                }
                            }
                        } else {
                            try {
                                bringIntoViewRequester.bringIntoView()
                            } catch (_: Throwable) {}
                        }
                    } catch (_: Throwable) {
                        // Поглощаем любые отмены корутин и сбои скролла для стабильности
                    }
                }
            }
        }
        .focusable()
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )
        .onKeyEvent { keyEvent ->
            when (keyEvent.nativeKeyEvent.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER,
                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (keyEvent.type == KeyEventType.KeyDown) {
                        isPressed = true
                        true
                    } else if (keyEvent.type == KeyEventType.KeyUp) {
                        isPressed = false
                        onClick()
                        true
                    } else false
                }
                else -> false
            }
        }
        .tvPulsingFocusBorder(
            isFocused = isFocused,
            focusedBorderColor = focusedBorderColor,
            shape = shape,
            baseBorderWidth = focusedBorderWidth
        )
}

/**
 * Универсальный расширитель скроллинга списков LazyListState с пульта (D-Pad).
 * Позволяет плавно прокручивать списки при нажатии стрелок вверх/вниз и PageUp/PageDown,
 * даже если фокус не установлен на конкретный элемент или достиг границы.
 */
fun Modifier.dpadScrollable(
    lazyListState: LazyListState,
    scrollStepPx: Float = 320f
): Modifier = composed {
    val coroutineScope = rememberCoroutineScope()
    this.onKeyEvent { keyEvent ->
        if (keyEvent.type == KeyEventType.KeyDown) {
            when (keyEvent.nativeKeyEvent.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                android.view.KeyEvent.KEYCODE_PAGE_DOWN -> {
                    coroutineScope.launch {
                        try {
                            lazyListState.scroll {
                                scrollBy(scrollStepPx)
                            }
                        } catch (_: Exception) {}
                    }
                    false // Не поглощаем полностью, чтобы D-Pad фокус тоже мог перемещаться
                }
                android.view.KeyEvent.KEYCODE_DPAD_UP,
                android.view.KeyEvent.KEYCODE_PAGE_UP -> {
                    coroutineScope.launch {
                        try {
                            lazyListState.scroll {
                                scrollBy(-scrollStepPx)
                            }
                        } catch (_: Exception) {}
                    }
                    false
                }
                else -> false
            }
        } else false
    }
}

/**
 * Универсальный расширитель скроллинга для ScrollState (Column с verticalScroll).
 */
fun Modifier.dpadScrollable(
    scrollState: ScrollState,
    scrollStepPx: Int = 300
): Modifier = composed {
    val coroutineScope = rememberCoroutineScope()
    this.onKeyEvent { keyEvent ->
        if (keyEvent.type == KeyEventType.KeyDown) {
            when (keyEvent.nativeKeyEvent.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                android.view.KeyEvent.KEYCODE_PAGE_DOWN -> {
                    coroutineScope.launch {
                        try {
                            scrollState.animateScrollTo(
                                (scrollState.value + scrollStepPx).coerceAtMost(scrollState.maxValue)
                            )
                        } catch (_: Exception) {}
                    }
                    false
                }
                android.view.KeyEvent.KEYCODE_DPAD_UP,
                android.view.KeyEvent.KEYCODE_PAGE_UP -> {
                    coroutineScope.launch {
                        try {
                            scrollState.animateScrollTo(
                                (scrollState.value - scrollStepPx).coerceAtLeast(0)
                            )
                        } catch (_: Exception) {}
                    }
                    false
                }
                else -> false
            }
        } else false
    }
}

/**
 * Универсальный расширитель скроллинга для LazyGridState.
 */
fun Modifier.dpadScrollable(
    lazyGridState: LazyGridState,
    scrollStepPx: Float = 350f
): Modifier = composed {
    val coroutineScope = rememberCoroutineScope()
    this.onKeyEvent { keyEvent ->
        if (keyEvent.type == KeyEventType.KeyDown) {
            when (keyEvent.nativeKeyEvent.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                android.view.KeyEvent.KEYCODE_PAGE_DOWN -> {
                    coroutineScope.launch {
                        try {
                            lazyGridState.scroll {
                                scrollBy(scrollStepPx)
                            }
                        } catch (_: Exception) {}
                    }
                    false
                }
                android.view.KeyEvent.KEYCODE_DPAD_UP,
                android.view.KeyEvent.KEYCODE_PAGE_UP -> {
                    coroutineScope.launch {
                        try {
                            lazyGridState.scroll {
                                scrollBy(-scrollStepPx)
                            }
                        } catch (_: Exception) {}
                    }
                    false
                }
                else -> false
            }
        } else false
    }
}
