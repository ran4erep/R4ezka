package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.MirrorAuditUiState
import com.example.ui.theme.*
import com.example.ui.tv.tvFocusableItem
import com.example.ui.haptics.HapticEngine
import com.example.ui.haptics.HapticType
import kotlinx.coroutines.delay

@Composable
fun MirrorAuditOverlay(
    state: MirrorAuditUiState,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (state is MirrorAuditUiState.Idle) return

    val cancelButtonFocusRequester = remember { FocusRequester() }
    val settingsButtonFocusRequester = remember { FocusRequester() }

    LaunchedEffect(state) {
        delay(100)
        try {
            if (state is MirrorAuditUiState.Checking) {
                cancelButtonFocusRequester.requestFocus()
            } else if (state is MirrorAuditUiState.Failed) {
                settingsButtonFocusRequester.requestFocus()
            }
        } catch (_: Exception) {}
    }

    BackHandler {
        onCancel()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CinemaBlack.copy(alpha = 0.65f))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CinemaDark),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            border = CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.ui.graphics.SolidColor(CinemaPrimary.copy(alpha = 0.35f))
            ),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 440.dp)
                .testTag("mirror_audit_card")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                when (state) {
                    is MirrorAuditUiState.Checking -> {
                        CircularProgressIndicator(
                            color = CinemaPrimary,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(46.dp)
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        Text(
                            text = "Аудит доступных зеркал",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = CinemaTextWhite,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Индикатор сколько из скольки (например: 1/31, 5/31)
                        val counterText = if (state.totalCount > 0) {
                            "${state.checkedCount}/${state.totalCount}"
                        } else {
                            ""
                        }

                        if (counterText.isNotEmpty()) {
                            Text(
                                text = "Проверка: $counterText",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = CinemaPrimary,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                        }

                        val progressFraction = if (state.totalCount > 0) {
                            (state.checkedCount.toFloat() / state.totalCount.toFloat()).coerceIn(0f, 1f)
                        } else 0f

                        LinearProgressIndicator(
                            progress = { progressFraction },
                            color = CinemaPrimary,
                            trackColor = CinemaMuted.copy(alpha = 0.3f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(CircleShape)
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // Статус проверяемого зеркала (успешно / сбой)
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = CinemaBlack.copy(alpha = 0.4f),
                            border = CardDefaults.outlinedCardBorder().copy(
                                brush = androidx.compose.ui.graphics.SolidColor(CinemaCard)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val statusColor = when (state.lastCheckedSuccess) {
                                true -> Color(0xFF4CAF50)
                                false -> CinemaTextGray
                                null -> CinemaPrimary
                            }
                            val displayStatus = state.lastCheckedStatus.ifEmpty { "Проверка доступности и видеопотока..." }

                            Text(
                                text = displayStatus,
                                fontSize = 12.sp,
                                fontWeight = if (state.lastCheckedSuccess == true) FontWeight.Bold else FontWeight.Normal,
                                color = statusColor,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Button(
                            onClick = {
                                HapticEngine.get().perform(HapticType.GENTLE_TICK)
                                onCancel()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CinemaCard),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .focusRequester(cancelButtonFocusRequester)
                                .tvFocusableItem(onClick = onCancel)
                                .testTag("audit_cancel_btn")
                        ) {
                            Text(
                                text = "Отмена",
                                color = CinemaTextWhite,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    is MirrorAuditUiState.Failed -> {
                        Icon(
                            imageVector = Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = CinemaPrimary,
                            modifier = Modifier.size(52.dp)
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Text(
                            text = state.message,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = CinemaTextWhite,
                            textAlign = TextAlign.Center,
                            lineHeight = 22.sp
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedButton(
                                onClick = {
                                    HapticEngine.get().perform(HapticType.GENTLE_TICK)
                                    onCancel()
                                },
                                shape = RoundedCornerShape(12.dp),
                                border = ButtonDefaults.outlinedButtonBorder.copy(
                                    brush = androidx.compose.ui.graphics.SolidColor(CinemaMuted)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .tvFocusableItem(onClick = onCancel)
                                    .testTag("audit_failed_cancel_btn")
                            ) {
                                Text("Отмена", color = CinemaTextGray, fontSize = 14.sp)
                            }
                            Button(
                                onClick = {
                                    HapticEngine.get().perform(HapticType.SOFT_CLICK)
                                    onOpenSettings()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CinemaPrimary),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(settingsButtonFocusRequester)
                                    .tvFocusableItem(onClick = onOpenSettings)
                                    .testTag("audit_failed_settings_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Настройки", color = CinemaTextWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    MirrorAuditUiState.Idle -> {}
                }
            }
        }
    }
}
