package com.naua_braidwork.app.ui.screens

import com.naua_braidwork.app.ui.components.tapNoRipple
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_braidwork.app.ui.components.BwRadio
import com.naua_braidwork.app.ui.components.BwSwitch
import com.naua_braidwork.app.ui.components.InfoBanner
import com.naua_braidwork.app.ui.components.RowDivider
import com.naua_braidwork.app.ui.components.SubHeader
import com.naua_braidwork.app.ui.components.tapNoRipple
import com.naua_braidwork.app.ui.icons.IcwCheck
import com.naua_braidwork.app.ui.icons.IcwInfo
import com.naua_braidwork.app.ui.state.AccentSwatch
import com.naua_braidwork.app.ui.state.AppearanceState
import com.naua_braidwork.app.ui.state.SubRow
import com.naua_braidwork.app.ui.theme.ThemeChoice

/**
 * Внешний вид по спецификации `design/spec/SPEC.txt`.
 *
 * Группы «Язык» здесь нет, хотя в макете она есть. Выбор языка без переводов —
 * это список из двух строк, одна из которых ничего не делает: нажал «English» и
 * получил русский текст. Пустой переключатель хуже отсутствующего, поэтому
 * группы нет, а под экраном написано почему. Вернём вместе с переводами.
 *
 * Цвет акцента выбирается и показывается, но пока не применяется: палитра
 * акцента задана в токенах и не переопределяется. Переключатели
 * производительности не подключены по той же причине — они влияют на анимации
 * и масштаб текста, а это будет сделано вместе с постоянным хранилищем.
 */
@Composable
fun AppearanceScreen(
    state: AppearanceState,
    onBack: () -> Unit,
    onTheme: (ThemeChoice) -> Unit,
    onAccent: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 12.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        SubHeader(
            title = "Внешний вид",
            subtitle = "Тема, цвет и скорость интерфейса",
            onBack = onBack,
        )

        ChoiceGroup(
            title = "Тема",
            options = state.themes,
            selected = state.theme,
            label = { it.title },
            onSelect = onTheme,
        )

        AccentRow(
            accents = state.accents,
            selected = state.accent,
            onSelect = onAccent,
        )

        PerfGroup(state.perf)

        InfoBanner(
            icon = IcwInfo,
            text = "Тема применяется сразу и сохраняется: после перезапуска " +
                "приложения останется выбранная.",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
        )

        InfoBanner(
            icon = IcwInfo,
            text = "Выбора языка пока нет: переводы не написаны, а обещать " +
                "второй язык без него значило бы показывать пустой список.",
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

/** Группа с кружками выбора: тема и язык устроены одинаково. */
@Composable
private fun <T> ChoiceGroup(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    GroupLabel(title)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            options.forEachIndexed { index, option ->
                if (index > 0) RowDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Минимум: та же причина, что и в строках ниже.
                        .heightIn(min = 56.dp)
                        .tapNoRipple { onSelect(option) }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label(option),
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    BwRadio(checked = option == selected, onClick = { onSelect(option) })
                }
            }
        }
    }
}

/** Палитра акцента: пять кружков 48, у выбранного кольцо своего цвета и галочка. */
@Composable
private fun AccentRow(
    accents: List<AccentSwatch>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    GroupLabel("Цвет акцента")
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            accents.forEach { swatch ->
                val on = swatch.id == selected
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(swatch.color, RoundedCornerShape(24.dp))
                        .border(3.dp, Color.White, RoundedCornerShape(24.dp))
                        .tapNoRipple { onSelect(swatch.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (on) {
                        // Внешнее кольцо своего цвета рисуется поверх белой
                        // обводки: так выбранный кружок виден на любом фоне.
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .border(2.dp, swatch.color, RoundedCornerShape(24.dp)),
                        )
                        Icon(
                            imageVector = IcwCheck,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Производительность: три переключателя для слабых устройств. */
@Composable
private fun PerfGroup(rows: List<SubRow>) {
    GroupLabel("Производительность")
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            rows.forEachIndexed { index, row ->
                if (index > 0) RowDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = if (row.sub.isEmpty()) 56.dp else 66.dp)
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = row.title,
                            fontSize = 16.sp,
                            lineHeight = 22.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (row.sub.isNotEmpty()) {
                            Text(
                                text = row.sub,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    // Переключатели показаны, но не подключены: они влияют на
                    // анимации и масштаб текста, а это будет сделано вместе с
                    // постоянным хранилищем настроек.
                    BwSwitch(checked = false, enabled = false, onCheckedChange = { })
                }
            }
        }
    }
}

/** Подпись группы: 13 полужирный, отступ 24 слева и 8 снизу. */
@Composable
private fun GroupLabel(title: String) {
    Text(
        text = title,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 8.dp),
    )
}
