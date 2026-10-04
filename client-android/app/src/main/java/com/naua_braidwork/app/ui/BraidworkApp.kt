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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.naua_braidwork.app.ui.icons.IcwDevices
import com.naua_braidwork.app.ui.icons.IcwFamily
import com.naua_braidwork.app.ui.icons.IcwHome
import com.naua_braidwork.app.ui.icons.IcwSettings
import com.naua_braidwork.app.ui.icons.IcwSync
import com.naua_braidwork.app.ui.screens.DevicesScreen
import com.naua_braidwork.app.ui.screens.FamilyScreen
import com.naua_braidwork.app.ui.screens.MainScreen
import com.naua_braidwork.app.ui.screens.SettingsScreen
import com.naua_braidwork.app.ui.screens.ShareScreen
import com.naua_braidwork.app.ui.screens.WelcomeScreen
import com.naua_braidwork.app.ui.state.LinkMode
import com.naua_braidwork.app.ui.state.Sample

/**
 * Разделы приложения.
 *
 * Приветствия здесь нет намеренно: это разовый экран, а не раздел. Иначе оно
 * занимало бы место в нижнем меню и открывалось по тапу, что противоречит
 * смыслу: показать его нужно один раз, при первом запуске.
 *
 * Перечисление, а не набор строк в разметке: раздел — это ещё и маршрут,
 * и подпись, и иконка. Списком таких строк они разъезжаются уже на третьем.
 */
enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Главная", IcwHome),
    Devices("devices", "Устройства", IcwDevices),
    Share("share", "Обмен", IcwSync),
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
    val state = remember(deviceId) { Sample.mainState(deviceId, deviceName) }

    // Приветствие — разовый экран, а не раздел: пока флаг не выставлен,
    // нижнего меню нет вообще. Это отличает его от обычного экрана приложения.
    var welcomeDone by remember { mutableStateOf(Sample.seenWelcome()) }
    var tab by remember { mutableStateOf(Tab.Home) }

    // Режим связи общий для главного экрана и «Сети»: держать его в обоих
    // местах значило бы показывать пользователю два разных ответа на один
    // и тот же вопрос.
    var mode by remember { mutableStateOf(state.mode) }

    // Стек открытых подэкранов. Пустой — мы в разделе; непустой — поверх него
    // открыт подэкран, и последний элемент тот, что на экране.
    val stack = remember { mutableStateListOf<String>() }
    val route = stack.lastOrNull()
    val openSystemSettings = rememberSystemSettingsOpener()

    // Системная кнопка «назад» закрывает подэкран, а не выходит из приложения.
    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }

    if (!welcomeDone) {
        WelcomeScreen(
            onCreateFamily = {
                Sample.markWelcomeSeen()
                welcomeDone = true
            },
            onJoinByQr = {
                Sample.markWelcomeSeen()
                welcomeDone = true
                tab = Tab.Devices
                stack.add(Route.PAIR)
            },
        )
        return
    }

    // Полноэкранный режим: фон рисуется под статус-бар и под системную
    // навигацию, а отступы берутся из окна. Иначе сверху остаётся полоса
    // чужого цвета и экран выглядит обрезанным.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            if (route != null) {
                SubScreen(
                    route = route,
                    deviceId = deviceId,
                    mode = mode,
                    onMode = { mode = it },
                    onBack = { stack.removeAt(stack.lastIndex) },
                    onRow = { },
                    onRoute = { id -> if (id in OPEN_ROUTES) stack.add(id) },
                    onOpenSystemSettings = openSystemSettings,
                )
            } else {
                when (tab) {
                    Tab.Home -> MainScreen(
                        state = state,
                        mode = mode,
                        onMode = { mode = it },
                        onPairClick = { stack.add(Route.PAIR) },
                        onNotificationsClick = { tab = Tab.Share },
                        // Быстрые действия ведут в свои подэкраны обмена:
                        // так они задуманы, и кнопка не ведёт в пустоту.
                        onQuickAction = { action -> stack.add(quickRoute(action.id)) },
                        onChannelClick = { stack.add(Route.NETWORK) },
                        onBleClick = { stack.add(Route.NETWORK) },
                        onSoftApClick = { stack.add(Route.NETWORK) },
                    )

                    Tab.Devices -> DevicesScreen(
                        state = Sample.devicesState(),
                        onFilter = { tab = Tab.Devices },
                        onAdd = { stack.add(Route.PAIR) },
                        onDevice = { tab = Tab.Devices },
                    )

                    Tab.Share -> ShareScreen(
                        // Идентификаторы ячеек совпадают с маршрутами подэкранов.
                        onQuickAction = { id -> stack.add(quickRoute(id)) },
                        onAddDevice = { stack.add(Route.PAIR) },
                    )

                    Tab.Family -> FamilyScreen(
                        members = Sample.familyState(),
                        onInvite = { stack.add(Route.PAIR) },
                    )

                    Tab.Settings -> SettingsScreen(
                        groups = Sample.settingsGroups(),
                        deviceName = deviceName,
                        role = "Владелец устройства",
                        // Показываем начало и конец, а не полный идентификатор:
                        // на экране его достаточно для сверки, целиком он не нужен.
                        deviceIdShort = shortenId(deviceId),
                        // Открываем только те подэкраны, которые уже собраны.
                        // Остальные строки пока не ведут никуда: лучше ничего,
                        // чем экран-заглушка на месте настоящего.
                        onOpen = { id -> if (id in OPEN_ROUTES) stack.add(id) },
                        onOpenProfile = { tab = Tab.Family },
                    )
                }
            }
        }

        if (route == null) {
            BottomBar(current = tab, onSelect = { tab = it })
        }
    }
}

/**
 * Куда ведёт быстрое действие с главного экрана.
 *
 * Идентификаторы действий совпадают с маршрутами: список один, иначе пришлось бы
 * держать вторую таблицу соответствий, которая разъедется с первой.
 */
private fun quickRoute(actionId: String): String =
    if (actionId in OPEN_ROUTES) actionId else Route.SECURITY

/** Подэкраны, которые уже готовы: настройки ведут только в эти строки. */
private val OPEN_ROUTES = setOf(
    Route.NETWORK,
    Route.SECURITY,
    Route.BACKGROUND,
    Route.CLIPBOARD,
    Route.FILES,
    Route.NOTIFS,
    Route.MIRROR,
    Route.APPEARANCE,
    Route.CORE,
    Route.ABOUT,
    Route.ROADMAP,
    Route.PAIR,
    Route.DEVICE,
)

/**
 * Короткая запись идентификатора: первые и последние четыре символа.
 *
 * На карточке профиля полный идентификатор не нужен: для сверки хватает краёв,
 * а строки длиннее экрана он всё равно не покажет целиком.
 */
private fun shortenId(id: String): String {
    val letters = id.filter { it.isLetterOrDigit() }
    if (letters.length <= 10) return id
    return "${letters.take(4)}…${letters.takeLast(4)}"
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