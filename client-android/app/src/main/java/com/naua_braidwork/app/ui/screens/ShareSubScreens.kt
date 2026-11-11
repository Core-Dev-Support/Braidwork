package com.naua_braidwork.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_braidwork.app.ui.RowsSubScreen
import com.naua_braidwork.app.ui.components.PillButton
import com.naua_braidwork.app.ui.components.tapNoRipple
import com.naua_braidwork.app.ui.icons.IcwCheck
import com.naua_braidwork.app.ui.icons.IcwDevices
import com.naua_braidwork.app.ui.icons.IcwFolder
import com.naua_braidwork.app.ui.icons.IcwUpload
import com.naua_braidwork.app.ui.icons.IcwWarn
import com.naua_braidwork.app.ui.state.FilesState
import com.naua_braidwork.app.ui.state.SubGroup
import com.naua_braidwork.app.ui.state.SubRow
import com.naua_braidwork.app.ui.state.Toggles
import com.naua_braidwork.app.ui.state.Transfer

/**
 * Буфер обмена по спецификации `design/spec/SPEC.txt`.
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
    /**
     * Вставить запись из истории в буфер обмена.
     *
     * Отдельный обработчик, а не `onRow`: `onRow` открывает подэкран по
     * идентификатору строки, и запись из истории подэкраном не является.
     */
    onВставить: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    RowsSubScreen(
        title = "Буфер обмена",
        subtitle = "Копируй на одном устройстве, вставляй на другом",
        groups = groups,
        toggles = toggles,
        onBack = onBack,
        // Запись истории вставляется, а не открывается: её идентификатор не
        // маршрут, и `открыть` его отбросил бы молча.
        onRow = { строка ->
            if (строка.id.startsWith("cb_") && строка.id != "cb_empty") {
                onВставить(строка.id)
            } else {
                onRow(строка)
            }
        },
        onToggle = onToggle,
        modifier = modifier,
    )
}

/**
 * Файлы по спецификации `design/spec/SPEC.txt`.
 *
 * Кнопка отправки и выбор получателя остаются: они ведут туда же, куда вели
 * бы с готовым транспортом. Карточка передачи и полоса хранилища — с нулями.
 */
@Composable
fun FilesScreen(
    state: FilesState,
    toggles: Toggles,
    onBack: () -> Unit,
    onRow: (SubRow) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onSend: () -> Unit,
    onPickReceiver: () -> Unit,
    onCancel: (String) -> Unit,
    receiverName: String = "",
    modifier: Modifier = Modifier,
) {
    val комуТекст = if (receiverName.isNotBlank()) receiverName else "Кому"
    RowsSubScreen(
        title = "Файлы",
        subtitle = "Передача и хранилище по хэшу",
        groups = state.groups,
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
                    комуТекст, IcwDevices, filled = false,
                    onClick = onPickReceiver, modifier = Modifier.weight(1f),
                )
            }

            TransferList(transfers = state.transfers, onCancel = onCancel)

            StorageCard(
                storedText = state.storedText,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        },
    )
}

/**
 * Список передач с пустым состоянием.
 *
 * Список и заглушка — одна функция, а не два экрана. Когда появится первая
 * передача, подключать транспорт не придётся: список уже нарисован. Отмена
 * нарисована сразу по той же причине — она понадобится в первом же прогоне, и
 * дописывать её, когда передача уже идёт, хуже.
 */
@Composable
private fun TransferList(
    transfers: List<Transfer>,
    onCancel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (transfers.isEmpty()) {
        Surface(
            modifier = modifier
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
        return
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            transfers.forEachIndexed { index, transfer ->
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }
                TransferRow(transfer = transfer, onCancel = { onCancel(transfer.name) })
            }
        }
    }
}

/** Строка передачи: имя, получатель, доля, скорость и отмена. */
@Composable
private fun TransferRow(transfer: Transfer, onCancel: () -> Unit) {
    val isFinished = transfer.progress >= 1f || transfer.leftText.startsWith("Готово")
    Column(modifier = Modifier.padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTileGreen()
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = transfer.name,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                val targetLabel = if (transfer.target.startsWith("от ") || transfer.target.startsWith("на ") ||
                    transfer.target.startsWith("От ") || transfer.target.startsWith("На ")) {
                    transfer.target
                } else {
                    "На ${transfer.target}"
                }
                val subText = if (isFinished) {
                    "$targetLabel · ${transfer.leftText}"
                } else {
                    "$targetLabel · ${transfer.speedMbps} Мбит/с"
                }
                Text(
                    text = subText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                text = "${(transfer.progress * 100).toInt()}%",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isFinished) Color(0xFF2E7D32) else Color(0xFF1A6FDB),
            )
        }
        Spacer(Modifier.height(14.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(Color(0xFFE6E8EE), RoundedCornerShape(4.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(transfer.progress.coerceIn(0f, 1f))
                    .height(8.dp)
                    .background(if (isFinished) Color(0xFF4CAF50) else Color(0xFF2B8CF5), RoundedCornerShape(4.dp)),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = transfer.sentText,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = transfer.leftText,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!isFinished) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Отменить",
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.tapNoRipple(onClick = onCancel),
                    )
                }
            }
        }
    }
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
private fun StorageCard(storedText: String, modifier: Modifier = Modifier) {
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
                    text = storedText,
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
            // Прокручивается вбок по той же причине, что и фильтры в списке
            // устройств: при крупном системном шрифте три подписи легенды перестают
            // помещаться, а `Row` за своей шириной обрезает без предупреждения.
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
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

/**
 * Плашка «нужно действие».
 *
 * Раньше здесь была зелёная галочка, и это была ошибка: зелёный цвет с
 * галочкой читается как «всё работает», а текст под ним говорил, что разрешение
 * не выдано. Пользователь до текста не доходит.
 *
 * Теперь цвет и знак означают то же, что и слова: нужен доступ. А нажатие на
 * карточку ведёт в системные настройки уведомлений — единственное действие на
 * этом экране, которое уже можно выполнить по-настоящему.
 */
@Composable
internal fun NeedsAttentionBubble(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(44.dp)
            .background(Color(0xFFFFF1E6), RoundedCornerShape(22.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = IcwWarn,
            contentDescription = null,
            tint = Color(0xFFC65D0E),
            modifier = Modifier.size(24.dp),
        )
    }
}
