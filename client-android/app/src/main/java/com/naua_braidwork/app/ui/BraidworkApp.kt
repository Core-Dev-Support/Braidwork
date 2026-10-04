package com.naua_braidwork.app.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.naua_braidwork.app.ui.icons.IcwDevices
import com.naua_braidwork.app.ui.icons.IcwFamily
import com.naua_braidwork.app.ui.icons.IcwHome
import com.naua_braidwork.app.ui.icons.IcwSettings
import com.naua_braidwork.app.ui.icons.IcwSync
import com.naua_braidwork.app.MainActivity
import com.naua_braidwork.app.SettingsStore
import com.naua_braidwork.app.ui.components.RestartDialog
import com.naua_braidwork.app.ui.components.tapNoRipple
import com.naua_braidwork.app.ui.screens.DevicesScreen
import com.naua_braidwork.app.ui.screens.FamilyScreen
import com.naua_braidwork.app.ui.screens.MainScreen
import com.naua_braidwork.app.ui.screens.SettingsScreen
import com.naua_braidwork.app.ui.screens.ShareScreen
import com.naua_braidwork.app.ui.screens.WelcomeScreen
import com.naua_braidwork.app.ui.state.LinkMode
import com.naua_braidwork.app.ui.state.Sample
import com.naua_braidwork.app.ui.theme.LocalAppGradient
import com.naua_braidwork.app.ui.theme.ThemeChoice

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
fun BraidworkApp(
    deviceId: String,
    deviceName: String,
    settings: SettingsStore,
) {
    val state = remember(deviceId) { Sample.mainState(deviceId, deviceName) }

    // Приветствие — разовый экран, а не раздел: пока флаг не выставлен,
    // нижнего меню нет вообще. Это отличает его от обычного экрана приложения.
    var welcomeDone by remember { mutableStateOf(settings.welcomeSeen) }
    var tab by remember { mutableStateOf(Tab.Home) }

    // Режим связи общий для главного экрана и «Сети»: держать его в обоих
    // местах значило бы показывать пользователю два разных ответа на один
    // и тот же вопрос.
    var mode by remember { mutableStateOf(state.mode) }

    // Каналы «рядом» включаются независимо от режима связи, поэтому состояние
    // у них своё. Держать его в описании экрана нельзя: описание пересоздаётся
    // на каждой перерисовке, и нажатия бы терялись.
    var bleOn by remember { mutableStateOf(state.bleOn) }
    var softApOn by remember { mutableStateOf(state.softApOn) }

    // Окошко перезапуска после смены темы. Показывается один раз на выбор,
    // а не висит: решение пользователя — перезапустить сейчас или позже.
    var restartAsk by remember { mutableStateOf<ThemeChoice?>(null) }
    val context = LocalContext.current
    val restart = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { restartAsk = null }

    if (restartAsk != null) {
        RestartDialog(
            onRestart = {
                restartAsk = null
                restart.launch(
                    Intent(context, MainActivity::class.java).apply {
                        // Пересоздание Activity, а не запуск второй копии:
                        // иначе в стеке останется старая, со старой темой.
                        addFlags(
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                        )
                    },
                )
            },
            onLater = { restartAsk = null },
        )
    }

    // Стек открытых подэкранов. Пустой — мы в разделе; непустой — поверх него
    // открыт подэкран, и последний элемент тот, что на экране.
    val stack = remember { mutableStateListOf<String>() }
    val route = stack.lastOrNull()
    val openSystemSettings = rememberSystemSettingsOpener()
    val openListenerSettings = rememberListenerSettingsOpener()

    // Системная кнопка «назад» закрывает подэкран, а не выходит из приложения.
    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }

    if (!welcomeDone) {
        WelcomeScreen(
            onCreateFamily = {
                settings.markWelcomeSeen()
                welcomeDone = true
            },
            onJoinByQr = {
                settings.markWelcomeSeen()
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
    //
    // Градиент один на всё приложение, а не на каждый экран: в макете он
    // одинаковый везде, кроме «Обмена» (чёрный) и «Устройств» (свой тёмный).
    // Эти два экрана кладут свой фон поверх и перекрывают его.
    //
    // Важно: фон задаётся именно здесь, снаружи `windowInsetsPadding`. Если
    // окрасить только содержимое, под статус-баром останется градиент, а на
    // чёрном «Обмене» это читается как чужое окно поверх экрана.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                // `background` перегружен: отдельно для цвета и для кисти.
                // Поэтому чёрный задаётся кистью — иначе ветвление не
                // разрешится ни в одну из перегрузок.
                if (route == null && tab == Tab.Share) {
                    SolidColor(Color.Black)
                } else {
                    LocalAppGradient.current
                },
            )
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            if (route != null) {
                SubScreen(
                    route = route,
                    deviceId = deviceId,
                    mode = mode,
                    onMode = { mode = it },
                    themeChoice = settings.theme,
                    onThemeChoice = { choice ->
                        settings.chooseTheme(choice)
                        restartAsk = choice
                    },
                    onBack = { stack.removeAt(stack.lastIndex) },
                    onRow = { },
                    onRoute = { id -> if (id in OPEN_ROUTES) stack.add(id) },
                    onOpenSystemSettings = openSystemSettings,
                    onOpenListenerSettings = openListenerSettings,
                )
            } else {
                when (tab) {
                    Tab.Home -> MainScreen(
                        state = state,
                        mode = mode,
                        onMode = { mode = it },
                        bleOn = bleOn,
                        softApOn = softApOn,
                        onPairClick = { stack.add(Route.PAIR) },
                        onNotificationsClick = { stack.add(Route.NOTIFS) },
                        // Быстрое действие не должно открывать настройки: нажал
                        // «Буфер» — получил обмен, а не его настройки. Сводка по
                        // всем модулям живёт в «Обмене», оттуда и доходит до
                        // каждого. Когда появится транспорт, эти кнопки станут
                        // самими действиями.
                        onQuickAction = { tab = Tab.Share },
                        onBleClick = { bleOn = it },
                        onSoftApClick = { softApOn = it },
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
                            .tapNoRipple { onSelect(item) }
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