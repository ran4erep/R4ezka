package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
 * Компактный дизайн в едином стиле приложения: аккуратные карточки команд
 * без подсказок со словами, без растягивания по вертикали и со стабильной вёрсткой.
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

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.setDirtyHandsMode(true)
            Toast.makeText(context, "Режим «Грязные руки» включен", Toast.LENGTH_SHORT).show()
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
            recordingStatusText = "Говорите..."
            DirtyHandsEngine.startRecordingCommand(
                action = action,
                onProgress = { remaining, _ ->
                    recordingRemainingMs = remaining
                },
                onFinished = { success, errorMsg ->
                    if (success) {
                        HapticEngine.get().perform(HapticType.CONFIRM)
                        Toast.makeText(context, "Команда сохранена", Toast.LENGTH_SHORT).show()
                        activeRecordingAction = null
                    } else {
                        HapticEngine.get().perform(HapticType.WARNING)
                        recordingStatusText = errorMsg ?: "Ошибка записи"
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (dirtyHandsEnabled) CinemaPrimary.copy(alpha = 0.2f) else CinemaDark)
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        tint = if (dirtyHandsEnabled) CinemaPrimary else CinemaTextGray,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Режим «Грязные руки»",
                        color = CinemaTextWhite,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (dirtyHandsEnabled) "Голосовое управление активно" else "Управление плеером без касания экрана",
                        color = CinemaTextGray,
                        fontSize = 12.sp
                    )
                }
            }

            Switch(
                checked = dirtyHandsEnabled,
                onCheckedChange = { isChecked ->
                    if (isChecked) {
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
                colors = SwitchDefaults.colors(
                    checkedThumbColor = CinemaPrimary,
                    checkedTrackColor = CinemaPrimary.copy(alpha = 0.3f),
                    uncheckedThumbColor = CinemaTextGray,
                    uncheckedTrackColor = CinemaDark
                ),
                modifier = Modifier.testTag("dirty_hands_switch")
            )
        }

        // Блок списка команд при включенном режиме
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
                    text = "Команды управления",
                    color = CinemaTextWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 2.dp, bottom = 8.dp)
                )

                // Список команд
                DirtyHandsAction.entries.forEach { action ->
                    val isConfigured = registeredTemplates.containsKey(action)
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
                                recordingStatusText = "Говорите..."
                                DirtyHandsEngine.startRecordingCommand(
                                    action = action,
                                    onProgress = { remaining, _ ->
                                        recordingRemainingMs = remaining
                                    },
                                    onFinished = { success, errorMsg ->
                                        if (success) {
                                            HapticEngine.get().perform(HapticType.CONFIRM)
                                            Toast.makeText(context, "Команда сохранена", Toast.LENGTH_SHORT).show()
                                            activeRecordingAction = null
                                        } else {
                                            HapticEngine.get().perform(HapticType.WARNING)
                                            recordingStatusText = errorMsg ?: "Ошибка записи"
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
                            Toast.makeText(context, "Команда удалена", Toast.LENGTH_SHORT).show()
                        }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }
    }

    // Компактный диалог записи команды (строгий, аккуратный, без вертикального растягивания)
    if (activeRecordingAction != null) {
        val action = activeRecordingAction!!
        Dialog(onDismissRequest = {
            DirtyHandsEngine.cancelCurrentRecording()
            activeRecordingAction = null
        }) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = CinemaCard,
                border = BorderStroke(1.dp, CinemaPrimary.copy(alpha = 0.4f)),
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .wrapContentHeight()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
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
                            fontSize = 15.sp,
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
                                tint = CinemaTextGray,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = action.title,
                        color = CinemaPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Аккуратный индикатор микрофона фиксированного размера
                    val micBackground = if (isRecordingEngine && liveAudioLevel > 0.08f) {
                        CinemaPrimary
                    } else {
                        CinemaPrimary.copy(alpha = 0.25f)
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(micBackground)
                    ) {
                        Icon(
                            imageVector = if (isRecordingEngine) Icons.Default.Mic else Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = if (isRecordingEngine && liveAudioLevel > 0.08f) Color.Black else CinemaPrimary,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    val secondsFormatted = String.format("%.1f с", recordingRemainingMs / 1000f)
                    Text(
                        text = secondsFormatted,
                        color = CinemaTextWhite,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = recordingStatusText ?: "Говорите...",
                        color = CinemaTextGray,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = {
                            DirtyHandsEngine.cancelCurrentRecording()
                            activeRecordingAction = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CinemaDark),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                    ) {
                        Text("Отмена", color = CinemaTextWhite, fontSize = 13.sp)
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
 * Компактная, строгая вёрстка без растягиваний и без навязывания слов пользователю.
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
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Иконка действия слева
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(if (isConfigured) CinemaPrimary.copy(alpha = 0.15f) else CinemaDark)
            ) {
                Icon(
                    imageVector = getActionIcon(action),
                    contentDescription = null,
                    tint = if (isConfigured) CinemaPrimary else CinemaTextGray,
                    modifier = Modifier.size(17.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Название действия и статус
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = action.title,
                    color = CinemaTextWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                if (isConfigured) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = CinemaPrimary,
                            modifier = Modifier.size(11.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "Записано",
                            color = CinemaPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    Text(
                        text = "Не настроено",
                        color = CinemaTextGray.copy(alpha = 0.7f),
                        fontSize = 10.sp
                    )
                }
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
                            .size(30.dp)
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
                            modifier = Modifier.size(15.dp)
                        )
                    }

                    // Кнопка удаления
                    IconButton(
                        onClick = {
                            HapticEngine.get().perform(HapticType.SOFT_CLICK)
                            onDelete()
                        },
                        modifier = Modifier
                            .size(30.dp)
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
                            modifier = Modifier.size(14.dp)
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
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier
                        .height(30.dp)
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
                        modifier = Modifier.size(12.dp)
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
