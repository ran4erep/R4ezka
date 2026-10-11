package com.example.ui.tv

import android.content.Context
import android.graphics.Rect
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.KeyEvent
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.CinemaMuted
import com.example.ui.theme.CinemaTextWhite

/**
 * Специализированный нативный EditText для Android TV с перехватом кнопки Back на уровне Pre-IME.
 *
 * Проблема на Android TV:
 * При открытой экранной клавиатуре (IME) системный InputMethodService по умолчанию поглощает
 * первое нажатие кнопки KEYCODE_BACK на пульте исключительно для скрытия клавиатуры, не передавая
 * событие в Compose View. Из-за этого текстовое поле остаётся в режиме ввода (с мигающим курсором),
 * и пользователю приходится нажимать "Назад" второй раз, чтобы выйти из поля.
 *
 * Решение:
 * Переопределение [onKeyPreIme] позволяет перехватить нажатие Back на пульте ДО того, как IME поглотит его.
 * При первом же нажатии Back:
 * 1. Программная клавиатура скрывается немедленно.
 * 2. Срабатывает коллбэк [onBackPreIme] -> поле моментально выходит из режима ввода.
 * 3. Нагрузка на процессор минимальна: никаких лишних Compose-рекомпозиций на каждый кадр мигания курсора.
 */
class TvPreImeEditText(context: Context) : EditText(context) {

    var onBackPreIme: (() -> Unit)? = null
    var onCommit: (() -> Unit)? = null
    var onDpadDown: (() -> Unit)? = null
    var onDpadUp: (() -> Unit)? = null
    var onDpadLeft: (() -> Unit)? = null
    var onDpadRight: (() -> Unit)? = null
    var onTextChangedListener: ((String) -> Unit)? = null

    private var isUpdatingTextInternally = false
    private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var isKeyboardEverDetected = false

    init {
        // Убираем стандартные фоны и системные линии EditText
        background = null
        setPadding(0, 0, 0, 0)
        includeFontPadding = false
        maxLines = 1
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

        // Отслеживание ввода текста пользователем с пульта
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isUpdatingTextInternally) {
                    onTextChangedListener?.invoke(s?.toString().orEmpty())
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Обработка кнопки Search / Done / Go на экранной клавиатуре
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO
            ) {
                hideKeyboard()
                onCommit?.invoke()
                true
            } else false
        }
    }

    /**
     * Ключевой перехват: вызывается системой ДО того, как виртуальная клавиатура поглотит событие Back.
     */
    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP) {
                hideKeyboard()
                val currentText = text?.toString().orEmpty().trim()
                if (currentText.isNotEmpty()) {
                    onCommit?.invoke()
                }
                onBackPreIme?.invoke()
            }
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }

    /**
     * Обработка клавиш пульта (Enter, стрелки D-Pad) во время фокуса на поле ввода.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER -> {
                hideKeyboard()
                onCommit?.invoke()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (onDpadDown != null) {
                    hideKeyboard()
                    val currentText = text?.toString().orEmpty().trim()
                    if (currentText.isNotEmpty()) {
                        onCommit?.invoke()
                    }
                    onDpadDown?.invoke()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (onDpadUp != null) {
                    hideKeyboard()
                    onDpadUp?.invoke()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (selectionStart == 0 && onDpadLeft != null) {
                    hideKeyboard()
                    val currentText = text?.toString().orEmpty().trim()
                    if (currentText.isNotEmpty()) {
                        onCommit?.invoke()
                    }
                    onDpadLeft?.invoke()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val len = text?.length ?: 0
                if (selectionEnd >= len && onDpadRight != null) {
                    hideKeyboard()
                    val currentText = text?.toString().orEmpty().trim()
                    if (currentText.isNotEmpty()) {
                        onCommit?.invoke()
                    }
                    onDpadRight?.invoke()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Дополнительный уровень надёжности: отслеживание закрытия клавиатуры экранной кнопкой или жестом
        val decor = rootView
        layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            val r = Rect()
            decor.getWindowVisibleDisplayFrame(r)
            val screenHeight = decor.rootView.height
            val keypadHeight = screenHeight - r.bottom
            val keyboardVisible = keypadHeight > screenHeight * 0.15
            if (isKeyboardEverDetected && !keyboardVisible) {
                post {
                    val currentText = text?.toString().orEmpty().trim()
                    if (currentText.isNotEmpty()) {
                        onCommit?.invoke()
                    }
                    onBackPreIme?.invoke()
                }
            }
            if (keyboardVisible) {
                isKeyboardEverDetected = true
            }
        }
        decor.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        layoutListener?.let {
            rootView.viewTreeObserver.removeOnGlobalLayoutListener(it)
            layoutListener = null
        }
    }

    fun updateTextSafely(newText: String) {
        if (text?.toString() != newText) {
            isUpdatingTextInternally = true
            val cursor = selectionEnd.coerceAtMost(newText.length)
            setText(newText)
            setSelection(cursor)
            isUpdatingTextInternally = false
        }
    }

    fun showKeyboardAndFocus() {
        requestFocus()
        post {
            setSelection(text?.length ?: 0)
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
    }
}

/**
 * Высокопроизводительный Composable для ввода текста на телевизоре.
 * Гарантирует мгновенный выход из ввода по первому же нажатию кнопки "Назад" на пульте.
 */
@Composable
fun TvSearchInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    fontSizeSp: Float = 13f,
    textColor: Color = CinemaTextWhite,
    hintColor: Color = CinemaMuted,
    onExitEditing: () -> Unit,
    onCommit: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onUp: (() -> Unit)? = null,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null
) {
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnExitEditing by rememberUpdatedState(onExitEditing)
    val currentOnCommit by rememberUpdatedState(onCommit)
    val currentOnDown by rememberUpdatedState(onDown)
    val currentOnUp by rememberUpdatedState(onUp)
    val currentOnLeft by rememberUpdatedState(onLeft)
    val currentOnRight by rememberUpdatedState(onRight)

    AndroidView(
        factory = { context ->
            TvPreImeEditText(context).apply {
                setText(value)
                setSelection(value.length)
                hint = placeholder
                setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
                setTextColor(textColor.toArgb())
                setHintTextColor(hintColor.toArgb())

                this.onTextChangedListener = { newText ->
                    currentOnValueChange(newText)
                }
                this.onBackPreIme = {
                    currentOnExitEditing()
                }
                this.onCommit = {
                    currentOnCommit?.invoke() ?: currentOnExitEditing()
                }
                this.onDpadDown = currentOnDown
                this.onDpadUp = currentOnUp
                this.onDpadLeft = currentOnLeft
                this.onDpadRight = currentOnRight

                showKeyboardAndFocus()
            }
        },
        update = { editText ->
            editText.onTextChangedListener = { newText ->
                currentOnValueChange(newText)
            }
            editText.onBackPreIme = {
                currentOnExitEditing()
            }
            editText.onCommit = {
                currentOnCommit?.invoke() ?: currentOnExitEditing()
            }
            editText.onDpadDown = currentOnDown
            editText.onDpadUp = currentOnUp
            editText.onDpadLeft = currentOnLeft
            editText.onDpadRight = currentOnRight

            editText.updateTextSafely(value)
        },
        modifier = modifier
    )
}
