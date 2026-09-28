package com.example.ui.tv

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.ui.theme.CinemaCard
import com.example.ui.theme.CinemaMuted
import com.example.ui.theme.CinemaPrimary
import com.example.ui.theme.CinemaTextGray
import com.example.ui.theme.CinemaTextWhite
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val LocalTvShowCursor = staticCompositionLocalOf { true }

/**
 * Высокопроизводительный мерцающий неоновый ТВ-курсор вокруг выбранного элемента.
 *
 * Архитектурные свойства:
 * 1. Обводка ложится СТРОГО от внешнего края элемента ВГЛУБЬ (inner stroke):
 *    внешний край обводки совпадает с краем элемента, а вся толщина уходит внутрь.
 * 2. Ни одного пикселя не выходит наружу, благодаря чему курсор не обрезается краями контейнеров.
 * 3. Никаких лишних внутренних белых полос: цвет строго соответствует focusedBorderColor.
 * 4. Аппаратная отрисовка через drawWithContent (0% лишних LayoutNode, нулевая нагрузка на CPU).
 */
@Composable
fun Modifier.tvPulsingFocusBorder(
    isFocused: Boolean,
    focusedBorderColor: Color = CinemaPrimary,
    shape: Shape = RoundedCornerShape(12.dp),
    baseBorderWidth: Dp = 2.dp,
    inset: Dp = 0.dp
): Modifier {
    if (!isFocused || !LocalTvShowCursor.current) return this

    val infiniteTransition = rememberInfiniteTransition(label = "tv_cursor_pulse")

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.65f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursor_pulse_alpha"
    )

    return this.drawWithContent {
        drawContent()
        val strokePx = baseBorderWidth.toPx()
        val halfStroke = strokePx / 2f
        val insetPx = inset.toPx()
        val pad = insetPx + halfStroke
        val w = (size.width - 2f * pad).coerceAtLeast(0f)
        val h = (size.height - 2f * pad).coerceAtLeast(0f)

        if (w > 0f && h > 0f) {
            val cursorColor = focusedBorderColor.copy(alpha = pulseAlpha)
            if (shape is RoundedCornerShape) {
                val cornerSize = shape.topStart.toPx(size, this)
                val cornerRadius = (cornerSize - pad).coerceAtLeast(0f)
                drawRoundRect(
                    color = cursorColor,
                    topLeft = Offset(pad, pad),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(cornerRadius, cornerRadius),
                    style = Stroke(width = strokePx)
                )
            } else if (shape == CircleShape) {
                val radius = ((size.minDimension - 2f * pad) / 2f).coerceAtLeast(0f)
                drawCircle(
                    color = cursorColor,
                    radius = radius,
                    center = center,
                    style = Stroke(width = strokePx)
                )
            } else {
                translate(left = pad, top = pad) {
                    val outline = shape.createOutline(Size(w, h), layoutDirection, this)
                    drawOutline(
                        outline = outline,
                        color = cursorColor,
                        style = Stroke(width = strokePx)
                    )
                }
            }
        }
    }
}

/**
 * Автономный модификатор мерцающего ТВ-курсора для любого Composable элемента.
 */
