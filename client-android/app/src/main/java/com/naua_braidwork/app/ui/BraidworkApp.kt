package com.naua_braidwork.app.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.naua_braidwork.app.ui.icons.IcwDevices
import com.naua_braidwork.app.ui.icons.IcwFamily
import com.naua_braidwork.app.ui.icons.IcwHome
import com.naua_braidwork.app.ui.icons.IcwSettings
import com.naua_braidwork.app.ui.icons.IcwSync
import com.naua_braidwork.app.DeviceDiscovery
import com.naua_braidwork.app.LinkService
import androidx.compose.ui.platform.LocalContext
import com.naua_braidwork.app.SettingsStore
import com.naua_braidwork.app.TrustStore
import com.naua_braidwork.app.идентификатор
import com.naua_braidwork.app.ui.components.tapNoRipple
import com.naua_braidwork.app.ui.screens.DevicesScreen
import com.naua_braidwork.app.ui.screens.FamilyScreen
import com.naua_braidwork.app.ui.screens.MainScreen
import com.naua_braidwork.app.ui.screens.SettingsScreen
import com.naua_braidwork.app.ui.screens.ShareScreen
import com.naua_braidwork.app.ui.screens.WelcomeScreen
import com.naua_braidwork.app.ui.state.DeviceFilter
import com.naua_braidwork.app.ui.state.DeviceKind
import com.naua_braidwork.app.ui.state.DeviceSummary
import com.naua_braidwork.app.ui.state.DevicesState
import com.naua_braidwork.app.ui.state.LinkMode
import com.naua_braidwork.app.ui.state.Presence
import com.naua_braidwork.app.ui.state.Sample
import com.naua_braidwork.app.ui.theme.LocalAppGradient

/**
 * Как часто экран забирает снимок из канала.
 *
 * Канал обновляется сам каждые полсекунды, но перерисовывать экран с той же
 * частотой незачем: список меняется раз в две секунды, когда приходит очередное
 * объявление. Раз в секунду — быстрее, чем глаз заметит задержку, и в четыре
 * раза реже перерисовок.
 */
private const val ОПРОС_СНИМКА_МС = 1000L

/**
 * Описание найденного устройства для интерфейса.
 *
 * Одна функция на плитку и на карточку. Пока их было две, карточка брала
 * снимок на момент нажатия и показывала состояние, которое успело устареть:
 * список пересобирается каждые две секунды, а копия — нет.
 */
