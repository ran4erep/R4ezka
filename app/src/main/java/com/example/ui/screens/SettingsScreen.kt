package com.example.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.example.data.RezkaService
import com.example.ui.RezkaViewModel
import com.example.ui.theme.*
import com.example.ui.tv.TvDetector
import com.example.ui.tv.TvModePreference
import com.example.ui.tv.dpadScrollable
import com.example.ui.tv.tvFocusableItem
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: RezkaViewModel,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val currentMirror by viewModel.currentMirror.collectAsState()
    val defaultQuality by viewModel.defaultQuality.collectAsState()
    val autoNextEpisode by viewModel.autoNextEpisode.collectAsState()
    val defaultResizeMode by viewModel.defaultResizeMode.collectAsState()
    val tvModePreference by viewModel.tvModePreference.collectAsState()
    val presetMirrors = viewModel.presetMirrors

    var customMirrorInput by remember(currentMirror) { mutableStateOf(currentMirror) }
    var pingResult by remember { mutableStateOf<String?>(null) }
    var isPinging by remember { mutableStateOf(false) }

    // Категории в выпадающем виде (аккордеоны - все свёрнуты по умолчанию)
    var isMirrorsExpanded by remember { mutableStateOf(false) }
    var isPlaybackExpanded by remember { mutableStateOf(false) }
    var isTvExpanded by remember { mutableStateOf(false) }

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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CinemaBlack)
            .statusBarsPadding()
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
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(50.dp))
                        .background(CinemaCard)
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
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { isMirrorsExpanded = !isMirrorsExpanded }
                        .padding(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(CinemaPrimary.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = CinemaPrimary,
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
                    IconButton(
                        onClick = { isMirrorsExpanded = !isMirrorsExpanded },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isMirrorsExpanded) "Свернуть" else "Развернуть",
                            tint = CinemaTextGray,
                            modifier = Modifier.rotate(mirrorArrowRotation)
                        )
                    }
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
                            Button(
                                onClick = {
                                    isPinging = true
                                    pingResult = null
                                    coroutineScope.launch {
                                        val result = viewModel.testMirror(currentMirror)
                                        isPinging = false
                                        if (result.isSuccess) {
                                            val ms = result.getOrNull() ?: 0
                                            pingResult = "Отклик: ${ms} мс (Доступно)"
                                        } else {
                                            pingResult = "Недоступно: ${result.exceptionOrNull()?.message ?: "таймаут"}"
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CinemaSecondary),
                                shape = RoundedCornerShape(10.dp),
                                enabled = !isPinging,
                                modifier = Modifier.testTag("ping_mirror_button")
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
                                OutlinedButton(
                                    onClick = {
                                        val def = viewModel.resetMirrorToDefault()
                                        customMirrorInput = def
                                        Toast.makeText(context, "Сброшено на $def", Toast.LENGTH_SHORT).show()
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(CinemaMuted))
                                ) {
                                    Text("Сброс", fontSize = 12.sp, color = CinemaTextGray)
                                }
                            }
                        }

                        AnimatedVisibility(visible = pingResult != null) {
                            pingResult?.let { text ->
                                Spacer(modifier = Modifier.height(8.dp))
                                val isOk = text.contains("Доступно")
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
                                    .clickable { mirrorDropdownExpanded = true }
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

                        // Своё зеркало вручную (подсказка убрана по требованию пользователя)
                        Text(
                            text = "СВОЙ АДРЕС ЗЕРКАЛА",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CinemaTextGray,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        TextField(
                            value = customMirrorInput,
                            onValueChange = { customMirrorInput = it },
                            placeholder = { Text("https://зеркало.com", color = CinemaMuted, fontSize = 14.sp) },
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
                            trailingIcon = {
                                if (customMirrorInput.isNotEmpty()) {
                                    IconButton(onClick = { customMirrorInput = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Очистить", tint = CinemaMuted)
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("custom_mirror_input")
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Button(
                            onClick = {
                                val input = customMirrorInput.trim()
                                if (input.isBlank()) {
                                    Toast.makeText(context, "Введите адрес зеркала", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                val success = viewModel.setMirror(input)
                                if (success) {
                                    pingResult = null
                                    Toast.makeText(context, "Зеркало успешно установлено: ${viewModel.currentMirror.value}", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "Некорректный адрес URL зеркала", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CinemaPrimary),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
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
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { isPlaybackExpanded = !isPlaybackExpanded }
                        .padding(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(CinemaPrimary.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayCircle,
                            contentDescription = null,
                            tint = CinemaPrimary,
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
                    IconButton(
                        onClick = { isPlaybackExpanded = !isPlaybackExpanded },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isPlaybackExpanded) "Свернуть" else "Развернуть",
                            tint = CinemaTextGray,
                            modifier = Modifier.rotate(playbackArrowRotation)
                        )
                    }
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

                        Text(
                            text = "КАЧЕСТВО ВИДЕО ПО УМОЛЧАНИЮ",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CinemaPrimary,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Применяется ко всем фильмам и сериалам. Если видеофайл не содержит выбранного качества, будет автоматически запущен наиболее подходящий доступный поток.",
                            fontSize = 12.sp,
                            color = CinemaTextGray,
                            lineHeight = 16.sp
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        val qualityOptions = listOf(
                            RezkaService.QUALITY_1080P to ("1080p (Full HD)" to "Рекомендуется для большинства экранов"),
                            RezkaService.QUALITY_1080P_ULTRA to ("1080p Ultra" to "Максимальный битрейт и чёткость"),
                            RezkaService.QUALITY_720P to ("720p (HD)" to "Оптимально при среднем интернете"),
                            RezkaService.QUALITY_480P to ("480p (SD)" to "Экономия трафика"),
                            RezkaService.QUALITY_360P to ("360p" to "Минимальное качество"),
                            RezkaService.QUALITY_ASK to ("Спрашивать" to "Выбор качества в диалоге перед каждым запуском")
                        )

                        var qualityDropdownExpanded by remember { mutableStateOf(false) }
                        val selectedQualityPair = qualityOptions.find { it.first == defaultQuality } ?: qualityOptions[0]

                        Box(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(CinemaCard)
                                    .clickable { qualityDropdownExpanded = true }
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                                    .testTag("quality_dropdown_trigger"),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f, fill = false)) {
                                    Text(
                                        text = selectedQualityPair.second.first,
                                        color = CinemaTextWhite,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = selectedQualityPair.second.second,
                                        color = CinemaTextGray,
                                        fontSize = 11.sp
                                    )
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
                                qualityOptions.forEach { (key, info) ->
                                    val (title, subtitle) = info
                                    val isSelected = defaultQuality == key
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(
                                                    text = title,
                                                    color = if (isSelected) CinemaPrimary else CinemaTextWhite,
                                                    fontSize = 13.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                )
                                                Text(
                                                    text = subtitle,
                                                    color = if (isSelected) CinemaPrimary.copy(alpha = 0.8f) else CinemaTextGray,
                                                    fontSize = 10.sp
                                                )
                                            }
                                        },
                                        onClick = {
                                            qualityDropdownExpanded = false
                                            if (!isSelected) {
                                                viewModel.setDefaultQuality(key)
                                                Toast.makeText(context, "Качество по умолчанию: $title", Toast.LENGTH_SHORT).show()
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
                                .clickable { viewModel.setAutoNextEpisode(!autoNextEpisode) }
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
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "FIT — оригинал с полосами, ZOOM — кадрирование без полос, FILL — растянуть",
                            color = CinemaTextGray,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        val resizeOptions = listOf(
                            "FIT" to "Оригинал (FIT)",
                            "ZOOM" to "Заполнить (ZOOM)",
                            "FILL" to "Растянуть (FILL)"
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            resizeOptions.forEach { (mode, label) ->
                                val isSelected = defaultResizeMode == mode
                                Surface(
                                    color = if (isSelected) CinemaPrimary else CinemaCard,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            viewModel.setDefaultResizeMode(mode)
                                            Toast.makeText(context, "Режим экрана: $label", Toast.LENGTH_SHORT).show()
                                        }
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

        // ---- SECTION: TV & REMOTE CONTROL ----
        val isDeviceActuallyTv = remember { TvDetector.isRunningOnTv(context) }
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CinemaDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isTvExpanded = !isTvExpanded }
                        .padding(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(CinemaPrimary.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tv,
                            contentDescription = null,
                            tint = CinemaPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Режим интерфейса",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = CinemaTextWhite
                        )
                    }
                    IconButton(
                        onClick = { isTvExpanded = !isTvExpanded },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isTvExpanded) "Свернуть" else "Развернуть",
                            tint = CinemaTextGray,
                            modifier = Modifier.rotate(tvArrowRotation)
                        )
                    }
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

                        val tvModes = listOf(
                            TvModePreference.AUTO to "Автоопределение (Рекомендуется)",
                            TvModePreference.FORCE_TV to "Телевизор",
                            TvModePreference.FORCE_MOBILE to "Телефон/Планшет"
                        )

                        tvModes.forEach { (mode, title) ->
                            val isSelected = tvModePreference == mode.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) CinemaPrimary.copy(alpha = 0.15f) else Color.Transparent)
                                    .clickable {
                                        viewModel.setTvModePreference(mode.id)
                                        Toast.makeText(context, "Режим: $title", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        viewModel.setTvModePreference(mode.id)
                                        Toast.makeText(context, "Режим: $title", Toast.LENGTH_SHORT).show()
                                    },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = CinemaPrimary,
                                        unselectedColor = CinemaTextGray
                                    )
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = title,
                                        color = if (isSelected) CinemaTextWhite else CinemaTextGray,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ---- SECTION: DEVELOPER ----
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CinemaDark.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Разработчик:",
                    fontSize = 14.sp,
                    color = CinemaTextGray
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "ran4erep",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = CinemaPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

