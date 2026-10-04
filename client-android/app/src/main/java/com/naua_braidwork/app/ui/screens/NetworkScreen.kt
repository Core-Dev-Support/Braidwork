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
import com.naua_braidwork.app.ui.icons.IcwWifi
import com.naua_braidwork.app.ui.state.NetworkState
import com.naua_braidwork.app.ui.state.RowEnd
import com.naua_braidwork.app.ui.state.SubRow
import com.naua_braidwork.app.ui.state.Toggles
import com.naua_braidwork.app.ui.state.Tone

/**
 * Сеть и соединение по спецификации `Network.dc.html`.
 *
 * Режим подключения — общий с главным экраном, поэтому выбранный вариант
 * приходит снаружи и меняет там же: два экрана не должны показывать разное.
 */
@Composable
fun NetworkScreen(
    state: NetworkState,
    modeId: String,
    toggles: Toggles,
    onBack: () -> Unit,
    onRow: (SubRow) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onMode: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 12.dp),
    ) {
        SubHeader(
            title = "Сеть и соединение",
            subtitle = "Как устройства находят друг друга",
            onBack = onBack,
        )

        SubBody {
            StatusCard(
                icon = IcwWifi,
                title = state.lanStatus,
                subtitle = state.lanSub,
                tone = Tone.Blue,
                tileSize = 44.dp,
                titleSize = 16,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            )

            state.groups.forEach { group ->
                SubGroupTitle(
                    title = group.title,
                    modifier = Modifier.padding(start = 24.dp, top = 20.dp),
                )
                SubGroupCard(
                    rows = group.rows.withToggleValues(toggles).map { row ->
                        if (row.end is RowEnd.Radio) {
                            row.copy(end = RowEnd.Radio(checked = row.id == modeId))
                        } else {
                            row
                        }
                    },
                    onRow = { row ->
                        val end = row.end
                        when {
                            end is RowEnd.Toggle && end.enabled -> onToggle(row.id, !end.checked)
                            end is RowEnd.Radio -> onMode(row.id)
                            else -> onRow(row)
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}
