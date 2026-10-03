package com.naua_braidwork.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.naua_braidwork.app.R

/**
 * Цвета из `design/tokens.json`.
 *
 * Значения не подбираются здесь: они перенесены из общего файла токенов, чтобы
 * Android и десктопный клиент выглядели одинаково. Менять палитру нужно там,
 * а не здесь.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF3B6FF6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE4EBFD),
    onPrimaryContainer = Color(0xFF12151C),
    secondary = Color(0xFF565E70),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF4F5F8),
    onBackground = Color(0xFF12151C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF12151C),
    surfaceVariant = Color(0xFFEDEFF3),
    onSurfaceVariant = Color(0xFF565E70),
    outline = Color(0xFFDFE3EB),
    outlineVariant = Color(0xFFE4E7EE),
    error = Color(0xFFD03B3B),
    onError = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5B87F8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF1E2A47),
    onPrimaryContainer = Color(0xFFEDEFF5),
    secondary = Color(0xFFA6ADBD),
    onSecondary = Color(0xFF12141A),
    background = Color(0xFF12141A),
    onBackground = Color(0xFFEDEFF5),
    surface = Color(0xFF1B1E26),
    onSurface = Color(0xFFEDEFF5),
    surfaceVariant = Color(0xFF232733),
    onSurfaceVariant = Color(0xFFA6ADBD),
    outline = Color(0xFF2E3340),
    outlineVariant = Color(0xFF21252E),
    error = Color(0xFFF0625E),
    onError = Color(0xFF12141A),
)

/**
 * Состояния системы.
 *
 * Отдельная сущность от палитры Material: здесь нет «успеха», есть
 * «устройство на связи», «нужно проверить» и «недоступно на этом устройстве».
 */
data class StatusColors(
    val online: Color,
    val onlineSubtle: Color,
    val warning: Color,
    val warningSubtle: Color,
    val danger: Color,
    val dangerSubtle: Color,
    val idle: Color,
    val idleSubtle: Color,
    val info: Color,
    val infoSubtle: Color,
    /** Плашка, тёмная в обеих темах: обозначает действие или проверку. */
    val chip: Color,
    val onChip: Color,
)

private val LightStatus = StatusColors(
    online = Color(0xFF1F9D55),
    onlineSubtle = Color(0xFFE2F4EA),
    warning = Color(0xFFC77700),
    warningSubtle = Color(0xFFFCF0DC),
    danger = Color(0xFFD03B3B),
    dangerSubtle = Color(0xFFFCE8E8),
    idle = Color(0xFF858DA0),
    idleSubtle = Color(0xFFEDEFF3),
    info = Color(0xFF3B6FF6),
    infoSubtle = Color(0xFFE4EBFD),
    chip = Color(0xFF22262F),
    onChip = Color(0xFFFFFFFF),
)

private val DarkStatus = StatusColors(
    online = Color(0xFF3DD68C),
    onlineSubtle = Color(0xFF13291F),
    warning = Color(0xFFE3A43A),
    warningSubtle = Color(0xFF2E2415),
    danger = Color(0xFFF0625E),
    dangerSubtle = Color(0xFF2F1919),
    idle = Color(0xFF727A8B),
    idleSubtle = Color(0xFF21252E),
    info = Color(0xFF5B87F8),
    infoSubtle = Color(0xFF1A2440),
    chip = Color(0xFF2A2F3B),
    onChip = Color(0xFFEDEFF5),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

/** Типографика из токенов: одиннадцать размеров с заданной высотой строки. */
private val BraidTypography = Typography(
    displaySmall = TextStyle(
        fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold,
    ),
    headlineLarge = TextStyle(
        fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold,
    ),
    headlineMedium = TextStyle(
        fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
    ),
    titleLarge = TextStyle(
        fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal,
    ),
    bodySmall = TextStyle(
        fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Normal,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium,
    ),
)

/**
 * Тема приложения.
 *
 * По умолчанию следует за системой, но пользователь может зафиксировать схему.
 * Системная тема читается здесь, а не в разных экранах, иначе на части
 * устройств фон и текст разойдутся при первом же изменении настроек.
 */
@Composable
fun BraidworkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val status = if (darkTheme) DarkStatus else LightStatus

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

    CompositionLocalProvider(LocalStatusColors provides status) {
        MaterialTheme(
            colorScheme = colors,
            typography = BraidTypography,
            content = content,
        )
    }
}