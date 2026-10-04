package com.naua_braidwork.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import com.naua_braidwork.app.ui.state.LinkMode
import com.naua_braidwork.app.ui.state.RowEnd
import com.naua_braidwork.app.ui.state.Sample
import com.naua_braidwork.app.ui.state.SubGroup
import com.naua_braidwork.app.ui.state.SubRow
import com.naua_braidwork.app.ui.state.Toggles
import com.naua_braidwork.app.ui.state.withToggleValues

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
    onBack: () -> Unit,
    onRow: (String) -> Unit,
    onRoute: (String) -> Unit,
    onOpenSystemSettings: () -> Unit,
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
    var theme by remember { mutableStateOf("Как в системе") }
    var lang by remember { mutableStateOf("Русский") }
    var accent by remember { mutableStateOf("blue") }
    var coreToggles by remember { mutableStateOf(Toggles(emptyMap())) }
    var testsRan by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
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
                state = Sample.securityState(deviceId),
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
                groups = Sample.clipboardGroups(),
                toggles = clipboardToggles,
                onBack = onBack,
                onRow = { onRow(it.id) },
                onToggle = { id, value -> clipboardToggles = clipboardToggles.with(id, value) },
            )

            Route.FILES -> FilesScreen(
                groups = Sample.filesGroups(),
                toggles = filesToggles,
                onBack = onBack,
                onRow = { onRow(it.id) },
                onToggle = { id, value -> filesToggles = filesToggles.with(id, value) },
                onSend = { onRow(Route.FILES) },
                onPickReceiver = { onRow(Route.FILES) },
            )

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

            Route.APPEARANCE -> {
                // Список вариантов нужен и для показа, и для разбора нажатия.
                val options = Sample.appearanceState(theme, lang, accent)
                AppearanceScreen(
                    state = options,
                    onBack = onBack,
                    onTheme = { theme = options.themes[it] },
                    onLang = { lang = options.langs[it] },
                    onAccent = { accent = it },
                )
            }

            Route.CORE -> CoreScreen(
                state = Sample.coreState(if (testsRan) true else null),
                toggles = coreToggles,
                ran = testsRan,
                onRun = { testsRan = true },
                onBack = onBack,
                onRow = { onRow(it.id) },
                onToggle = { id, value -> coreToggles = coreToggles.with(id, value) },
            )

            Route.ABOUT -> AboutScreen(
                state = Sample.aboutState(),
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

            Route.DEVICE -> {
                // Устройства пока нет, поэтому состояние для карточки тоже
                // нет. Экран включается вместе с первым найденным устройством.
                val device = Sample.devicesState().devices.firstOrNull()
                if (device == null) {
                    MissingScreen("Устройство не выбрано", onBack)
                } else {
                    DeviceDetailScreen(
                        state = Sample.deviceDetailState(device),
                        onBack = onBack,
                        onMore = { onRow(Route.DEVICE) },
                        onFeature = { onRow(Route.DEVICE) },
                        onSend = { onRow(Route.FILES) },
                        onMirror = { onRow(Route.MIRROR) },
                        onRow = { onRow(it.id) },
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
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", context.packageName, null),
        )
        runCatching { context.startActivity(intent) }
    }
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
