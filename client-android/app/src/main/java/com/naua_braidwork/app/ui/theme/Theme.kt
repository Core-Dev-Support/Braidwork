package com.naua_braidwork.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Палитра и размеры из `design/tokens.json`, а тот в свою очередь взят из
 * спецификации дизайна. Здесь значения не придумываются: меняется файл токенов,
 * а не этот код.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF1A6FDB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE4F1FF),
    onPrimaryContainer = Color(0xFF121316),
    background = Color(0xFFF1F2F6),
    onBackground = Color(0xFF121316),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF121316),
    surfaceVariant = Color(0xFFEEEEF1),
    onSurfaceVariant = Color(0xFF666A75),
    outline = Color(0xFFF0F0F3),
    outlineVariant = Color(0xFFE8E9EE),
    error = Color(0xFFC42B2B),
    onError = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5B9BF5),
    onPrimary = Color(0xFF0B0E13),
    primaryContainer = Color(0xFF1B2C45),
    onPrimaryContainer = Color(0xFFEDEFF5),
    background = Color(0xFF1B1716),
    onBackground = Color(0xFFEDEFF5),
    surface = Color(0xFF252A33),
    onSurface = Color(0xFFEDEFF5),
    surfaceVariant = Color(0xFF313743),
    onSurfaceVariant = Color(0xFFA8AEBC),
    outline = Color(0xFF3A4049),
    outlineVariant = Color(0xFF2E3340),
    error = Color(0xFFF0625E),
    onError = Color(0xFF0B0E13),
)

/**
 * Цвета состояний и цветные плашки.
 *
 * Отдельная сущность от палитры Material: здесь нет «успеха», есть конкретные
 * состояния устройства и четыре пары плашек из спецификации.
 */
data class BwColors(
    val appTop: Color,
    val appBottom: Color,
    val online: Color,
    val onlineSubtle: Color,
    val warning: Color,
    val warningSubtle: Color,
    val orangeSubtle: Color,
    val greenSubtle: Color,
    val purpleSubtle: Color,
    val blueSubtle: Color,
    val chip: Color,
    val onChip: Color,
    /** Фон кнопки-режима в невыбранном состоянии. */
    val modeIdle: Color,
    val active: Color,
)

private val LightBw = BwColors(
    appTop = Color(0xFFE3EAF4),
    appBottom = Color(0xFFF1F2F6),
    online = Color(0xFF16804A),
    onlineSubtle = Color(0xFFE8F6EE),
    warning = Color(0xFFC65D0E),
    warningSubtle = Color(0xFFFFF1E6),
    orangeSubtle = Color(0xFFFFF1E6),
    greenSubtle = Color(0xFFE8F6EE),
    purpleSubtle = Color(0xFFEFEAFB),
    blueSubtle = Color(0xFFE4F1FF),
    chip = Color(0xFF23262D),
    onChip = Color(0xFFFFFFFF),
    modeIdle = Color(0xFFEDEDF0),
    active = Color(0xFF2B8CF5),
)

private val DarkBw = BwColors(
    appTop = Color(0xFF3B4252),
    appBottom = Color(0xFF1B1716),
    online = Color(0xFF3DD68C),
    onlineSubtle = Color(0xFF13291F),
    warning = Color(0xFFE3A43A),
    warningSubtle = Color(0xFF2E2415),
    orangeSubtle = Color(0xFF33230F),
    greenSubtle = Color(0xFF14261B),
    purpleSubtle = Color(0xFF241E3A),
    blueSubtle = Color(0xFF16243A),
    chip = Color(0xFF313743),
    onChip = Color(0xFFEDEFF5),
    modeIdle = Color(0xFF2E3340),
    active = Color(0xFF5B9BF5),
)

val LocalBwColors = staticCompositionLocalOf { LightBw }

/** Градиент фона приложения: в каждой теме свой, в тёмной он сильно темнее. */
val LocalAppGradient = staticCompositionLocalOf<Brush> {
    Brush.verticalGradient(listOf(LightBw.appTop, LightBw.appBottom))
}

/** Типографика из спецификации. Высота строки задана явно. */
private val BwTypography = Typography(
    displaySmall = TextStyle(
        fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold,
    ),
    titleLarge = TextStyle(
        fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal,
    ),
    bodySmall = TextStyle(
        fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal,
    ),
    labelMedium = TextStyle(
        fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold,
    ),
)

/**
 * Тема приложения.
 *
 * Следует за системой по умолчанию. Пользователь сможет зафиксировать схему
 * на экране «Внешний вид», поэтому чтение темы живёт здесь, а не на каждом
 * экране отдельно: иначе при смене настройки фон и текст разойдутся.
 */
@Composable
fun BraidworkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val bw = if (darkTheme) DarkBw else LightBw
    val gradient = Brush.verticalGradient(listOf(bw.appTop, bw.appBottom))

    val view = LocalView.current
    if (!view.isInEditMode) {
        val context = LocalContext.current
        SideEffect {
            (context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view)
                    .isAppearanceLightStatusBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalBwColors provides bw,
        LocalAppGradient provides gradient,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = BwTypography,
            content = content,
        )
    }
}