private fun описание(найденное: DeviceDiscovery.Device) = DeviceSummary(
    id = найденное.deviceId.идентификатор(),
    // Имя приходит из объявления напарника. Оно ещё не проверено подписью — та
    // сверяется на соединении, — но «Устройство» в плитке хуже: в общей
    // квартире чужой телефон рядом со своим, и отличить их можно только по
    // названию.
    name = найденное.name,
    // Состояние видно сразу: устройство найдено, но канал с ним ещё не
    // установлен. Раньше здесь стояло «Офлайн», и плитка, которая прямо сейчас
    // объявляет себя, читалась как молчащая.
    subtitle = if (найденное.connected) "На связи" else "Рядом, не подключено",
    kind = DeviceKind.Desktop,
    presence = if (найденное.connected) Presence.Online else Presence.Nearby,
    // Заряд придёт с первым кадром после соединения. До этого «нет данных»
    // честнее выдуманного процента.
    batteryPercent = null,
)

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
    /**
     * Собственный отпечаток ключа.
     *
     * Показывается в настройках, чтобы человек сверил его с отпечатком этого
     * же устройства на экране сопряжения у соседа. Без него сверка невыполнима:
     * у соседа отпечаток есть, а у себя его негде посмотреть.
     */
    fingerprint: String = "",
    settings: SettingsStore,
) {
    // Имя устройства пока задано здесь, а не приходит из ядра: оно не входит в
    // подпись и может быть переименовано, поэтому это свойство профиля, а не
    // часть идентичности. После появления хранилища профиля будет свой экран.
    val deviceName = "Телефон"
    val state = remember(deviceId) { Sample.mainState(deviceId, deviceName) }

    // Приветствие — разовый экран, а не раздел: пока флаг не выставлен,
    // нижнего меню нет вообще. Это отличает его от обычного экрана приложения.
    var welcomeDone by remember { mutableStateOf(settings.welcomeSeen) }
    var tab by remember { mutableStateOf(Tab.Home) }

    // Канал поиска. Значение **перечитывается**, а не запоминается один раз.
    //
    // Сервис создаётся системой позже, чем Activity, поэтому при первой
    // композиции `LinkService.поиск` ещё `null`. С `remember` это значение
    // застывало бы навсегда, и весь поиск молча перестал бы работать: список
    // пустой, нажатие ничего не делает, ошибок нет.
    val поиск = remember { mutableStateOf(LinkService.поиск) }

    // Снимок того, что канал видит. Обновляется по таймеру: подписка на поток
    // означала бы перерисовку экрана из фонового потока, а Compose этого не
    // любит.
    val снимок = remember { mutableStateOf(DeviceDiscovery.Snapshot.EMPTY) }

    // Фильтр живёт здесь, а не в состоянии экрана: тот пересоздаётся на каждой
    // перерисовке, и нажатия бы терялись.
    var filter by remember { mutableStateOf(DeviceFilter.All) }

    LaunchedEffect(Unit) {
        // Сервис поднимает канал, а не этот экран: он обязан пережить
        // сворачивание. Пока сервис не начал, список честно пуст — и список
        // берётся заново, как только канал появится.
        while (isActive) {
            val свежий = LinkService.поиск
            if (поиск.value !== свежий) поиск.value = свежий
            снимок.value = свежий?.snapshot() ?: DeviceDiscovery.Snapshot.EMPTY
            delay(ОПРОС_СНИМКА_МС)
        }
    }

    // Устройство, открытое на карточке.
    //
    // Хранится **только идентификатор**, а не сама карточка устройства.
    //
    // Раньше здесь лежал снимок на момент нажатия, и его состояние уже не
    // менялось: список пересобирается каждые две секунды, а копия — нет.
    // Карточка поэтому вечно показывала «канал не установлен», даже когда
    // соединение было установлено и рукопожатие прошло. Пользователь видел
    // прямое противоречие: в журнале «рукопожатие прошло», а в шапке карточки
    // «канал не установлен».
    var выбранныйId by remember { mutableStateOf<String?>(null) }

    // Карточка собирается из текущего снимка — того же, что и плитка. Одна
    // функция на оба: своя копия разъехалась бы при первом же изменении, и
    // противоречие вернулось бы в другой форме.
    val выбранное: DeviceSummary? = снимок.value.devices
        .firstOrNull { it.deviceId.идентификатор() == выбранныйId }
        ?.let(::описание)

    val devicesState = remember(снимок.value, filter) {
        DevicesState(
            devices = снимок.value.devices.map(::описание),
            filter = filter,
        )
    }

    // Режим связи общий для главного экрана и «Сети»: держать его в обоих
    // местах значило бы показывать пользователю два разных ответа на один
    // и тот же вопрос.
    var mode by remember { mutableStateOf(state.mode) }

    // Каналы «рядом» включаются независимо от режима связи, поэтому состояние
    // у них своё. Держать его в описании экрана нельзя: описание пересоздаётся
    // на каждой перерисовке, и нажатия бы терялись.
    var bleOn by remember { mutableStateOf(state.bleOn) }
    var softApOn by remember { mutableStateOf(state.softApOn) }

    // Стек открытых подэкранов. Пустой — мы в разделе; непустой — поверх него
    // открыт подэкран, и последний элемент тот, что на экране.
    // Решения о доверии живут в хранилище, а не в состоянии: сопряжение
    // переживает перезапуск процесса, и иначе каждое устройство снова было бы
    // «не сверено» после убийства приложения.
    // Контекст читается ДО remember, а не внутри. LocalContext.current —
    // обращение к композабельному состоянию, и внутри лямбды remember оно
    // запрещено. Ошибка звучит как «вызов @Composable вне @Composable-функции»
    // и указывает совсем не туда, где ошибка на самом деле.
    val контекст = LocalContext.current
    val trust = remember { TrustStore.get(контекст) }

    val stack = remember { mutableStateListOf<String>() }
    val route = stack.lastOrNull()
    val openSystemSettings = rememberSystemSettingsOpener()
    val openListenerSettings = rememberListenerSettingsOpener()

    // Открытие подэкрана идёт через одну функцию, а не через `stack.add` в
    // каждом обработчике.
    //
    // Причина — не удобство. Повторное открытие того, что уже сверху, должно
    // игнорироваться, иначе стек копит одинаковые записи. На карточке
    // устройства кнопки «ещё» и плитки возможностей были подключены к
    // `Route.DEVICE`, то есть к экрану, который уже открыт: нажатие
    // добавляло в стек копию того же самого. «Назад» снимал копию за копией,
    // и каждый раз показывался тот же экран — выглядело это как «назад не
    // работает», а на самом деле работало четыре раза подряд. Пятое нажатие
    // выходило из приложения, потому что стек наконец опустел.
    //
    // Проверка на вершине стека, а не на всём стеке: вернуться на тот же
    // экран через промежуточный — обычное дело и должно работать.
    val открыть = remember {
        { id: String ->
            if (id in OPEN_ROUTES && id != stack.lastOrNull()) stack.add(id)
        }
    }

    // Закрытие верхнего подэкрана — с проверкой на пустоту. `removeAt` с
    // индексом −1 бросает исключение, а вызвать его на пустом стеке легко:
    // стрелка «назад» остаётся на экране кадры ещё секунду после нажатия.
    val закрыть = remember {
        {
            if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        }
    }

    // Системная кнопка «назад» закрывает подэкран, а не выходит из приложения.
    BackHandler(enabled = stack.isNotEmpty(), onBack = закрыть)

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
                открыть(Route.PAIR)
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
                    onThemeChoice = { choice -> settings.chooseTheme(choice) },
                    onBack = закрыть,
                    // Строки внутри подэкрана открывают свои подэкраны, как и
                    // раньше. Раньше здесь стояла пустая лямбда, и нажатие на
                    // любую строку не делало ничего.
                    onRow = { id -> открыть(id) },
                    onRoute = { id -> открыть(id) },
                    onOpenSystemSettings = openSystemSettings,
                    onOpenListenerSettings = openListenerSettings,
                    selectedDevice = выбранное,
                    trust = trust,
                    fingerprint = fingerprint,
                    devices = снимок.value.devices,
                    trail = поиск.value?.trail() ?: emptyList(),
                )
            } else {
                when (tab) {
                    Tab.Home -> MainScreen(
                        state = state,
                        mode = mode,
                        onMode = { mode = it },
                        bleOn = bleOn,
                        softApOn = softApOn,
                        onPairClick = { открыть(Route.PAIR) },
                        onNotificationsClick = { открыть(Route.NOTIFS) },
                        // Быстрое действие открывает свой модуль: подпись под
                        // иконкой должна совпадать с тем, куда попал. Раньше все
                        // четыре вели в «Обмен», и это было хуже — четыре
                        // одинаковые кнопки не отличаются от одной.
                        //
                        // Это компромисс, а не решение: настоящее быстрое
                        // действие отправляет буфер или запускает трансляцию.
                        // Пока транспорта нет, показывать вместо действия пустую
                        // ссылку хуже, чем вести в модуль. Менять здесь — по
                        // одной строке на кнопку, когда появится первая
                        // работающая передача.
                        onQuickAction = { action -> открыть(quickRoute(action.id)) },
                        onBleClick = { bleOn = it },
                        onSoftApClick = { softApOn = it },
                    )

                    Tab.Devices -> DevicesScreen(
                        state = devicesState,
                        problem = снимок.value.problem,
                        onFilter = { filter = it },
                        onAdd = { открыть(Route.PAIR) },
                        // Нажатие открывает карточку и начинает подключение.
                        // Раньше здесь стояло `tab = Tab.Devices`, то есть
                        // нажатие на плитку ничего не делало: пользователь
                        // жал, экран не менялся, и это выглядело как поломка.
                        //
                        // Подключение идёт по адресу из объявления: порт у всех
                        // устройств один, и без адреса подключаться некуда.
                        onDevice = { device ->
                            выбранныйId = device.id
                            открыть(Route.DEVICE)

                            // Подключение идёт к тому объекту, что пришёл из
                            // канала, а не к тому, что собран из строк для
                            // показа: адрес напарника есть только в первом, а
                            // выковывать его из подписи значило бы разбирать
                            // обратно то, что ядро уже разобрало.
                            //
                            // Параметр лямбды назван `канал`, а не `поиск`: иначе он
                            // перекрыл бы внешнюю `поиск` (это `MutableState`), и
                            // внутри получилось бы `поиск.value.connect` вместо
                            // `поиск.connect` — с обращением к свойству, которого
                            // у канала нет. Такая ошибка не ловится чтением: оба
                            // имени выглядят правильно.
                            поиск.value?.let { канал ->
                                снимок.value.devices
                                    .firstOrNull { it.deviceId.идентификатор() == device.id }
                                    ?.let { канал.connect(it) }
                            }
                        },
                    )

                    Tab.Share -> ShareScreen(
                        // Идентификаторы ячеек совпадают с маршрутами подэкранов.
                        onQuickAction = { id -> открыть(quickRoute(id)) },
                        onAddDevice = { открыть(Route.PAIR) },
                    )

                    Tab.Family -> FamilyScreen(
                        members = Sample.familyState(),
                        onInvite = { открыть(Route.PAIR) },
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
                        onOpen = { id -> открыть(id) },
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
    // Сопряжение ведёт на настоящий экран: без него строка «Доверие» на
    // карточке была бы шевроном без цели.
    Route.TRUST,
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
