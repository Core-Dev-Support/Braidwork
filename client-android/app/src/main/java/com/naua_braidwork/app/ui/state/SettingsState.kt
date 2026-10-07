package com.naua_braidwork.app.ui.state

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.naua_braidwork.app.ui.theme.ThemeChoice
import com.naua_braidwork.app.ui.icons.IcwBell
import com.naua_braidwork.app.ui.icons.IcwClipboard
import com.naua_braidwork.app.ui.icons.IcwCpu
import com.naua_braidwork.app.ui.icons.IcwFolder
import com.naua_braidwork.app.ui.icons.IcwInfo
import com.naua_braidwork.app.ui.icons.IcwMirror
import com.naua_braidwork.app.ui.icons.IcwMoon
import com.naua_braidwork.app.ui.icons.IcwPower
import com.naua_braidwork.app.ui.icons.IcwShield
import com.naua_braidwork.app.ui.icons.IcwWifi

/** Участник семьи с ролью. */
data class FamilyMember(
    val letter: String,
    val name: String,
    val subtitle: String,
    val role: String,
)

/** Состояние раздела «Семья». */
data class FamilyState(
    val name: String,
    val participants: Int,
    val devices: Int,
    val avatars: List<String>,
    val members: List<FamilyMember>,
)

/** Строка настроек: заголовок, значение справа и иконка в плашке. */
data class SettingsRow(
    val id: String,
    val title: String,
    val value: String,
    val icon: ImageVector,
    val tone: Tone,
)

/** Группа настроек с необязательным заголовком. */
data class SettingsGroup(
    val title: String,
    val rows: List<SettingsRow>,
)

/**
 * Что стоит в правой части строки подэкрана.
 *
 * Отдельные варианты, а не флаги по образцу макета: строка с переключателем
 * и строка со значением выглядят по-разному, и «одна строка с флагами» быстро
 * расползлась бы на девяти экранах.
 */
sealed interface RowEnd {
    /** Ничего: заголовок занимает всю ширину. */
    data object None : RowEnd

    /** Значение справа, например «Noise XX». */
    data class Value(val text: String) : RowEnd

    /** Шеврон: строка открывает следующий экран. */
    data object Chevron : RowEnd

    /**
     * Переключатель.
     *
     * [enabled] различает «выключено» и «подключать не к чему». Второе важно:
     * без него переключатель, к которому ничего не подключено, выглядит так
     * же, как выключенный пользователем, и вводит в заблуждение.
     */
    data class Toggle(val checked: Boolean, val enabled: Boolean = true) : RowEnd

    /** Кружок выбора: один из вариантов в группе. */
    data class Radio(val checked: Boolean) : RowEnd
}

/**
 * Строка подэкрана настроек.
 *
 * Иконка есть только у части строк — там, где строка обозначает конкретное
 * приложение или устройство, а не параметр. Такая строка выше остальных (64
 * против 66 и 56), и это тоже из макета.
 */
data class SubRow(
    val id: String,
    val title: String,
    val sub: String = "",
    val end: RowEnd = RowEnd.None,
    /** Опасное действие: заголовок красный. */
    val danger: Boolean = false,
    val icon: ImageVector? = null,
    val tone: Tone = Tone.Blue,
    /**
     * Текст под заголовком, во всю ширину строки.
     *
     * Для длинных записей вроде журнала подключения: в [RowEnd.Value] они
     * обрезались до неузнаваемого обрывка («соединение с»), и прочитать их
     * было невозможно. Здесь текст занимает отдельные строки целиком.
     *
     * `null` — ничего не показывать. Пустая строка не то же самое: она
     * оставила бы пустое место под заголовком.
     */
    val multiline: List<String>? = null,
)

/** Группа строк подэкрана. Пустой заголовок — группа без подписи. */
data class SubGroup(
    val title: String,
    val rows: List<SubRow>,
)

/**
 * Состояние группы переключателей подэкрана.
 *
 * Ключи — [SubRow.id]. Хранится одним отрезком, потому что переключатели
 * связаны между собой (например, включение интернета влияет на трафик), и
 * разносить их по разным полям значило бы забыть про связь.
 */
