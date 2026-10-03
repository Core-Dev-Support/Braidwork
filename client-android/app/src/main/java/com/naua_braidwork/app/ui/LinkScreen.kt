package com.naua_braidwork.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_braidwork.app.BraidworkCore
import com.naua_braidwork.app.BraidworkException
import com.naua_braidwork.app.ui.theme.LocalStatusColors

/**
 * Состояние экрана на время, пока транспорта нет.
 *
 * Ядро уже работает: ключи созданы, идентификатор вычислен. Соединений пока
 * ноль, и это показывается честно, а не пустым списком с обещанием.
 */
@Composable
fun LinkScreen(
    onOpenBackgroundSettings: () -> Unit,
) {
    val status = LocalStatusColors.current

    var deviceId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var checkingBackground by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            val keys = BraidworkCore.generateKeys()
            deviceId = BraidworkCore.deviceId(keys.signing)
        } catch (t: Throwable) {
            // Ловится Throwable, а не только BraidworkException: ошибка загрузки
            // библиотеки приходит как UnsatisfiedLinkError, и при перехвате
            // одного типа она просто убивала приложение с белым экраном.
            error = "${t::class.java.simpleName}: ${t.message ?: "без сообщения"}"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Сеть",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "Устройства связаны напрямую, без аккаунта и без сервера.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(4.dp))

        // Карточка «это устройство»: то, что уже работает.
        InfoCard {
            Text(
                text = "Это устройство",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            when {
                deviceId != null -> Text(
                    text = deviceId.orEmpty(),
                    fontSize = 17.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                error != null -> Text(
                    text = error.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Вычисляем идентификатор",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            StatusChip(text = "Ядро запущено", background = status.onlineSubtle, foreground = status.online)
        }

        // Честное состояние: устройств ноль, потому что транспорта ещё нет.
        InfoCard {
            Text(
                text = "Устройства",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "0 устройств",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FeatureChip("Буфер обмена", enabled = false)
                FeatureChip("Файлы", enabled = false)
                FeatureChip("Зеркало", enabled = false)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Связь между устройствами ещё не включена. Ядро готово: " +
                    "ключи созданы, шифрование работает.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Фоновая работа: объясняем, почему это важно именно на Xiaomi.
        InfoCard {
            Text(
                text = "Фоновая работа",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Чтобы связь не прерывалась, система не должна убивать " +
                    "приложение. На Xiaomi, Oppo и Vivo для этого нужен отдельный " +
                    "разрешённый список.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            StatusChip(
                text = "Не проверено",
                background = status.warningSubtle,
                foreground = status.warning,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    checkingBackground = true
                    onOpenBackgroundSettings()
                    checkingBackground = false
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("Открыть настройки фона")
            }
        }

        Text(
            text = "Braidwork · версия 1.0.0 · отладочная сборка",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
    }
}

/** Карточка с обводкой, а не тенью: тень на слабых экранах дороже. */
@Composable
private fun InfoCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

/**
 * Плашка состояния.
 *
 * Тёмная в светлой теме и тёмно-серая в тёмной: обозначает то, что надо
 * проверить или сделать, и потому не должна сливаться с фоном.
 */
@Composable
private fun StatusChip(text: String, background: Color, foreground: Color) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = background,
        contentColor = foreground,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/**
 * Фича в списке.
 *
 * Недоступная показывается прочерком, а не прячется: пользователь должен видеть,
 * что фича есть, но на этом устройстве ещё не работает.
 */
@Composable
private fun FeatureChip(text: String, enabled: Boolean) {
    val status = LocalStatusColors.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (enabled) status.infoSubtle else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (enabled) status.info else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}