fun Modifier.tvFocusCursor(
    isFocused: Boolean,
    focusedBorderColor: Color = CinemaPrimary,
    shape: Shape = RoundedCornerShape(12.dp),
    focusedBorderWidth: Dp = 2.dp,
    inset: Dp = 0.dp
): Modifier = composed {
    this.tvPulsingFocusBorder(
        isFocused = isFocused,
        focusedBorderColor = focusedBorderColor,
        shape = shape,
        baseBorderWidth = focusedBorderWidth,
        inset = inset
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
    onFocusChanged: ((Boolean) -> Unit)? = null,
    scaleFactor: Float = 1.0f,
    focusedBorderColor: Color = CinemaPrimary,
    focusedBorderWidth: Dp = 2.dp,
    shape: Shape = RoundedCornerShape(12.dp),
    focusRequester: FocusRequester? = null,
    lazyListState: LazyListState? = null,
    targetViewportY: Float = 220f,
    inset: Dp = 0.dp
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

    val haptic = remember { com.example.ui.haptics.HapticEngine.get() }

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
            val hasFocus = focusState.isFocused || focusState.hasFocus
            isFocused = hasFocus
            onFocusChanged?.invoke(hasFocus)
            if (hasFocus) {
                haptic.perform(com.example.ui.haptics.HapticType.SELECTION)
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
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = {
                haptic.perform(com.example.ui.haptics.HapticType.SOFT_CLICK)
                onClick()
            }
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
                        haptic.perform(com.example.ui.haptics.HapticType.SOFT_CLICK)
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
            baseBorderWidth = focusedBorderWidth,
            inset = inset
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
                android.view.KeyEvent.KEYCODE_PAGE_DOWN -> {
                    coroutineScope.launch {
                        try {
                            lazyGridState.scroll {
                                scrollBy(scrollStepPx)
                            }
                        } catch (_: Exception) {}
                    }
                    true
                }
                android.view.KeyEvent.KEYCODE_PAGE_UP -> {
                    coroutineScope.launch {
                        try {
                            lazyGridState.scroll {
                                scrollBy(-scrollStepPx)
                            }
                        } catch (_: Exception) {}
                    }
                    true
                }
                else -> false
            }
        } else false
    }
}

/**
 * Безопасный вызов requestFocus для предотвращения IllegalStateException,
 * если компонент еще не прикреплен к дереву композиции или уже уничтожен.
 */
fun FocusRequester.requestFocusSafe() {
    try {
        this.requestFocus()
    } catch (_: Throwable) {
        // Поглощаем любые исключения неинициализированного/открепленного фокуса для стабильности
    }
}

/**
 * Высокопроизводительное поле текстового ввода для Android TV и пультов D-Pad.
 *
 * Логика работы (полностью идентично поиску в каталоге):
 * 1. В обычном режиме навигации клавиатура НЕ открывается при простом перемещении фокуса D-Pad!
 *    Элемент выделяется нашим фирменным мерцающим акцентным неоновым курсором (tvFocusableItem).
 * 2. Режим редактирования текста активируется СТРОГО при нажатии ОК (DPAD_CENTER / ENTER) с пульта.
 * 3. При открытой клавиатуре:
 *    - Кнопка "Назад" на пульте перехватывается через BackHandler: клавиатура плавно скрывается,
 *      режим редактирования завершается, а фокус сохраняется на этом же поле ввода.
 *    - Нажатие Enter / Done на экранной клавиатуре или пульте: фиксирует введённый текст,
 *      скрывает клавиатуру и вызывает onCommit.
 *    - Кнопка очистки позволяет быстро стереть введенный текст.
 */
