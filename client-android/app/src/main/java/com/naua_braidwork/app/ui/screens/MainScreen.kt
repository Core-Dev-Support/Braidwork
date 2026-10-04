package com.naua_braidwork.app.ui.screens

import com.naua_braidwork.app.ui.components.tapNoRipple
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_braidwork.app.ui.components.BwCard
import com.naua_braidwork.app.ui.components.EmptyHint
import com.naua_braidwork.app.ui.components.Gap
import com.naua_braidwork.app.ui.components.IconTile
import com.naua_braidwork.app.ui.components.SectionTitle
import com.naua_braidwork.app.ui.components.StatusChip
import com.naua_braidwork.app.ui.components.toneColors
import com.naua_braidwork.app.ui.icons.IcwBell
import com.naua_braidwork.app.ui.icons.IcwBluetooth
import com.naua_braidwork.app.ui.icons.IcwHotspot
import com.naua_braidwork.app.ui.icons.IcwPhone
import com.naua_braidwork.app.ui.icons.IcwPlus
import com.naua_braidwork.app.ui.icons.IcwWifi
import com.naua_braidwork.app.ui.state.ActivityEntry
import com.naua_braidwork.app.ui.state.LinkMode
import com.naua_braidwork.app.ui.state.MainState
import com.naua_braidwork.app.ui.state.QuickAction
import com.naua_braidwork.app.ui.theme.LocalBwColors

/**
 * Главный экран по спецификации `design/spec/SPEC.txt`.
 *
 * Порядок блоков и размеры взяты из макета: заголовок со счётчиком устройств,
 * строка канала, карточка своего устройства с тремя режимами связи, две кнопки
 * резервных каналов, полоса быстрых действий, лента активности.
 */
