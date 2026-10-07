package com.naua_braidwork.app.ui.screens

import com.naua_braidwork.app.ui.components.tapNoRipple
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_braidwork.app.ui.components.BackButton
import com.naua_braidwork.app.ui.components.IconTile
import com.naua_braidwork.app.ui.components.PillButton
import com.naua_braidwork.app.ui.components.PrimaryButton
import com.naua_braidwork.app.ui.components.RowDivider
import com.naua_braidwork.app.ui.components.SubBody
import com.naua_braidwork.app.ui.components.SubGroupCard
import com.naua_braidwork.app.ui.components.SubGroupTitle
import com.naua_braidwork.app.ui.components.SubHeader
import com.naua_braidwork.app.ui.icons.IcwCheck
import com.naua_braidwork.app.ui.icons.IcwChevron
import com.naua_braidwork.app.ui.icons.IcwClock
import com.naua_braidwork.app.ui.icons.IcwCpu
import com.naua_braidwork.app.ui.icons.IcwMirror
import com.naua_braidwork.app.ui.icons.IcwMore
import com.naua_braidwork.app.ui.icons.IcwPlay
import com.naua_braidwork.app.ui.icons.IcwRefresh
import com.naua_braidwork.app.ui.icons.IcwSync
import com.naua_braidwork.app.ui.icons.IcwClipboard
import com.naua_braidwork.app.ui.state.AboutState
import com.naua_braidwork.app.ui.state.CoreState
import com.naua_braidwork.app.ui.state.CoreTest
import com.naua_braidwork.app.ui.state.DeviceDetailState
import com.naua_braidwork.app.ui.state.DeviceFeature
import com.naua_braidwork.app.ui.state.RoadmapStep
import com.naua_braidwork.app.TrustStore
import com.naua_braidwork.app.ui.state.RowEnd
import com.naua_braidwork.app.ui.state.SubRow
import com.naua_braidwork.app.ui.state.Toggles
import com.naua_braidwork.app.ui.state.Tone
import com.naua_braidwork.app.ui.state.withToggleValues
import com.naua_braidwork.app.ui.theme.LocalBwColors

/**
 * Ядро и диагностика по спецификации `design/spec/SPEC.txt`.
 *
 * Сведения о сборке настоящие: они выведены из того, что CI действительно
 * собирает и проверяет. Кнопка запуска проверки двух устройств ничего не
 * запускает — такого теста в приложении ещё нет, и подпись об этом говорит.
 */
