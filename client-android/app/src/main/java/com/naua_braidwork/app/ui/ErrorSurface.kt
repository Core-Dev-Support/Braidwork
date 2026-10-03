package com.naua_braidwork.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_braidwork.app.ui.theme.LocalStatusColors

/**
 * Экран с текстом ошибки.
 *
 * Отдельная composable-функция, а не `try` вокруг вызова другой: Kotlin это
 * прямо запрещает, потому что компоновка должна идти по известному пути.
 *
 * Ошибку сюда передаёт та точка, где исключение действительно возникает, —
 * сейчас это загрузка библиотек в [com.naua_braidwork.app.BraidworkCore].
 */
@Composable
fun ErrorScreen(message: String) {
    val status = LocalStatusColors.current
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
            text = message,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/**
 * Текст ошибки вместе с цепочкой причин.
 *
 * Одной верхней строки мало: в Java причиной падения бывает ошибка ниже по
 * стеку, и именно она объясняет, что пошло не так.
 */
fun describe(t: Throwable): String {
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