@Composable
fun MainScreen(
    state: MainState,
    mode: LinkMode,
    onMode: (LinkMode) -> Unit,
    bleOn: Boolean,
    softApOn: Boolean,
    onPairClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onQuickAction: (QuickAction) -> Unit,
    onBleClick: (Boolean) -> Unit,
    onSoftApClick: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {

    LazyColumn(
        modifier = modifier
            .fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
    ) {
        item {
            // Отступ сверху задаётся системной панелью: за статус-бар
            // залезать нельзя, а в макете он не нарисован.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Моя сеть",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = deviceCountLabel(state.deviceCount),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Колокольчик открывает настройку уведомлений, а не раздел
                // «Обмен»: раздел показывает сводку, а колокольчик зовёт туда,
                // где их настраивают.
                HeaderIcon(IcwBell, "Уведомления", onNotificationsClick)
                Spacer(Modifier.width(2.dp))
                HeaderIcon(IcwPlus, "Добавить устройство", onPairClick)
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = IcwWifi,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = state.channelLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${state.directLinks} прямых соединений",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Spacer(Modifier.height(18.dp))
            BwCard(radius = 28.dp, paddingHorizontal = 12.dp) {
                ThisDeviceBlock(state)
                Gap(22.dp)
                ModeRow(current = mode, onPick = onMode)
                Gap(22.dp)
                // Каналы «рядом» — не режимы. Режим выбирает, как искать
                // устрой��ства, а здесь включается сам канал, и оба могут
                // работать вместе с любым режимом. Поэтому это тумблеры, а не
                // кружки: раньше они выглядели как режимы, и нажатие на
                // «Точку доступа» читалось как смена режима.
                ChannelRow(
                    label = "Рядом (BLE)",
                    icon = IcwBluetooth,
                    checked = bleOn,
                    onCheckedChange = onBleClick,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                ChannelRow(
                    label = "Точка доступа",
                    icon = IcwHotspot,
                    checked = softApOn,
                    onCheckedChange = onSoftApClick,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            QuickRow(state.quick, onQuickAction)
        }

        item {
            Gap(20.dp)
            SectionTitle("Последняя активность", Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(10.dp))
            if (state.activity.isEmpty()) {
                // Пусто вместо выдуманных записей: экран показывают тестерам,
                // и правдоподобные фальшивые события вредят сильнее отсутствия.
                BwCard(paddingVertical = 20.dp) {
                    EmptyHint("Пока ничего не происходило")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Связь между устройствами ещё не включена. " +
                            "Ядро готово: ключи созданы, шифрование работает.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            } else {
                ActivityCard(state.activity)
            }
        }
    }
}

/**
 * Число с существительным в нужном падеже.
 *
 * Отдельная функция, потому что правило не сводится к «1, 2-4, остальное»:
 * при одиннадцати и при двадцати одном окончание снова единственное. Ошибка
 * «21 устройств» бросается в глаза сразу, а «21 устройство» нет.
 */
internal fun plural(count: Int, one: String, few: String, many: String): String {
    val mod100 = count % 100
    val mod10 = count % 10
    return when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
}

private fun deviceCountLabel(count: Int): String =
    "$count ${plural(count, "устройство", "устройства", "устройств")} в сети"

@Composable
private fun HeaderIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .tapNoRipple(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(26.dp),
        )
    }
}

/** Карточка «это устройство» с тремя режимами связи. */
@Composable
private fun ThisDeviceBlock(state: MainState) {
    val bw = LocalBwColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(
            icon = IcwPhone,
            tone = com.naua_braidwork.app.ui.state.Tone.Blue,
            size = 64.dp,
            radius = 18.dp,
            strokeWidth = 1.6f,
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.thisDeviceName,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Это устройство · ключи в порядке",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(Modifier.height(14.dp))
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bw.onlineSubtle,
        contentColor = bw.online,
    ) {
        Text(
            text = state.thisDeviceId,
            fontSize = 17.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp,
            maxLines = 2,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

/** Три круглые кнопки режима связи: работают как радиокнопки. */
@Composable
private fun ModeRow(current: LinkMode, onPick: (LinkMode) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top,
    ) {
        LinkMode.entries.forEach { mode ->
            val selected = mode == current
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .background(
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                LocalBwColors.current.modeIdle
                            },
                            shape = RoundedCornerShape(34.dp),
                        )
                        .tapNoRipple { onPick(mode) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = mode.icon,
                        contentDescription = mode.label,
                        tint = if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.size(30.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = mode.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Кнопка резервного канала. Недоступная остаётся видимой, но не нажимается. */
@Composable
/**
 * Канал «рядом»: иконка, подпись и тумблер.
 *
 * Отдельная форма вместо кружка режима. Прямоугольная плашка с тумблером читается
 * как «включить и выключить», круг с заливкой — как «выбрать один из трёх».
 * Смешивать эти два смысла в одной форме нельзя.
 */
@Composable
private fun ChannelRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .tapNoRipple { onCheckedChange(!checked) }
                .padding(start = 14.dp, end = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            BwSwitch(
                checked = checked,
                enabled = true,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

/** Полоса быстрых действий: четыре круглые плашки. */
@Composable
private fun QuickRow(actions: List<QuickAction>, onClick: (QuickAction) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top,
        ) {
            actions.forEach { action ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .tapNoRipple { onClick(action) }
                        .padding(vertical = 2.dp),
                ) {
                    IconTile(
                        icon = action.icon,
                        tone = action.tone,
                        size = 52.dp,
                        radius = 26.dp,
                        strokeWidth = 1.7f,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = action.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

/** Лента активности. Строки без разделителя на первой позиции. */
@Composable
private fun ActivityCard(entries: List<ActivityEntry>) {
    BwCard(radius = 24.dp, paddingVertical = 0.dp) {
        entries.forEachIndexed { index, entry ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTile(
                    icon = entry.icon,
                    tone = entry.tone,
                    size = 40.dp,
                    radius = 12.dp,
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = entry.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = entry.time,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}