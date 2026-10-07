package com.naua_braidwork.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.naua_braidwork.app.TrustStore
import com.naua_braidwork.app.ui.components.PillButton
import com.naua_braidwork.app.ui.components.StatusCard
import com.naua_braidwork.app.ui.components.SubBody
import com.naua_braidwork.app.ui.components.SubGroupCard
import com.naua_braidwork.app.ui.components.SubGroupTitle
import com.naua_braidwork.app.ui.components.SubHeader
import com.naua_braidwork.app.ui.icons.IcwShield
import com.naua_braidwork.app.ui.icons.IcwStop
import com.naua_braidwork.app.ui.state.RowEnd
import com.naua_braidwork.app.ui.state.SubRow
import com.naua_braidwork.app.ui.state.Tone

/**
 * Сопряжение: сверка отпечатка ключа человеком.
 *
 * Экран отвечает на один вопрос — **то** ли это устройство — и больше ничего не
 * решает. Всё криптографическое уже сделано ядром: оно сверило объявленный
 * идентификатор с ключом подписи и проверило подпись. Здесь человек сравнивает
 * отпечаток, и его решение записывается.
 *
 * ## Почему решает человек, а не приложение
 *
 * Подделка, у которой есть собственный ключ, — это уже другое устройство.
 * Отличить его от ожидаемого автоматически нельзя: сеть не различает. Единственный
 * источник, который знает, какое устройство ждали, — человек.
 *
 * ## Что показано честно
 *
 * Пустой отпечаток означает старое ядро напарника, и экран говорит об этом
 * прямо, а не рисует прочеркивание, которое выглядит как поломка.
 */
@Composable
fun TrustScreen(
    deviceName: String,
    fingerprint: String,
    адрес: String,
    версияЯдра: String,
    доверие: TrustStore.Доверие,
    onBack: () -> Unit,
    onСопрячь: () -> Unit,
    onЗабыть: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Отпечаток пуст у устройства со старым ядром. Подставлять «—» молча нельзя:
    // человек решил бы, что сверять нечего, и нажал «Сопрячь» вслепую.
    val показатьОтпечаток = fingerprint.ifBlank { "ядро напарника не присылает отпечаток" }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 12.dp),
    ) {
        SubHeader(
            title = "Сопряжение",
            subtitle = "Отпечаток ключа напарника",
            onBack = onBack,
        )

        SubBody {
            // Цвет здесь не украшение: зелёный — приложение доверяет ключу,
            // оранжевый — человек ещё не решил, красный — ключ изменился.
            StatusCard(
                icon = IcwShield,
                title = заголовок(доверие),
                subtitle = пояснение(доверие),
                tone = when (доверие) {
                    TrustStore.Доверие.СОПРЯЖЕНО -> Tone.Green
                    TrustStore.Доверие.ОТПЕЧАТОК_ИЗМЕНИЛСЯ -> Tone.Orange
                    TrustStore.Доверие.НЕ_СВЕРЕНО -> Tone.Neutral
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            )

            SubGroupTitle(
                title = "Устройство",
                modifier = Modifier.padding(start = 24.dp, top = 20.dp),
            )
            SubGroupCard(
                rows = listOf(
                    SubRow("name", "Название", "", RowEnd.Value(deviceName)),
                    SubRow("fp", "Отпечаток", "", RowEnd.Value(показатьОтпечаток)),
                    SubRow("address", "Адрес", "", RowEnd.Value(адрес)),
                    SubRow("core", "Версия ядра", "", RowEnd.Value(версияЯдра)),
                ),
                onRow = {},
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            // Действие одно и оно меняет состояние на противоположное. Две
            // кнопки рядом сбивали бы с толку: при доверии «Сопрячь»
            // бессмысленно, а «Забыть» пугает без повода.
            when (доверие) {
                TrustStore.Доверие.СОПРЯЖЕНО -> PillButton(
                    text = "Забыть устройство",
                    icon = IcwStop,
                    filled = false,
                    onClick = onЗабыть,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                )

                TrustStore.Доверие.НЕ_СВЕРЕНО,
                TrustStore.Доверие.ОТПЕЧАТОК_ИЗМЕНИЛСЯ,
                -> PillButton(
                    text = if (доверие == TrustStore.Доверие.ОТПЕЧАТОК_ИЗМЕНИЛСЯ) {
                        "Принять новый отпечаток"
                    } else {
                        "Сопрячь"
                    },
                    icon = IcwShield,
                    filled = true,
                    onClick = onСопрячь,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                )
            }
        }
    }
}

/** Заголовок карточки состояния. */
private fun заголовок(доверие: TrustStore.Доверие): String = when (доверие) {
    TrustStore.Доверие.СОПРЯЖЕНО -> "Устройство сопряжено"
    TrustStore.Доверие.НЕ_СВЕРЕНО -> "Устройство не сверено"
    TrustStore.Доверие.ОТПЕЧАТОК_ИЗМЕНИЛСЯ -> "Отпечаток изменился"
}

/** Пояснение под заголовком состояния. */
private fun пояснение(доверие: TrustStore.Доверие): String = when (доверие) {
    TrustStore.Доверие.СОПРЯЖЕНО ->
        "Отпечаток совпал с тем, что вы сверили. Приложение доверяет этому ключу."

    TrustStore.Доверие.НЕ_СВЕРЕНО ->
        "Сверьте отпечаток на обоих экранах и нажмите «Сопрячь», если они совпали."

    TrustStore.Доверие.ОТПЕЧАТОК_ИЗМЕНИЛСЯ ->
        "Тот же идентификатор пришёл с другим ключом. Так бывает при законной " +
            "ротации ключей и при подмене, а различить их автоматически нельзя."
}