package com.naua_braidwork.app.ui.state

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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

/** Состояние «Внешнего вида и языка». */
data class AppearanceState(
    val theme: String,
    val lang: String,
    val accent: String,
    val themes: List<String>,
    val langs: List<String>,
    val accents: List<AccentSwatch>,
    val perf: List<SubRow>,
)