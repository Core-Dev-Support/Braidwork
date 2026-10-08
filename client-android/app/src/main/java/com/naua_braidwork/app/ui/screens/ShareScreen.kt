package com.naua_braidwork.app.ui.screens

import com.naua_braidwork.app.ui.components.tapNoRipple
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_braidwork.app.ui.icons.IcwBell
import com.naua_braidwork.app.ui.icons.IcwChevron
import com.naua_braidwork.app.ui.icons.IcwClipboard
import com.naua_braidwork.app.ui.icons.IcwFolder
import com.naua_braidwork.app.ui.icons.IcwPlus
import com.naua_braidwork.app.ui.icons.IcwUpload

/**
 * Обмен по спецификации `design/spec/SPEC.txt`.
 *
 * Экран тёмный в обеих темах: так задано в макете, фон `#000000`.
 * Три кольца активности рисуются на Canvas из тех же радиусов и дуг.
 */
@Composable
fun ShareScreen(
    onQuickAction: (String) -> Unit,
    onAddDevice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val orange = Color(0xFFFF6A1F)
    val green = Color(0xFF9BEA3C)
    val cyan = Color(0xFF4FE3F2)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = "Обмен",
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
            )
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.Transparent, RoundedCornerShape(22.dp))
                    .tapNoRipple(onClick = onAddDevice),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = IcwPlus,
                    contentDescription = "Добавить устройство",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(250.dp),
            contentAlignment = Alignment.Center,
        ) {
            ActivityRings(colors = listOf(orange, green, cyan))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Сегодня",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = Color(0xFFB4B7C0),
                )
                Text(
                    text = "0 МБ",
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            shape = RoundedCornerShape(26.dp),
            color = Color(0xFF1C1C1F),
        ) {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    StatCell("Буфер", "0", "записей", IcwClipboard, orange, Modifier.weight(1f)) {
                        onQuickAction("clipboard")
                    }
                    StatCell("Файлы", "0", "штук", IcwFolder, green, Modifier.weight(1f)) {
                        onQuickAction("files")
                    }
                    StatCell("Уведом.", "0", "пересылок", IcwBell, cyan, Modifier.weight(1f)) {
                        onQuickAction("notifs")
                    }
                }

                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color(0xFF34343A)),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Минимум: строка файла несёт имя и размер, оба
                        // переносятся при крупном шрифте.
                        .heightIn(min = 52.dp)
                        .tapNoRipple { },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = IcwUpload,
                            contentDescription = null,
                            tint = cyan,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Очередь отправки (0)",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                        )
                    }
                    Icon(
                        imageVector = IcwChevron,
                        contentDescription = null,
                        tint = Color(0xFFA9ACB6),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * Три кольца активности.
 *
 * Радиусы 108, 82 и 56 — из макета. Ширина дуги 22. Пока обмена нет, кольца
 * нарисованы только подложкой: полная дуга означала бы activity, которого
 * не было. Сама дуга появится вместе с транспортом.
 */
@Composable
private fun ActivityRings(
    colors: List<Color>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val scale = size.minDimension / 250f
        val stroke = 22f * scale
        val radii = listOf(108f, 82f, 56f).map { it * scale }

        radii.forEachIndexed { index, radius ->
            val color = colors.getOrElse(index) { Color.Gray }
            drawArc(
                color = color.copy(alpha = 0.18f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/** Ячейка с числом: подпись, крупное значение и единица измерения. */
@Composable
private fun StatCell(
    label: String,
    value: String,
    unit: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier.tapNoRipple(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = value,
            fontSize = 38.sp,
            lineHeight = 44.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
        Text(
            text = unit,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = Color(0xFFA9ACB6),
        )
    }
}