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
import com.example.ui.util.SeriesBadgeMode
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
import androidx.compose.foundation.gestures.Orientation
import com.example.ui.haptics.HapticEngine
import com.example.ui.haptics.HapticType
import com.example.ui.haptics.bounceOverscroll
import com.example.ui.haptics.hapticClickable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.DownloadHelper
import com.example.data.DnsPreference
import com.example.data.ResilientSslEngine
import com.example.data.SafeDns
import com.example.data.OfflineMediaEntity
import com.example.data.RezkaService
import com.example.data.RezkaType
import com.example.data.SectionType
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
    onNavigateToDetail: ((com.example.data.RezkaItem) -> Unit)? = null,
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
    val seriesBadgeMode by viewModel.seriesBadgeMode.collectAsState()
    var badgeModeDropdownExpanded by remember { mutableStateOf(false) }
    val defaultCatalogType by viewModel.defaultCatalogType.collectAsState()
    val defaultCatalogSection by viewModel.defaultCatalogSection.collectAsState()
    val dnsPreference by viewModel.dnsPreference.collectAsState()
    val customDnsAddress by viewModel.customDnsAddress.collectAsState()
    var customDnsInput by remember(customDnsAddress) { mutableStateOf(customDnsAddress) }
    var dnsDropdownExpanded by remember { mutableStateOf(false) }
    var isTestingDns by remember { mutableStateOf(false) }
    var dnsCheckResult by remember { mutableStateOf<String?>(null) }
    var isTestingSslFallback by remember { mutableStateOf(false) }
    var sslFallbackResult by remember { mutableStateOf<String?>(null) }
    var isForceFallbackActive by remember { mutableStateOf(ResilientSslEngine.forceFallbackMode) }
    val presetMirrors = viewModel.presetMirrors

    var customMirrorInput by remember(currentMirror) { mutableStateOf(currentMirror) }
    var pingResult by remember { mutableStateOf<String?>(null) }
    var isPinging by remember { mutableStateOf(false) }

    // Категории в выпадающем виде (аккордеоны - все свёрнуты по умолчанию)
    var isMirrorsExpanded by remember { mutableStateOf(false) }
    var isPlaybackExpanded by remember { mutableStateOf(false) }
    var isTvExpanded by remember { mutableStateOf(false) }
    var isSubscriptionsExpanded by remember { mutableStateOf(false) }
    var isOfflineExpanded by remember { mutableStateOf(false) }

    val subscriptions by viewModel.subscriptions.collectAsState()
    val isCheckingSeriesUpdates by viewModel.isCheckingSeriesUpdates.collectAsState()
    val offlineMedia by viewModel.offlineMedia.collectAsState()
    val totalOfflineBytes by viewModel.totalOfflineSizeBytes.collectAsState()
    var showClearOfflineDialog by remember { mutableStateOf(false) }

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
    val offlineArrowRotation by animateFloatAsState(
        targetValue = if (isOfflineExpanded) 180f else 0f,
        label = "offlineArrowRotation"
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
                .bounceOverscroll(Orientation.Vertical)
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
                // Header аккордеона Сеть
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
                        .testTag("settings_accordion_network")
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
                        text = "Сеть",
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
                                val isOk = text.startsWith("Отклик:")
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                        .background(
                                            if (isOk) CinemaGreen.copy(alpha = 0.12f) else CinemaPrimary.copy(alpha = 0.12f),
                                            RoundedCornerShape(8.dp)
                                        )
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = text,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isOk) CinemaGreen else CinemaPrimary
                                    )
                                    if (!isOk) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Совет: воспользуйтесь кнопкой «Провести аудит» выше — приложение проверит резервные зеркала через защищенный DoH и переключится на рабочее.",
                                            fontSize = 11.sp,
                                            color = CinemaTextGray,
                                            lineHeight = 15.sp
                                        )
                                    }
                                }
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

                        // ==========================================
                        // РАЗДЕЛ: DNS
                        // ==========================================
                        Spacer(modifier = Modifier.height(18.dp))
                        Divider(color = CinemaMuted.copy(alpha = 0.3f), thickness = 1.dp)
                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = "DNS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CinemaPrimary,
                            letterSpacing = 1.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Банальный выпадающий список выбора DNS
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(CinemaCard)
                                    .tvFocusableItem(
                                        onClick = {
                                            HapticEngine.get().perform(HapticType.SOFT_CLICK)
                                            dnsDropdownExpanded = true
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        scaleFactor = 1.0f
                                    )
                                    .clickable {
                                        HapticEngine.get().perform(HapticType.SOFT_CLICK)
                                        dnsDropdownExpanded = true
                                    }
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                                    .testTag("dns_dropdown_trigger"),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f, fill = false)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Dns,
                                        contentDescription = null,
                                        tint = CinemaPrimary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = dnsPreference.title,
                                        color = CinemaTextWhite,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Icon(
                                    imageVector = if (dnsDropdownExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                    contentDescription = "Выбор DNS",
                                    tint = CinemaPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = dnsDropdownExpanded,
                                onDismissRequest = { dnsDropdownExpanded = false },
                                modifier = Modifier
                                    .background(CinemaDark)
                                    .fillMaxWidth(0.85f)
                            ) {
                                DnsPreference.entries.forEach { pref ->
                                    val isSelected = (dnsPreference == pref)
                                    DropdownMenuItem(
                                        leadingIcon = {
                                            Icon(
                                                imageVector = if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                                contentDescription = null,
                                                tint = if (isSelected) CinemaPrimary else CinemaTextGray,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        },
                                        text = {
                                            Text(
                                                text = pref.title,
                                                color = if (isSelected) CinemaPrimary else CinemaTextWhite,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                fontSize = 14.sp
                                            )
                                        },
                                        onClick = {
                                            HapticEngine.get().perform(HapticType.SELECTION)
                                            dnsCheckResult = null
                                            viewModel.setDnsPreference(pref, if (pref == DnsPreference.CUSTOM) customDnsInput else "")
                                            dnsDropdownExpanded = false
                                        },
                                        modifier = Modifier.tvFocusableItem(
                                            onClick = {
                                                HapticEngine.get().perform(HapticType.SELECTION)
                                                dnsCheckResult = null
                                                viewModel.setDnsPreference(pref, if (pref == DnsPreference.CUSTOM) customDnsInput else "")
                                                dnsDropdownExpanded = false
                                            },
                                            scaleFactor = 1.0f
                                        )
                                    )
                                }
                            }
                        }

                        // Поле ввода для ручного DNS (только при Свой DNS)
                        AnimatedVisibility(
                            visible = (dnsPreference == DnsPreference.CUSTOM),
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp)
                            ) {
                                val customDnsFocusRequester = remember { FocusRequester() }
                                val saveCustomDnsAction = {
                                    HapticEngine.get().perform(HapticType.CONFIRM)
                                    val input = customDnsInput.trim()
                                    if (input.isBlank()) {
                                        Toast.makeText(context, "Введите IP или DoH URL", Toast.LENGTH_SHORT).show()
                                    } else {
                                        viewModel.setDnsPreference(DnsPreference.CUSTOM, input)
                                        dnsCheckResult = "DNS сохранён: $input"
                                    }
                                }

                                TvRemoteInputField(
                                    value = customDnsInput,
                                    onValueChange = { customDnsInput = it },
                                    placeholder = "77.88.8.8 или https://...",
                                    onCommit = saveCustomDnsAction,
                                    singleLine = true,
                                    shape = RoundedCornerShape(10.dp),
                                    containerColor = CinemaCard,
                                    focusRequester = customDnsFocusRequester,
                                    testTag = "custom_dns_input",
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Button(
                                    onClick = saveCustomDnsAction,
                                    colors = ButtonDefaults.buttonColors(containerColor = CinemaPrimary),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .tvFocusableItem(
                                            onClick = saveCustomDnsAction,
                                            shape = RoundedCornerShape(10.dp),
                                            scaleFactor = 1.0f
                                        )
                                        .testTag("save_custom_dns_button")
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Применить DNS", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Кнопки тестирования и сброса кэша
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val testDnsAction: () -> Unit = {
                                HapticEngine.get().perform(HapticType.CONFIRM)
                                isTestingDns = true
                                dnsCheckResult = null
                                coroutineScope.launch {
                                    val result = viewModel.testDnsLookup()
                                    isTestingDns = false
                                    dnsCheckResult = if (result.success) {
                                        val ipsStr = if (result.ips.isNotEmpty()) " • IP: ${result.ips.joinToString(", ")}" else ""
                                        "Отклик: ${result.durationMs} мс • ${result.providerUsed}$ipsStr"
                                    } else {
                                        "Недоступно: ${result.errorMessage ?: "ошибка DNS"}"
                                    }
                                }
                            }

                            Button(
                                onClick = testDnsAction,
                                enabled = !isTestingDns,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CinemaMuted.copy(alpha = 0.5f),
                                    contentColor = CinemaTextWhite
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .tvFocusableItem(
                                        onClick = testDnsAction,
                                        shape = RoundedCornerShape(10.dp),
                                        scaleFactor = 1.0f
                                    )
                                    .testTag("test_dns_button")
                            ) {
                                if (isTestingDns) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = CinemaTextWhite,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Проверка...", fontSize = 12.sp)
                                } else {
                                    Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Проверить DNS", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }

                            val clearCacheAction = {
                                HapticEngine.get().perform(HapticType.CONFIRM)
                                viewModel.clearDnsCache()
                                dnsCheckResult = "Кэш DNS успешно очищен"
                            }

                            OutlinedButton(
                                onClick = clearCacheAction,
                                border = BorderStroke(1.dp, CinemaMuted),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = CinemaTextWhite),
                                modifier = Modifier
                                    .weight(1f)
                                    .tvFocusableItem(
                                        onClick = clearCacheAction,
                                        shape = RoundedCornerShape(10.dp),
                                        scaleFactor = 1.0f
                                    )
                                    .testTag("clear_dns_cache_button")
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Сброс кэша", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        AnimatedVisibility(visible = dnsCheckResult != null) {
                            dnsCheckResult?.let { text ->
                                val isOk = text.startsWith("Отклик:") || text.contains("очищен") || text.contains("сохранён")
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                        .background(
                                            if (isOk) CinemaGreen.copy(alpha = 0.12f) else CinemaPrimary.copy(alpha = 0.12f),
                                            RoundedCornerShape(8.dp)
                                        )
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                        .testTag("dns_check_result")
                                ) {
                                    Text(
                                        text = text,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isOk) CinemaGreen else CinemaPrimary
                                    )
                                }
                            }
                        }

                        // Временная кнопка тестирования Fallback SSL
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val testFallbackAction: () -> Unit = {
                                HapticEngine.get().perform(HapticType.CONFIRM)
                                isTestingSslFallback = true
                                sslFallbackResult = null
                                coroutineScope.launch {
                                    try {
                                        val res = ResilientSslEngine.testFallbackValidation(RezkaService.currentBaseUrl)
                                        sslFallbackResult = res
                                    } catch (e: Exception) {
                                        sslFallbackResult = "Ошибка: ${e.message}"
                                    } finally {
                                        isTestingSslFallback = false
                                    }
                                }
                            }

                            Button(
                                onClick = testFallbackAction,
                                enabled = !isTestingSslFallback,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CinemaMuted.copy(alpha = 0.5f),
                                    contentColor = CinemaTextWhite
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .tvFocusableItem(
                                        onClick = testFallbackAction,
                                        shape = RoundedCornerShape(10.dp),
                                        scaleFactor = 1.0f
                                    )
                                    .testTag("test_ssl_fallback_button")
                            ) {
                                if (isTestingSslFallback) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = CinemaTextWhite,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Проверка...", fontSize = 12.sp)
                                } else {
                                    Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Тест Fallback SSL", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }

                            val toggleFallbackAction = {
                                HapticEngine.get().perform(HapticType.CONFIRM)
                                val newState = !isForceFallbackActive
                                isForceFallbackActive = newState
                                ResilientSslEngine.forceFallbackMode = newState
                                sslFallbackResult = if (newState) {
                                    "✓ Принудительный Fallback SSL ВКЛЮЧЁН (системный trust store обойдён)"
                                } else {
                                    "Принудительный Fallback SSL ВЫКЛЮЧЕН (штатный режим)"
                                }
                            }

                            FilterChip(
                                selected = isForceFallbackActive,
                                onClick = toggleFallbackAction,
                                label = {
                                    Text(
                                        if (isForceFallbackActive) "Fallback: ВКЛ" else "Fallback: ВЫКЛ",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CinemaPrimary,
                                    selectedLabelColor = CinemaTextWhite
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .tvFocusableItem(
                                        onClick = toggleFallbackAction,
                                        shape = RoundedCornerShape(10.dp),
                                        scaleFactor = 1.0f
                                    )
                                    .testTag("toggle_force_fallback_chip")
                            )
                        }

                        AnimatedVisibility(visible = sslFallbackResult != null) {
                            sslFallbackResult?.let { text ->
                                val isOk = text.startsWith("✓") || text.contains("ВКЛЮЧЁН") || text.contains("ВЫКЛЮЧЕН")
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                        .background(
                                            if (isOk) CinemaGreen.copy(alpha = 0.12f) else CinemaPrimary.copy(alpha = 0.12f),
                                            RoundedCornerShape(8.dp)
                                        )
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                        .testTag("ssl_fallback_result")
                                ) {
                                    Text(
                                        text = text,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isOk) CinemaGreen else CinemaPrimary
                                    )
                                }
                            }
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
                                onCheckedChange = {
                                    HapticEngine.get().perform(HapticType.TOGGLE)
                                    viewModel.setAutoNextEpisode(it)
                                },
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

                        Spacer(modifier = Modifier.height(18.dp))
                        HorizontalDivider(color = CinemaMuted.copy(alpha = 0.3f), thickness = 1.dp)
                        Spacer(modifier = Modifier.height(14.dp))

                        // 2.1. НАСТРОЙКА: ПЛАШКИ НА КАРТОЧКАХ СЕРИАЛОВ
                        Text(
                            text = "ПЛАШКИ НА КАРТОЧКАХ СЕРИАЛОВ",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = CinemaTextGray,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Box(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(CinemaCard)
                                    .tvFocusableItem(
                                        onClick = { badgeModeDropdownExpanded = true },
                                        shape = RoundedCornerShape(10.dp),
                                        scaleFactor = 1.0f
                                    )
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                                    .testTag("series_badge_mode_dropdown_trigger"),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = seriesBadgeMode.title,
                                    color = CinemaTextWhite,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = if (badgeModeDropdownExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                    contentDescription = "Выбор режима плашек",
                                    tint = CinemaPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = badgeModeDropdownExpanded,
                                onDismissRequest = { badgeModeDropdownExpanded = false },
                                modifier = Modifier
                                    .background(CinemaDark)
                                    .fillMaxWidth(0.85f)
                            ) {
                                SeriesBadgeMode.entries.forEach { mode ->
                                    val isSelected = seriesBadgeMode == mode
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = mode.title,
                                                color = if (isSelected) CinemaPrimary else CinemaTextWhite,
                                                fontSize = 13.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        onClick = {
                                            badgeModeDropdownExpanded = false
                                            if (!isSelected) {
                                                viewModel.setSeriesBadgeMode(mode)
                                                Toast.makeText(context, "Плашки: ${mode.title}", Toast.LENGTH_SHORT).show()
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

                        // 3. НАСТРОЙКА: КАТЕГОРИЯ ПО УМОЛЧАНИЮ
                        Text(
                            text = "КАТЕГОРИЯ ПО УМОЛЧАНИЮ",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = CinemaTextGray,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Фильтры, активные по умолчанию при входе в каталог",
                            fontSize = 11.sp,
                            color = CinemaTextGray.copy(alpha = 0.8f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        // Подраздел: Тип контента
                        Text(
                            text = "Тип контента",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CinemaTextWhite
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        val contentTypes = remember {
                            listOf(
                                listOf(RezkaType.MOVIE to "Фильмы", RezkaType.SERIES to "Сериалы"),
                                listOf(RezkaType.ANIME to "Аниме", RezkaType.CARTOON to "Мультики")
                            )
                        }

                        contentTypes.forEachIndexed { rowIndex, rowItems ->
                            if (rowIndex > 0) {
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowItems.forEach { (type, label) ->
                                    val isSelected = defaultCatalogType == type
                                    val triggerType: () -> Unit = {
                                        HapticEngine.get().perform(HapticType.SELECTION)
                                        viewModel.setDefaultCatalogType(type)
                                        Toast.makeText(context, "Категория по умолчанию: $label", Toast.LENGTH_SHORT).show()
                                    }
                                    Surface(
                                        color = if (isSelected) CinemaPrimary else CinemaCard,
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(
                                            1.dp,
                                            if (isSelected) CinemaPrimary else CinemaMuted.copy(alpha = 0.25f)
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .tvFocusableItem(
                                                onClick = triggerType,
                                                shape = RoundedCornerShape(10.dp),
                                                scaleFactor = 1.02f
                                            )
                                            .testTag("default_type_${type.name.lowercase()}")
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 11.dp)
                                        ) {
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = CinemaBlack,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                            }
                                            Text(
                                                text = label,
                                                fontSize = 13.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSelected) CinemaBlack else CinemaTextWhite,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Подраздел: Раздел каталога
                        Text(
                            text = "Раздел каталога",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CinemaTextWhite
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        val sectionsList = remember {
                            listOf(
                                listOf(SectionType.LATEST to "Новые поступления", SectionType.POPULAR to "Популярные"),
                                listOf(SectionType.WATCHING to "Сейчас смотрят", SectionType.AWAITING to "В ожидании")
                            )
                        }

                        sectionsList.forEachIndexed { rowIndex, rowItems ->
                            if (rowIndex > 0) {
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowItems.forEach { (section, label) ->
                                    val isSelected = defaultCatalogSection == section
                                    val triggerSection: () -> Unit = {
                                        HapticEngine.get().perform(HapticType.SELECTION)
                                        viewModel.setDefaultCatalogSection(section)
                                        Toast.makeText(context, "Раздел по умолчанию: $label", Toast.LENGTH_SHORT).show()
                                    }
                                    Surface(
                                        color = if (isSelected) CinemaPrimary else CinemaCard,
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(
                                            1.dp,
                                            if (isSelected) CinemaPrimary else CinemaMuted.copy(alpha = 0.25f)
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .tvFocusableItem(
                                                onClick = triggerSection,
                                                shape = RoundedCornerShape(10.dp),
                                                scaleFactor = 1.02f
                                            )
                                            .testTag("default_section_${section.name.lowercase()}")
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 11.dp)
                                        ) {
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = CinemaBlack,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                            }
                                            Text(
                                                text = label,
                                                fontSize = 12.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSelected) CinemaBlack else CinemaTextWhite,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
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

        Spacer(modifier = Modifier.height(16.dp))

        // ==========================================
        // 4. КАТЕГОРИЯ: УВЕДОМЛЕНИЯ О НОВЫХ СЕРИЯХ
        // ==========================================
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CinemaDark),
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .testTag("subscriptions_category_card")
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
                        .padding(16.dp)
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
                                .background(CinemaMuted.copy(alpha = 0.2f), RoundedCornerShape(10.dp)),
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
                                text = "Отслеживание серий",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = CinemaTextWhite
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

        // ==========================================
        // КАТЕГОРИЯ: ОФФЛАЙН БИБЛИОТЕКА
        // ==========================================
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CinemaDark),
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .testTag("offline_category_card")
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header аккордеона
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusableItem(
                            onClick = {
                                HapticEngine.get().perform(HapticType.GENTLE_TICK)
                                isOfflineExpanded = !isOfflineExpanded
                            },
                            shape = RoundedCornerShape(16.dp),
                            scaleFactor = 1.0f
                        )
                        .padding(16.dp)
                        .testTag("settings_accordion_offline"),
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
                                .background(CinemaMuted.copy(alpha = 0.2f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudOff,
                                contentDescription = null,
                                tint = CinemaTextGray,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Column {
                            Text(
                                text = "Оффлайн библиотека",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = CinemaTextWhite
                            )
                            val computedTotalBytes = remember(offlineMedia) {
                                offlineMedia.sumOf { entity ->
                                    if (entity.fileSizeBytes > 0) entity.fileSizeBytes
                                    else {
                                        val f = java.io.File(entity.videoPath)
                                        if (f.exists()) f.length() else 0L
                                    }
                                }
                            }
                            Text(
                                text = if (offlineMedia.isEmpty()) "Оффлайн библиотека пуста"
                                       else "${offlineMedia.size} ${if (offlineMedia.size == 1) "файл" else "файлов"} • ${DownloadHelper.formatFileSize(computedTotalBytes)}",
                                fontSize = 11.sp,
                                color = CinemaTextGray,
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
                            .rotate(offlineArrowRotation)
                    )
                }

                // Тело аккордеона
                AnimatedVisibility(
                    visible = isOfflineExpanded,
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

                        if (offlineMedia.isEmpty()) {
                            Text(
                                text = "Оффлайн библиотека пуста",
                                color = CinemaTextGray,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        } else {
                            val computedTotalBytes = remember(offlineMedia) {
                                offlineMedia.sumOf { entity ->
                                    if (entity.fileSizeBytes > 0) entity.fileSizeBytes
                                    else {
                                        val f = java.io.File(entity.videoPath)
                                        if (f.exists()) f.length() else 0L
                                    }
                                }
                            }
                            // Кнопка очистки всей библиотеки
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Всего занято: ${DownloadHelper.formatFileSize(computedTotalBytes)}",
                                    fontSize = 12.sp,
                                    color = CinemaTextWhite,
                                    fontWeight = FontWeight.SemiBold
                                )

                                TextButton(
                                    onClick = {
                                        HapticEngine.get().perform(HapticType.WARNING)
                                        showClearOfflineDialog = true
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF5252)),
                                    modifier = Modifier.tvFocusableItem(
                                        onClick = {
                                            HapticEngine.get().perform(HapticType.WARNING)
                                            showClearOfflineDialog = true
                                        }
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteSweep,
                                        contentDescription = "Очистить библиотеку",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Очистить всё", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Список скачанных файлов
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                offlineMedia.forEach { entity ->
                                    val isSeries = entity.type == "SERIES"
                                    val subText = if (isSeries) {
                                        "Сезон ${entity.season}, Серия ${entity.episode} • ${entity.quality} • ${entity.translatorName}"
                                    } else {
                                        listOfNotNull(
                                            entity.year.ifEmpty { null },
                                            entity.quality.ifEmpty { null },
                                            entity.translatorName.ifEmpty { null }
                                        ).joinToString(" • ")
                                    }
                                    val actualFileSize = remember(entity.videoPath, entity.fileSizeBytes) {
                                        if (entity.fileSizeBytes > 0) entity.fileSizeBytes
                                        else {
                                            val f = java.io.File(entity.videoPath)
                                            if (f.exists()) f.length() else 0L
                                        }
                                    }

                                    Card(
                                        shape = RoundedCornerShape(10.dp),
                                        colors = CardDefaults.cardColors(containerColor = CinemaDark),
                                        border = BorderStroke(1.dp, CinemaMuted.copy(alpha = 0.25f)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .tvFocusableItem(
                                                onClick = {
                                                    HapticEngine.get().perform(HapticType.SOFT_CLICK)
                                                    val openItem = com.example.data.RezkaItem(
                                                        id = entity.itemId,
                                                        title = entity.title,
                                                        subtitle = subText,
                                                        imageUrl = if (entity.localPosterPath.isNotEmpty()) "file://${entity.localPosterPath}" else entity.imageUrl,
                                                        rating = "Оффлайн",
                                                        url = "",
                                                        type = try {
                                                            com.example.data.RezkaType.valueOf(entity.type.uppercase())
                                                        } catch (_: Exception) {
                                                            if (isSeries) com.example.data.RezkaType.SERIES else com.example.data.RezkaType.MOVIE
                                                        }
                                                    )
                                                    onNavigateToDetail?.invoke(openItem)
                                                },
                                                shape = RoundedCornerShape(10.dp)
                                            )
                                            .clickable {
                                                HapticEngine.get().perform(HapticType.SOFT_CLICK)
                                                val openItem = com.example.data.RezkaItem(
                                                    id = entity.itemId,
                                                    title = entity.title,
                                                    subtitle = subText,
                                                    imageUrl = if (entity.localPosterPath.isNotEmpty()) "file://${entity.localPosterPath}" else entity.imageUrl,
                                                    rating = "Оффлайн",
                                                    url = "",
                                                    type = try {
                                                        com.example.data.RezkaType.valueOf(entity.type.uppercase())
                                                    } catch (_: Exception) {
                                                        if (isSeries) com.example.data.RezkaType.SERIES else com.example.data.RezkaType.MOVIE
                                                    }
                                                )
                                                onNavigateToDetail?.invoke(openItem)
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Превью постера
                                            val posterModel = remember(entity.localPosterPath, entity.imageUrl) {
                                                if (entity.localPosterPath.isNotEmpty()) {
                                                    java.io.File(entity.localPosterPath)
                                                } else {
                                                    entity.imageUrl
                                                }
                                            }
                                            AsyncImage(
                                                model = posterModel,
                                                contentDescription = entity.title,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .size(width = 44.dp, height = 62.dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(CinemaCard)
                                            )

                                            Spacer(modifier = Modifier.width(12.dp))

                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = entity.title,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = CinemaTextWhite,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = subText,
                                                    fontSize = 11.sp,
                                                    color = CinemaPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = DownloadHelper.formatFileSize(actualFileSize),
                                                    fontSize = 11.sp,
                                                    color = CinemaTextGray
                                                )
                                            }

                                            Spacer(modifier = Modifier.width(8.dp))

                                             // Кнопка удаления файла
                                            IconButton(
                                                onClick = {
                                                    HapticEngine.get().perform(HapticType.WARNING)
                                                    viewModel.deleteOfflineMedia(entity)
                                                    Toast.makeText(context, "Файл удален", Toast.LENGTH_SHORT).show()
                                                },
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .tvFocusableItem(
                                                        onClick = {
                                                            HapticEngine.get().perform(HapticType.WARNING)
                                                            viewModel.deleteOfflineMedia(entity)
                                                            Toast.makeText(context, "Файл удален", Toast.LENGTH_SHORT).show()
                                                        },
                                                        shape = CircleShape
                                                    )
                                                    .testTag("offline_delete_${entity.id}")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.DeleteOutline,
                                                    contentDescription = "Удалить файл",
                                                    tint = Color(0xFFFF5252),
                                                    modifier = Modifier.size(18.dp)
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

        // Диалог подтверждения очистки всей библиотеки
        if (showClearOfflineDialog) {
            AlertDialog(
                onDismissRequest = { showClearOfflineDialog = false },
                containerColor = CinemaDark,
                title = {
                    Text(
                        text = "Очистить библиотеку?",
                        color = CinemaTextWhite,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = "Все сохраненные фильмы и серии (${offlineMedia.size} шт.) будут удалены из памяти устройства.",
                        color = CinemaTextGray,
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            HapticEngine.get().perform(HapticType.WARNING)
                            viewModel.clearOfflineLibrary()
                            showClearOfflineDialog = false
                            Toast.makeText(context, "Оффлайн библиотека очищена", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.tvFocusableItem(
                            onClick = {
                                HapticEngine.get().perform(HapticType.WARNING)
                                viewModel.clearOfflineLibrary()
                                showClearOfflineDialog = false
                                Toast.makeText(context, "Оффлайн библиотека очищена", Toast.LENGTH_SHORT).show()
                            }
                        )
                    ) {
                        Text("Удалить всё", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            HapticEngine.get().perform(HapticType.GENTLE_TICK)
                            showClearOfflineDialog = false
                        },
                        modifier = Modifier.tvFocusableItem(onClick = { showClearOfflineDialog = false })
                    ) {
                        Text("Отмена", color = CinemaTextGray)
                    }
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