data class Toggles(val values: Map<String, Boolean>) {
    operator fun get(id: String): Boolean = values[id] == true

    fun with(id: String, value: Boolean): Toggles =
        Toggles(values + (id to value))
}

/**
 * Подставляет в строки текущие значения переключателей.
 *
 * Описание строк пересоздаётся заново при каждой перерисовке, поэтому нажатия
 * хранятся отдельно — в [Toggles]. Здесь они сводятся обратно со строками.
 */
fun List<SubRow>.withToggleValues(toggles: Toggles): List<SubRow> = map { row ->
    val end = row.end
    if (end is RowEnd.Toggle) {
        row.copy(end = RowEnd.Toggle(checked = toggles[row.id], enabled = end.enabled))
    } else {
        row
    }
}

/** Один из вариантов, выбираемых кружком. */
data class RadioOption(
    val id: String,
    val label: String,
    val sub: String = "",
)
/**
 * Состояние «Сети и соединения»: что показывает карточка сверху и какие строки
 * в ней есть.
 *
 * Живые значения переключателей сюда не входят: описание экрана пересоздаётся
 * при каждой перерисовке, и хранить в нём нажатия значило бы терять их.
 */
data class NetworkState(
    val lanStatus: String,
    val lanSub: String,
    val groups: List<SubGroup>,
)

/** Состояние «Безопасности и ключей». */
data class SecurityState(
    val deviceIdShort: String,
    /**
     * Собственный отпечаток ключа.
     *
     * Показывается рядом с идентификатором, а не вместо него: без него
     * сверка с соседом невыполнима.
     */
    val fingerprint: String = "",
    val groups: List<SubGroup>,
)

/** Шаг настройки фоновой работы под конкретный бренд. */
data class BrandStep(
    val title: String,
    val done: Boolean,
)

/** Состояние «Фоновой работы»: бренд устройства и шаги для него. */
data class BackgroundState(
    val serviceTitle: String,
    val serviceSub: String,
    val brand: String,
    val brands: List<String>,
    val steps: List<BrandStep>,
    val groups: List<SubGroup>,
)

/** Кружок выбора цвета акцента. */
data class AccentSwatch(
    val id: String,
    val name: String,
    val color: Color,
)

/** Активная передача файла: имя, доля, скорость и остаток времени. */
data class Transfer(
    val name: String,
    val target: String,
    /** Отправлено, доля от единицы. */
    val progress: Float,
    val speedMbps: Double,
    val leftText: String,
    val sentText: String,
)

/** Состояние «Файлов»: активные передачи и группы настроек. */
data class FilesState(
    val transfers: List<Transfer>,
    val storedText: String,
    val limitText: String,
    val groups: List<SubGroup>,
)

/** Состояние «Внешнего вида». */
data class AppearanceState(
    val theme: ThemeChoice,
    val accent: String,
    val themes: List<ThemeChoice>,
    val accents: List<AccentSwatch>,
    val perf: List<SubRow>,
)

/** Платформа со статусом: Android, Windows, Linux и дальше по плану. */
data class PlatformEntry(
    val icon: ImageVector,
    val tone: Tone,
    val title: String,
    val sub: String,
    val step: String,
)

/** Состояние «О приложении». */
data class AboutState(
    val appVersion: String,
    val coreVersion: String,
    val platforms: List<PlatformEntry>,
    val links: List<String>,
)

/** Шаг «Ядра и диагностики»: имя и результат проверки. */
data class CoreTest(
    val title: String,
    val passed: Boolean?,
)

/** Состояние «Ядра и диагностики». */
data class CoreState(
    val step: String,
    val buildGroups: List<SubGroup>,
    val tests: List<CoreTest>,
    val logGroups: List<SubGroup>,
)

/** Шаг плана разработки. */
data class RoadmapStep(
    val n: String,
    val title: String,
    val sub: String,
    val current: Boolean,
)

/** Возможность, включаемая для конкретного устройства. */
data class DeviceFeature(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val on: Boolean,
)

/** Состояние карточки устройства. */
data class DeviceDetailState(
    val name: String,
    val statusLine: String,
    val online: Boolean,
    val features: List<DeviceFeature>,
    val groups: List<SubGroup>,
)