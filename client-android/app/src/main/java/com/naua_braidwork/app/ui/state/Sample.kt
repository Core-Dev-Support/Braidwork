package com.naua_braidwork.app.ui.state

import com.naua_braidwork.app.BraidworkCore
import androidx.compose.ui.graphics.Color
import com.naua_braidwork.app.ui.icons.IcwAddress
import com.naua_braidwork.app.ui.icons.IcwBattery
import com.naua_braidwork.app.ui.icons.IcwBell
import com.naua_braidwork.app.ui.icons.IcwCalendar
import com.naua_braidwork.app.ui.icons.IcwCard
import com.naua_braidwork.app.ui.icons.IcwChat
import com.naua_braidwork.app.ui.icons.IcwClipboard
import com.naua_braidwork.app.ui.icons.IcwCpu
import com.naua_braidwork.app.ui.icons.IcwFolder
import com.naua_braidwork.app.ui.icons.IcwId
import com.naua_braidwork.app.ui.icons.IcwInfo
import com.naua_braidwork.app.ui.icons.IcwLaptop
import com.naua_braidwork.app.ui.icons.IcwMail
import com.naua_braidwork.app.ui.icons.IcwMirror
import com.naua_braidwork.app.ui.icons.IcwMoon
import com.naua_braidwork.app.ui.icons.IcwPhone
import com.naua_braidwork.app.ui.icons.IcwPower
import com.naua_braidwork.app.ui.icons.IcwPulse
import com.naua_braidwork.app.ui.icons.IcwShield
import com.naua_braidwork.app.ui.icons.IcwTablet
import com.naua_braidwork.app.ui.icons.IcwWifi
import com.naua_braidwork.app.ui.theme.ThemeChoice
import java.text.DateFormat
import java.util.Date

/**
 * Данные для экранов, пока нет транспорта.
 *
 * Важно: подставляются настоящие значения там, где они есть. Идентификатор
 * устройства приходит из ядра, всё остальное честно пустое. Набор правдоподобных
 * «примерных» устройств был бы враньём на экране, который показывают тестерам.
 */
object Sample {

