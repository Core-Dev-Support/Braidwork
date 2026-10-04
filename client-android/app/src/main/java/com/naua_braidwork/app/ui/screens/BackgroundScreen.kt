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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_braidwork.app.ui.components.PrimaryButton
import com.naua_braidwork.app.ui.components.RowDivider
import com.naua_braidwork.app.ui.components.StatusCard
import com.naua_braidwork.app.ui.components.SubBody
import com.naua_braidwork.app.ui.components.SubGroupCard
import com.naua_braidwork.app.ui.components.SubGroupTitle
import com.naua_braidwork.app.ui.components.SubHeader
import com.naua_braidwork.app.ui.icons.IcwCheck
import com.naua_braidwork.app.ui.icons.IcwPower
import com.naua_braidwork.app.ui.icons.IcwSliders
import com.naua_braidwork.app.ui.icons.IcwWarn
import com.naua_braidwork.app.ui.state.BackgroundState
import com.naua_braidwork.app.ui.state.BrandStep
import com.naua_braidwork.app.ui.state.RowEnd
import com.naua_braidwork.app.ui.state.SubRow
import com.naua_braidwork.app.ui.state.Toggles
import com.naua_braidwork.app.ui.state.Tone
import com.naua_braidwork.app.ui.theme.LocalBwColors

/**
 * Фоновая работа по спецификации `Background.dc.html`.
 *
 * Названия шагов различаются у Xiaomi, Samsung, Huawei, Realme и прочих
 * оболочек, поэтому шаги приходят для выбранного бренда, а не хранятся здесь.
 */
@Composable
fun BackgroundScreen(
    state: BackgroundState,
    toggles: Toggles,
    onBack: () -> Unit,
    onRow: (SubRow) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onBrand: (String) -> Unit,
    onOpenSystemSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 12.dp),
    ) {
        SubHeader(
            title = "Фоновая работа",
            subtitle = "Чтобы связь не обрывалась на экране блокировки",
            onBack = onBack,
        )

        SubBody {
            StatusCard(
                icon = IcwPower,
                title = state.serviceTitle,
                subtitle = state.serviceSub,
                tone = Tone.Green,
                tileSize = 44.dp,
                titleSize = 16,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            )

            SubGroupTitle(
                title = "Бренд устройства",
                modifier = Modifier.padding(start = 24.dp, top = 20.dp),
            )
            BrandPills(
                brands = state.brands,
                selected = state.brand,
                onSelect = onBrand,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            SubGroupTitle(
                title = "Шаги для ${state.brand}",
                modifier = Modifier.padding(start = 24.dp, top = 20.dp),
            )
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column {
                    state.steps.forEachIndexed { index, step ->
                        if (index > 0) RowDivider()
                        StepRow(step)
                    }
                }
            }

            PrimaryButton(
                text = "Открыть настройки системы",
                icon = IcwSliders,
                onClick = onOpenSystemSettings,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            )

            state.groups.forEach { group ->
                SubGroupTitle(
                    title = group.title,
                    modifier = Modifier.padding(start = 24.dp, top = 0.dp),
                )
                SubGroupCard(
                    rows = group.rows.withToggleValues(toggles),
                    onRow = { row ->
                        val end = row.end
                        if (end is RowEnd.Toggle && end.enabled) {
                            onToggle(row.id, !end.checked)
                        } else {
                            onRow(row)
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

/** Выбор бренда: круглые кнопки высотой 44, переносятся по мере добавления. */
@Composable
private fun BrandPills(
    brands: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        brands.chunked(3).forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { brand ->
                    val on = brand == selected
                    Surface(
                        modifier = Modifier.height(44.dp),
                        shape = RoundedCornerShape(22.dp),
                        color = if (on) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    ) {
                        Box(
                            modifier = Modifier
                                .height(44.dp)
                                .clickable { onSelect(brand) }
                                .padding(horizontal = 18.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = brand,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (on) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Шаг настройки: плашка 40 со знаком или галочкой, заголовок и состояние. */
@Composable
private fun StepRow(step: BrandStep) {
    val bw = LocalBwColors.current
    val background = if (step.done) bw.greenSubtle else bw.orangeSubtle
    val foreground = if (step.done) bw.online else Color(0xFFB4500A)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(66.dp)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(background, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (step.done) IcwCheck else IcwWarn,
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = step.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (step.done) "Готово" else "Нужно настроить",
                style = MaterialTheme.typography.bodySmall,
                color = if (step.done) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    Color(0xFFB4500A)
                },
            )
        }
    }
}
