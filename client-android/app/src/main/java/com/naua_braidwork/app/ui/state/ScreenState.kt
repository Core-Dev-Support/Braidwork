package com.naua_braidwork.app.ui.state

import androidx.compose.ui.graphics.vector.ImageVector
import com.naua_braidwork.app.ui.icons.IcwClipboard
import com.naua_braidwork.app.ui.icons.IcwFolder
import com.naua_braidwork.app.ui.icons.IcwGlobe
import com.naua_braidwork.app.ui.icons.IcwMirror
import com.naua_braidwork.app.ui.icons.IcwSync
import com.naua_braidwork.app.ui.icons.IcwWifi

/**
 * Состояние экранов.
 *
 * Экраны читают только эти структуры и ничего не знают о том, откуда данные.
 * Сейчас их даёт [Sample], позже даст транспорт.
 *
 * Именно ради этого состояние вынесено отдельно: подключение функций не должно
 * трогать разметку. Иначе «сначала дизайн, потом функции» обернётся переписыванием
 * экранов целиком.
 */
data class DeviceSummary(
    val id: String,
    val name: String,
    val subtitle: String,
    val kind: DeviceKind,
    val online: Boolean,
    /** Заряд в процентах. `null`, когда устройство не сообщает состояние. */
    val batteryPercent: Int?,
)

enum class DeviceKind { Phone, Tablet, Desktop, Laptop }

enum class Presence { Online, Nearby, Offline }

/** Режим связи, переключается на главном экране круглыми кнопками. */
enum class LinkMode(val id: String, val label: String, val icon: ImageVector) {
    Lan("lan", "Только LAN", IcwWifi),
    Auto("auto", "Авто", IcwSync),
    Internet("ice", "Интернет", IcwGlobe);

    companion object {
        val DEFAULT = Auto
        fun from(id: String): LinkMode = entries.firstOrNull { it.id == id } ?: Auto
    }
}

/** Быстрое действие: плашка с цветной подложкой. */
data class QuickAction(
    val id: String,
    val label: String,
    val icon: ImageVector,
    /** Какой оттенок плашки использовать. */
    val tone: Tone,
)

/** Событие в ленте активности. */
data class ActivityEntry(
    val title: String,
    val subtitle: String,
    val time: String,
    val icon: ImageVector,
    val tone: Tone,
)

/**
 * Оттенок плашки: четыре пары из спецификации плюс нейтральный.
 *
 * Перечисление, а не цвет: экран выбирает тон, а палитру берёт из темы. Иначе
 * цвета расползлись бы по разметке.
 */
enum class Tone { Blue, Green, Orange, Purple, Neutral }

/** Главный экран целиком. */
data class MainState(
    val deviceCount: Int,
    val thisDeviceId: String,
    val thisDeviceName: String,
    val channelLabel: String,
    val directLinks: Int,
    val mode: LinkMode,
    /** Каналы «рядом» включаются независимо от режима. */
    val bleOn: Boolean,
    val softApOn: Boolean,
    val quick: List<QuickAction>,
    val activity: List<ActivityEntry>,
) {
    companion object {
        val QUICK = listOf(
            QuickAction("clipboard", "Буфер", IcwClipboard, Tone.Orange),
            QuickAction("files", "Файлы", IcwFolder, Tone.Green),
            QuickAction("notifs", "Уведомления", com.naua_braidwork.app.ui.icons.IcwBell, Tone.Purple),
            QuickAction("mirror", "Зеркало", IcwMirror, Tone.Blue),
        )
    }
}

/** Список устройств. */
data class DevicesState(
    val devices: List<DeviceSummary>,
    val filter: DeviceFilter,
)

enum class DeviceFilter(val label: String) {
    All("Все"),
    Online("В сети"),
    Nearby("Рядом"),
    Offline("Офлайн"),
}