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
@OptIn(androidx.compose.ui.graphics.ExperimentalPathApi::class)
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

val IcwBell: ImageVector by lazy { IcwBell }

val IcwHome: ImageVector by lazy {
    bwIcon("home", "M4 11l8-7 8 7v8a1 1 0 0 1-1 1h-4v-6H9v6H5a1 1 0 0 1-1-1z")
}

val IcwDevices: ImageVector by lazy {
    bwIcon("devices", "M3 6a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v9H3zM1 19h16M19 9h2a1 1 0 1 1 1 1v8a1 1 0 0 1-1 1h-2a1 1 0 0 1-1-1v-8a1 1 0 0 1 1-1z")
}

val IcwFamily: ImageVector by lazy {
    bwIcon("family", "M6 8a3 3 0 1 0 6 0a3 3 0 1 0-6 0M3 20c0-3.3 2.7-6 6-6s6 2.7 6 6M14.7 6.2a2.6 2.6 0 0 1 0 5.2M17 14.2c2.4.6 4 2.6 4 5.3")
}

val IcwSettings: ImageVector by lazy {
    bwIcon("settings", "M4 7h9M17 7h3M4 17h3M11 17h9M13 7a2 2 0 1 0 4 0a2 2 0 1 0-4 0M7 17a2 2 0 1 0 4 0a2 2 0 1 0-4 0")
}