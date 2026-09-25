package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.MirrorAuditUiState
import com.example.ui.theme.*
import com.example.ui.tv.tvFocusableItem
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
            .background(CinemaBlack.copy(alpha = 0.94f))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CinemaDark),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .widthIn(max = 440.dp)
                .testTag("mirror_audit_card")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                when (state) {
                    is MirrorAuditUiState.Checking -> {
                        CircularProgressIndicator(
                            color = CinemaPrimary,
                            strokeWidth = 3.5.dp,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "Проверяем доступные зеркала...",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CinemaTextWhite,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = onCancel,
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
                                fontSize = 15.sp,
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
                                onClick = onCancel,
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
                                onClick = onOpenSettings,
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
