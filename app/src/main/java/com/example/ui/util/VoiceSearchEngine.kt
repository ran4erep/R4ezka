package com.example.ui.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.haptics.HapticEngine
import com.example.ui.haptics.HapticType
import com.example.ui.theme.CinemaBorder
import com.example.ui.theme.CinemaDark
import com.example.ui.theme.CinemaPrimary
import com.example.ui.theme.CinemaTextWhite
import com.example.ui.tv.tvFocusableItem
import java.util.Locale

/**
 * Высокопроизводительный движок голосового поиска (Voice Search Engine).
 *
 * Архитектурные особенности:
 * 1. Использование встроенного системного API распознавания речи Android (RecognizerIntent.ACTION_RECOGNIZE_SPEECH).
 *    - Нулевая постоянная нагрузка на CPU (0% idle overhead), не требует локальных тяжелых моделей.
 *    - Системный интерфейс Google Speech / системный ассистент сам обрабатывает поток аудио.
 * 2. Полная отказоустойчивость: безопасный перехват отсутствия сервиса распознавания речи (ActivityNotFoundException).
 * 3. Поддержка управления с пульта (D-Pad / Smart TV):
 *    - Неоновый пульсирующий курсор (tvFocusableItem).
 *    - Кинетический пружинный масштаб при фокусе.
 *    - Обработка нажатий Enter / DPAD_CENTER.
 * 4. Тактильный отклик (Haptic Feedback) через HapticEngine:
 *    - SELECTION при наведении курсора с пульта.
 *    - SOFT_CLICK / GENTLE_TICK при нажатии.
 */
object VoiceSearchEngine {

    /**
     * Создание безопасного интента голосового ввода с локализацией и подсказкой.
     */
    fun createSpeechRecognizerIntent(
        prompt: String = "Назовите фильм или сериал",
        locale: Locale = Locale.getDefault()
    ): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag().ifEmpty { "ru-RU" })
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, "com.example")
        }
    }

    /**
     * Очистка и извлечение наиболее точного распознанного текста из списка кандидатов.
     */
    fun cleanRecognizedText(results: List<String>?): String? {
        if (results.isNullOrEmpty()) return null
        return results.firstOrNull { it.isNotBlank() }?.trim()
    }

    /**
     * Извлечение распознанного текста из результата Activity.
     */
    fun extractSpokenText(data: Intent?): String? {
        if (data == null) return null
        val results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        return cleanRecognizedText(results)
    }

    /**
     * Проверка доступности системного распознавателя речи на устройстве.
     */
    fun isVoiceInputAvailable(context: Context): Boolean {
        return try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            val activities = context.packageManager.queryIntentActivities(intent, 0)
            activities.isNotEmpty()
        } catch (_: Throwable) {
            false
        }
    }
}

/**
 * Composable-лаунчер для запуска системного голосового ввода с возвратом результата в callback.
 */
@Composable
fun rememberVoiceSearchLauncher(
    prompt: String = "Назовите фильм или сериал",
    onSpeechResult: (String) -> Unit
): () -> Unit {
    val context = LocalContext.current
    val haptic = remember { HapticEngine.get() }

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenText = VoiceSearchEngine.extractSpokenText(result.data)
            if (!spokenText.isNullOrBlank()) {
                haptic.perform(HapticType.CONFIRM)
                onSpeechResult(spokenText)
            }
        }
    }

    return remember(prompt) {
        {
            haptic.perform(HapticType.GENTLE_TICK)
            try {
                val intent = VoiceSearchEngine.createSpeechRecognizerIntent(prompt = prompt)
                speechLauncher.launch(intent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(
                    context,
                    "Голосовой ввод недоступен на данном устройстве",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Throwable) {
                Toast.makeText(
                    context,
                    "Ошибка запуска голосового поиска",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}

/**
 * Унифицированная кнопка голосового поиска для мобильного и ТВ-интерфейса.
 *
 * Поддерживает:
 * - Управление с пульта (D-Pad, курсор, фокус-рамочка CinemaPrimary)
 * - Вибрацию при клике и наведении
 * - Одинаковый размер с полем ввода и кнопкой фильтра (52x52 dp по умолчанию)
 */
@Composable
fun VoiceSearchButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    iconSize: Dp = 24.dp,
    shape: Shape = CircleShape,
    backgroundColor: Color = Color.Transparent,
    borderColor: Color = Color.Transparent,
    iconTint: Color = CinemaTextWhite.copy(alpha = 0.85f),
    focusRequester: FocusRequester? = null,
    testTag: String = "catalog_voice_search_button",
    onFocused: (() -> Unit)? = null
) {
    val haptic = remember { HapticEngine.get() }

    Surface(
        shape = shape,
        color = backgroundColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .size(size)
            .testTag(testTag)
            .tvFocusableItem(
                onClick = {
                    haptic.perform(HapticType.SOFT_CLICK)
                    onClick()
                },
                onFocused = onFocused,
                scaleFactor = 1.05f,
                focusedBorderColor = CinemaPrimary,
                focusedBorderWidth = 2.dp,
                shape = shape,
                focusRequester = focusRequester
            )
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = "Голосовой поиск",
                tint = iconTint,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}
