package com.naua_braidwork.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.naua_braidwork.app.ui.icons.IcwCheck
import com.naua_braidwork.app.ui.icons.IcwInfo
import com.naua_braidwork.app.ui.state.AccentSwatch
import com.naua_braidwork.app.ui.state.AppearanceState
import com.naua_braidwork.app.ui.state.SubRow

/**
 * Внешний вид и язык по спецификации `Appearance.dc.html`.
 *
 * Выбор сохраняется и сразу показывается, но не применяется: тема по-прежнему
 * следует за системной, потому что постоянного хранилища настроек ещё нет.
 * Поэтому под списком стоит пояснение — иначе нажатый кружок выглядел бы как
 * поломка.
 */
@Composable
fun AppearanceScreen(
    state: AppearanceState,
    onBack: () -> Unit,
    onTheme: (Int) -> Unit,
    onLang: (Int) -> Unit,
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
            title = "Внешний вид и язык",
            subtitle = "Тема, цвет и скорость интерфейса",
            onBack = onBack,
        )

        ChoiceGroup(
            title = "Тема",
            options = state.themes,
            selected = state.theme,
            onSelect = onTheme,
        )

        AccentRow(
            accents = state.accents,
            selected = state.accent,
            onSelect = onAccent,
        )

        ChoiceGroup(
            title = "Язык",
            options = state.langs,
            selected = state.lang,
            onSelect = onLang,
        )

        PerfGroup(state.perf)

        InfoBanner(
            icon = IcwInfo,
            text = "Выбор запоминается только до перезапуска. Тема, язык и " +
                "цвет акцента применятся, когда появится постоянное хранилище настроек.",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
        )
    }
}

/** Группа с кружками выбора: тема и язык устроены одинаково. */
@Composable
private fun ChoiceGroup(
    title: String,
    options: List<String>,
    selected: String,
    onSelect: (Int) -> Unit,
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
                        .height(56.dp)
                        .clickable { onSelect(index) }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = option,
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    BwRadio(checked = option == selected, onClick = { onSelect(index) })
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
                        .clickable { onSelect(swatch.id) },
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
                        .height(if (row.sub.isEmpty()) 56.dp else 66.dp)
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
