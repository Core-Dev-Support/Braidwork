package com.naua_braidwork.app.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_braidwork.app.ui.icons.IcwBack
import com.naua_braidwork.app.ui.icons.IcwCheck
import com.naua_braidwork.app.ui.icons.IcwChevron
import com.naua_braidwork.app.ui.state.RowEnd
import com.naua_braidwork.app.ui.state.SubRow
import com.naua_braidwork.app.ui.state.Tone
import com.naua_braidwork.app.ui.theme.LocalBwColors

/**
 * Элементы подэкранов настроек.
 *
 * Все подэкраны устроены одинаково: стрелка назад, заголовок с подзаголовком,
 * карточки со строками. Общие части собраны здесь, иначе девять экранов
 * разъедутся друг с другом одинаково же и одинаково неверно.
 */

/**
 * Шапка подэкрана: стрелка назад, заголовок 32 и подзаголовок 16.
 *
 * Отступ сверху 12, а не 56 из макета: в макете 56 — это координата внутри
 * холста 390×960 с уже учтённым статус-баром. У нас статус-бар уже съеден
 * `windowInsetsPadding`, поэтому остаётся поле самого экрана.
 */
@Composable
fun SubHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .padding(start = 12.dp)
                .size(44.dp)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = IcwBack,
                contentDescription = "Назад",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(26.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 24.dp, top = 4.dp),
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, top = 4.dp),
        )
    }
}

/**
 * Полоса прокрутки для подэкрана.
 *
 * Отдельная функция, потому что почти все подэкраны длиннее экрана: без неё
 * пришлось бы повторять одну и ту же цепочку модификаторов девять раз.
 */
@Composable
fun SubBody(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        content()
    }
}

/**
 * Переключатель из спецификации: 52×32, ручка 28.
 *
 * Собственный, а не `Switch` из Material: у него другие пропорции и другая
 * анимация, а различие видно на каждой строке каждого экрана.
 */
@Composable
fun BwSwitch(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bw = LocalBwColors.current
    val knobOffset by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        label = "knob",
    )
    Box(
        modifier = modifier
            .size(width = 52.dp, height = 32.dp)
            .background(
                color = if (checked) bw.active else bw.trackOff,
                shape = RoundedCornerShape(16.dp),
            )
            .then(
                if (enabled) {
                    Modifier.clickable(role = Role.Switch) { onCheckedChange(!checked) }
                } else {
                    Modifier
                },
            ),
    ) {
        Box(
            modifier = Modifier
                .padding(start = knobOffset, top = 2.dp)
                .size(28.dp)
                .background(Color.White, RoundedCornerShape(14.dp)),
        )
    }
}

