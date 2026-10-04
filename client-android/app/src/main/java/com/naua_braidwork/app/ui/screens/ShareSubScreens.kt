package com.naua_braidwork.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_braidwork.app.ui.RowsSubScreen
import com.naua_braidwork.app.ui.icons.IcwCheck
import com.naua_braidwork.app.ui.icons.IcwDevices
import com.naua_braidwork.app.ui.icons.IcwFolder
import com.naua_braidwork.app.ui.icons.IcwUpload
import com.naua_braidwork.app.ui.state.SubGroup
import com.naua_braidwork.app.ui.state.SubRow
import com.naua_braidwork.app.ui.state.Toggles

/**
 * Буфер обмена по спецификации `Clipboard.dc.html`.
 *
 * Групп «Принимают буфер» и «Недавнее» здесь нет: подключённых устройств и
 * переданных записей ещё не существует. Пустые группы занимали бы место и
 * намекали бы, что данные есть, просто не показаны.
 */
@Composable
fun ClipboardScreen(
    groups: List<SubGroup>,
    toggles: Toggles,
    onBack: () -> Unit,
    onRow: (SubRow) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    RowsSubScreen(
        title = "Буфер обмена",
        subtitle = "Копируй на одном устройстве, вставляй на другом",
        groups = groups,
        toggles = toggles,
        onBack = onBack,
        onRow = onRow,
        onToggle = onToggle,
        modifier = modifier,
    )
}

/**
 * Файлы по спецификации `Files.dc.html`.
 *
 * Кнопка отправки и выбор получателя остаются: они ведут туда же, куда вели
 * бы с готовым транспортом. Карточка передачи и полоса хранилища — с нулями.
 */
@Composable
fun FilesScreen(
    groups: List<SubGroup>,
    toggles: Toggles,
    onBack: () -> Unit,
    onRow: (SubRow) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onSend: () -> Unit,
    onPickReceiver: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RowsSubScreen(
        title = "Файлы",
        subtitle = "Передача и хранилище по хэшу",
        groups = groups,
        toggles = toggles,
        onBack = onBack,
        onRow = onRow,
        onToggle = onToggle,
        modifier = modifier,
        top = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PillButton(
                    "Отправить файл", IcwUpload, filled = true,
                    onClick = onSend, modifier = Modifier.weight(1f),
                )
                PillButton(
                    "Кому", IcwDevices, filled = false,
                    onClick = onPickReceiver, modifier = Modifier.weight(1f),
                )
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTileGreen()
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Передач нет",
                                fontSize = 16.sp,
                                lineHeight = 22.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Устройство ещё не нашло ни одного получателя",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    // Полоса пустая, но рисуется: так видно, где будет прогресс.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .background(Color(0xFFE6E8EE), RoundedCornerShape(4.dp)),
                    )
                }
            }

            StorageCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        },
    )
}

@Composable
private fun IconTileGreen() {
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(Color(0xFFE8F6EE), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = IcwFolder,
            contentDescription = null,
            tint = Color(0xFF16804A),
            modifier = Modifier.size(24.dp),
        )
    }
}

/** Хранилище: подпись, полоса из трёх долей и расшифровка. */
@Composable
private fun StorageCard(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = "Хранилище",
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "0 Б из 5 ГБ",
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .background(Color(0xFFE6E8EE), RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StorageLegend("Видео", Color(0xFF2B8CF5))
                StorageLegend("Фото", Color(0xFF3DDC84))
                StorageLegend("Другое", Color(0xFFFF9A3D))
            }
        }
    }
}

@Composable
private fun StorageLegend(label: String, dot: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dot, RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = Color(0xFF4A4D57),
        )
    }
}

/** Кнопка-таблетка: либо синяя с белым текстом, либо белая с тёмным. */
@Composable
private fun PillButton(
    text: String,
    icon: ImageVector,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(28.dp),
        color = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (filled) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onClick),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                fontSize = 16.sp,
                fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

/** Галочка в кружке: статус разрешения на уведомления. */
@Composable
internal fun CheckBubble(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(44.dp)
            .background(Color(0xFFE8F6EE), RoundedCornerShape(22.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = IcwCheck,
            contentDescription = null,
            tint = Color(0xFF16804A),
            modifier = Modifier.size(24.dp),
        )
    }
}
