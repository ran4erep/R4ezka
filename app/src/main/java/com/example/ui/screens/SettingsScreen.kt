package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import com.example.ui.util.rememberSavedScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.RezkaService
import com.example.data.isMovie
import com.example.ui.RezkaViewModel
import com.example.ui.theme.*
import com.example.ui.tv.TvDetector
import com.example.ui.tv.TvModePreference
import com.example.ui.tv.TvRemoteInputField
import com.example.ui.tv.dpadScrollable
import com.example.ui.tv.tvFocusableItem
import androidx.compose.foundation.shape.CircleShape
import kotlinx.coroutines.launch

private data class QualityOption(
    val key: String,
    val title: String,
    val subtitle: String? = null
)

@Composable
fun SettingsScreen(
    viewModel: RezkaViewModel,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberSavedScrollState("settings", viewModel)
    val currentMirror by viewModel.currentMirror.collectAsState()
    val defaultQuality by viewModel.defaultQuality.collectAsState()
    val selectedPlayer by viewModel.selectedPlayer.collectAsState()
    val installedPlayers by viewModel.installedPlayers.collectAsState()
    val autoNextEpisode by viewModel.autoNextEpisode.collectAsState()
    val defaultResizeMode by viewModel.defaultResizeMode.collectAsState()
    val tvModePreference by viewModel.tvModePreference.collectAsState()
    val cardGridMode by viewModel.cardGridMode.collectAsState()
    val presetMirrors = viewModel.presetMirrors

    var customMirrorInput by remember(currentMirror) { mutableStateOf(currentMirror) }
    var pingResult by remember { mutableStateOf<String?>(null) }
    var isPinging by remember { mutableStateOf(false) }

    // Категории в выпадающем виде (аккордеоны - все свёрнуты по умолчанию)
    var isMirrorsExpanded by remember { mutableStateOf(false) }
    var isPlaybackExpanded by remember { mutableStateOf(false) }
    var isTvExpanded by remember { mutableStateOf(false) }
    var isSubscriptionsExpanded by remember { mutableStateOf(false) }

    val subscriptions by viewModel.subscriptions.collectAsState()
    val isCheckingSeriesUpdates by viewModel.isCheckingSeriesUpdates.collectAsState()

    var tvModeDropdownExpanded by remember { mutableStateOf(false) }
    var playerDropdownExpanded by remember { mutableStateOf(false) }
    var gridDropdownExpanded by remember { mutableStateOf(false) }
    var showCustomGridDialog by remember { mutableStateOf(false) }

    val mirrorArrowRotation by animateFloatAsState(
        targetValue = if (isMirrorsExpanded) 180f else 0f,
        label = "mirrorArrowRotation"
    )
    val playbackArrowRotation by animateFloatAsState(
        targetValue = if (isPlaybackExpanded) 180f else 0f,
        label = "playbackArrowRotation"
    )
    val tvArrowRotation by animateFloatAsState(
        targetValue = if (isTvExpanded) 180f else 0f,
        label = "tvArrowRotation"
    )
    val subscriptionsArrowRotation by animateFloatAsState(
        targetValue = if (isSubscriptionsExpanded) 180f else 0f,
        label = "subscriptionsArrowRotation"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CinemaBlack)
            .statusBarsPadding()
    ) {
        // Прокручиваемая область настроек
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .dpadScrollable(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
        // Header with Back Button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            if (onBack != null) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(CinemaCard)
                        .tvFocusableItem(
                            onClick = onBack,
                            shape = CircleShape,
                            scaleFactor = 1.0f
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Назад",
                        tint = CinemaTextWhite,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
            } else {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Настройки",
                    tint = CinemaPrimary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Text(
                text = "Настройки",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = CinemaTextWhite
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ==========================================
        // КАТЕГОРИЯ: ЗЕРКАЛА САЙТА
        // ==========================================
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CinemaDark),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("mirrors_category_card")
        ) {
            Column {
                // Header аккордеона
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusableItem(
                            onClick = { isMirrorsExpanded = !isMirrorsExpanded },
                            shape = RoundedCornerShape(16.dp),
                            scaleFactor = 1.0f
                        )
                        .padding(16.dp)
                        .testTag("settings_accordion_mirrors")
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(CinemaMuted.copy(alpha = 0.2f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = CinemaTextGray,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Зеркала",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = CinemaTextWhite,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isMirrorsExpanded) "Свернуть" else "Развернуть",
                        tint = CinemaTextGray,
                        modifier = Modifier
                            .size(22.dp)
                            .rotate(mirrorArrowRotation)
                    )
                }

                // Тело аккордеона Зеркала
                AnimatedVisibility(
                    visible = isMirrorsExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                    ) {
                        Divider(color = CinemaMuted.copy(alpha = 0.3f), thickness = 1.dp)
                        Spacer(modifier = Modifier.height(14.dp))

                        // Текущий статус зеркала
                        Text(
                            text = "ТЕКУЩЕЕ ЗЕРКАЛО",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CinemaPrimary,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(CinemaGreen, RoundedCornerShape(50.dp))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = currentMirror,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = CinemaTextWhite,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val triggerPing: () -> Unit = {
                                isPinging = true
                                pingResult = null
                                coroutineScope.launch {
                                    val result = viewModel.testMirror(currentMirror)
                                    isPinging = false
                                    if (result.isSuccess) {
                                        val ms = result.getOrNull() ?: 0
                                        pingResult = "Отклик: ${ms} мс (Каталог доступен)"
                                    } else {
                                        pingResult = "Недоступно: ${result.exceptionOrNull()?.message ?: "таймаут"}"
                                    }
                                }
                            }
                            Button(
                                onClick = triggerPing,
                                colors = ButtonDefaults.buttonColors(containerColor = CinemaSecondary),
                                shape = RoundedCornerShape(10.dp),
                                enabled = !isPinging,
                                modifier = Modifier
                                    .tvFocusableItem(
                                        onClick = { if (!isPinging) triggerPing() },
                                        shape = RoundedCornerShape(10.dp),
                                        scaleFactor = 1.0f
                                    )
                                    .testTag("ping_mirror_button")
                            ) {
                                if (isPinging) {
                                    CircularProgressIndicator(
                                        color = CinemaTextWhite,
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                } else {
                                    Icon(
                                        Icons.Default.Speed,
                                        contentDescription = null,
                                        tint = CinemaTextWhite,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                }
                                Text("Проверить отклик", fontSize = 12.sp, color = CinemaTextWhite)
                            }

                            if (currentMirror != RezkaService.PRIMARY_MIRROR) {
                                val triggerReset = {
                                    val def = viewModel.resetMirrorToDefault()
                                    customMirrorInput = def
                                    Toast.makeText(context, "Сброшено на $def", Toast.LENGTH_SHORT).show()
                                }
                                OutlinedButton(
                                    onClick = triggerReset,
                                    shape = RoundedCornerShape(10.dp),
                                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(CinemaMuted)),
                                    modifier = Modifier.tvFocusableItem(
                                        onClick = triggerReset,
                                        shape = RoundedCornerShape(10.dp),
                                        scaleFactor = 1.0f
                                    )
                                ) {
                                    Text("Сброс", fontSize = 12.sp, color = CinemaTextGray)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        val triggerAudit = { viewModel.startMirrorAudit(isFirstLaunch = false) }
                        Button(
                            onClick = triggerAudit,
                            colors = ButtonDefaults.buttonColors(containerColor = CinemaCard),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, CinemaPrimary.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .tvFocusableItem(
                                    onClick = triggerAudit,
                                    shape = RoundedCornerShape(10.dp),
                                    scaleFactor = 1.0f
                                )
                                .testTag("run_mirror_audit_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = CinemaPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Провести аудит",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = CinemaTextWhite
                            )
                        }

                        AnimatedVisibility(visible = pingResult != null) {
                            pingResult?.let { text ->
                                Spacer(modifier = Modifier.height(8.dp))
                                val isOk = text.startsWith("Отклик:")
                                Text(
                                    text = text,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isOk) CinemaGreen else CinemaPrimary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Предустановленные зеркала
                        Text(
                            text = "ПРЕДУСТАНОВЛЕННЫЕ ЗЕРКАЛА",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CinemaTextGray,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        var mirrorDropdownExpanded by remember { mutableStateOf(false) }

                        Box(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(CinemaCard)
                                    .tvFocusableItem(
                                        onClick = { mirrorDropdownExpanded = true },
                                        shape = RoundedCornerShape(10.dp),
                                        scaleFactor = 1.0f
                                    )
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                                    .testTag("mirror_dropdown_trigger"),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f, fill = false)) {
                                    Text(
                                        text = currentMirror,
                                        color = CinemaTextWhite,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (currentMirror == RezkaService.PRIMARY_MIRROR) {
                                        Text(
                                            text = "Основное зеркало",
                                            fontSize = 11.sp,
                                            color = CinemaPrimary
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = if (mirrorDropdownExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                    contentDescription = "Выбор зеркала",
                                    tint = CinemaPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = mirrorDropdownExpanded,
                                onDismissRequest = { mirrorDropdownExpanded = false },
                                modifier = Modifier
                                    .background(CinemaDark)
                                    .fillMaxWidth(0.85f)
                                    .heightIn(max = 280.dp)
                            ) {
                                presetMirrors.forEach { mirror ->
                                    val isSelected = currentMirror == mirror
                                    val isDefault = mirror == RezkaService.PRIMARY_MIRROR
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(
                                                    text = mirror,
                                                    color = if (isSelected) CinemaPrimary else CinemaTextWhite,
                                                    fontSize = 13.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                )
                                                if (isDefault) {
                                                    Text(
                                                        text = "Основное зеркало",
                                                        color = CinemaPrimary,
                                                        fontSize = 10.sp
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            mirrorDropdownExpanded = false
                                            if (!isSelected) {
                                                viewModel.setMirror(mirror)
                                                customMirrorInput = mirror
                                                pingResult = null
                                                Toast.makeText(context, "Зеркало изменено на $mirror", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        colors = MenuDefaults.itemColors(
                                            textColor = CinemaTextWhite
                                        )
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Своё зеркало вручную
                        Text(
                            text = "СВОЙ АДРЕС ЗЕРКАЛА",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CinemaTextGray,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        val applyCustomMirror = {
                            val input = customMirrorInput.trim()
                            if (input.isBlank()) {
                                Toast.makeText(context, "Введите адрес зеркала", Toast.LENGTH_SHORT).show()
                            } else {
                                val success = viewModel.setMirror(input)
                                if (success) {
                                    pingResult = null
                                    Toast.makeText(context, "Зеркало успешно установлено: ${viewModel.currentMirror.value}", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "Некорректный адрес URL зеркала", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }

                        val customMirrorFocusRequester = remember { FocusRequester() }

                        TvRemoteInputField(
                            value = customMirrorInput,
                            onValueChange = { customMirrorInput = it },
                            placeholder = "https://зеркало.com",
                            onCommit = applyCustomMirror,
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            containerColor = CinemaCard,
                            focusRequester = customMirrorFocusRequester,
                            testTag = "custom_mirror_input",
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Button(
                            onClick = applyCustomMirror,
                            colors = ButtonDefaults.buttonColors(containerColor = CinemaPrimary),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .tvFocusableItem(
                                    onClick = applyCustomMirror,
                                    shape = RoundedCornerShape(10.dp),
                                    scaleFactor = 1.0f
                                )
                                .testTag("apply_mirror_button")
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Сохранить и применить", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ==========================================
        // КАТЕГОРИЯ: ВОСПРОИЗВЕДЕНИЕ
        // ==========================================
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CinemaDark),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("playback_category_card")
        ) {
            Column {
                // Header аккордеона Воспроизведение
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusableItem(
                            onClick = { isPlaybackExpanded = !isPlaybackExpanded },
                            shape = RoundedCornerShape(16.dp),
                            scaleFactor = 1.0f
                        )
                        .padding(16.dp)
                        .testTag("settings_accordion_playback")
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(CinemaMuted.copy(alpha = 0.2f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayCircle,
                            contentDescription = null,
                            tint = CinemaTextGray,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Воспроизведение",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = CinemaTextWhite,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isPlaybackExpanded) "Свернуть" else "Развернуть",
                        tint = CinemaTextGray,
                        modifier = Modifier
                            .size(22.dp)
                            .rotate(playbackArrowRotation)
                    )
                }

                // Тело аккордеона Воспроизведение
                AnimatedVisibility(
                    visible = isPlaybackExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                    ) {
                        Divider(color = CinemaMuted.copy(alpha = 0.3f), thickness = 1.dp)
                        Spacer(modifier = Modifier.height(14.dp))

                        // ---- ОПЦИЯ: ПЛЕЕР ----
                        Text(
                            text = "ПЛЕЕР",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CinemaPrimary,
                            letterSpacing = 1.sp
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        val currentSelectedExternalPlayer = remember(selectedPlayer, installedPlayers) {
                            if (selectedPlayer.startsWith("package:")) {
                                val pkg = selectedPlayer.removePrefix("package:")
                                installedPlayers.find { it.packageName == pkg }
                            } else null
                        }

                        val playerDisplayTitle = when {
                            selectedPlayer == RezkaService.PLAYER_INTERNAL -> "Встроенный"
                            selectedPlayer == RezkaService.PLAYER_ASK_EXTERNAL -> "Спросить внешний"
                            currentSelectedExternalPlayer != null -> currentSelectedExternalPlayer.label
                            else -> "Внешний плеер"
                        }

                        val playerDisplaySubtitle = when {
                            selectedPlayer == RezkaService.PLAYER_INTERNAL -> "Встроенный плеер приложения (по умолчанию)"
                            selectedPlayer == RezkaService.PLAYER_ASK_EXTERNAL -> "Системный диалог выбора при каждом запуске"
                            currentSelectedExternalPlayer != null -> currentSelectedExternalPlayer.packageName
                            else -> "Выбранный сторонний плеер"
                        }

                        Box(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(CinemaCard)
                                    .tvFocusableItem(
                                        onClick = {
                                            viewModel.loadInstalledPlayers()
                                            playerDropdownExpanded = true
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        scaleFactor = 1.0f
                                    )
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                                    .testTag("player_dropdown_trigger"),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f, fill = false),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (currentSelectedExternalPlayer?.icon != null) {
                                        AsyncImage(
                                            model = currentSelectedExternalPlayer.icon,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                    } else {
                                        Icon(
                                            imageVector = if (selectedPlayer == RezkaService.PLAYER_INTERNAL) Icons.Default.PlayCircle else Icons.AutoMirrored.Filled.OpenInNew,
                                            contentDescription = null,
                                            tint = CinemaPrimary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                    }

                                    Column {
                                        Text(
                                            text = playerDisplayTitle,
                                            color = CinemaTextWhite,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = playerDisplaySubtitle,
                                            color = CinemaTextGray,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = if (playerDropdownExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                    contentDescription = "Выбор плеера",
                                    tint = CinemaPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = playerDropdownExpanded,
                                onDismissRequest = { playerDropdownExpanded = false },
                                modifier = Modifier
                                    .background(CinemaDark)
                                    .fillMaxWidth(0.85f)
                                    .heightIn(max = 360.dp)
                            ) {
                                // 1. Встроенный
                                val isInternalSelected = selectedPlayer == RezkaService.PLAYER_INTERNAL
                                DropdownMenuItem(
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.PlayCircle,
                                            contentDescription = null,
                                            tint = if (isInternalSelected) CinemaPrimary else CinemaTextGray,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    },
                                    text = {
                                        Column {
                                            Text(
                                                text = "Встроенный",
                                                color = if (isInternalSelected) CinemaPrimary else CinemaTextWhite,
                                                fontSize = 13.sp,
                                                fontWeight = if (isInternalSelected) FontWeight.Bold else FontWeight.Normal
                                            )
                                            Text(
                                                text = "Встроенный плеер приложения (по умолчанию)",
                                                color = if (isInternalSelected) CinemaPrimary.copy(alpha = 0.8f) else CinemaTextGray,
                                                fontSize = 10.sp
                                            )
                                        }
                                    },
                                    onClick = {
                                        playerDropdownExpanded = false
                                        viewModel.setSelectedPlayer(RezkaService.PLAYER_INTERNAL)
                                        Toast.makeText(context, "Выбран встроенный плеер", Toast.LENGTH_SHORT).show()
                                    }
                                )

                                // 2. Спросить внешний
                                val isAskExternalSelected = selectedPlayer == RezkaService.PLAYER_ASK_EXTERNAL
                                DropdownMenuItem(
                                    leadingIcon = {
                                        Icon(
                                            Icons.AutoMirrored.Filled.OpenInNew,
                                            contentDescription = null,
                                            tint = if (isAskExternalSelected) CinemaPrimary else CinemaTextGray,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    },
                                    text = {
                                        Column {
                                            Text(
                                                text = "Спросить внешний",
                                                color = if (isAskExternalSelected) CinemaPrimary else CinemaTextWhite,
                                                fontSize = 13.sp,
                                                fontWeight = if (isAskExternalSelected) FontWeight.Bold else FontWeight.Normal
                                            )
                                            Text(
                                                text = "Системный диалог выбора при каждом запуске",
                                                color = if (isAskExternalSelected) CinemaPrimary.copy(alpha = 0.8f) else CinemaTextGray,
                                                fontSize = 10.sp
                                            )
                                        }
                                    },
                                    onClick = {
                                        playerDropdownExpanded = false
                                        viewModel.setSelectedPlayer(RezkaService.PLAYER_ASK_EXTERNAL)
                                        Toast.makeText(context, "При воспроизведении откроется выбор плеера", Toast.LENGTH_SHORT).show()
                                    }
                                )

                                // 3. Установленные плееры (динамически обнаруженные в системе)
                                if (installedPlayers.isNotEmpty()) {
                                    HorizontalDivider(
                                        color = CinemaMuted.copy(alpha = 0.4f),
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    )
                                    Box(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = "УСТАНОВЛЕННЫЕ ПЛЕЕРЫ В СИСТЕМЕ",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = CinemaTextGray,
                                            letterSpacing = 0.5.sp
                                        )
                                    }
                                    installedPlayers.forEach { player ->
                                        val isThisPlayerSelected = selectedPlayer == "package:${player.packageName}"
                                        DropdownMenuItem(
                                            leadingIcon = {
                                                if (player.icon != null) {
                                                    AsyncImage(
                                                        model = player.icon,
                                                        contentDescription = player.label,
                                                        modifier = Modifier
                                                            .size(22.dp)
                                                            .clip(RoundedCornerShape(4.dp))
                                                    )
                                                } else {
                                                    Icon(
                                                        Icons.Default.SmartDisplay,
                                                        contentDescription = null,
                                                        tint = if (isThisPlayerSelected) CinemaPrimary else CinemaTextGray,
                                                        modifier = Modifier.size(22.dp)
                                                    )
                                                }
                                            },
                                            text = {
                                                Column {
                                                    Text(
                                                        text = player.label,
                                                        color = if (isThisPlayerSelected) CinemaPrimary else CinemaTextWhite,
                                                        fontSize = 13.sp,
                                                        fontWeight = if (isThisPlayerSelected) FontWeight.Bold else FontWeight.Normal,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = player.packageName,
                                                        color = if (isThisPlayerSelected) CinemaPrimary.copy(alpha = 0.8f) else CinemaTextGray,
                                                        fontSize = 10.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            },
                                            onClick = {
                                                playerDropdownExpanded = false
                                                viewModel.setSelectedPlayer("package:${player.packageName}")
                                                Toast.makeText(context, "Выбран плеер: ${player.label}", Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))
                        HorizontalDivider(color = CinemaMuted.copy(alpha = 0.3f), thickness = 1.dp)
                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "КАЧЕСТВО ВИДЕО ПО УМОЛЧАНИЮ",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CinemaPrimary,
                            letterSpacing = 1.sp
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        val qualityOptions = remember {
                            listOf(
                                QualityOption(RezkaService.QUALITY_1080P, "1080p (Full HD)", null),
                                QualityOption(RezkaService.QUALITY_720P, "720p (HD)", null),
                                QualityOption(RezkaService.QUALITY_480P, "480p (SD)", null),
                                QualityOption(RezkaService.QUALITY_360P, "360p", null),
                                QualityOption(RezkaService.QUALITY_ASK, "Спрашивать", "Выбор качества в диалоге перед каждым запуском")
                            )
                        }

                        var qualityDropdownExpanded by remember { mutableStateOf(false) }
                        val selectedQualityOption = qualityOptions.find { it.key == defaultQuality } ?: qualityOptions[0]

                        Box(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(CinemaCard)
                                    .tvFocusableItem(
                                        onClick = { qualityDropdownExpanded = true },
                                        shape = RoundedCornerShape(10.dp),
                                        scaleFactor = 1.0f
                                    )
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                                    .testTag("quality_dropdown_trigger"),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f, fill = false)) {
                                    Text(
                                        text = selectedQualityOption.title,
                                        color = CinemaTextWhite,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (selectedQualityOption.subtitle != null) {
                                        Text(
                                            text = selectedQualityOption.subtitle,
                                            color = CinemaTextGray,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = if (qualityDropdownExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                    contentDescription = "Выбор качества",
                                    tint = CinemaPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = qualityDropdownExpanded,
                                onDismissRequest = { qualityDropdownExpanded = false },
                                modifier = Modifier
                                    .background(CinemaDark)
                                    .fillMaxWidth(0.85f)
                                    .heightIn(max = 300.dp)
                            ) {
                                qualityOptions.forEach { option ->
                                    val isSelected = defaultQuality == option.key
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(
                                                    text = option.title,
                                                    color = if (isSelected) CinemaPrimary else CinemaTextWhite,
                                                    fontSize = 13.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                )
                                                if (option.subtitle != null) {
                                                    Text(
                                                        text = option.subtitle,
                                                        color = if (isSelected) CinemaPrimary.copy(alpha = 0.8f) else CinemaTextGray,
                                                        fontSize = 10.sp
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            qualityDropdownExpanded = false
                                            if (!isSelected) {
                                                viewModel.setDefaultQuality(option.key)
                                                Toast.makeText(context, "Качество по умолчанию: ${option.title}", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        colors = MenuDefaults.itemColors(
                                            textColor = CinemaTextWhite
                                        )
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))
                        HorizontalDivider(color = CinemaMuted.copy(alpha = 0.3f), thickness = 1.dp)
                        Spacer(modifier = Modifier.height(16.dp))

                        // Настройка: Автоматически переключать серию
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(CinemaCard)
                                .tvFocusableItem(
                                    onClick = { viewModel.setAutoNextEpisode(!autoNextEpisode) },
                                    shape = RoundedCornerShape(10.dp),
                                    scaleFactor = 1.0f
                                )
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                                .testTag("auto_next_episode_row"),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 12.dp)
                            ) {
                                Text(
                                    text = "Автоматически переключать серию",
                                    color = CinemaTextWhite,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Автоматический запуск следующей серии с 5-секундным таймером при завершении текущей",
                                    color = CinemaTextGray,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                            }

                            Switch(
                                checked = autoNextEpisode,
                                onCheckedChange = { viewModel.setAutoNextEpisode(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = CinemaTextWhite,
                                    checkedTrackColor = CinemaPrimary,
                                    uncheckedThumbColor = CinemaTextGray,
                                    uncheckedTrackColor = CinemaDark
                                ),
                                modifier = Modifier.testTag("auto_next_episode_switch")
                            )
                        }

                        Spacer(modifier = Modifier.height(18.dp))
                        HorizontalDivider(color = CinemaMuted.copy(alpha = 0.3f), thickness = 1.dp)
                        Spacer(modifier = Modifier.height(16.dp))

                        // Настройка: Режим масштабирования видео (FIT, ZOOM, FILL)
                        Text(
                            text = "Масштабирование видео по умолчанию",
                            color = CinemaTextWhite,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        val resizeOptions = listOf(
                            "FIT" to "Оригинал",
                            "ZOOM" to "Заполнить",
                            "FILL" to "Растянуть"
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            resizeOptions.forEach { (mode, label) ->
                                val isSelected = defaultResizeMode == mode
                                val triggerResize = {
                                    viewModel.setDefaultResizeMode(mode)
                                    Toast.makeText(context, "Режим экрана: $label", Toast.LENGTH_SHORT).show()
                                }
                                Surface(
                                    color = if (isSelected) CinemaPrimary else CinemaCard,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .tvFocusableItem(
                                            onClick = triggerResize,
                                            shape = RoundedCornerShape(8.dp),
                                            scaleFactor = 1.0f
                                        )
                                ) {
                                    Text(
                                        text = label,
                                        color = if (isSelected) Color.Black else CinemaTextWhite,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ---- SECTION: INTERFACE & GRID ----
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CinemaDark),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("interface_category_card")
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusableItem(
                            onClick = { isTvExpanded = !isTvExpanded },
                            shape = RoundedCornerShape(16.dp),
                            scaleFactor = 1.0f
                        )
                        .padding(16.dp)
                        .testTag("settings_accordion_interface")
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(CinemaMuted.copy(alpha = 0.2f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tv,
                            contentDescription = null,
                            tint = CinemaTextGray,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Интерфейс",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = CinemaTextWhite
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isTvExpanded) "Свернуть" else "Развернуть",
                        tint = CinemaTextGray,
                        modifier = Modifier
                            .size(22.dp)
                            .rotate(tvArrowRotation)
                    )
                }

                AnimatedVisibility(
                    visible = isTvExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                    ) {
                        HorizontalDivider(color = CinemaMuted.copy(alpha = 0.3f), thickness = 1.dp)
                        Spacer(modifier = Modifier.height(14.dp))

                        // 1. ВЫПАДАЮЩИЙ СПИСОК: РЕЖИМ ИНТЕРФЕЙСА
                        Text(
                            text = "РЕЖИМ ИНТЕРФЕЙСА",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = CinemaTextGray,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        val tvModes = listOf(
                            TvModePreference.AUTO to ("Автоопределение (Рекомендуется)" to "Автоматический выбор под тип устройства"),
                            TvModePreference.FORCE_TV to ("Телевизор" to "Полноэкранный ТВ-интерфейс под пульт D-Pad"),
                            TvModePreference.FORCE_MOBILE to ("Телефон/Планшет" to "Сенсорный мобильный интерфейс с вкладками")
                        )

                        val currentTvMode = tvModes.find { it.first.id == tvModePreference } ?: tvModes[0]

                        Box(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(CinemaCard)
                                    .tvFocusableItem(
                                        onClick = { tvModeDropdownExpanded = true },
                                        shape = RoundedCornerShape(10.dp),
                                        scaleFactor = 1.0f
                                    )
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                                    .testTag("tv_mode_dropdown_trigger"),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = currentTvMode.second.first,
                                    color = CinemaTextWhite,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = if (tvModeDropdownExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                    contentDescription = "Выбор режима интерфейса",
                                    tint = CinemaPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = tvModeDropdownExpanded,
                                onDismissRequest = { tvModeDropdownExpanded = false },
                                modifier = Modifier
                                    .background(CinemaDark)
                                    .fillMaxWidth(0.85f)
                            ) {
                                tvModes.forEach { (mode, info) ->
                                    val (title, _) = info
                                    val isSelected = tvModePreference == mode.id
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = title,
                                                color = if (isSelected) CinemaPrimary else CinemaTextWhite,
                                                fontSize = 13.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        onClick = {
                                            tvModeDropdownExpanded = false
                                            if (!isSelected) {
                                                viewModel.setTvModePreference(mode.id)
                                                Toast.makeText(context, "Режим: $title", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        colors = MenuDefaults.itemColors(textColor = CinemaTextWhite)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))
                        HorizontalDivider(color = CinemaMuted.copy(alpha = 0.3f), thickness = 1.dp)
                        Spacer(modifier = Modifier.height(14.dp))

                        // 2. ВЫПАДАЮЩИЙ СПИСОК: СЕТКА КАРТОЧЕК
                        Text(
                            text = "СЕТКА КАРТОЧЕК",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = CinemaTextGray,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        val parsedCurrentGrid = remember(cardGridMode) { RezkaService.parseCardGrid(cardGridMode) }
                        val presetGridOptions = listOf(
                            "auto" to "Авто",
                            "2x2" to "2 x 2",
                            "2x3" to "2 x 3",
                            "3x2" to "3 x 2",
                            "3x3" to "3 x 3",
                            "4x2" to "4 x 2",
                            "4x3" to "4 x 3",
                            "5x2" to "5 x 2",
                            "5x3" to "5 x 3",
                            "6x2" to "6 x 2",
                            "7x2" to "7 x 2",
                            "8x2" to "8 x 2",
                            "10x2" to "10 x 2",
                            "10x5" to "10 x 5"
                        )

                        val currentGridTitle = if (cardGridMode == "auto" || parsedCurrentGrid == null) {
                            "Авто"
                        } else {
                            "${parsedCurrentGrid.columns} x ${parsedCurrentGrid.rows}"
                        }

                        Box(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(CinemaCard)
                                    .tvFocusableItem(
                                        onClick = { gridDropdownExpanded = true },
                                        shape = RoundedCornerShape(10.dp),
                                        scaleFactor = 1.0f
                                    )
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                                    .testTag("card_grid_dropdown_trigger"),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = currentGridTitle,
                                    color = CinemaTextWhite,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = if (gridDropdownExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                    contentDescription = "Выбор сетки карточек",
                                    tint = CinemaPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = gridDropdownExpanded,
                                onDismissRequest = { gridDropdownExpanded = false },
                                modifier = Modifier
                                    .background(CinemaDark)
                                    .fillMaxWidth(0.85f)
                                    .heightIn(max = 320.dp)
                            ) {
                                if (cardGridMode != "auto" && presetGridOptions.none { it.first == cardGridMode } && parsedCurrentGrid != null) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "${parsedCurrentGrid.columns} x ${parsedCurrentGrid.rows}",
                                                color = CinemaPrimary,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        },
                                        onClick = { gridDropdownExpanded = false },
                                        colors = MenuDefaults.itemColors(textColor = CinemaTextWhite)
                                    )
                                }

                                presetGridOptions.forEach { (key, title) ->
                                    val isSelected = cardGridMode == key
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = title,
                                                color = if (isSelected) CinemaPrimary else CinemaTextWhite,
                                                fontSize = 13.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        onClick = {
                                            gridDropdownExpanded = false
                                            if (!isSelected) {
                                                viewModel.setCardGridMode(key)
                                                Toast.makeText(context, "Сетка: $title", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        colors = MenuDefaults.itemColors(textColor = CinemaTextWhite)
                                    )
                                }

                                HorizontalDivider(color = CinemaMuted.copy(alpha = 0.4f), thickness = 1.dp)

                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.Tune,
                                                contentDescription = null,
                                                tint = CinemaPrimary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "Своя сетка...",
                                                color = CinemaPrimary,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    },
                                    onClick = {
                                        gridDropdownExpanded = false
                                        showCustomGridDialog = true
                                    },
                                    colors = MenuDefaults.itemColors(textColor = CinemaTextWhite)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ==========================================
        // 4. КАТЕГОРИЯ: УВЕДОМЛЕНИЯ О НОВЫХ СЕРИЯХ
        // ==========================================
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CinemaDark),
            border = BorderStroke(1.dp, CinemaMuted.copy(alpha = 0.25f)),
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header аккордеона
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusableItem(
                            onClick = { isSubscriptionsExpanded = !isSubscriptionsExpanded },
                            shape = RoundedCornerShape(16.dp),
                            scaleFactor = 1.0f
                        )
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                        .testTag("settings_accordion_subscriptions"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(CinemaMuted.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.NotificationsActive,
                                contentDescription = null,
                                tint = CinemaTextGray,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Column {
                            Text(
                                text = "ОТСЛЕЖИВАНИЕ СЕРИЙ",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = CinemaTextWhite,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = if (subscriptions.isEmpty()) "Нет активных подписок" else "Активных подписок: ${subscriptions.size}",
                                fontSize = 11.sp,
                                color = if (subscriptions.isEmpty()) CinemaTextGray else CinemaPrimary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = CinemaTextGray,
                        modifier = Modifier
                            .size(22.dp)
                            .rotate(subscriptionsArrowRotation)
                    )
                }

                // Тело аккордеона
                AnimatedVisibility(
                    visible = isSubscriptionsExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                    ) {
                        HorizontalDivider(color = CinemaMuted.copy(alpha = 0.3f), thickness = 1.dp)
                        Spacer(modifier = Modifier.height(14.dp))

                        // Кнопка ручной проверки прямо сейчас
                        val triggerCheckUpdates = {
                            if (!isCheckingSeriesUpdates) {
                                viewModel.triggerManualSeriesCheck(context)
                                Toast.makeText(context, "Проверка обновлений запущена...", Toast.LENGTH_SHORT).show()
                            }
                        }
                        Button(
                            onClick = triggerCheckUpdates,
                            enabled = !isCheckingSeriesUpdates,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CinemaPrimary,
                                contentColor = CinemaBlack,
                                disabledContainerColor = CinemaCard,
                                disabledContentColor = CinemaTextGray
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .tvFocusableItem(
                                    onClick = triggerCheckUpdates,
                                    shape = RoundedCornerShape(10.dp),
                                    scaleFactor = 1.0f
                                )
                                .testTag("check_series_updates_button")
                        ) {
                            if (isCheckingSeriesUpdates) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = CinemaPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Проверка...",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Проверить обновления",
                                    fontWeight = FontWeight.Bold,
                                     fontSize = 13.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        if (subscriptions.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Список пуст",
                                    color = CinemaTextGray,
                                    fontSize = 13.sp
                                )
                            }
                        } else {
                            Text(
                                text = "СПИСОК ОТСЛЕЖИВАЕМЫХ ТАЙТЛОВ",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = CinemaTextGray,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                subscriptions.forEach { sub ->
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = CinemaCard,
                                        border = BorderStroke(1.dp, if (sub.hasUnseenUpdate) CinemaPrimary else Color.White.copy(alpha = 0.08f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                modifier = Modifier.weight(1f),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                AsyncImage(
                                                    model = ImageRequest.Builder(context)
                                                        .data(sub.imageUrl)
                                                        .crossfade(true)
                                                        .build(),
                                                    contentDescription = sub.title,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .size(42.dp, 58.dp)
                                                        .clip(RoundedCornerShape(6.dp))
                                                )

                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = sub.title,
                                                        color = CinemaTextWhite,
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    val isMovie = sub.isMovie()
                                                    val isWaitingMovie = isMovie && ((sub.lastKnownSeason == 0 && sub.lastKnownEpisode == 0) || sub.lastEpisodeName.contains("[TEST]"))
                                                    val statusText = if (isWaitingMovie) {
                                                        "Фильм • Ожидается выход"
                                                    } else if (isMovie) {
                                                        "Фильм • Релиз доступен"
                                                    } else if (sub.lastKnownSeason == 0 && sub.lastKnownEpisode == 0) {
                                                        "Сериал • Ожидается премьера"
                                                    } else {
                                                        "Текущая: Сезон ${sub.lastKnownSeason}, серия ${sub.lastKnownEpisode}"
                                                    }
                                                    Text(
                                                        text = statusText,
                                                        color = if (isWaitingMovie) CinemaAmber else CinemaPrimary,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                    if (sub.hasUnseenUpdate) {
                                                        val unseenText = if (isMovie) {
                                                            "Фильм вышел!"
                                                        } else {
                                                            "Новая серия: ${sub.lastEpisodeName}"
                                                        }
                                                        Text(
                                                            text = unseenText,
                                                            color = CinemaAmber,
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                            }

                                             Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(36.dp)
                                                        .clip(CircleShape)
                                                        .tvFocusableItem(
                                                            onClick = { viewModel.removeSubscription(sub.id) },
                                                            shape = CircleShape,
                                                            scaleFactor = 1.0f
                                                        ),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.DeleteOutline,
                                                        contentDescription = "Отписаться",
                                                        tint = CinemaTextGray,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Диалог точной настройки сетки (до 10x5)
        if (showCustomGridDialog) {
            val initialGrid = RezkaService.parseCardGrid(cardGridMode)
            CustomGridDialog(
                initialCols = initialGrid?.columns ?: 7,
                initialRows = initialGrid?.rows ?: 2,
                onDismiss = { showCustomGridDialog = false },
                onConfirm = { cols, rows ->
                    val newMode = "${cols}x${rows}"
                    viewModel.setCardGridMode(newMode)
                    Toast.makeText(context, "Сетка: $newMode", Toast.LENGTH_SHORT).show()
                    showCustomGridDialog = false
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

        // ---- НИЖНЯЯ ПАНЕЛЬ: ВСЕГДА ВНИЗУ ЭКРАНА (ОТДЕЛЬНО ОТ НАСТРОЕК) ----
        val supportUrl = stringResource(R.string.support_project_url)
        val supportTitle = stringResource(R.string.support_project)
        val supportDisplay = stringResource(R.string.support_project_link_display)
        val supportIconUrl = stringResource(R.string.support_project_icon_url)
        val supportIconDesc = stringResource(R.string.support_project_icon_desc)
        val errorOpenLinkText = stringResource(R.string.error_cannot_open_link)
        val developerLabel = stringResource(R.string.developer_label)
        val developerName = stringResource(R.string.developer_name)

        Surface(
            color = CinemaDark.copy(alpha = 0.98f),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            border = BorderStroke(1.dp, CinemaMuted.copy(alpha = 0.35f)),
            shadowElevation = 10.dp,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Карточка поддержки Buy Me a Coffee
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = CinemaCard),
                    border = BorderStroke(1.dp, CinemaPrimary.copy(alpha = 0.25f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusableItem(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(supportUrl)).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                runCatching {
                                    context.startActivity(intent)
                                }.onFailure {
                                    Toast.makeText(context, errorOpenLinkText, Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            scaleFactor = 1.0f
                        )
                        .testTag("support_project_card")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        // Иконка Buy Me a Coffee непосредственно с сайта
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFFFFDD00)),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(supportIconUrl)
                                    .crossfade(200)
                                    .build(),
                                contentDescription = supportIconDesc,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = supportTitle,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = CinemaTextWhite
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = supportDisplay,
                                fontSize = 12.sp,
                                color = CinemaPrimary,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = stringResource(R.string.support_project_open_desc),
                            tint = CinemaPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Разработчик
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                ) {
                    Text(
                        text = developerLabel,
                        fontSize = 13.sp,
                        color = CinemaTextGray
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = developerName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = CinemaPrimary
                    )
                }
            }
        }
    }
}

/**
 * Диалог ручной настройки сетки каталога.
 */
@Composable
private fun CustomGridDialog(
    initialCols: Int,
    initialRows: Int,
    onDismiss: () -> Unit,
    onConfirm: (cols: Int, rows: Int) -> Unit
) {
    var tempCols by remember { mutableStateOf(initialCols.coerceIn(1, 10)) }
    var tempRows by remember { mutableStateOf(initialRows.coerceIn(1, 5)) }

    val decColsRequester = remember { FocusRequester() }
    val incColsRequester = remember { FocusRequester() }
    val decRowsRequester = remember { FocusRequester() }
    val incRowsRequester = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CinemaDark,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                text = "Сетка карточек",
                color = CinemaTextWhite,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // По ширине (1..10)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "По ширине:",
                        color = CinemaTextWhite,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (tempCols > 1) CinemaCard else CinemaCard.copy(alpha = 0.4f))
                                .tvFocusableItem(
                                    onClick = { if (tempCols > 1) tempCols-- },
                                    shape = RoundedCornerShape(8.dp),
                                    scaleFactor = 1.0f,
                                    focusRequester = decColsRequester
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Remove,
                                contentDescription = "Уменьшить ширину",
                                tint = if (tempCols > 1) CinemaPrimary else CinemaTextGray.copy(alpha = 0.4f),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Text(
                            text = "$tempCols",
                            color = CinemaTextWhite,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(36.dp)
                        )

                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (tempCols < 10) CinemaCard else CinemaCard.copy(alpha = 0.4f))
                                .tvFocusableItem(
                                    onClick = { if (tempCols < 10) tempCols++ },
                                    shape = RoundedCornerShape(8.dp),
                                    scaleFactor = 1.0f,
                                    focusRequester = incColsRequester
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Увеличить ширину",
                                tint = if (tempCols < 10) CinemaPrimary else CinemaTextGray.copy(alpha = 0.4f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // По высоте (1..5)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "По высоте:",
                        color = CinemaTextWhite,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (tempRows > 1) CinemaCard else CinemaCard.copy(alpha = 0.4f))
                                .tvFocusableItem(
                                    onClick = { if (tempRows > 1) tempRows-- },
                                    shape = RoundedCornerShape(8.dp),
                                    scaleFactor = 1.0f,
                                    focusRequester = decRowsRequester
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Remove,
                                contentDescription = "Уменьшить высоту",
                                tint = if (tempRows > 1) CinemaPrimary else CinemaTextGray.copy(alpha = 0.4f),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Text(
                            text = "$tempRows",
                            color = CinemaTextWhite,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(36.dp)
                        )

                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (tempRows < 5) CinemaCard else CinemaCard.copy(alpha = 0.4f))
                                .tvFocusableItem(
                                    onClick = { if (tempRows < 5) tempRows++ },
                                    shape = RoundedCornerShape(8.dp),
                                    scaleFactor = 1.0f,
                                    focusRequester = incRowsRequester
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Увеличить высоту",
                                tint = if (tempRows < 5) CinemaPrimary else CinemaTextGray.copy(alpha = 0.4f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(tempCols, tempRows) },
                colors = ButtonDefaults.buttonColors(containerColor = CinemaPrimary),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.tvFocusableItem(
                    onClick = { onConfirm(tempCols, tempRows) },
                    shape = RoundedCornerShape(8.dp),
                    scaleFactor = 1.0f
                )
            ) {
                Text(
                    text = "Применить",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.tvFocusableItem(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(8.dp),
                    scaleFactor = 1.0f
                )
            ) {
                Text(
                    text = "Отмена",
                    color = CinemaTextGray,
                    fontSize = 14.sp
                )
            }
        }
    )
}

