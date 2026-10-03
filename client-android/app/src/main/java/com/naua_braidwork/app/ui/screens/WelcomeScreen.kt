package com.naua_braidwork.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_braidwork.app.ui.icons.IcwLaptop
import com.naua_braidwork.app.ui.icons.IcwLock
import com.naua_braidwork.app.ui.icons.IcwMirror
import com.naua_braidwork.app.ui.icons.IcwPhone
import com.naua_braidwork.app.ui.icons.IcwQr
import com.naua_braidwork.app.ui.icons.IcwSync
import com.naua_braidwork.app.ui.icons.IcwTablet

/**
 * Приветствие по спецификации `Welcome.dc.html`.
 *
 * Иллюстрация нарисована на Canvas из тех же координат, что и SVG в макете:
 * четыре устройства вокруг центра, соединённые пунктирными линиями.
 * Перерисовывать её картинкой нельзя — она должна менять цвет вместе с темой.
 */
@Composable
fun WelcomeScreen(
    onCreateFamily: () -> Unit,
    onJoinByQr: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bw = com.naua_braidwork.app.ui.theme.LocalBwColors.current
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = IcwSync,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Braidwork",
                fontSize = 20.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        Spacer(Modifier.height(16.dp))
        FamilyIllustration(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
            lineColor = if (isDark) Color(0xFF3A4A63) else Color(0xFF9CC4F5),
            ringInner = if (isDark) Color(0xFF2F3746) else Color(0xFFC5DBF7),
            ringOuter = if (isDark) Color(0xFF28303D) else Color(0xFFD5E4F8),
            nodeFill = MaterialTheme.colorScheme.surface,
            nodeStroke = if (isDark) Color(0xFF39404C) else Color(0xFFDCE6F4),
            centerFill = MaterialTheme.colorScheme.primary,
            nodeGlyph = if (isDark) Color(0xFF8FB8F5) else Color(0xFF1A6FDB),
        )

        Spacer(Modifier.height(16.dp))
        Text(
            text = "Семья на связи без облака",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Буфер обмена, файлы, уведомления и экран идут напрямую " +
                "между вашими устройствами.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(28.dp))
        PrimaryButton(text = "Создать семью", onClick = onCreateFamily)
        Spacer(Modifier.height(12.dp))
        SecondaryButton(text = "Присоединиться по QR", icon = IcwQr, onClick = onJoinByQr)

        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = IcwLock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Шифрование Noise XX. Данные не проходят через сервер.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Иллюстрация приветствия.
 *
 * Координаты взяты из SVG макета и масштабируются под ширину, поэтому на
 * любом экране пропорции сохраняются.
 */
@Composable
private fun FamilyIllustration(
    modifier: Modifier,
    lineColor: Color,
    ringInner: Color,
    ringOuter: Color,
    nodeFill: Color,
    nodeStroke: Color,
    centerFill: Color,
    nodeGlyph: Color,
) {
    Canvas(modifier = modifier) {
        // Исходная сетка макета: 342×300.
        val scale = size.width / 342f
        fun p(x: Float, y: Float) = Offset(x * scale, y * scale)
        fun r(v: Float) = v * scale

        val center = p(171f, 150f)
        val nodes = listOf(
            p(50f, 60f) to IcwPhone,
            p(292f, 60f) to IcwLaptop,
            p(50f, 240f) to IcwTablet,
            p(292f, 240f) to IcwMirror,
        )

        val dash = PathEffect.dashPathEffect(floatArrayOf(r(4f), r(6f)))

        drawCircle(
            color = ringInner,
            radius = r(92f),
            center = center,
            style = Stroke(width = r(1.5f), pathEffect = dash),
        )
        drawCircle(
            color = ringOuter,
            radius = r(136f),
            center = center,
            style = Stroke(width = r(1.5f), pathEffect = dash),
        )

        val linkDash = PathEffect.dashPathEffect(floatArrayOf(r(3f), r(6f)))
        nodes.forEach { (node, _) ->
            drawLine(
                color = lineColor,
                start = center,
                end = node,
                strokeWidth = r(2f),
                pathEffect = linkDash,
            )
        }

        nodes.forEach { (node, _) ->
            drawCircle(color = nodeFill, radius = r(34f), center = node)
            drawCircle(
                color = nodeStroke,
                radius = r(34f),
                center = node,
                style = Stroke(width = r(1.5f)),
            )
        }

        drawCircle(color = centerFill, radius = r(46f), center = center)
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = text, fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SecondaryButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onClick),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(text = text, fontSize = 17.sp)
        }
    }
}