@Composable
fun CoreScreen(
    state: CoreState,
    toggles: Toggles,
    ran: Boolean,
    onRun: () -> Unit,
    onBack: () -> Unit,
    onRow: (SubRow) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 12.dp),
    ) {
        SubHeader(
            title = "Ядро и диагностика",
            subtitle = "Rust-ядро под приложением",
            onBack = onBack,
        )

        SubBody {
            CoreStatusCard(
                state = state,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            )

            state.buildGroups.forEach { group ->
                SubGroupTitle(
                    title = group.title,
                    modifier = Modifier.padding(start = 24.dp, top = 20.dp),
                )
                SubGroupCard(
                    rows = group.rows,
                    onRow = onRow,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            SubGroupTitle(
                title = "Тест двух устройств",
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
                    state.tests.forEachIndexed { index, test ->
                        if (index > 0) RowDivider()
                        TestRow(test)
                    }
                }
            }

            PrimaryButton(
                text = if (ran) "Запустить тест снова" else "Запустить тест",
                icon = if (ran) IcwRefresh else IcwPlay,
                onClick = onRun,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            )

            state.logGroups.forEach { group ->
                SubGroupTitle(
                    title = group.title,
                    modifier = Modifier.padding(start = 24.dp, top = 0.dp),
                )
                SubGroupCard(
                    rows = group.rows.withToggleValues(toggles),
                    onRow = { row ->
                        val end = row.end
                        if (end is RowEnd.Toggle) {
                            onToggle(row.id, !end.checked)
                        } else {
                            onRow(row)
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun CoreStatusCard(state: CoreState, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(icon = IcwCpu, tone = Tone.Purple, size = 52.dp, radius = 16.dp)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Ядро работает",
                    fontSize = 17.sp,
                    lineHeight = 23.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Ключи, идентификатор и Noise XX уже на устройстве",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StepChip(state.step)
        }
    }
}

/** Плашка шага: высота 26, радиус 13. */
@Composable
internal fun StepChip(text: String) {
    val bw = LocalBwColors.current
    Surface(shape = RoundedCornerShape(13.dp), color = bw.infoBg) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = bw.infoFg,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** Строка проверки: плашка с галочкой или часами и результат. */
@Composable
private fun TestRow(test: CoreTest) {
    val bw = LocalBwColors.current
    val passed = test.passed == true
    val background = if (passed) bw.greenSubtle else MaterialTheme.colorScheme.surfaceVariant
    val foreground = if (passed) bw.online else Color(0xFF4A4D57)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(66.dp)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(background, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (passed) IcwCheck else IcwClock,
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = test.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // `null` — тест не запускался. Это не то же самое, что провал,
            // и не то же самое, что успех, поэтому подпись отдельная.
            Text(
                text = when (test.passed) {
                    true -> "Успешно"
                    false -> "Не пройден"
                    null -> "Ожидает запуска"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** О приложении по спецификации `design/spec/SPEC.txt`. */
@Composable
fun AboutScreen(
    state: AboutState,
    onBack: () -> Unit,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 12.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        SubHeader(title = "О приложении", subtitle = "", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .background(Color(0xFF2B8CF5), RoundedCornerShape(26.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = IcwSync,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(52.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Braidwork",
                fontSize = 24.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = state.appVersion,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SubGroupTitle(
            title = "Платформы",
            modifier = Modifier.padding(start = 24.dp),
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column {
                state.platforms.forEachIndexed { index, platform ->
                    if (index > 0) RowDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconTile(
                            icon = platform.icon,
                            tone = platform.tone,
                            size = 40.dp,
                            radius = 12.dp,
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = platform.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = platform.sub,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        StepChip(platform.step)
                    }
                }
            }
        }

        SubGroupTitle(
            title = "Проект",
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
                state.links.forEachIndexed { index, link ->
                    if (index > 0) RowDivider()
                    LinkRow(link, onClick = { onLink(link) })
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** Этапы разработки по спецификации `design/spec/SPEC.txt`. */
@Composable
fun RoadmapScreen(
    steps: List<RoadmapStep>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 12.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        SubHeader(
            title = "Этапы разработки",
            // Шагов одиннадцать, нумерация с нуля: «из 10» противоречило бы
            // собственному списку, в котором после девятого идут десятый и
            // одиннадцатый.
            subtitle = "Сейчас идёт шаг 0 из 11",
            onBack = onBack,
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp)) {
                steps.forEach { step ->
                    TimelineRow(step, isLast = step.n == steps.last().n)
                }
            }
        }
    }
}

/** Строка плана: кружок с номером, вертикальная линия и подписи. */
@Composable
private fun TimelineRow(step: RoadmapStep, isLast: Boolean) {
    Row(modifier = Modifier.height(84.dp)) {
        Column(
            modifier = Modifier.width(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        color = if (step.current) Color(0xFF2B8CF5) else Color.White,
                        shape = RoundedCornerShape(16.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = step.n,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (step.current) Color.White else Color(0xFF4A4D57),
                )
            }
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .weight(1f)
                        .padding(vertical = 4.dp)
                        .background(Color(0xFFE4E6EC)),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.padding(top = 3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = step.title,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (step.current) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(11.dp),
                        color = MaterialTheme.colorScheme.primary,
                    ) {
                        Text(
                            text = "Сейчас",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            Text(
                text = step.sub,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LinkRow(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .tapNoRipple(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            fontSize = 16.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = IcwChevron,
            contentDescription = null,
            tint = Color(0xFF8A8D97),
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Карточка устройства по спецификации `design/spec/SPEC.txt`.
 *
 * Экран включается, как только в списке появится хоть одно устройство: сейчас
 * их нет, и показывать карточку выдуманного устройства было бы враньём.
 */
@Composable
fun DeviceDetailScreen(
    state: DeviceDetailState,
    onBack: () -> Unit,
    onMore: () -> Unit,
    onFeature: (String) -> Unit,
    onSend: () -> Unit,
    onMirror: () -> Unit,
    onRow: (SubRow) -> Unit,
    trust: TrustStore,
    onTrust: () -> Unit,
    /**
     * Пульсирует ли точка «На связи».
     *
     * Истина ставится только когда пришло настоящее сердцебиение. Точка, пульсирующая
     * по таймеру, врала бы: человек увидел бы живое соединение с телефоном,
     * который молчит. Здесь пульс значит ровно одно — «данные идут сейчас».
     */
    pulseLive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // Ритм «тук-тук», а не ровное дыхание.
    //
    // Сначала было `infiniteRepeatable` туда-обратно, и точка просто плавно
    // разрасталась и сжималась. Владелец прав: так выглядит не пульс, а
    // дыхание. Сердечный ритм узнаётся по двум ударам подряд — второй слабее
    // первого, — и именно эта пара отличает его от любой другой анимации.
    //
    // Ключевые кадры заданы вручную: пара ударов, затем пауза. Кривая
    // `FastOutSlowIn` размазывает переходы, а удар должен быть резким.
    val пульс = rememberInfiniteTransition(label = "пульс связи")
    val удар by пульс.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = keyframes {
            durationMillis = 1400
            0f at 0 using LinearOutSlowInEasing
            0.45f at 90
            0.10f at 260
            0.38f at 350
            0f at 560
            0f at 1400
        },
        label = "удар",
    )
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 12.dp),
    ) {
        Row(
            // Без горизонтального отступа: свой отступ 12 dp есть у самой
            // стрелки назад, и строка добавляла к нему ещё 12. Стрелка уезжала
            // на 24 dp от края, тогда как на «Сети» и «Соединении» она стоит
            // на тех же 12. Шаг в 12 на глаз виден сразу и выглядит как ошибка.
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BackButton(onClick = onBack)

            // Отступ справа задаётся обёрткой, а не самой кнопкой: иначе он
            // съел бы часть поля нажатия, и кнопка «Ещё» попадала бы под палец
            // заметно труднее, чем стрелка.
            Box(modifier = Modifier.padding(end = 12.dp)) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .tapNoRipple(onClick = onMore),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = IcwMore,
                        contentDescription = "Ещё",
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }

        Text(
            text = state.name,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 24.dp, top = 4.dp),
        )
        Row(
            modifier = Modifier.padding(start = 24.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .graphicsLayer {
                        // Пульс только пришедшим сердцебиением. Точка, бьющаяся
                        // по таймеру, врала бы: человек увидел бы живое соединение
                        // с телефоном, который молчит.
                        //
                        // Между ударами сила ноль, и фон спокойный: видно ритм,
                        // а не мигание.
                        val сила = if (pulseLive) удар else 0f
                        scaleX = 1f + сила * 0.9f
                        scaleY = 1f + сила * 0.9f
                        alpha = if (pulseLive) 0.55f + сила * 0.45f else 1f
                    }
                    .background(
                        color = if (state.online) Color(0xFF2BB673) else Color(0xFFA4A7B2),
                        shape = RoundedCornerShape(5.dp),
                    ),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = state.statusLine,
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFF4A4D57),
            )
        }

        SubBody {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 20.dp)) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconTile(
                            icon = IcwMirror,
                            tone = Tone.Blue,
                            size = 64.dp,
                            radius = 18.dp,
                        )
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                text = "Что передавать",
                                fontSize = 20.sp,
                                lineHeight = 26.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Для этого устройства",
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.padding(top = 22.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        state.features.forEach { feature ->
                            FeatureDial(
                                feature = feature,
                                onClick = { onFeature(feature.id) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.padding(top = 22.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PillButton(
                            // Не «Отправить файл»: файлы отправлять нечем, и
                            // кнопка вела бы на экран-заготовку. Здесь то, что
                            // действительно уходит по каналу сейчас.
                            "Отправить буфер", IcwClipboard, filled = true,
                            onClick = onSend, modifier = Modifier.weight(1f),
                        )
                        PillButton(
                            "Зеркало", IcwMirror, filled = false,
                            onClick = onMirror, modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            state.groups.forEach { group ->
                SubGroupTitle(
                    title = group.title,
                    modifier = Modifier.padding(start = 24.dp, top = 20.dp),
                )

                // Журнал подключения рисуется отдельным блоком, а не строкой
                // настроек.
                //
                // В строке текст справа обрезается по ширине, и журнал
                // превращался в «соединение с» — то есть в нечто, из чего
                // нельзя понять ничего. Здесь записи занимают всю ширину и
                // переносятся: обрезанный диагноз бесполезен.
                val журнал = group.rows.firstOrNull { it.multiline != null }
                if (журнал?.multiline != null) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            журнал.multiline.forEach { строка ->
                                Text(
                                    text = строка,
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    SubGroupCard(
                        rows = group.rows,
                        onRow = onRow,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

/**
 * Круглый переключатель возможности: 60, подпись под ним 13.
 *
 * В отличие от [com.naua_braidwork.app.ui.components.BwSwitch] здесь не
 * прямоугольник, а круг, и подпись меняет насыщенность вместе с ним.
 */
@Composable
private fun FeatureDial(
    feature: DeviceFeature,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.tapNoRipple(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .background(
                    color = if (feature.on) Color(0xFF2B8CF5) else Color(0xFFEDEDF0),
                    shape = RoundedCornerShape(30.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = feature.icon,
                contentDescription = feature.label,
                tint = if (feature.on) Color.White else Color(0xFF121316),
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        // Подпись в одну строку с многоточием, а не в две.
        //
        // «Уведомления» — длинное слово, и на узком экране или при крупном
        // шрифте оно не влезало в четверть ширины и переносилось посреди
        // слова: «Уведомлени» / «я». Перенос по слогам читается как опечатка.
        //
        // Троеточие честнее разорванного слова: человек понимает, что
        // подпись сокращена, а не что её перепутали с другой.
        Text(
            text = feature.label,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = if (feature.on) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
            textAlign = TextAlign.Center,
        )
    }
}
