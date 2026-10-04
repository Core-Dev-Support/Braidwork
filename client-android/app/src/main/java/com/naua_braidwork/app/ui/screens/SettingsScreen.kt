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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_braidwork.app.ui.components.IconTile
import com.naua_braidwork.app.ui.icons.IcwChevron
import com.naua_braidwork.app.ui.state.SettingsGroup
import com.naua_braidwork.app.ui.state.SettingsRow

/**
 * Настройки по спецификации `Settings.dc.html`.
 *
 * Значения справа показывают фактическое состояние, а не из макета: например
 * «Фоновая работа — Не проверена», а не «Активна». На экране, который
 * показывают тестерам, правдоподобные значения опаснее отсутствующих.
 */
@Composable
fun SettingsScreen(
    groups: List<SettingsGroup>,
    deviceName: String,
    role: String,
    deviceIdShort: String,
    onOpen: (String) -> Unit,
    onOpenProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Настройки",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 24.dp),
        )

        Spacer(Modifier.height(18.dp))

        // Карточка профиля: кто вы и каким устройством зашли в семью.
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenProfile)
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(Color(0xFFDCEBFF), RoundedCornerShape(32.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = deviceName.take(1).uppercase(),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1A5CB8),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = deviceName,
                        fontSize = 20.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = role,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "ID устройства: $deviceIdShort",
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = IcwChevron,
                    contentDescription = null,
                    tint = Color(0xFF8A8D97),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        groups.forEach { group ->
            if (group.title.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = group.title,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, bottom = 8.dp),
                )
            } else {
                Spacer(Modifier.height(20.dp))
            }
            RowGroup(group, onOpen)
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** Группа строк в одной карточке. Разделитель есть начиная со второй строки. */
@Composable
private fun RowGroup(group: SettingsGroup, onOpen: (String) -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            group.rows.forEachIndexed { index, row ->
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }
                SettingsRowView(row, onOpen)
            }
        }
    }
}

@Composable
private fun SettingsRowView(row: SettingsRow, onOpen: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clickable { onOpen(row.id) }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(
            icon = row.icon,
            tone = row.tone,
            size = 40.dp,
            radius = 12.dp,
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = row.title,
            fontSize = 16.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (row.value.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = row.value,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = IcwChevron,
            contentDescription = null,
            tint = Color(0xFF8A8D97),
            modifier = Modifier.size(18.dp),
        )
    }
}