package com.example.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

data class AvatarPreset(
    val id: String,
    val label: String,
    val emoji: String
)

val AVATAR_PRESETS = listOf(
    AvatarPreset("preset:popcorn", "Попкорн", "🍿"),
    AvatarPreset("preset:clapper", "Хлопушка", "🎬"),
    AvatarPreset("preset:sunglasses", "Агент", "🕶️"),
    AvatarPreset("preset:astronaut", "Космос", "🚀"),
    AvatarPreset("preset:theatre", "Театр", "🎭"),
    AvatarPreset("preset:robot", "Киборг", "🤖"),
    AvatarPreset("preset:cat", "Киса", "🐱"),
    AvatarPreset("preset:dragon", "Дракон", "🐉"),
    AvatarPreset("preset:ninja", "Ниндзя", "🥷"),
    AvatarPreset("preset:star", "Звезда", "⭐")
)

/**
 * Высокопроизводительный компонент круглого аватара пользователя.
 * Поддерживает пресеты (эмодзи), кастомные base64/Uri изображения и красивый фолбэк.
 */
@Composable
fun UserAvatar(
    avatar: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val clickModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else Modifier

    val baseModifier = modifier
        .size(size)
        .clip(CircleShape)
        .then(clickModifier)

    if (avatar.isNullOrBlank()) {
        Box(
            modifier = baseModifier
                .background(CinemaCard)
                .border(1.dp, CinemaBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = "Аватар",
                tint = CinemaTextWhite,
                modifier = Modifier.size(size * 0.6f)
            )
        }
    } else if (avatar.startsWith("preset:")) {
        val preset = AVATAR_PRESETS.find { it.id == avatar }
        val emoji = preset?.emoji ?: "🎬"
        Box(
            modifier = baseModifier
                .background(CinemaCard)
                .border(1.dp, CinemaBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = emoji,
                fontSize = (size.value * 0.52f).sp
            )
        }
    } else {
        // Кастомное изображение (base64 Data URI или ссылка)
        Box(
            modifier = baseModifier
                .background(CinemaCard)
                .border(1.dp, CinemaBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = avatar,
                contentDescription = "Аватар",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * Диалог выбора аватара: Photo Picker (галерея) или коллекция кино-пресетов.
 */
@Composable
fun AvatarPickerDialog(
    currentAvatar: String?,
    onAvatarSelected: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isProcessingPhoto by remember { mutableStateOf(false) }

    // Android Zero-Permission Photo Picker
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            isProcessingPhoto = true
            coroutineScope.launch {
                val base64 = withContext(Dispatchers.IO) {
                    processPickedImage(context, uri)
                }
                isProcessingPhoto = false
                if (base64 != null) {
                    onAvatarSelected(base64)
                    onDismiss()
                }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = CinemaDark),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Заголовок
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Выбор аватара",
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

                Spacer(modifier = Modifier.height(16.dp))

                // Кнопка загрузки из галереи
                OutlinedButton(
                    onClick = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    enabled = !isProcessingPhoto,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = CinemaCard,
                        contentColor = CinemaTextWhite
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isProcessingPhoto) {
                        CircularProgressIndicator(
                            color = CinemaPrimary,
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Обработка фото...", fontSize = 14.sp)
                    } else {
                        Icon(
                            imageVector = Icons.Default.AddPhotoAlternate,
                            contentDescription = null,
                            tint = CinemaPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Выбрать фото из галереи",
                            color = CinemaTextWhite,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Или выберите кино-образ:",
                    color = CinemaTextGray,
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.Start)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Сетка кино-пресетов
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp)
                ) {
                    items(AVATAR_PRESETS) { preset ->
                        val isSelected = currentAvatar == preset.id
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) CinemaPrimary.copy(alpha = 0.25f) else CinemaCard)
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) CinemaPrimary else CinemaBorder,
                                    shape = CircleShape
                                )
                                .clickable {
                                    onAvatarSelected(preset.id)
                                    onDismiss()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = preset.emoji, fontSize = 24.sp)
                        }
                    }
                }

                // Кнопка удаления аватара (сброс к дефолтному)
                if (!currentAvatar.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    TextButton(
                        onClick = {
                            onAvatarSelected(null)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = CinemaPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Удалить аватар",
                            color = CinemaPrimary,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Оптимизированное сжатие изображения для аватара:
 * 1. Читает размеры без загрузки пикселей (inJustDecodeBounds).
 * 2. Вычисляет оптимальный sampleSize для экономии RAM.
 * 3. Делает квадратный кроп по центру и масштабирует до 160x160 px.
 * 4. Сжимает в JPEG 75% — итоговый размер ~6-10 КБ, минимальная нагрузка на CPU и базу.
 */
private fun processPickedImage(context: Context, uri: Uri): String? {
    return try {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOptions)
        }
        val origWidth = boundsOptions.outWidth
        val origHeight = boundsOptions.outHeight
        if (origWidth <= 0 || origHeight <= 0) return null

        val targetSize = 160
        var sampleSize = 1
        while ((origWidth / sampleSize) > targetSize * 2 || (origHeight / sampleSize) > targetSize * 2) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val sampledBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: return null

        val minEdge = Math.min(sampledBitmap.width, sampledBitmap.height)
        val cropX = (sampledBitmap.width - minEdge) / 2
        val cropY = (sampledBitmap.height - minEdge) / 2
        val cropped = Bitmap.createBitmap(sampledBitmap, cropX, cropY, minEdge, minEdge)
        val scaled = Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)

        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 75, baos)
        val bytes = baos.toByteArray()
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        "data:image/jpeg;base64,$base64"
    } catch (e: Exception) {
        Log.e("UserAvatar", "Error processing image", e)
        null
    }
}
