package com.naua_braidwork.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.naua_braidwork.app.ui.components.StatusCard
import com.naua_braidwork.app.ui.components.SubBody
import com.naua_braidwork.app.ui.components.SubGroupCard
import com.naua_braidwork.app.ui.components.SubGroupTitle
import com.naua_braidwork.app.ui.components.SubHeader
import com.naua_braidwork.app.ui.icons.IcwShield
import com.naua_braidwork.app.ui.state.RowEnd
import com.naua_braidwork.app.ui.state.SecurityState
import com.naua_braidwork.app.ui.state.SubRow
import com.naua_braidwork.app.ui.state.Toggles
import com.naua_braidwork.app.ui.state.Tone
import com.naua_braidwork.app.ui.state.withToggleValues

/**
 * Безопасность и ключи по спецификации `design/spec/SPEC.txt`.
 *
 * Экран — это карточка состояния и группы строк, поэтому здесь только
 * перечисление: всё общее живёт в компонентах.
 */
@Composable
fun SecurityScreen(
    state: SecurityState,
    toggles: Toggles,
    onBack: () -> Unit,
    onRow: (SubRow) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 12.dp),
    ) {
        SubHeader(
            title = "Безопасность и ключи",
            subtitle = "Защита данных между устройствами",
            onBack = onBack,
        )

        SubBody {
            StatusCard(
                icon = IcwShield,
                title = "Сквозное шифрование",
                subtitle = "Noise XX · ключи хранятся только на устройстве",
                tone = Tone.Green,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            )

            state.groups.forEach { group ->
                SubGroupTitle(
                    title = group.title,
                    modifier = Modifier.padding(start = 24.dp, top = 20.dp),
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
