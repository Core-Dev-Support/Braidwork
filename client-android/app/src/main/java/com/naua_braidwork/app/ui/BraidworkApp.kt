package com.naua_braidwork.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.naua_braidwork.app.ui.components.EmptyHint
import com.naua_braidwork.app.ui.icons.IcwDevices
import com.naua_braidwork.app.ui.icons.IcwFamily
import com.naua_braidwork.app.ui.icons.IcwHome
import com.naua_braidwork.app.ui.icons.IcwSettings
import com.naua_braidwork.app.ui.screens.MainScreen
import com.naua_braidwork.app.ui.state.Sample

/**
 * Пять разделов нижнего меню из спецификации.
 *
 * Перечисление, а не набор строк в разметке: раздел — это ещё и маршрут,
 * и подпись, и иконка. Списком таких строк они разъезжаются уже на третьем.
 */
enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Главная", IcwHome),
    Devices("devices", "Устройства", IcwDevices),
    Share("share", "Обмен", IcwFamily),
    Family("family", "Семья", IcwFamily),
    Settings("settings", "Настройки", IcwSettings),
}

/**
 * Каркас приложения: нижнее меню и содержимое раздела.
 *
 * Экраны получают данные, а не ходят за ними: пока источник — [Sample],
 * позже транспорт. Меняется только он, разметка остаётся.
 */
@Composable
fun BraidworkApp(deviceId: String, deviceName: String) {
    var tab by remember { mutableStateOf(Tab.Home) }

    val state = remember(deviceId) { Sample.mainState(deviceId, deviceName) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            when (tab) {
                Tab.Home -> MainScreen(
                    state = state,
                    onPairClick = { tab = Tab.Devices },
                    onNotificationsClick = { tab = Tab.Share },
                    onQuickAction = { tab = Tab.Home },
                    onChannelClick = { tab = Tab.Settings },
                    onBleClick = { tab = Tab.Settings },
                    onSoftApClick = { tab = Tab.Settings },
                )

                else -> PlaceholderScreen(tab)
            }
        }

        BottomBar(current = tab, onSelect = { tab = it })
    }
}

/**
 * Заглушка раздела.
 *
 * Показывает, что раздел существует и что внутри ещё ничего не работает.
 * Это лучше пустого белого экрана: пользователь видит, что это отложенная
 * работа, а не сбой.
 */
@Composable
private fun PlaceholderScreen(tab: Tab) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = tab.label,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                EmptyHint("Экран по спецификации готов, наполнение ждёт функции")
                Text(
                    text = "Раздел «${tab.label}» будет сделан сразу после того, " +
                        "как появится то, что он показывает. Пустой экран или " +
                        "выдуманные данные были бы хуже: ими пришлось бы врать.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, start = 12.dp),
                )
            }
        }
    }
}

/**
 * Нижнее меню: высота 84, иконки 26, подписи 11.
 */
@Composable
private fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(84.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
                    .align(Alignment.TopCenter),
            )
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tab.entries.forEach { item ->
                    val selected = item == current
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSelect(item) }
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(26.dp),
                        )
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}