    // Показывалось ли приветствие — теперь живёт в SettingsStore: до него флаг
    // держался в памяти процесса, и приветствие возвращалось после каждого
    // запуска приложения.

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
        // Каналы «рядом» включаются независимо от режима связи, поэтому у них
        // своё состояние, а не следствие выбранного режима.
        bleOn = false,
        softApOn = false,
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
                SettingsRow("appearance", "Внешний вид", "Системная", IcwMoon, Tone.Neutral),
                SettingsRow("core", "Ядро и диагностика", "Шаг 0", IcwCpu, Tone.Neutral),
                SettingsRow("about", "О приложении", "", IcwInfo, Tone.Neutral),
            ),
        ),
    )

    /**
     * Сеть и соединение.
     *
     * Все переключатели выключены: ни один транспорт ещё не работает, и включённый
     * переключатель означал бы обещание, которого нет. Режим подключения —
     * общий с главным экраном, поэтому он и берётся из [MainState].
     */
    fun networkState(mode: LinkMode, deviceId: String): NetworkState {
        val modes = listOf(
            RadioOption("auto", "Авто", "Сначала LAN, потом точка доступа, потом интернет"),
            RadioOption("lan", "Только локальная сеть", "Без выхода в интернет"),
            RadioOption("ice", "Только интернет (ICE)", "Для устройств в разных сетях"),
        )

        val groups = listOf(
            SubGroup(
                title = "Режим подключения",
                rows = modes.map { option ->
                    SubRow(
                        id = option.id,
                        title = option.label,
                        sub = option.sub,
                        end = RowEnd.Radio(checked = mode.id == option.id),
                    )
                },
            ),
            SubGroup(
                title = "Локальная сеть",
                rows = listOf(
                    SubRow(
                        "mdns", "Поиск устройств (mDNS)",
                        "Находит семью в той же сети",
                        RowEnd.Toggle(false),
                    ),
                    SubRow("visible", "Видимость", "", RowEnd.Chevron),
                    SubRow(
                        "softap", "Точка доступа без роутера",
                        "Телефон создаёт сеть сам",
                        RowEnd.Toggle(false),
                    ),
                    SubRow(
                        "apname", "Имя точки", "",
                        RowEnd.Value("BW-${shortTag(deviceId)}"),
                    ),
                ),
            ),
            SubGroup(
                title = "Рядом",
                rows = listOf(
                    SubRow("ble", "BLE-прогрев", "Быстрое соединение рядом", RowEnd.Toggle(false)),
                    SubRow(
                        "acoustic", "Акустический запуск",
                        "Звуковой сигнал для сопряжения",
                        RowEnd.Toggle(false, enabled = false),
                    ),
                ),
            ),
            SubGroup(
                title = "Интернет",
                rows = listOf(
                    SubRow(
                        "ice", "ICE со STUN",
                        "Связь между сетями без своих серверов",
                        RowEnd.Toggle(false, enabled = false),
                    ),
                    SubRow("stun", "STUN-серверы", "", RowEnd.Chevron),
                    SubRow(
                        "mobile", "Мобильная сеть",
                        "Передача может тратить трафик",
                        RowEnd.Toggle(false),
                    ),
                    // Почему переключатель выше нельзя включить: на Android
                    // используемая библиотека STUN не собирается. Прячем это
                    // здесь, а не оставляем переключатель серым без причины.
                    SubRow(
                        "ice_state", "Состояние на этом устройстве", "",
                        RowEnd.Value("Нужен другой клиент STUN"),
                    ),
                ),
            ),
            SubGroup(
                title = "Адреса семьи",
                rows = listOf(
                    SubRow("gossip", "Обновление адресов", "", RowEnd.Value("Gossip")),
                    SubRow("known", "Известные адреса", "", RowEnd.Value("0")),
                ),
            ),
        )

        return NetworkState(
            lanStatus = "Локальная сеть · не проверена",
            lanSub = "Поиск устройств ещё не запускался",
            groups = groups,
        )
    }

    /**
     * Безопасность и ключи.
     *
     * Тождественная защита — единственное, что уже работает: ключи создаются
     * на устройстве и в канал уходят только подписью. Остальные переключатели
     * показывают замысел, а биометрия честно помечена как неподключённая.
     */
    fun securityState(deviceId: String, fingerprint: String = ""): SecurityState {
        val groups = listOf(
            SubGroup(
                title = "Ключи",
                rows = listOf(
                    SubRow("id", "Идентификатор устройства", "", RowEnd.Value(shortTag(deviceId)), smallIcon = IcwId),
                    SubRow(
                        "fingerprint",
                        "Отпечаток ключа",
                        "",
                        RowEnd.ValueWrap(
                            fingerprint.ifBlank { "ядро не сообщило отпечаток" },
                        ),
                        smallIcon = IcwShield,
                    ),
                    SubRow("rotation", "Ротация ключей", "", RowEnd.Value("Выключена")),
                ),
            ),
            SubGroup(
                title = "Доверие",
                rows = listOf(
                    SubRow(
                        "trusted", "Доверенные выдают ключи",
                        "Новые устройства подключает не только админ",
                        RowEnd.Toggle(true),
                    ),
                    SubRow(
                        "confirm", "Подтверждать новые устройства",
                        "Запрос приходит всем доверенным",
                        RowEnd.Toggle(true),
                    ),
                    SubRow("blocked", "Заблокированные", "", RowEnd.Chevron),
                ),
            ),
            SubGroup(
                title = "Защита приложения",
                rows = listOf(
                    SubRow(
                        "bio", "Биометрия при входе", "Отпечаток или лицо",
                        RowEnd.Toggle(false, enabled = false),
                    ),
                    SubRow(
                        "preview", "Скрывать превью в недавних", "",
                        RowEnd.Toggle(true),
                    ),
                ),
            ),
            SubGroup(
                title = "Опасная зона",
                rows = listOf(
                    SubRow(
                        "reset", "Сбросить ключи устройства",
                        "Потребуется новое сопряжение",
                        RowEnd.Chevron,
                        danger = true,
                    ),
                    SubRow("leave", "Выйти из семьи", "", RowEnd.Chevron, danger = true),
                ),
            ),
        )

        return SecurityState(
            deviceIdShort = shortTag(deviceId),
            fingerprint = fingerprint,
            groups = groups,
        )
    }

    /**
     * Фоновая работа.
     *
     * Служба связи действительно поднимается при открытии приложения, но
     * watchdog и автозапуск ещё не сделаны. Поэтому шаги показаны
     * невыполненными все: «готово» означало бы, что система это проверила,
     * а проверить пока нечем.
     */
    fun backgroundState(brand: String): BackgroundState {
        val brands = listOf("Xiaomi", "Samsung", "Huawei", "Realme", "Другой")
        val titles = mapOf(
            "Xiaomi" to listOf("Автозапуск", "Батарея без ограничений", "Закрепить в недавних", "Экран блокировки"),
            "Samsung" to listOf("Не усыплять приложение", "Батарея без ограничений", "Исключить из оптимизации", "Работа в фоне"),
            "Huawei" to listOf("Автозапуск", "Ручное управление запуском", "Работа в фоне", "Не закрывать при очистке"),
            "Realme" to listOf("Автозапуск", "Фоновая активность", "Батарея без ограничений", "Закрепить в недавних"),
            "Другой" to listOf("Батарея без ограничений", "Автозапуск", "Уведомление службы", "Закрепить в недавних"),
        )
        val chosen = if (brand in brands) brand else brands.first()

        return BackgroundState(
            serviceTitle = "Служба связи запускается при открытии приложения",
            serviceSub = "Watchdog и автозапуск пока не подключены",
            brand = chosen,
            brands = brands,
            steps = titles.getValue(chosen).map { BrandStep(it, done = false) },
            groups = listOf(
                SubGroup(
                    title = "Дополнительно",
                    rows = listOf(
                        SubRow("boot", "Запуск при включении", "", RowEnd.Toggle(false, enabled = false)),
                        SubRow(
                            "notif", "Постоянное уведомление",
                            "Нужно для работы службы",
                            RowEnd.Toggle(true),
                        ),
                        SubRow("watchdog", "Проверка Watchdog", "", RowEnd.Value("Не задана")),
                    ),
                ),
            ),
        )
    }

    /** Четыре символа идентификатора: для имен точек и короткой записи. */
    private fun shortTag(id: String): String {
        val letters = id.filter { it.isLetterOrDigit() }
        return if (letters.length <= 4) id else letters.take(4)
    }

    /**
     * Идентификатор устройства в виде, пригодном для строки списка.
     *
     * Восемь символов, а не четыре: четыре одинаковы у почти всех телефонов подряд,
     * и список превратился бы в «От ABCD», где ничего не различить.
     */
    fun deviceTag(id: String): String = id.trim().ifEmpty { "устройство" }.let {
        if (it.length <= 8) it else it.take(8)
    }

    /**
     * Тип устройства словами для карточки.
     *
     * Отдельная функция, а не `when` на месте вызова: строки читаются глазами, и
     * одно место, где они живут, не даст им разъехаться. Плюс здесь же
     * [DeviceKind.Неизвестно], который обязан называться словом, а не молчать и не
     * подставлять чужой тип.
     */
    fun названиеТипа(kind: DeviceKind): String = when (kind) {
        DeviceKind.Phone -> "телефон"
        DeviceKind.Tablet -> "планшет"
        DeviceKind.Laptop -> "ноутбук"
        DeviceKind.Desktop -> "компьютер"
        DeviceKind.Неизвестно -> ТИП_НЕИЗВЕСТЕН
    }

    /**
     * Время получения словами: «сегодня, 14:32».
     *
     * Только время, без даты: запись в списке всегда свежая — старые отсеиваются
     * по глубине, — а дата заняла бы место, которое отдано самому тексту.
     */
    fun whenAt(мс: Long): String =
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(мс))

    /**
     * Буфер обмена.
     *
     * Группа «Недавнее» строится из того, что **действительно пришло по каналу**.
     * Раньше её не было вовсе, и присланный текст оседал только строкой в
     * журнале подключения: человек не мог ни увидеть его целиком, ни вставить.
     *
     * Пустой список — честное «ничего не приходило», а не пример из макета.
     * Переключатели остаются неподключёнными: копирование в фоне ещё не сделано,
     * и помечать их выключенными было бы ложью — выглядит так, будто человек
     * сам выключил то, что можно включить.
     */
    fun clipboardGroups(items: List<ClipboardItem> = emptyList()): List<SubGroup> = listOf(
        SubGroup(
            title = "Недавнее",
            rows = if (items.isEmpty()) {
                listOf(
                    SubRow(
                        "cb_empty",
                        "Ничего не приходило",
                        "Отправьте буфер с другого устройства — он появится здесь",
                        RowEnd.None,
                    )
                )
            } else {
                items.map { предмет ->
                    SubRow(
                        предмет.id,
                        "От ${deviceTag(предмет.отКого)}",
                        // Только время. Тип и длина были убраны по просьбе
                        // владельца: «text/plain, 9 симв.» занимало строку целиком и
                        // ничего не говорило. Тип для обычного текста всегда один,
                        // а длина видна по самому тексту ниже.
                        whenAt(предмет.вМс),
                        RowEnd.Chevron,
                        // Полный текст, а не обрезок: человек вставляет именно
                        // его, и по огрызку нельзя понять, то ли это.
                        multiline = listOf(предмет.текст),
                    )
                }
            },
        ),
        SubGroup(
            title = "Основное",
            rows = listOf(
                SubRow(
                    "cb_on", "Сквозной буфер обмена",
                    "Копируешь здесь, вставляешь везде",
                    RowEnd.Toggle(false, enabled = false),
                ),
                SubRow(
                    "cb_ask", "Спрашивать перед отправкой",
                    "Подтверждение для каждой копии",
                    RowEnd.Toggle(false, enabled = false),
                ),
                SubRow("history", "Хранить историю", "", RowEnd.Chevron),
            ),
        ),
        SubGroup(
            title = "Что передавать",
            rows = listOf(
                SubRow("t_text", "Текст", "", RowEnd.Toggle(false, enabled = false)),
                SubRow("t_link", "Ссылки", "", RowEnd.Toggle(false, enabled = false)),
                SubRow("t_img", "Изображения", "", RowEnd.Toggle(false, enabled = false)),
                SubRow("t_file", "Файлы", "", RowEnd.Toggle(false, enabled = false)),
            ),
        ),
        SubGroup(
            title = "Приватность",
            rows = listOf(
                SubRow(
                    "pw", "Не передавать пароли",
                    "Пропускать данные менеджеров паролей",
                    RowEnd.Toggle(true),
                ),
                SubRow("hide", "Скрывать данные в истории", "", RowEnd.Toggle(false, enabled = false)),
            ),
        ),
    )

    /**
     * Уведомления.
     *
     * Служба чтения уведомлений в приложении есть и включена в манифесте, но
     * читать она станет только после того, как пользователь разрешит доступ в
     * системе. Поэтому статус честно говорит «нужно разрешение».
     */
    fun notifStatus(): Pair<String, String> =
        "Доступ к уведомлениям не выдан" to
            "Нажмите, чтобы открыть настройки системы"

    fun notifGroups(): List<SubGroup> = listOf(
        SubGroup(
            title = "Направление",
            rows = listOf(
                SubRow(
                    "send", "Отправлять на другие устройства",
                    "С телефона на ноутбуки и ПК",
                    RowEnd.Toggle(false, enabled = false),
                ),
                SubRow(
                    "recv", "Принимать с других устройств",
                    "Показывать как обычные уведомления",
                    RowEnd.Toggle(false, enabled = false),
                ),
            ),
        ),
        SubGroup(
            title = "Приложения",
            rows = listOf(
                SubRow("a_chat", "Мессенджеры", "", RowEnd.Toggle(false), icon = IcwChat, tone = Tone.Blue),
                SubRow("a_mail", "Почта", "", RowEnd.Toggle(false), icon = IcwMail, tone = Tone.Orange),
                SubRow("a_bank", "Банки", "", RowEnd.Toggle(false), icon = IcwCard, tone = Tone.Green),
                SubRow("a_cal", "Календарь", "", RowEnd.Toggle(false), icon = IcwCalendar, tone = Tone.Purple),
                SubRow("a_call", "Звонки и SMS", "", RowEnd.Toggle(false), icon = IcwPhone, tone = Tone.Blue),
            ),
        ),
        SubGroup(
            title = "Действия",
            rows = listOf(
                SubRow(
                    "reply", "Отвечать с другого устройства",
                    "Ответ уйдёт из исходного приложения",
                    RowEnd.Toggle(false, enabled = false),
                ),
                SubRow(
                    "dismiss", "Закрывать вместе",
                    "Смахнул здесь, закрыто везде",
                    RowEnd.Toggle(false, enabled = false),
                ),
                SubRow(
                    "dnd", "Учитывать «Не беспокоить»",
                    "Тихо, если режим включён на телефоне",
                    RowEnd.Toggle(false, enabled = false),
                ),
            ),
        ),
        SubGroup(
            title = "Приватность",
            rows = listOf(
                SubRow(
                    "hidetext", "Скрывать текст сообщений",
                    "Только название приложения",
                    RowEnd.Toggle(false),
                ),
                SubRow("quiet", "Тихие часы", "", RowEnd.Chevron),
            ),
        ),
    )

    /**
     * Файлы и хранилище.
     *
     * Передач нет, поэтому список пуст, а не заполнен примерами. Но компонент
     * списка уже готов и ждёт данные: иначе подключение транспорта означало бы
     * ещё и вёрстку, а список передач — самое частое, что меняется на этом
     * экране.
     */
    fun filesState(): FilesState = FilesState(
        transfers = emptyList(),
        storedText = "0 Б из 5 ГБ",
        limitText = "5 ГБ",
        groups = listOf(
            SubGroup(
                title = "Приём файлов",
                rows = listOf(
                    SubRow(
                        "auto", "Принимать от семьи сразу",
                        "Без подтверждения от доверенных",
                        RowEnd.Toggle(false, enabled = false),
                    ),
                    SubRow(
                        "wifi", "Только по Wi-Fi",
                        "Мобильный трафик не расходуется",
                        RowEnd.Toggle(false, enabled = false),
                    ),
                    SubRow("folder", "Папка загрузки", "", RowEnd.Chevron),
                ),
            ),
            SubGroup(
                title = "Хранилище по хэшу",
                rows = listOf(
                    SubRow(
                        "dedupe", "Не дублировать одинаковые файлы",
                        "Файл хранится один раз",
                        RowEnd.Toggle(false, enabled = false),
                    ),
                    SubRow("limit", "Лимит хранилища", "", RowEnd.Chevron),
                    SubRow("keep", "Хранить файлы", "", RowEnd.Chevron),
                    SubRow("clean", "Очистить кэш", "", RowEnd.Chevron, danger = true),
                ),
            ),
        ),
    )

    /** Зеркало экрана: качество и параметры захвата. */
    fun mirrorGroups(): List<SubGroup> = listOf(
        SubGroup(
            title = "Качество",
            rows = listOf(
                SubRow("auto", "Авто", "Подстраивается под сеть", RowEnd.Radio(true)),
                SubRow("eco", "Экономия", "720p, 15 кадров в секунду", RowEnd.Radio(false)),
                SubRow("max", "Максимум", "Родное разрешение, 60 кадров", RowEnd.Radio(false)),
            ),
        ),
        SubGroup(
            title = "Параметры",
            rows = listOf(
                SubRow("audio", "Передавать звук", "", RowEnd.Toggle(false, enabled = false)),
                SubRow("touches", "Показывать касания", "", RowEnd.Toggle(false, enabled = false)),
                SubRow(
                    "control", "Управление с другого устройства",
                    "Нужно подтверждение на телефоне",
                    RowEnd.Toggle(false, enabled = false),
                ),
            ),
        ),
        SubGroup(
            title = "Способ захвата",
            // На Android доступен только MediaProjection. PipeWire и XShm
            // относятся к десктопным клиентам, и показывать их на телефоне
            // значит предлагать то, чего здесь сделать нельзя: выбор всё равно
            // не влияет на результат. В клиенте Linux список будет свой.
            rows = listOf(
                SubRow(
                    "phone", "Этот телефон",
                    "На этом устройстве доступен только MediaProjection",
                    RowEnd.Value("MediaProjection"),
                ),
            ),
        ),
    )

    /** Внешний вид: тема, цвет акцента и производительность. */
    fun appearanceState(theme: ThemeChoice, accent: String) = AppearanceState(
        theme = theme,
        accent = accent,
        themes = ThemeChoice.entries,
        accents = listOf(
            AccentSwatch("blue", "Синий", Color(0xFF2B8CF5)),
            AccentSwatch("green", "Зелёный", Color(0xFF1E9E5A)),
            AccentSwatch("orange", "Оранжевый", Color(0xFFD9660E)),
            AccentSwatch("purple", "Фиолетовый", Color(0xFF7A4FE0)),
            AccentSwatch("pink", "Розовый", Color(0xFFD93A7B)),
        ),
        perf = listOf(
            SubRow(
                "lite", "Упрощённая анимация", "Для слабых устройств",
                RowEnd.Toggle(false, enabled = false),
            ),
            SubRow("big", "Крупный текст", "", RowEnd.Toggle(false, enabled = false)),
            SubRow(
                "tray", "Значок в трее и шторке",
                "Быстрый доступ к обмену",
                RowEnd.Toggle(false, enabled = false),
            ),
        ),
    )

    /**
     * Ядро и диагностика.
     *
     * Значения взяты из того, что действительно сделано: версии, формат
     * сборки и решение по паникам зафиксированы в DECISIONS.md и CI. Проверки
     * двух устройств ещё не запускались, поэтому у них нет результата — не
     * «успешно» и не «провал», а пусто.
     */
    /**
     * Ядро и диагностика.
     *
     * [coreVersion] приходит из `versionName`, который Gradle вычислил из тега.
     * Ядро не спрашивается, и это не недосмотр: приложение и библиотека
     * собираются одним прогоном из одного коммита, а CI требует, чтобы версия в
     * `Cargo.toml` совпадала с тегом. Значит цифры совпадают по построению, и
     * спрашивать у ядра значило бы завести второй источник без новой информации.
     *
     * Раньше здесь стоял зашитый `0.1.0-step0`: правдоподобная и совершенно
     * неверная цифра, из-за которой человек перестал бы верить обеим.
     */
    fun coreState(testsPassed: Boolean?, coreVersion: String = ""): CoreState = CoreState(
        step = "Шаг 0",
        buildGroups = listOf(
            SubGroup(
                title = "Сборка",
                rows = listOf(
                    SubRow(
                        "core_version",
                        "Версия ядра",
                        "",
                        RowEnd.Value(coreVersion.ifBlank { "неизвестно" }),
                    ),
                    SubRow("proto", "Версия протокола", "", RowEnd.Value("v1 (Protobuf)")),
                    SubRow("format", "Формат сборки", "", RowEnd.Value("cdylib + rlib")),
                    SubRow("panic", "Обработка паник", "", RowEnd.Value("unwind (D-008)")),
                ),
            ),
            SubGroup(
                title = "Хэндлы",
                rows = listOf(
                    SubRow("active", "Активные хэндлы", "", RowEnd.Value("0")),
                    SubRow("scheme", "Схема хэндла", "", RowEnd.Value("uint64: слот + поколение")),
                    SubRow("stale", "Устаревший хэндл", "", RowEnd.Value("Отклоняется")),
                ),
            ),
        ),
        tests = listOf(
            CoreTest("Рукопожатие Noise XX", testsPassed),
            CoreTest("Обмен сообщением", testsPassed),
            CoreTest("Закрытие хэндлов", testsPassed),
        ),
        logGroups = listOf(
            SubGroup(
                title = "Журнал",
                rows = listOf(
                    SubRow("verbose", "Подробный журнал", "", RowEnd.Toggle(false)),
                    SubRow("export", "Экспортировать журнал", "", RowEnd.Chevron),
                    SubRow("reset", "Сбросить хэндлы", "", RowEnd.Chevron, danger = true),
                ),
            ),
        ),
    )

    /**
     * О приложении: версии, платформы и их шаги по плану.
     *
     * Обе версии — из `versionName`, вычисленного Gradle из тега. Раньше здесь стоял
     * собранный вручную текст «Версия 0.1.0 · ядро 0.1.0-step0», который перестал
     * соответствовать релизу ещё в v0.7.0 и молчал дальше: сборка знала
     * правильную версию, а экран показывал зашитую.
     *
     * [coreVersion] совпадает с [appVersion] по построению — см. [coreState]. Ядро
     * об этом не спрашивается: отдельная цифра оттуда не дала бы ничего нового,
     * а стоила бы ещё одну функцию в контракте.
     */
    fun aboutState(
        appVersion: String = "",
        coreVersion: String = "",
    ): AboutState = AboutState(
        appVersion = buildString {
            append("Версия ")
            append(appVersion.ifBlank { "неизвестна" })
            append(" · ядро ")
            append(coreVersion.ifBlank { "неизвестно" })
        },
        coreVersion = coreVersion.ifBlank { "неизвестно" },
        platforms = listOf(
            PlatformEntry(IcwPhone, Tone.Green, "Android 6 и новее", "Kotlin", "Шаг 3"),
            PlatformEntry(IcwMirror, Tone.Blue, "Windows", "WinUI 3", "Шаг 5"),
            PlatformEntry(IcwLaptop, Tone.Orange, "Linux", "AppImage, KDE и GNOME", "Шаг 6"),
            PlatformEntry(IcwTablet, Tone.Purple, "HarmonyOS NEXT", "ArkTS и NAPI", "Шаг 9"),
            PlatformEntry(IcwPhone, Tone.Green, "Android 5", "minSdk 21", "Шаг 10"),
        ),
        links = listOf(
            "Этапы разработки",
            "Лицензии",
            "Политика конфиденциальности",
        ),
    )

    /** План из одиннадцати шагов; текущий — первый. */
    fun roadmap(): List<RoadmapStep> = listOf(
        "Ядро" to "Протокол Protobuf, Noise XX, хэндлы, Rust-ядро",
        "LAN и точка доступа" to "Поиск по mDNS и SoftAP без роутера",
        "Семья как сеть" to "Сопряжение по QR, ключи, Gossip",
        "Клиент Android" to "Kotlin, Watchdog, мастер фоновой работы",
        "Функции основы" to "Буфер, файлы по хэшу, уведомления, зеркало",
        "Клиент Windows" to "WinUI 3, трей, автозапуск",
        "Клиент Linux" to "AppImage, KDE и GNOME, XShm и PipeWire",
        "BLE и звук" to "Быстрое соединение рядом",
        "ICE со STUN" to "Связь через интернет без своих серверов",
        "HarmonyOS NEXT" to "ArkTS и NAPI поверх ядра",
        "Android 5" to "minSdk 21, дешугаризация, обход java.time",
    ).mapIndexed { index, (title, sub) ->
        RoadmapStep(
            n = index.toString(),
            title = title,
            sub = sub,
            current = index == 0,
        )
    }

    /**
     * Карточка устройства.
     *
     * Значения, которых ещё нет, показаны честными прочерками, а не выдуманными:
     * платформа станет известна после соединения, отпечаток ключа — после
     * сопряжения. Заполнять их заранее значило бы показывать пользователю то,
     * чего никто не знает.
     *
     * Состояние соединения — единственное, что известно сразу, и оно здесь
     * настоящее.
     */
    fun deviceDetailState(
        device: DeviceSummary,
        address: String,
        port: Int,
        trail: List<String> = emptyList(),
        fingerprint: String = "",
        platform: String = "",
        coreVersion: String = "",
        trustText: String = "",
        /**
         * Заряд соседа из последнего сердцебиения.
         *
         * `null` означает «ничего не приходило». Показывать раньше, чем пришло
         * настоящее сообщение, нельзя: человек решил бы, что приложение знает
         * состояние чужого телефона, а оно его не знает.
         */
        peerBattery: Pair<Int, Boolean>? = null,
        /** Когда пришло сердцебиение, для подписи строки. */
        peerPulseAt: String = "",
        /**
         * Идут ли данные прямо сейчас.
         *
         * Отдельный признак от [peerPulseAt]: время прихода само по себе говорит
         * «когда было», а человеку нужно «живо ли сейчас». Точка это различие не
         * показывала — на девяти точках глазом не читается.
         */
        peerPulseFresh: Boolean = false,
    ): DeviceDetailState = DeviceDetailState(
        name = device.name,
        statusLine = when (device.presence) {
            Presence.Online -> "На связи"
            Presence.Nearby -> "Рядом, канал не установлен"
            Presence.Offline -> "Офлайн"
        },
        online = device.online,
        features = listOf(
            // Идентификатор плитки — это маршрут подэкрана, ровно такой же
            // строкой, какие использует `QuickAction` на главном.
            //
            // Раньше здесь стояли «clip» и «notif», а маршруты называются
            // «clipboard» и «notifs». Расхождение было коварным: две из четырёх
            // плиток совпадали с маршрутами, и нажатие на них работало, а две
            // молчали. Это вторая таблица соответствия, которая разъехалась с
            // первой при первом же переименовании.
            DeviceFeature("clipboard", "Буфер", IcwClipboard, false),
            DeviceFeature("files", "Файлы", IcwFolder, false),
            DeviceFeature("notifs", "Уведомления", IcwBell, false),
            DeviceFeature("mirror", "Зеркало", IcwMirror, false),
        ),
        groups = listOf(
            SubGroup(
                title = "О устройстве",
                rows = buildList {
                    // Идентификатор известен из объявления и постоянен — в отличие
                    // от платформы и заряда, которые придут только с кадрами.
                    // Идентификатор приходит целиком: 26 символов base32. Раньше он
                    // обрезался до 16 байт в структуре события и показывался
                    // шестнадцатеричным видом — получалось 64 символа мусора
                    // вместо идентификатора.
                    add(
                        SubRow(
                            "id",
                            "Идентификатор",
                            "",
                            RowEnd.Value(device.id),
                            smallIcon = IcwId,
                        )
                    )
                    add(
                        SubRow(
                            "address",
                            "Адрес",
                            "",
                            RowEnd.Value("$address:$port"),
                            smallIcon = IcwAddress,
                        )
                    )
                    // Платформа и версия приходят из объявления напарника.
                    //
                    // Обе приходят **без подписи**: сверить по ним нельзя,
                    // нужны они только строкой на карточке. Поэтому пустое
                    // значение — не поломка, а честное «не знаю», и
                    // подставлять «неизвестно» от имени ядра было бы враньём.
                    add(
                        SubRow(
                            "platform",
                            "Платформа",
                            "",
                            RowEnd.Value(platform.ifEmpty { "—" }),
                            smallIcon = IcwPhone,
                        )
                    )
                    // Тип устройства объявляет сам напарник: получатель знает из
                    // пакета только адрес и порт.
                    //
                    // Раньше тип был зашит на всё приложение, и карточка показывала
                    // «Ноут» для телефона. Теперь приходит настоящий, а у старого
                    // ядра — честное «неизвестно», а не ноутбук по умолчанию.
                    add(
                        SubRow(
                            "kind",
                            "Тип устройства",
                            if (device.kind == DeviceKind.Неизвестно) {
                                "сосед не сообщил"
                            } else {
                                ""
                            },
                            RowEnd.Value(названиеТипа(device.kind)),
                            smallIcon = IcwTablet,
                        )
                    )
                    // Заряд соседа — из сердцебиения, то есть из настоящего сообщения
                    // по каналу. Объявление о батарее не говорит ничего, а
                    // выдуманный процент человек принял бы за показание.
                    //
                    // Обе строки — только при соединении. У офлайн-устройства
                    // канала нет и показывать нечего: человек увидел бы пустое
                    // место там, где ждёт данных.
                    //
                    // ## Почему здесь нет колонки справа
                    //
                    // Сначала слово и время стояли в разных колонках: слово —
                    // справа, время — в подписи слева. Владелец увидел «пришло в »
                    // и ничего и решил, что время потерялось.
                    //
                    // Время не потерялось — его съела колонка. Когда подпись
                    // длинная, а справа стоит значение, правая колонка получает
                    // остаток ширины, и при крупном шрифте от неё остаётся ноль:
                    // значение ужимается в многоточие и пропадает. Текста
                    // становится больше, а видимого — меньше.
                    //
                    // Теперь всё в одной колонке, строкой под названием. Ничто не
                    // спорит за ширину, и при любом размере шрифта видно всё.
                    if (device.online) {
                        // Живость выражена **словами**, а не только точкой.
                        //
                        // Владелец проверял на двух устройствах — с включёнными
                        // и с отключёнными анимациями — и не увидел сердцебиения
                        // нигде. Точка в девять точек признаком быть не может:
                        // разница в размере и оттенке на таком маленьком пятне
                        // глазом не читается.
                        //
                        // Текст читается при любом шрифте, на любом экране и при
                        // выключенных анимациях.
                        val время = if (peerPulseAt.isEmpty()) {
                            "ожидается первый"
                        } else {
                            "пришло в $peerPulseAt"
                        }
                        add(
                            SubRow(
                                "peer_pulse",
                                "Пульс соседа",
                                "",
                                RowEnd.None,
                                smallIcon = IcwPulse,
                                multiline = listOf(
                                    if (peerPulseFresh) "данные идут" else "молчит",
                                    время,
                                ),
                            )
                        )
                        add(
                            SubRow(
                                "peer_battery",
                                "Заряд соседа",
                                "",
                                RowEnd.None,
                                smallIcon = IcwBattery,
                                multiline = listOf(
                                    if (peerBattery == null) {
                                        "не присылал"
                                    } else {
                                        "${peerBattery.first} %" +
                                            if (peerBattery.second) ", на зарядке" else ""
                                    },
                                    время,
                                ),
                            )
                        )
                    }
                    add(
                        SubRow(
                            "core",
                            "Версия ядра",
                            "",
                            RowEnd.Value(coreVersion.ifEmpty { "—" }),
                        )
                    )
                    // Отпечаток настоящий: ядро вывело его из ключа подписи и
                    // сверило с идентификатором, отбросив объявление при
                    // несовпадении. Сверять его можно — в отличие от имени.
                    //
                    // Пусто только у устройства с более старым ядром, которое
                    // отпечаток ещё не присылает.
                    add(
                        SubRow(
                            "fingerprint",
                            "Отпечаток ключа",
                            "",
                            RowEnd.Value(fingerprint.ifEmpty { "—" }),
                        )
                    )
                    // Состояние в подписи строки, а не значком: «Доверие» со
                    // шевроном и пустой подписью выглядит как готовая функция,
                    // которой нет. Теперь видно, что приложение думает.
                    add(SubRow("trust", "Доверие", trustText, RowEnd.Chevron))
                },
            ),
            // Ход подключения — не строка настроек, а лог шагов: каждая запись
            // длинная и самостоятельная. В строке настроек она обрезалась до
            // «соединение с», и прочитать её было невозможно — а читать её
            // ради того и сделано.
            //
            // Раньше здесь стояли две группы с одним заголовком: старая резала
            // записи, новая показывала целиком. Пользователь видел обе, и
            // выглядело это как два разных раздела об одном и том же.
            SubGroup(
                title = "Что происходит",
                rows = listOf(
                    SubRow(
                        "trail",
                        "Подключение",
                        "",
                        RowEnd.Chevron,
                        // Пусто вместо прочерка: шагов могло не быть вовсе, и
                        // прочерк выглядел бы как «состояние неизвестно».
                        multiline = trail,
                    ),
                ),
            ),
            SubGroup(
                title = "Управление",
                rows = listOf(
                    SubRow("rename", "Переименовать", "", RowEnd.Chevron),
                    SubRow("priority", "Приоритет передачи", "", RowEnd.Chevron),
                    SubRow("forget", "Забыть устройство", "", RowEnd.Chevron, danger = true),
                ),
            ),
        ),
    )
}
