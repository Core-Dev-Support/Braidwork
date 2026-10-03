package com.naua_braidwork.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.naua_braidwork.app.ui.state.Tone
import com.naua_braidwork.app.ui.theme.LocalBwColors

/**
 * Карточка.
 *
 * Радиус 24, на главных экранах 28 — как в спецификации. Обводка не рисуется:
 * карточка задаётся цветом поверхности, а от темы и так зависит.
 */
@Composable
fun BwCard(
    modifier: Modifier = Modifier,
    radius: Dp = 24.dp,
    paddingHorizontal: Dp = 16.dp,
    paddingVertical: Dp = 20.dp,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(radius),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(modifier = Modifier.padding(paddingHorizontal, paddingVertical)) {
            content()
        }
    }
}

/**
 * Цветная плашка под иконку.
 *
 * Пара «подложка и цвет» берётся из темы по тону, а не задаётся на месте:
 * так плашки одинаковы на всех экранах.
 */
@Composable
fun toneColors(tone: Tone): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> {
    val bw = LocalBwColors.current
    return when (tone) {
        Tone.Blue -> bw.blueSubtle to MaterialTheme.colorScheme.primary
        Tone.Green -> bw.greenSubtle to bw.online
        Tone.Orange -> bw.orangeSubtle to bw.warning
        Tone.Purple -> bw.purpleSubtle to MaterialTheme.colorScheme.tertiary
        Tone.Neutral -> MaterialTheme.colorScheme.surfaceVariant to
            MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/**
 * Плашка под иконку: круглая или скруглённая.
 *
 * Форма передаётся параметром, потому что в спецификации круглые 52 и 68
 * используются в быстрых действиях и режимах связи, а скруглённые 40×12 —
 * в строках списков.
 */
@Composable
fun IconTile(
    icon: ImageVector,
    tone: Tone,
    size: Dp,
    radius: Dp,
    strokeWidth: Float = 1.8f,
    modifier: Modifier = Modifier,
) {
    val (background, foreground) = toneColors(tone)
    val shape: Shape = RoundedCornerShape(radius)
    Box(
        modifier = modifier
            .size(size)
            .background(background, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = foreground,
            modifier = Modifier.size(size * 0.52f),
        )
    }
}

/**
 * Плашка состояния.
 *
 * Тёмная в светлой теме: обозначает то, что надо проверить, и потому не должна
 * сливаться с фоном.
 */
@Composable
fun StatusChip(
    text: String,
    background: androidx.compose.compose.ui.graphics.Color,
    foreground: androidx.compose.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = background,
        contentColor = foreground,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** Заголовок раздела вне карточки. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier,
    )
}

/**
 * Пустое состояние списка.
 *
 * Показывается вместо выдуманных данных: экран, который показывают тестерам,
 * не должен выглядеть работающим лучше, чем он работает.
 */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Разделительная линия в списках. */
@Composable
fun RowDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/** Пустое место между блоками: 20 dp из спецификации. */
@Composable
fun Gap(height: Dp = 20.dp) {
    Spacer(Modifier.height(height))
}

/** Горизонтальный ряд с равномерным распределением. */
@Composable
fun EvenRow(
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}