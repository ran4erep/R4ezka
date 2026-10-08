package com.example.ui.components

import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent as AndroidKeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.example.ui.RezkaViewModel
import com.example.ui.haptics.HapticEngine
import com.example.ui.haptics.HapticType
import com.example.ui.haptics.bounceOverscroll
import com.example.ui.theme.*
import com.example.ui.tv.TvDetector
import com.example.ui.tv.TvModePreference
import com.example.ui.tv.requestFocusSafe
import com.example.ui.tv.tvFocusableItem
import com.example.ui.tv.tvPulsingFocusBorder
import kotlinx.coroutines.delay

@Composable
fun AuthDialog(
    viewModel: RezkaViewModel,
    onDismiss: () -> Unit,
    isTvMode: Boolean = false
) {
    val context = LocalContext.current
    val tvModePrefString by viewModel.tvModePreference.collectAsState()
    val effectiveTvMode = isTvMode || remember(context, tvModePrefString) {
        val pref = when (tvModePrefString) {
            "force_tv" -> TvModePreference.FORCE_TV
            "force_mobile" -> TvModePreference.FORCE_MOBILE
            else -> TvModePreference.AUTO
        }
        when (pref) {
            TvModePreference.FORCE_TV -> true
            TvModePreference.FORCE_MOBILE -> false
            TvModePreference.AUTO -> TvDetector.isRunningOnTv(context) ||
                (context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
        }
    }

    val isLoggedIn by viewModel.isLoggedIn.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val currentUserAvatar by viewModel.currentUserAvatar.collectAsState()
    val currentUserRegisteredAt by viewModel.currentUserRegisteredAt.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()

    val favorites by viewModel.favorites.collectAsState()
    val watchHistory by viewModel.aggregatedWatchHistory.collectAsState()
    val subscriptions by viewModel.subscriptions.collectAsState()

    val formattedRegDate = remember(currentUserRegisteredAt) {
        formatRegistrationDate(currentUserRegisteredAt)
    }

    var isRegisterMode by remember { mutableStateOf(false) }
    var registerAvatar by remember { mutableStateOf<String?>(null) }
    var showAvatarPicker by remember { mutableStateOf(false) }
    var loginInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var confirmPasswordInput by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }
    var isSubmittingAuth by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }

    if (showAvatarPicker) {
        AvatarPickerDialog(
            currentAvatar = if (isLoggedIn) currentUserAvatar else registerAvatar,
            onAvatarSelected = { newAvatar ->
                if (isLoggedIn) {
                    viewModel.updateAvatar(newAvatar) { _, msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    registerAvatar = newAvatar
                }
            },
            onDismiss = { showAvatarPicker = false }
        )
    }

    Dialog(
        onDismissRequest = {
            if (!isSubmittingAuth) {
                onDismiss()
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = true
        )
    ) {
        val currentView = LocalView.current
        DisposableEffect(currentView) {
            var parent = currentView.parent
            while (parent != null) {
                if (parent is DialogWindowProvider) {
                    val window = parent.window
                    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                    window.statusBarColor = android.graphics.Color.TRANSPARENT
                    window.navigationBarColor = android.graphics.Color.TRANSPARENT
                    window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                    break
                }
                parent = parent.parent
            }
            onDispose {}
        }

        val closeButtonRequester = remember { FocusRequester() }

        // Полноэкранный полупрозрачный фон без черных полос системных панелей
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    if (!isSubmittingAuth) onDismiss()
                },
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CinemaDark),
                modifier = Modifier
                    .widthIn(max = 440.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* Поглощаем клик внутри карточки */ }
                    .testTag("auth_dialog_card")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .bounceOverscroll(Orientation.Vertical)
                        .padding(20.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isLoggedIn) "Аккаунт" else if (isRegisterMode) "Регистрация" else "Вход",
                            color = CinemaTextWhite,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(
                            onClick = {
                                HapticEngine.get().perform(HapticType.GENTLE_TICK)
                                onDismiss()
                            },
                            modifier = Modifier
                                .size(32.dp)
                                .tvFocusableItem(
                                    onClick = {
                                        HapticEngine.get().perform(HapticType.GENTLE_TICK)
                                        onDismiss()
                                    },
                                    shape = CircleShape,
                                    focusRequester = closeButtonRequester
                                )
                                .testTag("auth_close_button")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = CinemaTextGray)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    if (isLoggedIn) {
                        val logoutButtonRequester = remember { FocusRequester() }
                        LaunchedEffect(Unit) {
                            delay(50L)
                            logoutButtonRequester.requestFocusSafe()
                        }

                        // Основной блок профиля
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(CinemaCard, RoundedCornerShape(16.dp))
                                .border(1.dp, CinemaBorder, RoundedCornerShape(16.dp))
                                .padding(16.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.BottomEnd,
                                modifier = Modifier
                                    .tvFocusableItem(
                                        onClick = {
                                            HapticEngine.get().perform(HapticType.SOFT_CLICK)
                                            showAvatarPicker = true
                                        },
                                        shape = CircleShape
                                    )
                            ) {
                                UserAvatar(
                                    avatar = currentUserAvatar,
                                    size = 58.dp,
                                    onClick = {
                                        HapticEngine.get().perform(HapticType.SOFT_CLICK)
                                        showAvatarPicker = true
                                    }
                                )
                                PencilAvatarBadge(
                                    size = 22.dp,
                                    iconSize = 13.dp
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = currentUser ?: "Пользователь",
                                    color = CinemaTextWhite,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Зарегистрирован $formattedRegDate",
                                    color = CinemaTextGray,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Normal
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Блок дополнительной информации / статистики аккаунта
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(CinemaCard, RoundedCornerShape(16.dp))
                                .border(1.dp, CinemaBorder, RoundedCornerShape(16.dp))
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceAround,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StatItem(
                                icon = Icons.Default.Bookmark,
                                value = favorites.size.toString(),
                                label = "В избранном"
                            )
                            StatItem(
                                icon = Icons.Default.History,
                                value = watchHistory.size.toString(),
                                label = "Просмотрено"
                            )
                            StatItem(
                                icon = Icons.Default.Subscriptions,
                                value = subscriptions.size.toString(),
                                label = "Подписки"
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                HapticEngine.get().perform(HapticType.CONFIRM)
                                viewModel.logout()
                                Toast.makeText(context, "Вы вышли из аккаунта", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CinemaSecondary),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .tvFocusableItem(
                                    onClick = {
                                        HapticEngine.get().perform(HapticType.CONFIRM)
                                        viewModel.logout()
                                        Toast.makeText(context, "Вы вышли из аккаунта", Toast.LENGTH_SHORT).show()
                                        onDismiss()
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    focusRequester = logoutButtonRequester
                                )
                        ) {
                            Text("Выйти из аккаунта", fontSize = 14.sp)
                        }
                    } else {
                        val loginTabRequester = remember { FocusRequester() }
                        val registerTabRequester = remember { FocusRequester() }
                        val regAvatarRequester = remember { FocusRequester() }
                        val loginFocusRequester = remember { FocusRequester() }
                        val passwordFocusRequester = remember { FocusRequester() }
                        val confirmPasswordFocusRequester = remember { FocusRequester() }
                        val submitButtonRequester = remember { FocusRequester() }
                        val switchButtonRequester = remember { FocusRequester() }

                        // При открытии диалога фокус аккуратно встаёт на вкладку "Вход",
                        // клавиатура гарантированно НЕ открывается сама по себе!
                        LaunchedEffect(Unit) {
                            delay(60L)
                            loginTabRequester.requestFocusSafe()
                        }

                        // Переключатель: Вход / Регистрация
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(CinemaCard, RoundedCornerShape(10.dp))
                                .padding(3.dp)
                        ) {
                            Surface(
                                color = if (!isRegisterMode) CinemaPrimary else Color.Transparent,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .tvFocusableItem(
                                        onClick = {
                                            HapticEngine.get().perform(HapticType.TOGGLE)
                                            isRegisterMode = false
                                            authError = null
                                        },
                                        scaleFactor = 1.02f,
                                        shape = RoundedCornerShape(8.dp),
                                        focusRequester = loginTabRequester
                                    )
                                    .onKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyDown) {
                                            when (event.nativeKeyEvent.keyCode) {
                                                AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                                                    registerTabRequester.requestFocusSafe()
                                                    true
                                                }
                                                AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                                    loginFocusRequester.requestFocusSafe()
                                                    true
                                                }
                                                AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                                                    closeButtonRequester.requestFocusSafe()
                                                    true
                                                }
                                                else -> false
                                            }
                                        } else false
                                    }
                                    .testTag("auth_tab_login")
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Вход",
                                        color = if (!isRegisterMode) CinemaBlack else CinemaTextGray,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Surface(
                                color = if (isRegisterMode) CinemaPrimary else Color.Transparent,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .tvFocusableItem(
                                        onClick = {
                                            HapticEngine.get().perform(HapticType.TOGGLE)
                                            isRegisterMode = true
                                            authError = null
                                        },
                                        scaleFactor = 1.02f,
                                        shape = RoundedCornerShape(8.dp),
                                        focusRequester = registerTabRequester
                                    )
                                    .onKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyDown) {
                                            when (event.nativeKeyEvent.keyCode) {
                                                AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                                                    loginTabRequester.requestFocusSafe()
                                                    true
                                                }
                                                AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                                    if (isRegisterMode) {
                                                        regAvatarRequester.requestFocusSafe()
                                                    } else {
                                                        loginFocusRequester.requestFocusSafe()
                                                    }
                                                    true
                                                }
                                                AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                                                    closeButtonRequester.requestFocusSafe()
                                                    true
                                                }
                                                else -> false
                                            }
                                        } else false
                                    }
                                    .testTag("auth_tab_register")
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Регистрация",
                                        color = if (isRegisterMode) CinemaBlack else CinemaTextGray,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        if (isRegisterMode) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .tvFocusableItem(
                                        onClick = {
                                            HapticEngine.get().perform(HapticType.SOFT_CLICK)
                                            showAvatarPicker = true
                                        },
                                        scaleFactor = 1.03f,
                                        shape = RoundedCornerShape(12.dp),
                                        focusRequester = regAvatarRequester
                                    )
                                    .onKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyDown) {
                                            when (event.nativeKeyEvent.keyCode) {
                                                AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                                                    registerTabRequester.requestFocusSafe()
                                                    true
                                                }
                                                AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                                    loginFocusRequester.requestFocusSafe()
                                                    true
                                                }
                                                else -> false
                                            }
                                        } else false
                                    }
                                    .padding(6.dp)
                            ) {
                                Box(contentAlignment = Alignment.BottomEnd) {
                                    UserAvatar(
                                        avatar = registerAvatar,
                                        size = 60.dp,
                                        onClick = {
                                            HapticEngine.get().perform(HapticType.SOFT_CLICK)
                                            showAvatarPicker = true
                                        }
                                    )
                                    PencilAvatarBadge(
                                        size = 22.dp,
                                        iconSize = 13.dp
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = if (registerAvatar == null) "Аватар (необязательно)" else "Аватар выбран (нажмите для смены)",
                                    color = if (registerAvatar == null) CinemaTextGray else CinemaPrimary,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Логин (работает идентично TvCompactSearchBar на ТВ)
                        TvAuthTextField(
                            value = loginInput,
                            onValueChange = {
                                loginInput = it
                                authError = null
                            },
                            placeholderText = "Логин",
                            isTvMode = effectiveTvMode,
                            focusRequester = loginFocusRequester,
                            onUp = {
                                if (isRegisterMode) {
                                    regAvatarRequester.requestFocusSafe()
                                } else {
                                    loginTabRequester.requestFocusSafe()
                                }
                            },
                            onDown = {
                                passwordFocusRequester.requestFocusSafe()
                            },
                            onDone = {
                                passwordFocusRequester.requestFocusSafe()
                            },
                            testTag = "auth_login_input"
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Пароль
                        TvAuthTextField(
                            value = passwordInput,
                            onValueChange = {
                                passwordInput = it
                                authError = null
                            },
                            placeholderText = "Пароль",
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        HapticEngine.get().perform(HapticType.TOGGLE)
                                        passwordVisible = !passwordVisible
                                    },
                                    modifier = Modifier
                                        .size(32.dp)
                                        .tvFocusableItem(
                                            onClick = {
                                                HapticEngine.get().perform(HapticType.TOGGLE)
                                                passwordVisible = !passwordVisible
                                            },
                                            shape = CircleShape
                                        )
                                ) {
                                    Icon(
                                        imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = if (passwordVisible) "Скрыть пароль" else "Показать пароль",
                                        tint = if (passwordVisible) CinemaPrimary else CinemaTextGray
                                    )
                                }
                            },
                            isTvMode = effectiveTvMode,
                            focusRequester = passwordFocusRequester,
                            onUp = {
                                loginFocusRequester.requestFocusSafe()
                            },
                            onDown = {
                                if (isRegisterMode) {
                                    confirmPasswordFocusRequester.requestFocusSafe()
                                } else {
                                    submitButtonRequester.requestFocusSafe()
                                }
                            },
                            onDone = {
                                if (isRegisterMode) {
                                    confirmPasswordFocusRequester.requestFocusSafe()
                                } else {
                                    submitButtonRequester.requestFocusSafe()
                                }
                            },
                            testTag = "auth_password_input"
                        )

                        // Повтор пароля (два раза) для регистрации
                        if (isRegisterMode) {
                            Spacer(modifier = Modifier.height(12.dp))
                            TvAuthTextField(
                                value = confirmPasswordInput,
                                onValueChange = {
                                confirmPasswordInput = it
                                authError = null
                            },
                            placeholderText = "Повторите пароль",
                            visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        HapticEngine.get().perform(HapticType.TOGGLE)
                                        confirmPasswordVisible = !confirmPasswordVisible
                                    },
                                    modifier = Modifier
                                        .size(32.dp)
                                        .tvFocusableItem(
                                            onClick = {
                                                HapticEngine.get().perform(HapticType.TOGGLE)
                                                confirmPasswordVisible = !confirmPasswordVisible
                                            },
                                            shape = CircleShape
                                        )
                                ) {
                                    Icon(
                                        imageVector = if (confirmPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = if (confirmPasswordVisible) "Скрыть пароль" else "Показать пароль",
                                        tint = if (confirmPasswordVisible) CinemaPrimary else CinemaTextGray
                                    )
                                }
                            },
                            isTvMode = effectiveTvMode,
                            focusRequester = confirmPasswordFocusRequester,
                            onUp = {
                                passwordFocusRequester.requestFocusSafe()
                            },
                            onDown = {
                                submitButtonRequester.requestFocusSafe()
                            },
                            onDone = {
                                submitButtonRequester.requestFocusSafe()
                            },
                            testTag = "auth_confirm_password_input"
                        )
                    }

                    if (authError != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = authError ?: "",
                            color = CinemaPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val doSubmit: () -> Unit = {
                        HapticEngine.get().perform(HapticType.CONFIRM)
                        val user = loginInput.trim()
                        val pass = passwordInput
                        if (user.isBlank() || pass.isBlank()) {
                            authError = "Заполните логин и пароль"
                        } else if (isRegisterMode) {
                            if (user.length < 3) {
                                authError = "Логин должен быть от 3 символов"
                            } else if (pass.length < 4) {
                                authError = "Пароль должен быть от 4 символов"
                            } else if (pass != confirmPasswordInput) {
                                authError = "Пароли не совпадают"
                            } else {
                                isSubmittingAuth = true
                                authError = null
                                viewModel.register(user, pass, registerAvatar) { success, msg ->
                                    isSubmittingAuth = false
                                    if (success) {
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        onDismiss()
                                    } else {
                                        authError = msg
                                    }
                                }
                            }
                        } else {
                            isSubmittingAuth = true
                            authError = null
                            viewModel.login(user, pass) { success, msg ->
                                isSubmittingAuth = false
                                if (success) {
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    onDismiss()
                                } else {
                                    authError = msg
                                }
                            }
                        }
                    }

                    // Кнопка действия (Войти / Зарегистрироваться)
                    Button(
                        onClick = doSubmit,
                        enabled = !isSubmittingAuth,
                        colors = ButtonDefaults.buttonColors(containerColor = CinemaPrimary),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocusableItem(
                                onClick = doSubmit,
                                shape = RoundedCornerShape(10.dp),
                                focusRequester = submitButtonRequester
                            )
                            .onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown) {
                                    when (event.nativeKeyEvent.keyCode) {
                                        AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                                            if (isRegisterMode) {
                                                confirmPasswordFocusRequester.requestFocusSafe()
                                            } else {
                                                passwordFocusRequester.requestFocusSafe()
                                            }
                                            true
                                        }
                                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                            switchButtonRequester.requestFocusSafe()
                                            true
                                        }
                                        else -> false
                                    }
                                } else false
                            }
                            .testTag("auth_submit_button")
                    ) {
                        if (isSubmittingAuth) {
                            CircularProgressIndicator(color = CinemaBlack, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text(
                                text = if (isRegisterMode) "Зарегистрироваться" else "Войти",
                                color = CinemaBlack,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(
                        onClick = {
                            HapticEngine.get().perform(HapticType.GENTLE_TICK)
                            isRegisterMode = !isRegisterMode
                            authError = null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocusableItem(
                                onClick = {
                                    HapticEngine.get().perform(HapticType.GENTLE_TICK)
                                    isRegisterMode = !isRegisterMode
                                    authError = null
                                },
                                shape = RoundedCornerShape(10.dp),
                                focusRequester = switchButtonRequester
                            )
                            .onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP) {
                                    submitButtonRequester.requestFocusSafe()
                                    true
                                } else false
                            }
                    ) {
                        Text(
                            text = if (isRegisterMode) "Уже есть аккаунт? Войти" else "Регистрация",
                            color = CinemaTextGray,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}
}

/**
 * Специализированное текстовое поле для ТВ, работающее аналогично строке поиска TvCompactSearchBar:
 * - При наведении курсора D-Pad виртуальная клавиатура НЕ выскакивает!
 * - Поле подсвечивается неоновой рамкой и масштабируется.
 * - Стрелки Вверх/Вниз плавно переводят фокус на соседние поля и кнопки.
 * - Клавиатура открывается СТРОГО по нажатию OK/ENTER или клику.
 * - Кнопка "Назад" пульта закрывает клавиатуру и возвращает фокус на само поле.
 */
@Composable
private fun TvAuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholderText: String,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
    isTvMode: Boolean = false,
    focusRequester: FocusRequester? = null,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onDone: (() -> Unit)? = null,
    testTag: String = ""
) {
    if (!isTvMode) {
        // Стандартное текстовое поле для смартфонов и сенсорных экранов
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholderText, color = CinemaMuted) },
            singleLine = true,
            visualTransformation = visualTransformation,
            trailingIcon = trailingIcon,
            shape = RoundedCornerShape(10.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = CinemaCard,
                unfocusedContainerColor = CinemaCard,
                focusedTextColor = CinemaTextWhite,
                unfocusedTextColor = CinemaTextWhite,
                focusedIndicatorColor = CinemaPrimary,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = CinemaPrimary
            ),
            modifier = modifier
                .fillMaxWidth()
                .then(
                    if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
                )
                .testTag(testTag)
        )
    } else {
        // ТВ-режим: высокопроизводительное поле с надежным сохранением фокуса
        var isEditing by remember { mutableStateOf(false) }
        var wasEditing by remember { mutableStateOf(false) }
        val internalFieldRequester = remember { FocusRequester() }
        val keyboardController = LocalSoftwareKeyboardController.current
        val localRequester = focusRequester ?: remember { FocusRequester() }

        // При входе/выходе из режима редактирования:
        // При выходе (по кнопке "Назад" на пульте или завершении ввода) через retry с кадровой задержкой
        // гарантированно восстанавливаем фокус на localRequester, чтобы курсор никуда не пропадал!
        LaunchedEffect(isEditing) {
            if (isEditing) {
                wasEditing = true
                for (attempt in 0..6) {
                    delay(if (attempt == 0) 30L else 40L)
                    try {
                        internalFieldRequester.requestFocus()
                        keyboardController?.show()
                        break
                    } catch (_: Throwable) {}
                }
            } else if (wasEditing) {
                wasEditing = false
                for (attempt in 0..6) {
                    delay(if (attempt == 0) 30L else 40L)
                    try {
                        localRequester.requestFocus()
                        break
                    } catch (_: Throwable) {}
                }
            }
        }

        BackHandler(enabled = isEditing) {
            isEditing = false
            keyboardController?.hide()
        }

        Surface(
            color = CinemaCard,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(
                1.dp,
                if (isEditing) CinemaPrimary else CinemaSecondary.copy(alpha = 0.35f)
            ),
            modifier = modifier
                .fillMaxWidth()
                .focusRequester(localRequester)
                .then(
                    if (!isEditing) {
                        Modifier
                            .onKeyEvent { keyEvent ->
                                if (keyEvent.type == KeyEventType.KeyDown) {
                                    when (keyEvent.nativeKeyEvent.keyCode) {
                                        AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                                            if (onUp != null) {
                                                onUp()
                                                true
                                            } else false
                                        }
                                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                            if (onDown != null) {
                                                onDown()
                                                true
                                            } else false
                                        }
                                        else -> false
                                    }
                                } else false
                            }
                            .tvFocusableItem(
                                onClick = {
                                    isEditing = true
                                },
                                scaleFactor = 1.02f,
                                focusedBorderWidth = 2.dp,
                                shape = RoundedCornerShape(10.dp),
                                focusRequester = localRequester
                            )
                    } else {
                        Modifier.tvPulsingFocusBorder(
                            isFocused = true,
                            focusedBorderColor = CinemaPrimary,
                            shape = RoundedCornerShape(10.dp),
                            baseBorderWidth = 2.dp
                        )
                    }
                )
                .testTag(testTag)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (!isEditing) {
                        val displayText = when {
                            value.isNotEmpty() && visualTransformation != VisualTransformation.None -> "•".repeat(value.length)
                            value.isNotEmpty() -> value
                            else -> placeholderText
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
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            singleLine = true,
                            visualTransformation = visualTransformation,
                            textStyle = TextStyle(
                                color = CinemaTextWhite,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            cursorBrush = SolidColor(CinemaPrimary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    isEditing = false
                                    keyboardController?.hide()
                                    onDone?.invoke()
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(internalFieldRequester)
                                .onKeyEvent { keyEvent ->
                                    if (keyEvent.type == KeyEventType.KeyDown) {
                                        when (keyEvent.nativeKeyEvent.keyCode) {
                                            AndroidKeyEvent.KEYCODE_ENTER,
                                            AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
                                            AndroidKeyEvent.KEYCODE_DPAD_CENTER -> {
                                                isEditing = false
                                                keyboardController?.hide()
                                                onDone?.invoke()
                                                true
                                            }
                                            AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                                                wasEditing = false
                                                isEditing = false
                                                keyboardController?.hide()
                                                onUp?.invoke() ?: localRequester.requestFocusSafe()
                                                true
                                            }
                                            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                                wasEditing = false
                                                isEditing = false
                                                keyboardController?.hide()
                                                onDown?.invoke() ?: localRequester.requestFocusSafe()
                                                true
                                            }
                                            else -> false
                                        }
                                    } else false
                                },
                            decorationBox = { innerTextField ->
                                if (value.isEmpty()) {
                                    Text(
                                        text = placeholderText,
                                        color = CinemaMuted,
                                        fontSize = 14.sp
                                    )
                                }
                                innerTextField()
                            }
                        )
                    }
                }

                if (trailingIcon != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    trailingIcon()
                } else if (value.isNotEmpty() && !isEditing) {
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = { onValueChange("") },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Очистить",
                            tint = CinemaMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PencilAvatarBadge(
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    iconSize: Dp = 13.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .background(Color(0xFF1E1E24), CircleShape)
            .border(1.2.dp, Color.Black, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Edit,
            contentDescription = null,
            tint = Color.Black,
            modifier = Modifier
                .size(iconSize + 1.dp)
                .offset(x = 0.5.dp, y = 0.5.dp)
        )
        Icon(
            imageVector = Icons.Default.Edit,
            contentDescription = null,
            tint = Color.Black,
            modifier = Modifier
                .size(iconSize + 1.dp)
                .offset(x = (-0.5).dp, y = (-0.5).dp)
        )
        Icon(
            imageVector = Icons.Default.Edit,
            contentDescription = "Сменить аватар",
            tint = Color.White,
            modifier = Modifier.size(iconSize)
        )
    }
}

private fun formatRegistrationDate(timestamp: Long?): String {
    if (timestamp == null || timestamp <= 0L) return "21.09.2026"
    return try {
        val sdf = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
        sdf.format(java.util.Date(timestamp))
    } catch (_: Exception) {
        "21.09.2026"
    }
}

@Composable
private fun StatItem(
    icon: ImageVector,
    value: String,
    label: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = CinemaTextGray,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = value,
                color = CinemaTextWhite,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            color = CinemaTextGray,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
