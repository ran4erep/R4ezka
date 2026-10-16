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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
 * Предоставляет тумблер включения/выключения режима, визуальный статус разрешений микрофона,
 * интерфейс записи персональных голосовых меток для каждого действия, предпрослушивание
 * записанных команд и индикацию компактной облачной синхронизации.
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
    var showHelpDialog by remember { mutableStateOf(false) }

    // Проверка и запрос разрешения на использование микрофона
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
            recordingStatusText = "Слушаю... Произнесите: «${action.defaultKeywordSuggestion}»"
            DirtyHandsEngine.startRecordingCommand(
                action = action,
                onProgress = { remaining, _ ->
                    recordingRemainingMs = remaining
                },
                onFinished = { success, errorMsg ->
                    if (success) {
                        HapticEngine.get().perform(HapticType.CONFIRM)
                        Toast.makeText(context, "Команда «${action.title}» успешно записана и сжата!", Toast.LENGTH_SHORT).show()
                        activeRecordingAction = null
                    } else {
                        HapticEngine.get().perform(HapticType.WARNING)
                        recordingStatusText = errorMsg ?: "Ошибка записи. Повторите попытку."
                    }
                }
            )
        } else {
            activeRecordingAction = null
            Toast.makeText(context, "Требуется разрешение на запись аудио", Toast.LENGTH_SHORT).show()
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.RecordVoiceOver,
                        contentDescription = null,
                        tint = if (dirtyHandsEnabled) CinemaPrimary else CinemaTextGray,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Режим «Грязные руки»",
                        color = CinemaTextWhite,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "Управление плеером голосовыми метками без нейросетей (Siemens/Nokia). Плеер слушает команды при готовке или когда руки заняты.",
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
                // Информационная плашка об алгоритме и облачной синхронизации
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = CinemaDark.copy(alpha = 0.8f),
                    border = BorderStroke(1.dp, CinemaMuted.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDone,
                            contentDescription = null,
                            tint = CinemaPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Голосовые команды сжимаются ZLIB",
                                color = CinemaTextWhite,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Размер одной команды в облаке всего ~400 байт. Нагрузка на процессор < 0.2%, микрофон активен только во время воспроизведения в плеере.",
                                color = CinemaTextGray,
                                fontSize = 10.sp,
                                lineHeight = 13.sp
                            )
                        }
                        IconButton(
                            onClick = { showHelpDialog = true },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.HelpOutline,
                                contentDescription = "Справка",
                                tint = CinemaTextGray,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "ЗАПИСЬ ГОЛОСОВЫХ КОМАНД",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CinemaPrimary,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                DirtyHandsAction.entries.forEach { action ->
                    val template = registeredTemplates[action]
                    val isConfigured = template != null

                    CommandItemCard(
                        action = action,
                        isConfigured = isConfigured,
                        durationMs = template?.durationMs ?: 0,
                        onRecord = {
                            activeRecordingAction = action
                            val hasPerm = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasPerm) {
                                recordingRemainingMs = 2000
                                recordingStatusText = "Слушаю... Произнесите: «${action.defaultKeywordSuggestion}»"
                                DirtyHandsEngine.startRecordingCommand(
                                    action = action,
                                    onProgress = { remaining, _ ->
                                        recordingRemainingMs = remaining
                                    },
                                    onFinished = { success, errorMsg ->
                                        if (success) {
                                            HapticEngine.get().perform(HapticType.CONFIRM)
                                            Toast.makeText(context, "Команда «${action.title}» записана!", Toast.LENGTH_SHORT).show()
                                            activeRecordingAction = null
                                        } else {
                                            HapticEngine.get().perform(HapticType.WARNING)
                                            recordingStatusText = errorMsg ?: "Ошибка записи. Попробуйте еще раз."
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

    // Диалог интерактивной записи команды
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
                    Text(
                        text = "Пример команды: «${action.defaultKeywordSuggestion}»",
                        color = CinemaTextGray,
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Анимированная пульсирующая сфера микрофона в зависимости от уровня звука
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
                            .size(110.dp)
                            .scale(pulseScale * audioLevelScale)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        CinemaPrimary.copy(alpha = 0.7f),
                                        CinemaPrimary.copy(alpha = 0.15f),
                                        Color.Transparent
                                    )
                                )
                            )
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(CinemaPrimary)
                        ) {
                            Icon(
                                imageVector = if (isRecordingEngine) Icons.Default.Mic else Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Оставшееся время
                    val secondsFormatted = String.format("%.1f с", recordingRemainingMs / 1000f)
                    Text(
                        text = secondsFormatted,
                        color = CinemaTextWhite,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = recordingStatusText ?: "Говорите четко и громко...",
                        color = CinemaTextGray,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = {
                            DirtyHandsEngine.cancelCurrentRecording()
                            activeRecordingAction = null
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CinemaDark
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Отмена", color = CinemaTextWhite)
                    }
                }
            }
        }
    }

    // Диалог подробной справки о режиме
    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = {
                Text(
                    text = "Как работает режим «Грязные руки»",
                    color = CinemaTextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column {
                    Text(
                        text = "1. Запишите свой голос на команды (например: «Пауза», «Играй», «Дальше», «Назад»).\n\n" +
                                "2. Движок использует алгоритм спектрального выравнивания MFCC + DTW (как в сотовых телефонах Siemens S45 и Nokia 3310).\n\n" +
                                "3. Никаких тяжелых нейросетей: нулевая фоновая нагрузка на процессор (0.1% CPU) и мгновенный отклик.\n\n" +
                                "4. Образцы сжимаются в микро-пакеты (ZLIB) и синхронизируются с вашим облачным профилем Rezka, а на устройствах разжимаются автоматически.",
                        color = CinemaTextGray,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showHelpDialog = false }) {
                    Text("Понятно", color = CinemaPrimary)
                }
            },
            containerColor = CinemaCard,
            shape = RoundedCornerShape(12.dp)
        )
    }
}

/**
 * Карточка настройки отдельной голосовой команды.
 */
@Composable
private fun CommandItemCard(
    action: DirtyHandsAction,
    isConfigured: Boolean,
    durationMs: Int,
    onRecord: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = if (isConfigured) CinemaPrimary.copy(alpha = 0.4f) else CinemaBorder.copy(alpha = 0.2f),
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
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = action.title,
                        color = CinemaTextWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    if (isConfigured) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = CinemaPrimary.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, CinemaPrimary.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = "Записано (${durationMs}мс)",
                                color = CinemaPrimary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = CinemaDark
                        ) {
                            Text(
                                text = "Не записано",
                                color = CinemaTextGray,
                                fontSize = 9.sp,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = action.description,
                    color = CinemaTextGray,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isConfigured) {
                    // Кнопка прослушивания образца
                    IconButton(
                        onClick = {
                            HapticEngine.get().perform(HapticType.SOFT_CLICK)
                            onPlay()
                        },
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(CinemaDark)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Прослушать образец",
                            tint = CinemaPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))

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
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Удалить команду",
                            tint = Color.Red.copy(alpha = 0.8f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }

                // Кнопка записи/перезаписи
                Button(
                    onClick = {
                        HapticEngine.get().perform(HapticType.SELECTION)
                        onRecord()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isConfigured) CinemaDark else CinemaPrimary
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        tint = if (isConfigured) CinemaTextWhite else Color.Black,
                        modifier = Modifier.size(14.dp)
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