@Composable
fun TvRemoteInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    onCommit: (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(10.dp),
    containerColor: Color = CinemaCard,
    focusRequester: FocusRequester? = null,
    testTag: String = "",
    enabled: Boolean = true
) {
    var isEditing by remember { mutableStateOf(false) }
    var hasBeenFocused by remember { mutableStateOf(false) }
    var lastActivationTime by remember { mutableLongStateOf(0L) }

    val localRequester = focusRequester ?: remember { FocusRequester() }
    val internalFieldRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val view = LocalView.current

    // При входе в режим редактирования плавно и надежно активируем поле ввода и открываем IME клавиатуру
    LaunchedEffect(isEditing) {
        if (isEditing) {
            for (attempt in 0..4) {
                delay(if (attempt == 0) 30L else 50L)
                try {
                    internalFieldRequester.requestFocus()
                    keyboardController?.show()
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
                    break
                } catch (_: Throwable) {}
            }
        }
    }

    // При открытой клавиатуре кнопка "Назад" пульта закрывает клавиатуру и возвращает фокус на поле с неоновым курсором
    BackHandler(enabled = isEditing) {
        isEditing = false
        keyboardController?.hide()
        localRequester.requestFocusSafe()
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(containerColor)
            .then(
                if (testTag.isNotEmpty()) Modifier.testTag(testTag) else Modifier
            )
            .then(
                if (!isEditing) {
                    Modifier.tvFocusableItem(
                        onClick = {
                            if (enabled) {
                                hasBeenFocused = false
                                lastActivationTime = System.currentTimeMillis()
                                isEditing = true
                            }
                        },
                        scaleFactor = 1.02f,
                        shape = shape,
                        focusRequester = localRequester
                    )
                } else {
                    Modifier.tvPulsingFocusBorder(
                        isFocused = true,
                        focusedBorderColor = CinemaPrimary,
                        shape = shape,
                        baseBorderWidth = 2.dp
                    )
                }
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingIcon != null) {
                leadingIcon()
                Spacer(modifier = Modifier.width(10.dp))
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.CenterStart
            ) {
                if (!isEditing) {
                    // В режиме навигации с пульта отображается только превью текста или placeholder.
                    // Клавиатура гарантированно НЕ выскакивает при простом наведении стрелок D-Pad!
                    val displayText = when {
                        value.isNotEmpty() && visualTransformation != VisualTransformation.None -> "•".repeat(value.length)
                        value.isNotEmpty() -> value
                        else -> placeholder
                    }
                    Text(
                        text = displayText,
                        color = if (value.isNotEmpty()) CinemaTextWhite else CinemaMuted,
                        fontSize = 14.sp,
                        fontWeight = if (value.isNotEmpty()) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    // Режим редактирования: активируется ТОЛЬКО по нажатию ОК на пульте
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = singleLine,
                        visualTransformation = visualTransformation,
                        textStyle = TextStyle(
                            color = CinemaTextWhite,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        cursorBrush = SolidColor(CinemaPrimary),
                        keyboardOptions = keyboardOptions,
                        keyboardActions = KeyboardActions(
                            onDone = {
                                onCommit?.invoke()
                                isEditing = false
                                keyboardController?.hide()
                                focusManager.clearFocus()
                                localRequester.requestFocusSafe()
                            },
                            onSearch = {
                                onCommit?.invoke()
                                isEditing = false
                                keyboardController?.hide()
                                focusManager.clearFocus()
                                localRequester.requestFocusSafe()
                            },
                            onGo = {
                                onCommit?.invoke()
                                isEditing = false
                                keyboardController?.hide()
                                focusManager.clearFocus()
                                localRequester.requestFocusSafe()
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(internalFieldRequester)
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused) {
                                    hasBeenFocused = true
                                } else if (hasBeenFocused && isEditing) {
                                    // Сбрасываем только если поле действительно получало фокус и потеряло его
                                    isEditing = false
                                    keyboardController?.hide()
                                }
                            }
                            .onKeyEvent { keyEvent ->
                                if (keyEvent.type == KeyEventType.KeyDown) {
                                    val elapsed = System.currentTimeMillis() - lastActivationTime
                                    when (keyEvent.nativeKeyEvent.keyCode) {
                                        android.view.KeyEvent.KEYCODE_ENTER,
                                        android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
                                        android.view.KeyEvent.KEYCODE_DPAD_CENTER -> {
                                            // Игнорируем "хвост" нажатия от открывающего клика пульта
                                            if (elapsed > 250L) {
                                                onCommit?.invoke()
                                                isEditing = false
                                                keyboardController?.hide()
                                                focusManager.clearFocus()
                                                localRequester.requestFocusSafe()
                                                true
                                            } else {
                                                true
                                            }
                                        }
                                        else -> false
                                    }
                                } else false
                            }
                    )
                }
            }

            if (trailingIcon != null) {
                Spacer(modifier = Modifier.width(8.dp))
                trailingIcon()
            } else if (value.isNotEmpty()) {
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        onValueChange("")
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Очистить",
                        tint = CinemaMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

