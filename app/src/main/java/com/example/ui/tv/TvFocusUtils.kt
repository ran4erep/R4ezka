package com.example.ui.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.ui.theme.CinemaPrimary
import kotlinx.coroutines.launch

/**
 * Высокопроизводительный модификатор фокуса для ТВ-интерфейса и пульта.
 * Оптимизации:
 * 1. Анимация масштаба через graphicsLayer (GPU-transform без remeasure/relayout).
 * 2. zIndex поднимает сфокусированный элемент над соседями.
 * 3. bringIntoViewRequester удерживает сфокусированный элемент в видимой области ТВ-экрана.
 * 4. Автоматическая обработка клавиш DPAD_CENTER / ENTER.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.tvFocusableItem(
    onClick: () -> Unit,
    onFocused: (() -> Unit)? = null,
    scaleFactor: Float = 1.06f,
    focusedBorderColor: Color = CinemaPrimary,
    focusedBorderWidth: Dp = 2.5.dp,
    shape: Shape = RoundedCornerShape(12.dp),
    focusRequester: FocusRequester? = null
): Modifier = composed {
    var isFocused by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) scaleFactor else 1.0f,
        animationSpec = tween(durationMillis = 150),
        label = "tv_focus_scale"
    )

    this
        .zIndex(if (isFocused) 5f else 1f)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .bringIntoViewRequester(bringIntoViewRequester)
        .then(
            if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
        )
        .onFocusChanged { focusState ->
            isFocused = focusState.isFocused
            if (focusState.isFocused) {
                onFocused?.invoke()
                coroutineScope.launch {
                    try {
                        bringIntoViewRequester.bringIntoView()
                    } catch (_: Exception) {}
                }
            }
        }
        .focusable(
            interactionSource = remember { MutableInteractionSource() }
        )
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )
        .onKeyEvent { keyEvent ->
            if (keyEvent.type == KeyEventType.KeyDown) {
                when (keyEvent.nativeKeyEvent.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER,
                    android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        onClick()
                        true
                    }
                    else -> false
                }
            } else false
        }
        .then(
            if (isFocused) {
                Modifier.border(BorderStroke(focusedBorderWidth, focusedBorderColor), shape)
            } else {
                Modifier
            }
        )
}
