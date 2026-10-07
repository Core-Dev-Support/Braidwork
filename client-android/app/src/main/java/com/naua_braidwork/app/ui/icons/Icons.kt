package com.naua_braidwork.app.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Иконки из спецификации дизайна, пути перенесены без изменений.
 *
 * Сетка 24×24, обводка 1.8, скруглённые концы, без заливки — так задано в
 * `design/spec/SPEC.txt`. Заливка в иконке не используется нигде, поэтому цвет
 * задаётся через `tint` при отрисовке.
 *
 * Цвет внутри вектора чёрный: иначе `tint` не смог бы перекрасить обводку.
 */
// PathParser в этой версии Compose стабильный и OptIn не требует.
private fun bwIcon(
    name: String,
    path: String,
    strokeWidth: Float = 1.8f,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    addPath(
        pathData = PathParser().parsePathString(path).toNodes(),
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = strokeWidth,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
}.build()

val IcwBell: ImageVector by lazy {
    bwIcon("bell", "M6 17v-6a6 6 0 1 1 12 0v6l1.5 2h-15zM10 21h4")
}

val IcwPlus: ImageVector by lazy {
    bwIcon("plus", "M12 5v14M5 12h14")
}

val IcwWifi: ImageVector by lazy {
    bwIcon("wifi", "M3 9a14 14 0 0 1 18 0M6 12.5a9.5 9.5 0 0 1 12 0M9 16a5 5 0 0 1 6 0M11 19.5a1 1 0 1 0 2 0a1 1 0 1 0-2 0")
}

val IcwPhone: ImageVector by lazy {
    bwIcon("phone", "M8 3h8a1.5 1.5 0 0 1 1.5 1.5v15A1.5 1.5 0 0 1 16 21H8a1.5 1.5 0 0 1-1.5-1.5v-15A1.5 1.5 0 0 1 8 3zM11 18h2", 1.6f)
}

val IcwSync: ImageVector by lazy {
    bwIcon("sync", "M4 8h13l-3-3M20 16H7l3 3", 1.7f)
}

val IcwGlobe: ImageVector by lazy {
    bwIcon("globe", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0M3 12h18M12 3c3 3.5 3 14.5 0 18M12 3c-3 3.5-3 14.5 0 18", 1.7f)
}

val IcwBluetooth: ImageVector by lazy {
    bwIcon("bluetooth", "M7 7l10 10-5 4V3l5 4L7 17")
}

val IcwHotspot: ImageVector by lazy {
    bwIcon("hotspot", "M10 12a2 2 0 1 0 4 0a2 2 0 1 0-4 0M7.8 7.8a6 6 0 0 0 0 8.4M16.2 7.8a6 6 0 0 1 0 8.4M4.9 4.9a10 10 0 0 0 0 14.2M19.1 4.9a10 10 0 0 1 0 14.2")
}

val IcwClipboard: ImageVector by lazy {
    bwIcon("clipboard", "M8 4h8a2 2 0 0 1 2 2v13a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM9.5 4V3h5v1M9 11h6M9 15h6")
}

val IcwFolder: ImageVector by lazy {
    bwIcon("folder", "M4 6a1 1 0 0 1 1-1h5l2 2h7a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z")
}

val IcwMirror: ImageVector by lazy {
    bwIcon("mirror", "M5 4h14a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM8 20h8M12 17v3")
}

val IcwLaptop: ImageVector by lazy {
    bwIcon("laptop", "M6 5h12a2 2 0 0 1 2 2v9H4V7a2 2 0 0 1 2-2zM2 19h20")
}

val IcwTablet: ImageVector by lazy {
    bwIcon("tablet", "M6 3h12a1.5 1.5 0 0 1 1.5 1.5v15A1.5 1.5 0 0 1 18 21H6a1.5 1.5 0 0 1-1.5-1.5v-15A1.5 1.5 0 0 1 6 3zM11 18h2", 1.6f)
}

val IcwQr: ImageVector by lazy {
    bwIcon("qr", "M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h3v3M20 14v2M14 20h3M20 20v0")
}

val IcwLock: ImageVector by lazy {
    bwIcon("lock", "M7 11h10a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2zM8 11V8a4 4 0 0 1 8 0v3")
}

/** Три точки. Обводка толще остальных — так в спецификации. */
val IcwMore: ImageVector by lazy {
    bwIcon("more", "M5 12h.01M12 12h.01M19 12h.01", 3f)
}

val IcwUpload: ImageVector by lazy {
    bwIcon("upload", "M12 16V5M7 10l5-5 5 5M5 20h14", 2f)
}

val IcwChevron: ImageVector by lazy {
    bwIcon("chevron", "M9 6l6 6-6 6", 2f)
}

val IcwShield: ImageVector by lazy {
    bwIcon("shield", "M12 3l7 3v5c0 4.5-3 8-7 10-4-2-7-5.5-7-10V6zM9 12l2 2 4-4")
}

val IcwPower: ImageVector by lazy {
    bwIcon("power", "M12 3v9M7 6.5a7.5 7.5 0 1 0 10 0")
}

val IcwMoon: ImageVector by lazy {
    bwIcon("moon", "M20 14.5A8 8 0 0 1 9.5 4 8 8 0 1 0 20 14.5z")
}

val IcwCpu: ImageVector by lazy {
    bwIcon("cpu", "M8 7h8a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1zM10 3v4M14 3v4M10 17v4M14 17v4M3 10h4M3 14h4M17 10h4M17 14h4")
}

val IcwInfo: ImageVector by lazy {
    bwIcon("info", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0M12 11v6M12 7.5v.5", 2f)
}

/** Стрелка назад. Зеркало шеврона: вверх-вправо против вправо-вниз. */
val IcwBack: ImageVector by lazy {
    bwIcon("back", "M15 6l-6 6 6 6", 1.9f)
}

val IcwCheck: ImageVector by lazy {
    bwIcon("check", "M5 12l4 4 10-10", 3f)
}

val IcwWarn: ImageVector by lazy {
    bwIcon("warn", "M12 4l9 16H3zM12 10v4M12 17v.5")
}

/** Ползунки: две строки с ручками. Открывает настройки системы. */
val IcwSliders: ImageVector by lazy {
    bwIcon("sliders", "M4 7h9M17 7h3M4 17h3M11 17h9M13 7a2 2 0 1 0 4 0a2 2 0 1 0-4 0M7 17a2 2 0 1 0 4 0a2 2 0 1 0-4 0", 1.9f)
}

/** Замок с дугой: поясняющая плашка о ключах. */
val IcwLockPad: ImageVector by lazy {
    bwIcon("lockpad", "M7 11h10a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2zM8 11V8a4 4 0 0 1 8 0v3", 1.9f)
}

/** Текст: буква Т из трёх штрихов. Тип содержимого буфера. */
val IcwText: ImageVector by lazy {
    bwIcon("text", "M5 6h14M12 6v13M9 19h6")
}

/** Ссылка: две скрещённые дуги. Тип содержимого буфера. */
val IcwLink: ImageVector by lazy {
    bwIcon("link", "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1")
}

/** Изображение: рамка, горы и солнце. Тип содержимого буфера. */
val IcwImage: ImageVector by lazy {
    bwIcon("image", "M4 5h16a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zM3 16l5-5 4 4 3-3 6 6M15 9.5a1 1 0 1 0 2 0a1 1 0 1 0-2 0")
}

/** Облачко сообщения: мессенджеры. */
val IcwChat: ImageVector by lazy {
    bwIcon("chat", "M4 5h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H9l-5 4V6a1 1 0 0 1 1-1z")
}

/** Конверт: почта. */
val IcwMail: ImageVector by lazy {
    bwIcon("mail", "M4 6h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1zM3 7l9 7 9-7")
}

/** Банковская карта: приложения банков. */
val IcwCard: ImageVector by lazy {
    bwIcon("card", "M3 7a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2zM3 10h18M7 15h4")
}

/** Календарь: приложения календаря. */
val IcwCalendar: ImageVector by lazy {
    bwIcon("calendar", "M5 5h14a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zM4 10h16M8 3v4M16 3v4")
}

/** Запуск трансляции: треугольник. */
val IcwPlay: ImageVector by lazy {
    bwIcon("play", "M8 5l11 7-11 7z", 1.9f)
}

/** Остановка трансляции: квадрат. Заливка, а не обводка. */
val IcwStop: ImageVector by lazy {
    bwIcon("stop", "M7 7h10v10H7z", 2f)
}

/** Часы: проверка ещё не запускалась. */
val IcwClock: ImageVector by lazy {
    bwIcon("clock", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0M12 7v5l3 2")
}

/** Круговая стрелка: повторный запуск проверки. */
val IcwRefresh: ImageVector by lazy {
    bwIcon("refresh", "M20 11a8 8 0 0 0-14-4M4 4v4h4M4 13a8 8 0 0 0 14 4M20 20v-4h-4", 1.9f)
}

val IcwHome: ImageVector by lazy {
    bwIcon("home", "M4 11l8-7 8 7v8a1 1 0 0 1-1 1h-4v-6H9v6H5a1 1 0 0 1-1-1z")
}

val IcwDevices: ImageVector by lazy {
    bwIcon("devices", "M3 6a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v9H3zM1 19h16M19 9h2a1 1 0 1 1 1 1v8a1 1 0 0 1-1 1h-2a1 1 0 0 1-1-1v-8a1 1 0 0 1 1-1z")
}

val IcwFamily: ImageVector by lazy {
    bwIcon("family", "M6 8a3 3 0 1 0 6 0a3 3 0 1 0-6 0M3 20c0-3.3 2.7-6 6-6s6 2.7 6 6M14.7 6.2a2.6 2.6 0 0 1 0 5.2M17 14.2c2.4.6 4 2.6 4 5.3")
}

/**
 * Батарея. Добавлена по просьбе владельца: без знака строка «Заряд соседа» была
 * единственной в группе без иконки и читалась как обычная подпись настроек.
 *
 * Три деления внутри корпуса — не уровень заряда, а сам знак батареи. Рисовать
 * уровень было бы враньём: иконка одна, а значение подставляется текстом рядом,
 * и «три деления» при 12 % читалось бы как «заряжено».
 */
val IcwBattery: ImageVector by lazy {
    bwIcon("battery", "M2.5 7.5h15v9h-15zM20 10.5v3M5.5 10.5v3M8.5 10.5v3")
}

/** Идентификатор устройства: тот же ключ, что и при сопряжении по QR. */
val IcwId: ImageVector by lazy {
    bwIcon("id", "M3 5.5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v13a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2zM7 8.5h4M7 12h10M7 15.5h6")
}

val IcwPulse: ImageVector by lazy {
    bwIcon("pulse", "M3 12h3.5l2-6l3.5 12l2.5-6H21")
}

/**
 * Адрес в сети: метка на карте.
 *
 * Раньше был кружок с точкой и хвостом, и хвост читался как случайная палка —
 * владелец так и сказал: «у кружка лишняя палка». Метка узнаётся сразу и читается
 * как место, а не как прочая точка: на карточке рядом стоят ещё точки.
 */
val IcwAddress: ImageVector by lazy {
    bwIcon("address", "M12 22s7-6.3 7-12a7 7 0 1 0-14 0c0 5.7 7 12 7 12zM12 7.6a2.4 2.4 0 1 0 0 4.8a2.4 2.4 0 1 0 0-4.8")
}

val IcwSettings: ImageVector by lazy {
    bwIcon("settings", "M4 7h9M17 7h3M4 17h3M11 17h9M13 7a2 2 0 1 0 4 0a2 2 0 1 0-4 0M7 17a2 2 0 1 0 4 0a2 2 0 1 0-4 0")
}