package com.naua_braidwork.app.ui.state

import com.naua_braidwork.app.BraidworkCore
import androidx.compose.ui.graphics.Color
import com.naua_braidwork.app.ui.icons.IcwBell
import com.naua_braidwork.app.ui.icons.IcwCalendar
import com.naua_braidwork.app.ui.icons.IcwCard
import com.naua_braidwork.app.ui.icons.IcwChat
import com.naua_braidwork.app.ui.icons.IcwClipboard
import com.naua_braidwork.app.ui.icons.IcwCpu
import com.naua_braidwork.app.ui.icons.IcwFolder
import com.naua_braidwork.app.ui.icons.IcwInfo
import com.naua_braidwork.app.ui.icons.IcwMail
import com.naua_braidwork.app.ui.icons.IcwMirror
import com.naua_braidwork.app.ui.icons.IcwMoon
import com.naua_braidwork.app.ui.icons.IcwPhone
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
                    SubRow("ble", "BLE-прогрев", "Быстрое соединение вблизи", RowEnd.Toggle(false)),
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
    fun securityState(deviceId: String): SecurityState {
        val groups = listOf(
            SubGroup(
                title = "Ключи",
                rows = listOf(
                    SubRow("id", "Идентификатор устройства", "", RowEnd.Value(shortTag(deviceId))),
                    SubRow("fingerprint", "Отпечаток ключа", "", RowEnd.Chevron),
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
     * Буфер обмена.
     *
     * Раздел «Принимают буфер» и группа «Недавнее» пустые: ни одного
     * подключённого устройства и ни одной копии пока нет, а выдумывать
     * «Список покупок на неделю» на экране тестера нельзя.
     */
    fun clipboardGroups(): List<SubGroup> = listOf(
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
        "Доступ нужно подтвердить в системе" to
            "Служба чтения уведомлений ждёт разрешения"

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
     * Передачи и файлов нет, поэтому вместо прогресса и разбивки хранилища
     * показывается то же самое с нулями.
     */
    fun filesGroups(): List<SubGroup> = listOf(
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
                    "Мобильный интернет не тратится",
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
            rows = listOf(
                SubRow("phone", "Этот телефон", "", RowEnd.Value("MediaProjection")),
                SubRow("laptop", "Ноутбук Linux", "", RowEnd.Value("PipeWire")),
                SubRow("backup", "Резервный ПК Linux", "", RowEnd.Value("XShm (X11)")),
            ),
        ),
    )

    /** Внешний вид: три группы выбора и производительность. */
    fun appearanceState(theme: String, lang: String, accent: String) = AppearanceState(
        theme = theme,
        lang = lang,
        accent = accent,
        themes = listOf("Как в системе", "Светлая", "Тёмная"),
        langs = listOf("Русский", "English"),
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
}