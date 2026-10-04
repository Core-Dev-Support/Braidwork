package com.naua_braidwork.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_braidwork.app.ui.icons.IcwDevices
import com.naua_braidwork.app.ui.icons.IcwLaptop
import com.naua_braidwork.app.ui.icons.IcwMore
import com.naua_braidwork.app.ui.icons.IcwPhone
import com.naua_braidwork.app.ui.icons.IcwPlus
import com.naua_braidwork.app.ui.icons.IcwTablet
import com.naua_braidwork.app.ui.state.DeviceFilter
import com.naua_braidwork.app.ui.state.DeviceKind
import com.naua_braidwork.app.ui.state.DeviceSummary
import com.naua_braidwork.app.ui.state.DevicesState
import com.naua_braidwork.app.ui.theme.LocalAppGradient

/**
 * Устройства по спецификации `design/spec/SPEC.txt`.
 *
 * Экран тёмный в обеих темах: так задано в макете градиентом. Светлая тема
 * его не отменяет — иначе плитки на светлом фоне выглядят выключенными.
 */
@Composable
fun DevicesScreen(
    state: DevicesState,
    onFilter: (DeviceFilter) -> Unit,
    onAdd: () -> Unit,
    onDevice: (DeviceSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = isSystemInDarkTheme()
    // Тёмный фон у «Устройств» особенный: три остановки вместо двух, средняя
    // на 42 % высоты. В светлой теме он совпадает с общим фоном приложения.
    val background = if (isDark) {
        Brush.verticalGradient(
            0f to Color(0xFF3B4252),
            0.42f to Color(0xFF2B2624),
            1f to Color(0xFF1B1716),
        )
    } else {
        LocalAppGradient.current
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(background),
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Устройства семьи",
                        style = MaterialTheme.typography.displaySmall,
                        color = foregroundOn(isDark),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${state.devices.size} в сети",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isDark) Color(0xFFC2C5CE) else Color(0xFF666A75),
                    )
                }
                HeaderIcon(IcwPlus, "Добавить устройство", onAdd, isDark)
                Spacer(Modifier.width(2.dp))
                HeaderIcon(IcwMore, "Ещё", {}, isDark, strokeNote = "толще в макете")
            }

            Spacer(Modifier.height(18.dp))
            FilterRow(current = state.filter, onFilter = onFilter, isDark = isDark)
            Spacer(Modifier.height(18.dp))
        }

        if (state.devices.isEmpty()) {
            // Плитки не рисуются, пока нет ни одного устройства: сетка из
            // пустых карточек выглядит поломкой, а не как начало работы.
            EmptyDevicesHint(onAdd = onAdd, modifier = Modifier.padding(horizontal = 24.dp))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.devices, key = { it.id }) { device ->
                    DeviceCard(device = device, onClick = { onDevice(device) })
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        DashedAddButton(onAdd = onAdd, isDark = isDark, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(12.dp))
    }
}

private fun foregroundOn(isDark: Boolean): Color =
    if (isDark) Color(0xFFFFFFFF) else Color(0xFF121316)

@Composable
private fun HeaderIcon(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    isDark: Boolean,
    strokeNote: String = "",
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = foregroundOn(isDark),
            modifier = Modifier.size(26.dp),
        )
    }
}

/** Фильтры работают как группа: выбран один. */
@Composable
private fun FilterRow(current: DeviceFilter, onFilter: (DeviceFilter) -> Unit, isDark: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DeviceFilter.entries.forEach { filter ->
            val selected = filter == current
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (selected) {
                    if (isDark) Color(0xFF3A4250) else Color(0xFF1A6FDB)
                } else if (isDark) {
                    Color(0xFF2C313B)
                } else {
                    Color(0xFFFFFFFF)
                },
                contentColor = if (selected) {
                    if (isDark) Color(0xFFFFFFFF) else Color(0xFFFFFFFF)
                } else if (isDark) {
                    Color(0xFFC2C5CE)
                } else {
                    Color(0xFF121316)
                },
            ) {
                Text(
                    text = filter.label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable { onFilter(filter) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** Плитка устройства: имя, состояние, крупная иконка. */
@Composable
private fun DeviceCard(device: DeviceSummary, onClick: () -> Unit) {
    val isDark = isSystemInDarkTheme()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp),
        shape = RoundedCornerShape(24.dp),
        color = if (isDark) Color(0xFF242830) else Color(0xFFFFFFFF),
        contentColor = foregroundOn(isDark),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onClick)
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = device.name,
                    fontSize = 16.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                color = if (device.online) {
                                    if (isDark) Color(0xFF3DD68C) else Color(0xFF16804A)
                                } else {
                                    Color(0xFF6C707B)
                                },
                                shape = RoundedCornerShape(4.dp),
                            ),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = statusLabel(device),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = if (isDark) Color(0xFFC2C5CE) else Color(0xFF666A75),
                    )
                }
            }
            Icon(
                imageVector = iconFor(device.kind),
                contentDescription = null,
                tint = if (device.online) foregroundOn(isDark) else Color(0xFF6C707B),
                modifier = Modifier
                    .size(44.dp)
                    .alpha(if (device.online) 1f else 0.55f),
            )
        }
    }
}

private fun statusLabel(device: DeviceSummary): String = when {
    !device.online -> "Офлайн"
    device.batteryPercent != null -> "На связи · ${device.batteryPercent}%"
    else -> "На связи"
}

private fun iconFor(kind: DeviceKind): ImageVector = when (kind) {
    DeviceKind.Phone -> IcwPhone
    DeviceKind.Tablet -> IcwTablet
    DeviceKind.Laptop, DeviceKind.Desktop -> IcwLaptop
}

/** Пока нет устройств: объяснение и кнопка, а не пустая сетка. */
@Composable
private fun EmptyDevicesHint(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val isDark = isSystemInDarkTheme()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = if (isDark) Color(0xFF242830) else Color(0xFFFFFFFF),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Icon(
                imageVector = IcwDevices,
                contentDescription = null,
                tint = if (isDark) Color(0xFFC2C5CE) else Color(0xFF666A75),
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Устройств пока нет",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = foregroundOn(isDark),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Связь между устройствами ещё не включена. Ядро готово: " +
                    "ключи созданы, шифрование работает. Как только появится " +
                    "транспорт, устройства найдут друг друга сами.",
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = if (isDark) Color(0xFFC2C5CE) else Color(0xFF666A75),
            )
        }
    }
}

/** Кнопка добавления с пунктирной рамкой — по макету. */
@Composable
private fun DashedAddButton(
    onAdd: () -> Unit,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent,
        border = BorderStroke(
            1.5.dp,
            if (isDark) Color(0xFF6C707B) else Color(0xFFC8CCD6),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onAdd),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = IcwPlus,
                contentDescription = null,
                tint = if (isDark) Color(0xFFE4E6EB) else Color(0xFF121316),
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Добавить устройство",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = if (isDark) Color(0xFFE4E6EB) else Color(0xFF121316),
            )
        }
    }
}