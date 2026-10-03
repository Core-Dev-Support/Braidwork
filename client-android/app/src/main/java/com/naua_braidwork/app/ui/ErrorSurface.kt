package com.naua_braidwork.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_braidwork.app.ui.theme.LocalStatusColors

/**
 * Ловит падение при запуске и показывает его текстом.
 *
 * Пока нет канала диагностики на устройстве, это единственный способ узнать
 * причину: экран с ошибкой можно просто переписать или прислать скриншотом.
 * Ошибки внутри самого экрана этим не ловятся — только то, что происходит до
 * первого кадра.
 */
@Composable
fun ErrorSurface(content: @Composable () -> Unit) {
    val status = LocalStatusColors.current
    var failure by remember { mutableStateOf<String?>(null) }

    if (failure != null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Не удалось запустить",
                style = MaterialTheme.typography.headlineMedium,
                color = status.danger,
            )
            Text(
                text = failure.orEmpty(),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        return
    }

    try {
        content()
    } catch (t: Throwable) {
        failure = describe(t)
    }
}

/**
 * Текст ошибки вместе с цепочкой причин.
 *
 * Одной верхней строки мало: в Java причиной падения бывает ошибка ниже по
 * стеку, и именно она объясняет, что пошло не так.
 */
private fun describe(t: Throwable): String {
    val parts = StringBuilder()
    var current: Throwable? = t
    var depth = 0
    while (current != null && depth < 6) {
        if (depth > 0) parts.append("\n\nпричина: ")
        parts.append(current::class.java.name)
        current.message?.let { parts.append(": ").append(it) }
        current = current.cause
        depth++
    }
    return parts.toString()
}