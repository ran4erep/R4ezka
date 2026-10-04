package com.example.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

val LocalNavAnimatedVisibilityScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Высокопроизводительный модификатор бесшовного перетекания постера
 * между карточкой каталога и экраном деталей фильма.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedPosterElement(key: String): Modifier {
    val scope = LocalSharedTransitionScope.current ?: return this
    val animScope = LocalNavAnimatedVisibilityScope.current ?: return this
    if (key.isEmpty()) return this

    return with(scope) {
        this@sharedPosterElement.sharedElement(
            state = rememberSharedContentState(key = "poster_$key"),
            animatedVisibilityScope = animScope
        )
    }
}
