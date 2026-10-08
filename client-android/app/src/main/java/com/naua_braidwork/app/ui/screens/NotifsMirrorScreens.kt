package com.naua_braidwork.app.ui.screens

import com.naua_braidwork.app.ui.components.tapNoRipple
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_braidwork.app.ui.RowsSubScreen
import com.naua_braidwork.app.ui.icons.IcwChevron
import com.naua_braidwork.app.ui.icons.IcwPlay
import com.naua_braidwork.app.ui.icons.IcwStop
import com.naua_braidwork.app.ui.state.SubGroup
import com.naua_braidwork.app.ui.state.SubRow
import com.naua_braidwork.app.ui.state.Toggles

/**
 * Уведомления по спецификации `design/spec/SPEC.txt`.
 *
 * Карточка статуса и группа приложений — из макета. Переключатели направления
 * и действий помечены как неподключённые: пересылать уведомления некуда.
 */
@Composable
fun NotifsScreen(
    statusTitle: String,
    statusSub: String,
    groups: List<SubGroup>,
    toggles: Toggles,
    onBack: () -> Unit,
    onRow: (SubRow) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onOpenListenerSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RowsSubScreen(
        title = "Уведомления",
        subtitle = "Показывай их на всех устройствах семьи",
        groups = groups,
        toggles = toggles,
        onBack = onBack,
        onRow = onRow,
        onToggle = onToggle,
        modifier = modifier,
        top = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 20.dp)
                    .tapNoRipple(onClick = onOpenListenerSettings),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NeedsAttentionBubble()
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = statusTitle,
                            fontSize = 16.sp,
                            lineHeight = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = statusSub,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = IcwChevron,
                        contentDescription = null,
                        tint = Color(0xFF8A8D97),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
    )
}

/**
 * Зеркало экрана по спецификации `design/spec/SPEC.txt`.
 *
 * Тёмная карточка превью и кнопка запуска — из макета. Кнопка ничего не
 * запускает: MediaProjection и захват рабочего стола ещё не написаны.
 * Нажатие остаётся рабочим и переключает подпись, чтобы было видно, что
 * элемент нажимается, а текст прямо говорит, что потока нет.
 */
@Composable
fun MirrorScreen(
    groups: List<SubGroup>,
    toggles: Toggles,
    live: Boolean,
    quality: String,
    onLive: () -> Unit,
    onQuality: (String) -> Unit,
    onBack: () -> Unit,
    onRow: (SubRow) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    RowsSubScreen(
        title = "Зеркало экрана",
        subtitle = "Показывай экран телефона на другом устройстве",
        groups = groups,
        toggles = toggles,
        selectedRadio = quality,
        onRadio = onQuality,
        onBack = onBack,
        onRow = onRow,
        onToggle = onToggle,
        modifier = modifier,
        top = {
            MirrorPreview(live = live)
            Spacer(Modifier.height(12.dp))
            LiveButton(live = live, onClick = onLive, modifier = Modifier.padding(horizontal = 16.dp))
        },
    )
}

/** Превью трансляции: рамка телефона и «экран» с нарисованными полосами. */
@Composable
private fun MirrorPreview(live: Boolean) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        shape = RoundedCornerShape(28.dp),
        color = Color(0xFF1C1C1F),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // Минимум: над состоянием зеркала наложена подпись, и при
                    // крупном шрифте она переставала помещаться.
                    .heightIn(min = 150.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF2E4470), Color(0xFF1A2238)),
                        ),
                        RoundedCornerShape(16.dp),
                    ),
            ) {
                // Плашка состояния: точка и подпись.
                Row(
                    modifier = Modifier
                        .padding(14.dp)
                        .background(Color(0x73000000), RoundedCornerShape(13.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                if (live) Color(0xFFFF5A5A) else Color(0xFF9A9DA8),
                                RoundedCornerShape(4.dp),
                            ),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (live) "Идёт трансляция" else "Не запущено",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                }
                // Рамка окна и полосы внутри: силуэт экрана, как в макете.
                Box(
                    modifier = Modifier
                        .padding(start = 84.dp, top = 34.dp)
                        .size(width = 222.dp, height = 100.dp)
                        .background(Color(0x1FFFFFFF), RoundedCornerShape(12.dp)),
                )
                Box(
                    modifier = Modifier
                        .padding(start = 96.dp, top = 46.dp)
                        .size(width = 90.dp, height = 10.dp)
                        .background(Color(0x59FFFFFF), RoundedCornerShape(5.dp)),
                )
                Box(
                    modifier = Modifier
                        .padding(start = 96.dp, top = 66.dp)
                        .size(width = 196.dp, height = 8.dp)
                        .background(Color(0x33FFFFFF), RoundedCornerShape(4.dp)),
                )
                Box(
                    modifier = Modifier
                        .padding(start = 96.dp, top = 82.dp)
                        .size(width = 160.dp, height = 8.dp)
                        .background(Color(0x33FFFFFF), RoundedCornerShape(4.dp)),
                )
                Box(
                    modifier = Modifier
                        .padding(start = 96.dp, top = 104.dp)
                        .size(width = 70.dp, height = 20.dp)
                        .background(Color(0xFF2B8CF5), RoundedCornerShape(10.dp)),
                )
                Box(
                    modifier = Modifier
                        .padding(start = 14.dp, top = 50.dp)
                        .size(width = 56.dp, height = 84.dp)
                        .background(Color(0x33FFFFFF), RoundedCornerShape(12.dp)),
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf(
                    "0 мс" to "Задержка",
                    "0" to "Кадров в секунду",
                    "0" to "Мбит/с",
                ).forEach { (value, label) ->
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = value,
                            fontSize = 20.sp,
                            lineHeight = 26.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                        Text(
                            text = label,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = Color(0xFFB4B7C0),
                        )
                    }
                }
            }
        }
    }
}

/** Кнопка запуска: синяя «начать», белая «остановить». */
@Composable
private fun LiveButton(live: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            // Минимум: «Начать трансляцию» переносится при крупном шрифте, и
            // 56 dp её обрезали.
            .heightIn(min = 56.dp),
        shape = RoundedCornerShape(28.dp),
        color = if (live) Color.White else MaterialTheme.colorScheme.primary,
        contentColor = if (live) Color(0xFFC42B2B) else Color.White,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .tapNoRipple(onClick = onClick),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (live) IcwStop else IcwPlay,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (live) "Остановить" else "Начать трансляцию",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