/** Кружок выбора: 28, обводка 2, при отметке залит синим с белой галочкой. */
@Composable
fun BwRadio(
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(28.dp)
            .background(
                color = if (checked) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = IcwCheck,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Строка списка настроек.
 *
 * Высота 66 с пояснением и 56 без него, поля 12 по вертикали и 18 по
 * горизонтали — как в макете. Разделитель рисует владелец группы, а не строка:
 * иначе первая строка каждой карточки получила бы лишнюю черту.
 */
@Composable
fun SettingRowView(
    row: SubRow,
    onRowClick: () -> Unit = {},
    onToggle: (Boolean) -> Unit = {},
    onRadio: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val clickable = row.end !is RowEnd.Toggle && row.end !is RowEnd.Radio
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(
                when {
                    row.icon != null -> 64.dp
                    row.sub.isEmpty() -> 56.dp
                    else -> 66.dp
                },
            )
            .then(
                if (clickable) Modifier.clickable(onClick = onRowClick) else Modifier,
            )
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.icon != null) {
            IconTile(
                icon = row.icon,
                tone = row.tone,
                size = 40.dp,
                radius = 12.dp,
            )
            Spacer(Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (row.danger) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.sub.isNotEmpty()) {
                Text(
                    text = row.sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        when (val end = row.end) {
            is RowEnd.Value -> {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = end.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            is RowEnd.Chevron -> Icon(
                imageVector = IcwChevron,
                contentDescription = null,
                tint = Color(0xFF8A8D97),
                modifier = Modifier.size(18.dp),
            )

            is RowEnd.Toggle -> {
                Spacer(Modifier.width(12.dp))
                BwSwitch(
                    checked = end.checked,
                    enabled = end.enabled,
                    onCheckedChange = onToggle,
                    modifier = if (end.enabled) {
                        Modifier
                    } else {
                        Modifier.alpha(0.4f)
                    },
                )
            }

            is RowEnd.Radio -> {
                Spacer(Modifier.width(12.dp))
                BwRadio(checked = end.checked, onClick = onRadio)
            }

            RowEnd.None -> Unit
        }
    }
}

/**
 * Заголовок группы над карточкой. Пустое имя — группы без заголовка.
 *
 * Внутреннего отступа слева нет намеренно: в макете подпись группы стоит на
 * 24 dp от края экрана, а сама карточка — на 16, и эти восемь dp разные у
 * разных экранов. Отступ задаёт вызывающий.
 */
@Composable
fun SubGroupTitle(title: String, modifier: Modifier = Modifier) {
    if (title.isEmpty()) return
    Text(
        text = title,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(bottom = 8.dp),
    )
}

/**
 * Карточка группы строк с разделителями.
 *
 * Скругление 24, разрез по краю: так в макете, и на светлом фоне это читается
 * как отдельная карточка, а не как продолжение предыдущей.
 */
@Composable
fun SubGroupCard(
    rows: List<SubRow>,
    onRow: (SubRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            rows.forEachIndexed { index, row ->
                if (index > 0) RowDivider()
                SettingRowView(
                    row = row,
                    onRowClick = { onRow(row) },
                    onToggle = { onRow(row) },
                    onRadio = { onRow(row) },
                )
            }
        }
    }
}

/**
 * Карточка состояния: плашка иконки, заголовок, пояснение.
 *
 * Есть на «Безопасности», «Сети» и «Фоновой работе» с разными размерами
 * плашки (52×16 и 44×12), поэтому размер задаётся параметром. Иконку
 * [IconTile] считает сам от размера плашки: в обоих случаях это примерно
 * половина, отдельно задавать нечего.
 */
@Composable
fun StatusCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tone: Tone,
    tileSize: Dp = 52.dp,
    tileRadius: Dp = 16.dp,
    titleSize: Int = 17,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(
                icon = icon,
                tone = tone,
                size = tileSize,
                radius = tileRadius,
                strokeWidth = 1.8f,
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = titleSize.sp,
                    lineHeight = (titleSize + 6).sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Поясняющая плашка: иконка и текст на цветной подложке, радиус 20. */
@Composable
fun InfoBanner(
    icon: ImageVector,
    text: String,
    shape: Shape = RoundedCornerShape(20.dp),
    modifier: Modifier = Modifier,
) {
    val bw = LocalBwColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bw.infoBg, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = bw.infoFg,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = bw.infoFg,
        )
    }
}

/** Синяя кнопка во всю ширину: высота 56, радиус 28. */
@Composable
fun PrimaryButton(
    text: String,
    icon: ImageVector? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(text = text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * Переключатель-сегмент: два или три варианта в одной дорожке.
 *
 * Высота 48, радиус дорожки 24 и активной части 20, поля 4 — из макета.
 */
@Composable
fun SegmentedTabs(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(Color(0xFFE4E6EC), RoundedCornerShape(24.dp))
            .padding(4.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { index, label ->
                val on = index == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .height(40.dp)
                        .background(
                            color = if (on) Color.White else Color.Transparent,
                            shape = RoundedCornerShape(20.dp),
                        )
                        .clickable { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (on) {
                            Color(0xFF121316)
                        } else {
                            Color(0xFF4A4D57)
                        },
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
