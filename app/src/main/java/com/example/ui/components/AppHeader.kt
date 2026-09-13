package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

/**
 * Единая шапка приложения R4ezka:
 * - Логотип "R4ezka" в фирменном красном стиле
 * - Кнопка аккаунта ("Войти" или аватарка с никнеймом)
 * - Кнопка перехода в "Настройки"
 *
 * Отображается на всех основных вкладках (Каталог, Избранное, История).
 * Высокопроизводительный компонент с минимальной нагрузкой на CPU.
 */
@Composable
fun AppHeader(
    isLoggedIn: Boolean,
    currentUser: String?,
    currentUserAvatar: String?,
    onAuthClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "R4ezka",
            color = CinemaPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.2.sp
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(IntrinsicSize.Min)
        ) {
            // Кнопка входа / Профиль (аватарка и никнейм)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50.dp))
                    .background(CinemaCard)
                    .clickable(onClick = onAuthClick)
                    .padding(horizontal = if (isLoggedIn) 10.dp else 14.dp, vertical = 6.dp)
                    .testTag("header_account_button"),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isLoggedIn) {
                        UserAvatar(
                            avatar = currentUserAvatar,
                            size = 20.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = currentUser ?: "Профиль",
                            color = CinemaTextWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        Text(
                            text = "Войти",
                            color = CinemaTextWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Кнопка настроек
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(50.dp))
                    .background(CinemaCard)
                    .clickable(onClick = onSettingsClick)
                    .testTag("header_settings_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Настройки",
                    tint = CinemaTextWhite,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
