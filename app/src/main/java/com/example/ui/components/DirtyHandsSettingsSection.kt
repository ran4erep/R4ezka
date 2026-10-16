package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.example.data.DirtyHandsAction
import com.example.data.DirtyHandsEngine
import com.example.ui.RezkaViewModel
import com.example.ui.haptics.HapticEngine
import com.example.ui.haptics.HapticType
import com.example.ui.theme.*
import com.example.ui.tv.tvFocusableItem

/**
 * Секция настроек режима «Грязные руки» в категории «Воспроизведение».
 *
 * Чистый, лаконичный дизайн без лишних технических терминов, с идеальной
 * вёрсткой списка команд и удобным интерфейсом записи персональных голосовых меток.
 */
@Composable
fun DirtyHandsSettingsSection(
    viewModel: RezkaViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val dirtyHandsEnabled by viewModel.dirtyHandsMode.collectAsState()
    val registeredTemplates by DirtyHandsEngine.registeredTemplates.collectAsState()
    val isRecordingEngine by DirtyHandsEngine.isRecording.collectAsState()
    val liveAudioLevel by DirtyHandsEngine.audioLevel.collectAsState()

    var activeRecordingAction by remember { mutableStateOf<DirtyHandsAction?>(null) }
    var recordingRemainingMs by remember { mutableStateOf(2000) }
    var recordingStatusText by remember { mutableStateOf<String?>(null) }

    // Разрешение на использование микрофона
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.setDirtyHandsMode(true)
            Toast.makeText(context, "Режим «Грязные руки» активирован", Toast.LENGTH_SHORT).show()
        } else {
            viewModel.setDirtyHandsMode(false)
            Toast.makeText(context, "Для работы режима необходим доступ к микрофону", Toast.LENGTH_LONG).show()
        }
    }

    val recordPermissionLauncherForRecordButton = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val action = activeRecordingAction
        if (isGranted && action != null) {
            recordingRemainingMs = 2000
            recordingStatusText = "Произнесите: «${action.defaultKeywordSuggestion}»"
            DirtyHandsEngine.startRecordingCommand(
                action = action,
                onProgress = { remaining, _ ->
                    recordingRemainingMs = remaining
                },
                onFinished = { success, errorMsg ->
                    if (success) {
                        HapticEngine.get().perform(HapticType.CONFIRM)
                        Toast.makeText(context, "Команда «${action.title}» записана", Toast.LENGTH_SHORT).show()
                        activeRecordingAction = null
                    } else {
                        HapticEngine.get().perform(HapticType.WARNING)
                        recordingStatusText = errorMsg ?: "Ошибка записи. Повторите попытку."
                    }
                }
            )
        } else {
            activeRecordingAction = null
            Toast.makeText(context, "Требуется доступ к микрофону", Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Главная строка с тумблером: "Режим «Грязные руки»"
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(CinemaCard)
                .tvFocusableItem(
                    onClick = {
                        val newTarget = !dirtyHandsEnabled
                        if (newTarget) {
                            val hasPerm = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasPerm) {
                                HapticEngine.get().perform(HapticType.TOGGLE)
                                viewModel.setDirtyHandsMode(true)
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        } else {
                            HapticEngine.get().perform(HapticType.TOGGLE)
                            viewModel.setDirtyHandsMode(false)
                            DirtyHandsEngine.stopListening()
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    scaleFactor = 1.0f
                )
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .testTag("dirty_hands_mode_row"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            ) {
                Text(
                    text = "Режим «Грязные руки»",
                    color = CinemaTextWhite,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "Управление воспроизведением голосовыми командами без касания экрана (например, при готовке)",
                    color = CinemaTextGray,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }

            Switch(
                checked = dirtyHandsEnabled,
                onCheckedChange = { checked ->
                    HapticEngine.get().perform(HapticType.TOGGLE)
                    if (checked) {
                        val hasPerm = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasPerm) {
                            viewModel.setDirtyHandsMode(true)
                        } else {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    } else {
                        viewModel.setDirtyHandsMode(false)
                        DirtyHandsEngine.stopListening()
                    }
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = CinemaTextWhite,
                    checkedTrackColor = CinemaPrimary,
                    uncheckedThumbColor = CinemaTextGray,
                    uncheckedTrackColor = CinemaDark
                ),
                modifier = Modifier.testTag("dirty_hands_mode_switch")
            )
        }

        // Настройки команд под тумблером
        AnimatedVisibility(
            visible = dirtyHandsEnabled,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            ) {
                Text(
                    text = "ГОЛОСОВЫЕ КОМАНДЫ",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CinemaPrimary,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                )

                DirtyHandsAction.entries.forEach { action ->
                    val template = registeredTemplates[action]
                    val isConfigured = template != null

                    CommandItemCard(
                        action = action,
                        isConfigured = isConfigured,
                        onRecord = {
                            activeRecordingAction = action
                            val hasPerm = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasPerm) {
                                recordingRemainingMs = 2000
                                recordingStatusText = "Произнесите команду: «${action.defaultKeywordSuggestion}»"
                                DirtyHandsEngine.startRecordingCommand(
                                    action = action,
                                    onProgress = { remaining, _ ->
                                        recordingRemainingMs = remaining
                                    },
                                    onFinished = { success, errorMsg ->
                                        if (success) {
                                            HapticEngine.get().perform(HapticType.CONFIRM)
                                            Toast.makeText(context, "Команда «${action.title}» сохранена", Toast.LENGTH_SHORT).show()
                                            activeRecordingAction = null
                                        } else {
                                            HapticEngine.get().perform(HapticType.WARNING)
                                            recordingStatusText = errorMsg ?: "Ошибка записи. Повторите попытку."
                                        }
                                    }
                                )
                            } else {
                                recordPermissionLauncherForRecordButton.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onPlay = {
                            DirtyHandsEngine.playRecordedSample(action)
                        },
                        onDelete = {
                            DirtyHandsEngine.deleteCommand(action)
                            Toast.makeText(context, "Команда «${action.title}» удалена", Toast.LENGTH_SHORT).show()
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }

    // Диалог записи команды
    if (activeRecordingAction != null) {
        val action = activeRecordingAction!!
        Dialog(onDismissRequest = {
            DirtyHandsEngine.cancelCurrentRecording()
            activeRecordingAction = null
        }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = CinemaCard,
                border = BorderStroke(1.dp, CinemaPrimary.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Запись команды",
                            color = CinemaTextWhite,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(
                            onClick = {
                                DirtyHandsEngine.cancelCurrentRecording()
                                activeRecordingAction = null
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Закрыть",
                                tint = CinemaTextGray
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = action.title,
                        color = CinemaPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Рекомендуемое слово: «${action.defaultKeywordSuggestion}»",
                        color = CinemaTextGray,
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Индикатор микрофона
                    val pulseScale by rememberInfiniteTransition(label = "pulse").animateFloat(
                        initialValue = 1f,
                        targetValue = 1.08f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "mic_scale"
                    )

                    val audioLevelScale = 1f + liveAudioLevel * 0.45f

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(100.dp)
                            .scale(pulseScale * audioLevelScale)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        CinemaPrimary.copy(alpha = 0.65f),
                                        CinemaPrimary.copy(alpha = 0.12f),
                                        Color.Transparent
                                    )
                                )
                            )
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(60.dp)
                                .clip(CircleShape)
                                .background(CinemaPrimary)
                        ) {
                            Icon(
                                imageVector = if (isRecordingEngine) Icons.Default.Mic else Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val secondsFormatted = String.format("%.1f с", recordingRemainingMs / 1000f)
                    Text(
                        text = secondsFormatted,
                        color = CinemaTextWhite,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = recordingStatusText ?: "Говорите четко в микрофон...",
                        color = CinemaTextGray,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    Button(
                        onClick = {
                            DirtyHandsEngine.cancelCurrentRecording()
                            activeRecordingAction = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CinemaDark),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Отмена", color = CinemaTextWhite)
                    }
                }
            }
        }
    }
}

/**
 * Иконка соответствующего действия плеера.
 */
private fun getActionIcon(action: DirtyHandsAction): ImageVector {
    return when (action) {
        DirtyHandsAction.PAUSE -> Icons.Default.Pause
        DirtyHandsAction.RESUME -> Icons.Default.PlayArrow
        DirtyHandsAction.NEXT_EPISODE -> Icons.Default.SkipNext
        DirtyHandsAction.PREV_EPISODE -> Icons.Default.SkipPrevious
        DirtyHandsAction.SEEK_FORWARD -> Icons.Default.FastForward
        DirtyHandsAction.SEEK_BACKWARD -> Icons.Default.FastRewind
    }
}

/**
 * Карточка настройки отдельной голосовой команды.
 * Вёрстка строго выверена: никаких съезжающих текстов или ломающихся элементов.
 */
@Composable
private fun CommandItemCard(
    action: DirtyHandsAction,
    isConfigured: Boolean,
    onRecord: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = if (isConfigured) CinemaPrimary.copy(alpha = 0.35f) else CinemaBorder.copy(alpha = 0.2f),
        label = "border_color"
    )

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = CinemaCard,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Иконка действия слева
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (isConfigured) CinemaPrimary.copy(alpha = 0.15f) else CinemaDark)
            ) {
                Icon(
                    imageVector = getActionIcon(action),
                    contentDescription = null,
                    tint = if (isConfigured) CinemaPrimary else CinemaTextGray,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Текстовая информация (название и подсказка слова)
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = action.title,
                        color = CinemaTextWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    if (isConfigured) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = CinemaPrimary.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, CinemaPrimary.copy(alpha = 0.4f))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = CinemaPrimary,
                                    modifier = Modifier.size(9.dp)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = "Записано",
                                    color = CinemaPrimary,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = "Слово: «${action.defaultKeywordSuggestion}»",
                    color = CinemaTextGray,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Кнопки управления справа
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isConfigured) {
                    // Кнопка прослушивания
                    IconButton(
                        onClick = {
                            HapticEngine.get().perform(HapticType.SOFT_CLICK)
                            onPlay()
                        },
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(CinemaDark)
                            .tvFocusableItem(
                                onClick = onPlay,
                                shape = CircleShape,
                                scaleFactor = 1.05f
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Прослушать",
                            tint = CinemaPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Кнопка удаления
                    IconButton(
                        onClick = {
                            HapticEngine.get().perform(HapticType.SOFT_CLICK)
                            onDelete()
                        },
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(CinemaDark)
                            .tvFocusableItem(
                                onClick = onDelete,
                                shape = CircleShape,
                                scaleFactor = 1.05f
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Удалить",
                            tint = Color.Red.copy(alpha = 0.75f),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }

                // Кнопка записи / перезаписи
                Button(
                    onClick = {
                        HapticEngine.get().perform(HapticType.SELECTION)
                        onRecord()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isConfigured) CinemaDark else CinemaPrimary
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier
                        .height(32.dp)
                        .tvFocusableItem(
                            onClick = onRecord,
                            shape = RoundedCornerShape(8.dp),
                            scaleFactor = 1.02f
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        tint = if (isConfigured) CinemaTextWhite else Color.Black,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isConfigured) "Заново" else "Записать",
                        color = if (isConfigured) CinemaTextWhite else Color.Black,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
