package com.naua_braidwork.app.ui.screens

import com.naua_braidwork.app.ui.components.rememberПульс
import com.naua_braidwork.app.ui.components.tapNoRipple
import com.naua_braidwork.app.ui.theme.ширинаОкна
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_braidwork.app.ui.icons.IcwDevices
import com.naua_braidwork.app.ui.icons.IcwLaptop
import com.naua_braidwork.app.ui.icons.IcwMore
import com.naua_braidwork.app.ui.icons.IcwPhone
import com.naua_braidwork.app.ui.icons.IcwPlus
import com.naua_braidwork.app.ui.icons.IcwTablet
import com.naua_braidwork.app.ui.state.DeviceFilter
import com.naua_braidwork.app.ui.state.DeviceKind
import com.naua_braidwork.app.ui.state.Presence
import com.naua_braidwork.app.ui.state.DeviceSummary
import com.naua_braidwork.app.ui.state.DevicesState
import com.naua_braidwork.app.ui.theme.LocalAppGradient

/**
 * Устройства по спецификации `design/spec/SPEC.txt`.
 *
 * Экран тёмный в обеих темах: так задано в макете градиентом. Светлая тема
 * его не отменяет — иначе плитки на светлом фоне выглядят выключенными.
 */
@Composable
fun DevicesScreen(
    state: DevicesState,
    onFilter: (DeviceFilter) -> Unit,
    onAdd: () -> Unit,
    onDevice: (DeviceSummary) -> Unit,
    /**
     * Причина, по которой канал не работает. Пустая строка — проблем нет.
     *
     * Показывается на этом же экране: ошибка, известная только журналу, для
     * пользователя неотличима от поломки без причины.
     */
    problem: String = "",
    modifier: Modifier = Modifier,
) {
    val isDark = isSystemInDarkTheme()
    // Тёмный фон у «Устройств» особенный: три остановки вместо двух, средняя
    // на 42 % высоты. В светлой теме он совпадает с общим фоном приложения.
    val background = if (isDark) {
        Brush.verticalGradient(
            0f to Color(0xFF3B4252),
            0.42f to Color(0xFF2B2624),
            1f to Color(0xFF1B1716),
        )
    } else {
        LocalAppGradient.current
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(background),
    ) {
        // Поле от края и число колонок берутся у класса ширины окна.
        //
        // Раньше здесь стояли 24 dp и `GridCells.Fixed(2)` на любом экране. На
        // планшете две колонки растягивали плитки вдвое, и карточка устройства
        // переставала быть карточкой — это становилось полем с текстом на
        // полэкрана. На очень узком окне, наоборот, вторая колонка оставляла
        // подпись в две строки от слова «Телефон».
        val поле = ширинаОкна.поле
        val колонки = ширинаОкна.колонки
        Column(modifier = Modifier.padding(horizontal = поле)) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Устройства",
                        style = MaterialTheme.typography.displaySmall,
                        color = foregroundOn(isDark),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${state.devices.size} в сети",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isDark) Color(0xFFC2C5CE) else Color(0xFF666A75),
                    )
                }
                HeaderIcon(IcwPlus, "Добавить устройство", onAdd, isDark)
                Spacer(Modifier.width(2.dp))
                HeaderIcon(IcwMore, "Ещё", {}, isDark, strokeNote = "толще в макете")
            }

            Spacer(Modifier.height(18.dp))
            FilterRow(current = state.filter, onFilter = onFilter, isDark = isDark)
            Spacer(Modifier.height(18.dp))

            // Причина показывается пользователю, а не только пишется в журнал.
            // Нажатие на устройство, после которого ничего не происходит, —
            // самый обидный вид поломки: пользователь не знает, куда смотреть,
            // и решает, что сломалось приложение.
            if (problem.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (isDark) Color(0xFF3A2A24) else Color(0xFFFDEDE7),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = поле),
                ) {
                    Text(
                        text = problem,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = if (isDark) Color(0xFFE8B9A8) else Color(0xFF8A3A1E),
                        modifier = Modifier.padding(14.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        if (state.devices.isEmpty()) {
            // Плитки не рисуются, пока нет ни одного устройства: сетка из
            // пустых карточек выглядит поломкой, а не как начало работы.
            EmptyDevicesHint(onAdd = onAdd, modifier = Modifier.padding(horizontal = поле))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(колонки),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = поле, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.devices, key = { it.id }) { device ->
                    DeviceCard(device = device, onClick = { onDevice(device) })
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        DashedAddButton(onAdd = onAdd, isDark = isDark, modifier = Modifier.padding(horizontal = поле))
        Spacer(Modifier.height(12.dp))
    }
}

private fun foregroundOn(isDark: Boolean): Color =
    if (isDark) Color(0xFFFFFFFF) else Color(0xFF121316)

@Composable
private fun HeaderIcon(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    isDark: Boolean,
    strokeNote: String = "",
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .tapNoRipple(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = foregroundOn(isDark),
            modifier = Modifier.size(26.dp),
        )
    }
}

/** Фильтры работают как группа: выбран один. */
@Composable
private fun FilterRow(current: DeviceFilter, onFilter: (DeviceFilter) -> Unit, isDark: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DeviceFilter.entries.forEach { filter ->
            val selected = filter == current
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (selected) {
                    if (isDark) Color(0xFF3A4250) else Color(0xFF1A6FDB)
                } else if (isDark) {
                    Color(0xFF2C313B)
                } else {
                    Color(0xFFFFFFFF)
                },
                contentColor = if (selected) {
                    if (isDark) Color(0xFFFFFFFF) else Color(0xFFFFFFFF)
                } else if (isDark) {
                    Color(0xFFC2C5CE)
                } else {
                    Color(0xFF121316)
                },
            ) {
                Text(
                    text = filter.label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .tapNoRipple { onFilter(filter) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** Плитка устройства: имя, состояние, крупная иконка. */
@Composable
private fun DeviceCard(device: DeviceSummary, onClick: () -> Unit) {
    val isDark = isSystemInDarkTheme()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            // Минимум, а не высота. Плитка содержит текст: имя, состояние и
            // подпись с данными. При шрифте 1.3 три строки не влезали в 140 dp,
            // и Compose срезал их сверху — имя исчезало, а состояние обрывалось
            // на середине слова. Смысл плитки — эти строки, терять их нельзя.
            .heightIn(min = 140.dp),
        shape = RoundedCornerShape(24.dp),
        color = if (isDark) Color(0xFF242830) else Color(0xFFFFFFFF),
        contentColor = foregroundOn(isDark),
    ) {
        Column(
            modifier = Modifier
                // Не `fillMaxSize`: высота плитки теперь растёт под текст, а
                // `fillMaxSize` внутри `Surface` означал бы «занять всё
                // доступное», то есть снова заданный размер вместо содержимого.
                .fillMaxWidth()
                .tapNoRipple(onClick = onClick)
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = device.name,
                    fontSize = 16.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Ритм считается один раз на плитку, а не в точке: иначе
                    // каждая строка списка завела бы своё преимущество, и
                    // несколько плиток били бы вразнобой.
                    val ритм = rememberПульс()
                    // Точка на плитке читается так же, как на карточке: крупнее и
                    // ярче при свежих данных, тусклее когда они перестали
                    // приходить. Владелец просил видеть это прямо в списке, не
                    // открывая карточку, — и по той же причине не только
                    // анимацией: у него отключены анимации.
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            // Ритм нужен и здесь. Раньше точка в списке только
                            // увеличивалась, и это выглядело как «статичный
                            // размер», а не как пульс: владелец прямо сказал, что
                            // пульса в плитке нет. Теперь обе точки бьют один
                            // ритм из одного места — [rememberПульс].
                            .graphicsLayer {
                                val основа = when {
                                    !device.online -> 1f
                                    !device.pulseFresh -> 1f
                                    else -> 1.35f + ритм * 0.65f
                                }
                                scaleX = основа
                                scaleY = основа
                            }
                            .background(
                                color = when {
                                    !device.online -> Color(0xFF6C707B)
                                    device.pulseFresh -> {
                                        if (isDark) Color(0xFF3DD68C) else Color(0xFF16804A)
                                    }
                                    else -> Color(0xFF8FBFA6)
                                },
                                shape = RoundedCornerShape(4.dp),
                            ),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = statusLabel(device),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = if (isDark) Color(0xFFC2C5CE) else Color(0xFF666A75),
                    )
                }
            }
            // Значка может не быть: тип неизвестен, и вместо него рисовать
            // нечего. Место под значок остаётся, чтобы плитки в списке не
            // прыгали по высоте в зависимости от того, знают ли соседи о себе.
            iconFor(device.kind)?.let { значок ->
                Icon(
                    imageVector = значок,
                    contentDescription = null,
                    tint = if (device.online) foregroundOn(isDark) else Color(0xFF6C707B),
                    modifier = Modifier
                        .size(44.dp)
                        .alpha(if (device.online) 1f else 0.55f),
                )
            }
        }
    }
}

/**
 * Подпись состояния под именем.
 *
 * Раньше здесь было два состояния, и устройство, которое объявляет себя прямо
 * сейчас, подписывалось «Офлайн». Это враньё: плитка стоит в списке «рядом», а
 * подпись говорит обратное. Теперь состояний три, и «Офлайн» достаётся только
 * тем, кто действительно молчит.
 *
 * ## Почему живость написана словами
 *
 * Владелец проверял на двух устройствах — с включёнными и с отключёнными
 * анимациями — и не увидел пульса нигде. Точка в списке меняла оттенок, и этого
 * не хватало: на восьми точках разница не читается глазом.
 *
 * Здесь точка остаётся как второстепенный знак, а смысл несёт текст. Человек
 * видит «данные идут» или «нет данных» прямо в списке, не открывая карточку.
 */
private fun statusLabel(device: DeviceSummary): String {
    if (device.presence != Presence.Online) {
        return when (device.presence) {
            Presence.Nearby -> "Рядом, не подключено"
            else -> "Офлайн"
        }
    }
    val заряд = device.batteryPercent?.let { " · $it %" }.orEmpty()
    // Канал есть, а данных нет — это разные вещи, и сводить их к «На связи»
    // нельзя: человек решил бы, что всё в порядке, и перестал бы смотреть.
    val живость = if (device.pulseFresh) "данные идут" else "нет данных"
    return "На связи$заряд · $живость"
}

/**
 * Значок типа устройства.
 *
 * Для неизвестного типа значка нет вовсе. Раньше на это место попадал ноутбук —
 * и вот почему это плохо: значок устройства на плитке это утверждение, а не
 * украшение. Ноутбук у телефона не «примерно похоже», а прямой ответ на вопрос,
 * на который у нас нет данных, и выглядит он при этом совершенно убедительно.
 *
 * Пустой значок читается как «не знаю» и вопрос оставляет открытым.
 */
private fun iconFor(kind: DeviceKind): ImageVector? = when (kind) {
    DeviceKind.Phone -> IcwPhone
    DeviceKind.Tablet -> IcwTablet
    DeviceKind.Laptop, DeviceKind.Desktop -> IcwLaptop
    DeviceKind.Неизвестно -> null
}

/** Пока нет устройств: объяснение и кнопка, а не пустая сетка. */
@Composable
private fun EmptyDevicesHint(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val isDark = isSystemInDarkTheme()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = if (isDark) Color(0xFF242830) else Color(0xFFFFFFFF),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Icon(
                imageVector = IcwDevices,
                contentDescription = null,
                tint = if (isDark) Color(0xFFC2C5CE) else Color(0xFF666A75),
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Устройств пока нет",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = foregroundOn(isDark),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Связь между устройствами ещё не включена. Ядро готово: " +
                    "ключи созданы, шифрование работает. Как только появится " +
                    "транспорт, устройства найдут друг друга сами.",
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = if (isDark) Color(0xFFC2C5CE) else Color(0xFF666A75),
            )
        }
    }
}

/** Кнопка добавления с пунктирной рамкой — по макету. */
@Composable
private fun DashedAddButton(
    onAdd: () -> Unit,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            // Минимум, а не высота: подпись «Добавить устройство» при шрифте
            // 1.3 переставала помещаться и обрезалась.
            .heightIn(min = 60.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent,
        border = BorderStroke(
            1.5.dp,
            if (isDark) Color(0xFF6C707B) else Color(0xFFC8CCD6),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .tapNoRipple(onClick = onAdd),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = IcwPlus,
                contentDescription = null,
                tint = if (isDark) Color(0xFFE4E6EB) else Color(0xFF121316),
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Добавить устройство",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = if (isDark) Color(0xFFE4E6EB) else Color(0xFF121316),
            )
        }
    }
}