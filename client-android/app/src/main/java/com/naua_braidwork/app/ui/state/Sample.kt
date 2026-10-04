package com.naua_braidwork.app.ui.state

import com.naua_braidwork.app.BraidworkCore
import com.naua_braidwork.app.ui.icons.IcwBell
import com.naua_braidwork.app.ui.icons.IcwClipboard
import com.naua_braidwork.app.ui.icons.IcwCpu
import com.naua_braidwork.app.ui.icons.IcwFolder
import com.naua_braidwork.app.ui.icons.IcwInfo
import com.naua_braidwork.app.ui.icons.IcwMirror
import com.naua_braidwork.app.ui.icons.IcwMoon
import com.naua_braidwork.app.ui.icons.IcwPower
import com.naua_braidwork.app.ui.icons.IcwShield
import com.naua_braidwork.app.ui.icons.IcwWifi

/**
 * Данные для экранов, пока нет транспорта.
 *
 * Важно: подставляются настоящие значения там, где они есть. Идентификатор
 * устройства приходит из ядра, всё остальное честно пустое. Набор правдоподобных
 * «примерных» устройств был бы враньём на экране, который показывают тестерам.
 */
object Sample {

    /**
     * Показывалось ли приветствие.
     *
     * Пока хранится только в памяти процесса: отдельное постоянное хранилище
     * появится вместе с настройками. После перезапуска приложения приветствие
     * появится снова — это заметно, но не ломает работу на этапе проверки.
     */
    private var welcomeSeen = false

    fun seenWelcome(): Boolean = welcomeSeen

    fun markWelcomeSeen() {
        welcomeSeen = true
    }

    /** Идентификатор берётся из ядра; до его получения показываем заглушку. */
    fun thisDeviceId(): String = runCatching {
        BraidworkCore.deviceId(BraidworkCore.generateKeys().signing)
    }.getOrElse { "идентификатор вычисляется" }

    fun mainState(deviceId: String, deviceName: String): MainState = MainState(
        deviceCount = 0,
        thisDeviceId = deviceId,
        thisDeviceName = deviceName,
        channelLabel = "Канал не выбран",
        directLinks = 0,
        mode = LinkMode.DEFAULT,
        bleAvailable = true,
        softApAvailable = true,
        quick = MainState.QUICK,
        activity = emptyList(),
    )

    fun devicesState(): DevicesState = DevicesState(
        devices = emptyList(),
        filter = DeviceFilter.All,
    )

    /**
     * Семья.
     *
     * Название и состав пустые: пока устройство не введено, любые имена были бы
     * выдумкой. Экран показывает ноль и объясняет, как устройство попадает в семью.
     */
    fun familyState(): FamilyState = FamilyState(
        name = "Семья",
        participants = 0,
        devices = 0,
        avatars = emptyList(),
        members = emptyList(),
    )

    /** Настройки: разделы из спецификации со значениями по факту состояния. */
    fun settingsGroups(): List<SettingsGroup> = listOf(
        SettingsGroup(
            title = "",
            rows = listOf(
                SettingsRow("network", "Сеть и соединение", "Авто", IcwWifi, Tone.Blue),
                SettingsRow("security", "Безопасность и ключи", "Noise XX", IcwShield, Tone.Purple),
                SettingsRow("background", "Фоновая работа", "Не проверена", IcwPower, Tone.Green),
            ),
        ),
        SettingsGroup(
            title = "Обмен",
            rows = listOf(
                SettingsRow("clipboard", "Буфер обмена", "Выкл.", IcwClipboard, Tone.Orange),
                SettingsRow("files", "Файлы и хранилище", "0 Б", IcwFolder, Tone.Green),
                SettingsRow("notifs", "Уведомления", "Выкл.", IcwBell, Tone.Purple),
                SettingsRow("mirror", "Зеркало экрана", "Выкл.", IcwMirror, Tone.Blue),
            ),
        ),
        SettingsGroup(
            title = "Приложение",
            rows = listOf(
                SettingsRow("appearance", "Внешний вид и язык", "Системная", IcwMoon, Tone.Neutral),
                SettingsRow("core", "Ядро и диагностика", "Шаг 0", IcwCpu, Tone.Neutral),
                SettingsRow("about", "О приложении", "", IcwInfo, Tone.Neutral),
            ),
        ),
    )
}