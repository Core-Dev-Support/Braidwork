package com.naua_braidwork.app.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.naua_braidwork.app.BuildConfig
import com.naua_braidwork.app.TrustStore
import com.naua_braidwork.app.идентификатор
import com.naua_braidwork.app.ui.components.BackButton
import com.naua_braidwork.app.ui.components.SubBody
import com.naua_braidwork.app.ui.components.SubGroupCard
import com.naua_braidwork.app.ui.components.SubGroupTitle
import com.naua_braidwork.app.ui.components.SubHeader
import com.naua_braidwork.app.ui.screens.AboutScreen
import com.naua_braidwork.app.ui.screens.AppearanceScreen
import com.naua_braidwork.app.ui.screens.BackgroundScreen
import com.naua_braidwork.app.ui.screens.ClipboardScreen
import com.naua_braidwork.app.ui.screens.CoreScreen
import com.naua_braidwork.app.ui.screens.DeviceDetailScreen
import com.naua_braidwork.app.ui.screens.FilesScreen
import com.naua_braidwork.app.ui.screens.MirrorScreen
import com.naua_braidwork.app.ui.screens.NetworkScreen
import com.naua_braidwork.app.ui.screens.NotifsScreen
import com.naua_braidwork.app.ui.screens.RoadmapScreen
import com.naua_braidwork.app.ui.screens.PairScreen
import com.naua_braidwork.app.ui.screens.SecurityScreen
import com.naua_braidwork.app.ui.screens.TrustScreen
import com.naua_braidwork.app.ui.state.DeviceSummary
import com.naua_braidwork.app.ui.state.LinkMode
import com.naua_braidwork.app.ui.state.RowEnd
import com.naua_braidwork.app.ui.state.Sample
import com.naua_braidwork.app.ui.state.SubGroup
import com.naua_braidwork.app.ui.state.SubRow
import com.naua_braidwork.app.ui.state.Toggles
import com.naua_braidwork.app.ui.state.withToggleValues
import com.naua_braidwork.app.ui.theme.ThemeChoice

/**
 * Маршруты подэкранов.
 *
 * Строки, а не перечисление с полями: у маршрута нет собственного заголовка —
 * заголовок берётся из описания экрана, и хранить его в двух местах значило бы
 * однажды забыть про это. Перечисление появится, когда появится что различать.
 */
object Route {
    const val PAIR = "pair"
    const val NETWORK = "network"
    const val SECURITY = "security"
    const val BACKGROUND = "background"
    const val CLIPBOARD = "clipboard"
    const val FILES = "files"
    const val NOTIFS = "notifs"
    const val MIRROR = "mirror"
    const val APPEARANCE = "appearance"
    const val CORE = "core"
    const val ABOUT = "about"
    const val ROADMAP = "roadmap"
    const val DEVICE = "device"
    const val TRUST = "trust"
}

/**
 * Системные настройки, куда приложение уводит пользователя.
 *
 * Отдельный список, потому что адреса у этих экранов разные и зависят от
 * версии Android: единого публичного адреса для всех из них нет.
 */
object SystemScreen {
    /** Доступ к уведомлениям. На Android 6+ открывается свой экран. */
    const val NOTIFICATION_LISTENER =
        "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"
}

/**
 * Подэкран поверх раздела.
 *
 * Нижнего меню здесь нет: открытый подэкран занимает весь экран, иначе меню
 * предлагало бы переключиться на другой раздел, потеряв открытый экран.
 */
