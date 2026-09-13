package com.example.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.ui.RezkaViewModel
import com.example.ui.theme.*

@Composable
fun AuthDialog(
    viewModel: RezkaViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isLoggedIn by viewModel.isLoggedIn.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val currentUserAvatar by viewModel.currentUserAvatar.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()

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

    Dialog(onDismissRequest = {
        if (!isSubmittingAuth) {
            onDismiss()
        }
    }) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CinemaDark),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .testTag("auth_dialog_card")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
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
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = CinemaTextGray)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (isLoggedIn) {
                    // Авторизованный аккаунт с кликабельным аватаром и ником
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CinemaCard, RoundedCornerShape(14.dp))
                            .clickable { showAvatarPicker = true }
                            .padding(14.dp)
                    ) {
                        Box(contentAlignment = Alignment.BottomEnd) {
                            UserAvatar(
                                avatar = currentUserAvatar,
                                size = 52.dp,
                                onClick = { showAvatarPicker = true }
                            )
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .background(CinemaPrimary, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AddPhotoAlternate,
                                    contentDescription = "Сменить аватар",
                                    tint = CinemaBlack,
                                    modifier = Modifier.size(11.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { showAvatarPicker = true }
                        ) {
                            Text(
                                text = currentUser ?: "Пользователь",
                                color = CinemaTextWhite,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Нажмите, чтобы сменить аватар",
                                color = CinemaPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Принудительная синхронизация
                    OutlinedButton(
                        onClick = {
                            viewModel.syncCloudData {
                                Toast.makeText(context, "Синхронизация завершена", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = !isSyncing,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CinemaTextWhite),
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            brush = SolidColor(CinemaPrimary)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(color = CinemaPrimary, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Синхронизация...", fontSize = 14.sp)
                        } else {
                            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp), tint = CinemaPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Синхронизировать сейчас", fontSize = 14.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = {
                            viewModel.logout()
                            Toast.makeText(context, "Вы вышли из аккаунта", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CinemaSecondary),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Выйти из аккаунта", fontSize = 14.sp)
                    }
                } else {
                    // Переключатель: Вход / Регистрация
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CinemaCard, RoundedCornerShape(10.dp))
                            .padding(3.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (!isRegisterMode) CinemaPrimary else Color.Transparent)
                                .clickable {
                                    isRegisterMode = false
                                    authError = null
                                }
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
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isRegisterMode) CinemaPrimary else Color.Transparent)
                                .clickable {
                                    isRegisterMode = true
                                    authError = null
                                }
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

                    if (isRegisterMode) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showAvatarPicker = true }
                        ) {
                            Box(contentAlignment = Alignment.BottomEnd) {
                                UserAvatar(
                                    avatar = registerAvatar,
                                    size = 60.dp,
                                    onClick = { showAvatarPicker = true }
                                )
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .background(CinemaPrimary, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AddPhotoAlternate,
                                        contentDescription = "Выбрать аватар",
                                        tint = CinemaBlack,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
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

                    // Логин
                    TextField(
                        value = loginInput,
                        onValueChange = {
                            loginInput = it
                            authError = null
                        },
                        placeholder = { Text("Логин", color = CinemaMuted) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = CinemaCard,
                            unfocusedContainerColor = CinemaCard,
                            focusedTextColor = CinemaTextWhite,
                            unfocusedTextColor = CinemaTextWhite,
                            focusedIndicatorColor = CinemaPrimary,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auth_login_input")
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Пароль
                    TextField(
                        value = passwordInput,
                        onValueChange = {
                            passwordInput = it
                            authError = null
                        },
                        placeholder = { Text("Пароль", color = CinemaMuted) },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = if (passwordVisible) "Скрыть пароль" else "Показать пароль",
                                    tint = CinemaTextGray
                                )
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = CinemaCard,
                            unfocusedContainerColor = CinemaCard,
                            focusedTextColor = CinemaTextWhite,
                            unfocusedTextColor = CinemaTextWhite,
                            focusedIndicatorColor = CinemaPrimary,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auth_password_input")
                    )

                    // Повтор пароля (два раза) для регистрации
                    if (isRegisterMode) {
                        Spacer(modifier = Modifier.height(12.dp))
                        TextField(
                            value = confirmPasswordInput,
                            onValueChange = {
                                confirmPasswordInput = it
                                authError = null
                            },
                            placeholder = { Text("Повторите пароль", color = CinemaMuted) },
                            singleLine = true,
                            visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                                    Icon(
                                        imageVector = if (confirmPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = if (confirmPasswordVisible) "Скрыть пароль" else "Показать пароль",
                                        tint = CinemaTextGray
                                    )
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = CinemaCard,
                                unfocusedContainerColor = CinemaCard,
                                focusedTextColor = CinemaTextWhite,
                                unfocusedTextColor = CinemaTextWhite,
                                focusedIndicatorColor = CinemaPrimary,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("auth_confirm_password_input")
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

                    // Кнопка действия
                    Button(
                        onClick = {
                            val user = loginInput.trim()
                            val pass = passwordInput
                            if (user.isBlank() || pass.isBlank()) {
                                authError = "Заполните логин и пароль"
                                return@Button
                            }

                            if (isRegisterMode) {
                                if (user.length < 3) {
                                    authError = "Логин должен быть от 3 символов"
                                    return@Button
                                }
                                if (pass.length < 4) {
                                    authError = "Пароль должен быть от 4 символов"
                                    return@Button
                                }
                                if (pass != confirmPasswordInput) {
                                    authError = "Пароли не совпадают"
                                    return@Button
                                }

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
                        },
                        enabled = !isSubmittingAuth,
                        colors = ButtonDefaults.buttonColors(containerColor = CinemaPrimary),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
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
                            isRegisterMode = !isRegisterMode
                            authError = null
                        },
                        modifier = Modifier.fillMaxWidth()
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
