package com.naua_braidwork.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_braidwork.app.ui.components.InfoBanner
import com.naua_braidwork.app.ui.components.RowDivider
import com.naua_braidwork.app.ui.components.SegmentedTabs
import com.naua_braidwork.app.ui.components.SubBody
import com.naua_braidwork.app.ui.components.SubGroupTitle
import com.naua_braidwork.app.ui.components.SubHeader
import com.naua_braidwork.app.ui.icons.IcwLockPad

/** Шаг «как подключить»: номер в кружке, заголовок и пояснение. */
private data class Step(val n: String, val title: String, val sub: String)

/**
 * Сопряжение по QR-коду по спецификации `design/spec/SPEC.txt`.
 *
 * Отличие от макета одно, и оно осознанное: сам QR-код не рисуется. Нарисовать
 * случайные квадраты — значит показать код, который невозможно распознать;
 * тестер попробует его отсканировать и решит, что приложение сломано. Рамка,
 * размеры и подписи те же, вместо кода — честная подпись.
 */
@Composable
fun PairScreen(
    mode: Int,
    onMode: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val steps = listOf(
        Step("1", "Открой Braidwork", "На новом устройстве"),
        Step("2", "Нажми «Присоединиться»", "Выбери вход по QR"),
        Step("3", "Отсканируй код", "Дождись подтверждения"),
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 12.dp),
    ) {
        SubHeader(
            title = "Добавить устройство",
            subtitle = "Сопряжение по QR-коду",
            onBack = onBack,
        )

        SubBody {
            SegmentedTabs(
                tabs = listOf("Показать код", "Сканировать"),
                selected = mode,
                onSelect = onMode,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (mode == 0) {
                        CodePlaceholder()
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "Код появится вместе с сопряжением",
                            fontSize = 16.sp,
                            lineHeight = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = "Механизма сопряжения ещё нет, показать пока нечего",
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        ScanPlaceholder()
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "Камера появится вместе с распознаванием",
                            fontSize = 16.sp,
                            lineHeight = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = "Рамка та же, что будет при съёмке",
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            SubGroupTitle(
                title = "Как подключить",
                modifier = Modifier.padding(start = 24.dp, top = 20.dp),
            )
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column {
                    steps.forEachIndexed { index, step ->
                        if (index > 0) RowDivider()
                        StepRow(step)
                    }
                }
            }

            InfoBanner(
                icon = IcwLockPad,
                text = "Ключи семьи выдаёт только доверенный участник. " +
                    "Новое устройство получает их после подтверждения.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            )
        }
    }
}

/**
 * Рамка будущего кода: 240, радиус 20, обводка 1.5.
 *
 * Толщина 1.5 задана явно: у `border` значение по умолчанию — 1 dp, и на белом
 * поле разница видна.
 */
@Composable
private fun CodePlaceholder() {
    Box(
        modifier = Modifier
            .size(240.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
            .border(1.5.dp, Color(0xFFE4E6EC), RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "QR",
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFDDDEE3),
        )
    }
}

/** Видоискатель: тёмный квадрат, белая рамка 168 и бирюзовая линия сканирования. */
@Composable
private fun ScanPlaceholder() {
    Box(
        modifier = Modifier
            .size(240.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF1A1D26)),
    ) {
        Box(
            modifier = Modifier
                .padding(start = 36.dp, top = 36.dp)
                .size(168.dp)
                .border(3.dp, Color.White, RoundedCornerShape(16.dp)),
        )
        Box(
            modifier = Modifier
                .padding(start = 36.dp, end = 36.dp, top = 119.dp)
                .fillMaxWidth()
                .height(2.dp)
                .background(Color(0xFF4FE3F2)),
        )
    }
}

@Composable
private fun StepRow(step: Step) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Минимум: шаг сопряжения несёт заголовок и подпись, оба
            // переносятся при крупном шрифте.
            .heightIn(min = 66.dp)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(Color(0xFFE4F1FF), RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = step.n,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1A6FDB),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = step.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = step.sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