@Composable
fun SubScreen(
    route: String,
    deviceId: String,
    mode: LinkMode,
    onMode: (LinkMode) -> Unit,
    themeChoice: ThemeChoice,
    onThemeChoice: (ThemeChoice) -> Unit,
    onBack: () -> Unit,
    onRow: (String) -> Unit,
    onRoute: (String) -> Unit,
    onOpenSystemSettings: () -> Unit,
    onOpenListenerSettings: () -> Unit,
    /**
     * Устройство, открытое на карточке.
     *
     * Передаётся данными, а не ищется в списке по индексу: список пересобирается
     * каждые две секунды, и устройство, выбранное полминуты назад, успело бы
     * поменяться местом. Карточка обязана показывать то, на что нажали.
     *
     * `null` — устройство не выбрано: список был пуст, или нажали на закрытый
     * подэкран. Тогда показывается честное сообщение, а не чужая карточка.
     */
    selectedDevice: DeviceSummary? = null,
    /**
     * Хранилище сопряжённых устройств.
     *
     * Передаётся, а не берётся из контекста: так решение человека о доверии
     * доходит до карточки, а состояние видно сразу — иначе строка «Доверие»
     * показывала бы старое значение до перерисовки и выглядела сломанной.
     */
    trust: TrustStore,
    /**
     * Собственный отпечаток ключа этого устройства.
     *
     * Показывается в «Безопасность». Экран сопряжения у соседа показывает
     * отпечаток **его** напарника, то есть нашего, — сверять надо именно эту
     * пару. Раньше настройки показывали укороченный идентификатор, а отпечатка
     * не было нигде, и сверку выполнить было нечем: инструкция звала сравнить
     * два экрана, которые совпасть не могут.
     */
    fingerprint: String = "",
    /** Открыть экран сопряжения выбранного устройства. */
    onTrust: () -> Unit = {},
    /**
     * Устройства, как их видит канал.
     *
     * Нужно карточке ради настоящего адреса и порта. Сам плитка не несёт их:
     * показывать «192.168.1.42:42424» в списке незачем, а разбирать это обратно
     * из подписи — значит завести второй источник правды о том же устройстве.
     */
    devices: List<com.naua_braidwork.app.DeviceDiscovery.Device> = emptyList(),

    /**
     * Канал, чтобы отправлять по нему прямо отсюда.
     *
     * Передаётся данными, а не берётся из `LinkService`: пока его нет, кнопка
     * отправки должна быть нажатием в пустоту без всякого смысла, а не искать
     * сервис по своему усмотрению.
     */
    discovery: com.naua_braidwork.app.DeviceDiscovery? = null,

    /**
     * Что сосед прислал последним сердцебиением, по идентификатору.
     *
     * Отдельными данными, а не свойством устройства: устройство обновляется
     * объявлением каждые две секунды, и заряд соседа в нём был бы не с того
     * момента. Пусто для устройства, которое ещё ничего не прислало.
     */
    pulses: Map<String, com.naua_braidwork.app.DeviceDiscovery.PeerPulse> = emptyMap(),

    /** Ход подключения для показа в карточке. Пустой список — событий не было. */
    trail: List<String> = emptyList(),
    modifier: Modifier = Modifier,
) {
    // Значения переключателей живут здесь, а не в описании экрана: описание
    // пересоздаётся на каждой перерисовке и нажатия бы потерялись.
    var securityToggles by remember { mutableStateOf(Toggles(emptyMap())) }
    var networkToggles by remember { mutableStateOf(Toggles(emptyMap())) }
    var backgroundToggles by remember { mutableStateOf(Toggles(emptyMap())) }
    var clipboardToggles by remember { mutableStateOf(Toggles(emptyMap())) }
    var filesToggles by remember { mutableStateOf(Toggles(emptyMap())) }
    var notifToggles by remember { mutableStateOf(Toggles(emptyMap())) }
    var mirrorToggles by remember { mutableStateOf(Toggles(emptyMap())) }
    var brand by remember { mutableStateOf("Xiaomi") }
    var pairMode by remember { mutableStateOf(0) }
    var mirrorQuality by remember { mutableStateOf("auto") }
    var mirrorLive by remember { mutableStateOf(false) }
    var accent by remember { mutableStateOf("blue") }
    var coreToggles by remember { mutableStateOf(Toggles(emptyMap())) }
    var testsRan by remember { mutableStateOf(false) }

    // Фона у подэкрана нет: его рисует каркас приложения. Иначе каждый экран
    // клал бы свой цвет поверх общего градиента и переход был бы виден только
    // на верхних 360 dp.
    Box(modifier = modifier.fillMaxSize()) {
        when (route) {
            Route.PAIR -> PairScreen(
                mode = pairMode,
                onMode = { pairMode = it },
                onBack = onBack,
            )

            Route.NETWORK -> NetworkScreen(
                state = Sample.networkState(mode, deviceId),
                modeId = mode.id,
                toggles = networkToggles,
                onBack = onBack,
                onRow = { onRow(route) },
                onToggle = { id, value -> networkToggles = networkToggles.with(id, value) },
                onMode = { onMode(LinkMode.from(it)) },
            )

            Route.SECURITY -> SecurityScreen(
                state = Sample.securityState(deviceId, fingerprint),
                toggles = securityToggles,
                onBack = onBack,
                onRow = { onRow(it.id) },
                onToggle = { id, value -> securityToggles = securityToggles.with(id, value) },
            )

            Route.BACKGROUND -> BackgroundScreen(
                state = Sample.backgroundState(brand),
                toggles = backgroundToggles,
                onBack = onBack,
                onRow = { onRow(it.id) },
                onToggle = { id, value -> backgroundToggles = backgroundToggles.with(id, value) },
                onBrand = { brand = it },
                onOpenSystemSettings = onOpenSystemSettings,
            )

            Route.CLIPBOARD -> ClipboardScreen(
                groups = Sample.clipboardGroups(
                    discovery?.полученноеСодержимое().orEmpty()
                ),
                toggles = clipboardToggles,
                onBack = onBack,
                onRow = { onRow(it.id) },
                onToggle = { id, value -> clipboardToggles = clipboardToggles.with(id, value) },
                onВставить = { id -> discovery?.вставитьПолученное(id) },
            )

            Route.FILES -> {
                val context = LocalContext.current
                var pickedReceiverId by remember { mutableStateOf<String?>(null) }
                var tick by remember { mutableStateOf(0) }

                LaunchedEffect(Unit) {
                    while (true) {
                        delay(500)
                        tick++
                    }
                }

                // Доступные устройства для отправки: выбранное на карточке, либо первое сопряжённое/найденное
                val targetDevice = devices.firstOrNull { it.deviceId.идентификатор() == pickedReceiverId }
                    ?: (if (selectedDevice != null) devices.firstOrNull { it.deviceId.идентификатор() == selectedDevice.id } else null)
                    ?: devices.firstOrNull { it.connected }
                    ?: devices.firstOrNull()

                val filePicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetContent()
                ) { uri: Uri? ->
                    if (uri != null) {
                        val targetId = targetDevice?.deviceId?.идентификатор()
                        if (targetId == null) {
                            Toast.makeText(context, "Нет устройства для отправки", Toast.LENGTH_SHORT).show()
                        } else {
                            val err = discovery?.отправитьФайлУстройству(targetId, uri)
                            if (err != null) {
                                Toast.makeText(context, "Ошибка отправки: $err", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }

                val currentTransfers = discovery?.getActiveTransfers().orEmpty()
                val storageUsage = discovery?.getStorageUsageText() ?: "0 МБ на диске"

                FilesScreen(
                    state = Sample.filesState().copy(
                        transfers = currentTransfers,
                        storedText = storageUsage,
                    ),
                    toggles = filesToggles,
                    receiverName = targetDevice?.name ?: "",
                    onBack = onBack,
                    onRow = { onRow(it.id) },
                    onToggle = { id, value -> filesToggles = filesToggles.with(id, value) },
                    onSend = {
                        if (targetDevice == null) {
                            Toast.makeText(context, "Нет доступных устройств для отправки", Toast.LENGTH_SHORT).show()
                        } else {
                            filePicker.launch("*/*")
                        }
                    },
                    onPickReceiver = {
                        if (devices.isEmpty()) {
                            Toast.makeText(context, "Устройства ещё не обнаружены", Toast.LENGTH_SHORT).show()
                        } else {
                            val curIdx = devices.indexOfFirst { it.deviceId.идентификатор() == targetDevice?.deviceId?.идентификатор() }
                            val nextIdx = if (curIdx >= 0) (curIdx + 1) % devices.size else 0
                            val nextDevice = devices[nextIdx]
                            pickedReceiverId = nextDevice.deviceId.идентификатор()
                            Toast.makeText(context, "Получатель: ${nextDevice.name}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onCancel = { name -> discovery?.cancelTransfer(name) },
                )
            }

            Route.NOTIFS -> {
                val (title, sub) = Sample.notifStatus()
                NotifsScreen(
                    statusTitle = title,
                    statusSub = sub,
                    groups = Sample.notifGroups(),
                    toggles = notifToggles,
                    onBack = onBack,
                    onRow = { onRow(it.id) },
                    onToggle = { id, value -> notifToggles = notifToggles.with(id, value) },
                    onOpenListenerSettings = onOpenListenerSettings,
                )
            }

            Route.MIRROR -> MirrorScreen(
                groups = Sample.mirrorGroups(),
                toggles = mirrorToggles,
                live = mirrorLive,
                quality = mirrorQuality,
                onLive = { mirrorLive = !mirrorLive },
                onQuality = { mirrorQuality = it },
                onBack = onBack,
                onRow = { onRow(it.id) },
                onToggle = { id, value -> mirrorToggles = mirrorToggles.with(id, value) },
            )

            Route.APPEARANCE -> AppearanceScreen(
                state = Sample.appearanceState(themeChoice, accent),
                onBack = onBack,
                onTheme = onThemeChoice,
                onAccent = { accent = it },
            )

            Route.CORE -> CoreScreen(
                state = Sample.coreState(
                    testsPassed = if (testsRan) true else null,
                    coreVersion = BuildConfig.VERSION_NAME,
                ),
                toggles = coreToggles,
                ran = testsRan,
                onRun = { testsRan = true },
                onBack = onBack,
                onRow = { onRow(it.id) },
                onToggle = { id, value -> coreToggles = coreToggles.with(id, value) },
            )

            Route.ABOUT -> AboutScreen(
                state = Sample.aboutState(
                    appVersion = BuildConfig.VERSION_NAME,
                    coreVersion = BuildConfig.VERSION_NAME,
                ),
                onBack = onBack,
                onLink = { link ->
                    // Единственная ссылка, которая ведёт внутрь приложения.
                    // Остальные требуют страниц, которых пока нет.
                    if (link == "Этапы разработки") onRoute(Route.ROADMAP) else onRow(link)
                },
            )

            Route.ROADMAP -> RoadmapScreen(
                steps = Sample.roadmap(),
                onBack = onBack,
            )

            Route.TRUST -> {
                val device = selectedDevice
                if (device == null) {
                    MissingScreen("Устройство не выбрано", onBack)
                } else {
                    val найденное = devices.firstOrNull { it.deviceId.идентификатор() == device.id }
                    val отпечаток = найденное?.fingerprint.orEmpty()
                    val доверие = trust.состояние(device.id, отпечаток)
                    TrustScreen(
                        deviceName = device.name,
                        fingerprint = отпечаток,
                        адрес = найденное?.ipv4
                            ?.joinToString(".") { (it.toInt() and 0xFF).toString() } ?: "—",
                        версияЯдра = найденное?.coreVersion?.ifEmpty { "—" } ?: "—",
                        доверие = доверие,
                        onBack = onBack,
                        onСопрячь = { trust.сопрячь(device.id, отпечаток) },
                        onЗабыть = { trust.забыть(device.id) },
                    )
                }
            }

            Route.DEVICE -> {
                // Устройство приходит из списка, а не берётся первым попавшимся:
                // нажатие на третью плитку обязано открыть третью.
                val device = selectedDevice
                if (device == null) {
                    MissingScreen("Устройство не выбрано", onBack)
                } else {
                    // Адрес и порт берутся из объекта канала, а не разбираются
                    // обратно из подписи списка: разбор строки, которую сам же
                    // собрали для показа, — это второй источник правды о том же
                    // устройстве, и они разошлись бы при первом же изменении
                    // формата подписи.
                    val найденное = devices.firstOrNull { it.deviceId.идентификатор() == device.id }

                    // Состояние доверия считается здесь, а не в Sample: оно
                    // зависит от хранилища, а Sample строит только строки.
                    val доверие = trust.состояние(device.id, найденное?.fingerprint.orEmpty())
                    val текстДоверия = when (доверие) {
                        TrustStore.Доверие.СОПРЯЖЕНО -> "Сопряжено"
                        TrustStore.Доверие.ОТПЕЧАТОК_ИЗМЕНИЛСЯ -> "Отпечаток изменился"
                        TrustStore.Доверие.НЕ_СВЕРЕНО -> "Не сверено"
                    }
                    // Читается здесь, а не внутри `onSend`: лямбда обработчика не
                    // является composable-функцией, и `Local*.current` в ней —
                    // ошибка компиляции. Значение берётся при построении экрана,
                    // а лямбде достаётся уже готовое.
                    val буферОбмена = LocalClipboardManager.current

                    // Пульс соседа: заряд и время последнего настоящего
                    // сердцебиения. Пусто — сосед ничего не присылал, и точка не
                    // пульсирует: анимация без данных была бы враньём.
                    val пульс = pulses[device.id]
                    val живойПульс = пульс?.свежее(System.currentTimeMillis()) == true
                    val когдаПульс = if (пульс != null) Sample.whenAt(пульс.вМс) else ""
                    DeviceDetailScreen(
                        state = Sample.deviceDetailState(
                            device = device,
                            address = найденное?.ipv4?.joinToString(".") { (it.toInt() and 0xFF).toString() }
                                ?: "—",
                            port = найденное?.port ?: 0,
                            trail = trail,
                            fingerprint = найденное?.fingerprint.orEmpty(),
                            platform = найденное?.platform.orEmpty(),
                            coreVersion = найденное?.coreVersion.orEmpty(),
                            trustText = текстДоверия,
                            peerBattery = пульс?.let { it.заряд to it.зарядка },
                            peerPulseAt = когдаПульс,
                            peerPulseFresh = живойПульс,
                        ),
                        trust = trust,
                        onTrust = onTrust,
                        /**
                         * Пульсирует ли точка «На связи».
                         *
                         * Только пришедшим сердцебиением. Анимация, которая шла бы
                         * по таймеру, врала бы: человек увидел бы живое соединение
                         * с телефоном, который молчит. Здесь пульс означает ровно
                         * то, что значит: «данные идут прямо сейчас».
                         */
                        pulseLive = живойПульс,
                        onBack = onBack,
                        // «Ещё» пока некуда вести: меню за ним не собрано.
                        //
                        // Раньше и оно, и плитки возможностей были подключены к
                        // `Route.DEVICE`, то есть к экрану, который уже открыт:
                        // нажатие добавляло в стек копию того же самого, и
                        // «назад» снимал копию за копией, ни разу не меняя
                        // картинку — четыре нажатия выглядели как «назад не
                        // работает», а на пятом приложение выходило.
                        //
                        // Теперь «Ещё» не ведёт никуда — как и остальные строки без
                        // готового экрана: лучше ничего, чем заглушка на месте
                        // настоящего. Проверка на вершине стека в `открыть`
                        // охраняет от повтора и от других таких кнопок.
                        //
                        // Плитки, в отличие от «Ещё», ведут по своему
                        // идентификатору, и он совпадает с маршрутом подэкрана.
                        // Раньше идентификатор выбрасывался, и нажатие открывало
                        // текущий экран вместо нужного: «Буфер» и «Уведомления»
                        // молчали, а «Файлы» и «Зеркало» работали — потому что их
                        // имена с маршрутами случайно совпадали.
                        onMore = {},
                        onFeature = { onRow(it) },
                        // Кнопка отправляет настоящее содержимое буфера, а не
                        // открывает экран файлов. Файлы ещё не сделаны: экран есть,
                        // но отправлять нечего, и кнопка вела бы в заготовку.
                        // Работающая отправка честнее ссылки на неготовую функцию.
                        onSend = {
                            // Пустой буфер и отсутствие соединения разбираются
                            // внутри канала и попадают в журнал: он уже показан на
                            // этой же карточке. Свой отдельный экран отказа был бы
                            // вторым местом, где об одном и том же написано по-
                            // разному.
                            discovery?.послатьБуферУстройству(
                                device.id,
                                буферОбмена.getText()?.toString().orEmpty(),
                            )
                        },
                        onMirror = { onRow(Route.MIRROR) },
                        onRow = { onRow(it.id) },
                        // Доверие ведёт на экран сопряжения. Идентификатор
                        // строки 	rust совпадает с маршрутом, поэтому
                        // обработчик общий.
                    )
                }
            }

            // Неизвестный маршрут означает ошибку в вызывающем коде, а не у
            // пользователя. Показываем пустой экран: так это заметно в
            // разработке и безопасно в сборке, отданной тестерам.
            else -> MissingScreen("Такого экрана нет", onBack)
        }
    }
}

/**
 * Открывает доступ к уведомлениям в системных настройках.
 *
 * Отдельный адрес, а не общий с настройками приложения: разрешение на чтение
 * уведомлений выдаёт система, и на Android 6+ это отдельный экран со списком
 * служб. На старых версиях адреса нет, поэтому при неудаче открываются общие
 * настройки приложения — туда тоже есть куда идти.
 */
@Composable
fun rememberListenerSettingsOpener(): () -> Unit {
    val context = LocalContext.current
    return {
        val direct = Intent(SystemScreen.NOTIFICATION_LISTENER)
        val opened = runCatching { context.startActivity(direct) }.isSuccess
        if (!opened) {
            runCatching { openAppSettings(context) }
        }
    }
}

/** Экран без содержимого, но с кнопкой возврата: лучше пустоты с выходом. */
@Composable
private fun MissingScreen(text: String, onBack: () -> Unit) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        BackButton(onClick = onBack)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

/** Открытие настроек фоновой работы: у каждой оболочки свой адрес. */
@Composable
fun rememberSystemSettingsOpener(): () -> Unit {
    val context = LocalContext.current
    return {
        // Точные экраны различаются у Xiaomi, Samsung и Huawei, а общего
        // адреса нет. Показываем настройки приложения: это единственное
        // место, которое есть на любом Android и ведёт туда же.
        runCatching { openAppSettings(context) }
    }
}

/** Общие настройки приложения: есть на любой версии Android. */
private fun openAppSettings(context: android.content.Context) {
    val intent = Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.fromParts("package", context.packageName, null),
    )
    context.startActivity(intent)
}

/**
 * Подэкран из групп строк: шапка, необязательный блок сверху и сами группы.
 *
 * Буфер, уведомления, файлы, зеркало, внешний вид — это один и тот же экран
 * с разным содержимым. Собирать его пять раз означало бы, что на пятом экране
 * отступы уже не совпадут с первым.
 */
@Composable
fun RowsSubScreen(
    title: String,
    subtitle: String,
    groups: List<SubGroup>,
    toggles: Toggles,
    onBack: () -> Unit,
    onRow: (SubRow) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    selectedRadio: String? = null,
    onRadio: (String) -> Unit = {},
    top: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 12.dp),
    ) {
        SubHeader(title = title, subtitle = subtitle, onBack = onBack)

        SubBody {
            top?.invoke()

            groups.forEach { group ->
                SubGroupTitle(
                    title = group.title,
                    modifier = Modifier.padding(start = 24.dp, top = 20.dp),
                )
                SubGroupCard(
                    rows = group.rows.withToggleValues(toggles).map { row ->
                        val end = row.end
                        if (end is RowEnd.Radio && selectedRadio != null) {
                            row.copy(end = RowEnd.Radio(checked = row.id == selectedRadio))
                        } else {
                            row
                        }
                    },
                    onRow = { row ->
                        val end = row.end
                        when {
                            end is RowEnd.Toggle && end.enabled -> onToggle(row.id, !end.checked)
                            end is RowEnd.Radio -> onRadio(row.id)
                            else -> onRow(row)
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}
