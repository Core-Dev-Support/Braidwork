package com.naua_braidwork.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Единственный экран приложения.
 *
 * Намеренно простой: показываем идентификатор устройства для сверки при
 * сопряжении и состояние ядра. Всё остальное появится вместе с транспортом.
 */
class MainActivity : ComponentActivity() {

    private val core by lazy { BraidworkCore }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            lastNotificationPermission = granted
        }

    private var lastNotificationPermission: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ForegroundNotification.ensureChannel(this)
        startLinkService()

        // На Android 13+ уведомления не показываются без разрешения, а без них
        // клиент молча не работает. Просим сразу и объясняем, зачем.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxWidth()) {
                    DeviceIdentityPanel()
                }
            }
        }
    }

    private fun startLinkService() {
        val intent = Intent(this, LinkService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    @androidx.compose.runtime.Composable
    private fun DeviceIdentityPanel() {
        val context = LocalContext.current
        var deviceId by remember { mutableStateOf("вычисляется...") }
        var error by remember { mutableStateOf<String?>(null) }

        androidx.compose.runtime.LaunchedEffect(Unit) {
            try {
                // Ключи создаются один раз при первом запуске и хранятся локально.
                val keys = core.generateKeys()
                deviceId = core.deviceId(keys.signing)
            } catch (e: BraidworkException) {
                error = e.message
            }
        }

        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Braidwork", fontSize = 28.sp, style = MaterialTheme.typography.headlineMedium)
            Text(
                "Устройство связано с семьёй по локальной сети. " +
                    "Аккаунт не нужен, данные никуда не отправляются.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Text("Идентификатор устройства", style = MaterialTheme.typography.labelLarge)
            Text(
                deviceId,
                fontSize = 16.sp,
                style = MaterialTheme.typography.bodyLarge,
            )

            error?.let {
                Text("Ошибка: $it", color = MaterialTheme.colorScheme.error)
            }

            Text(
                "Пока реализована только идентичность устройства. " +
                    "Связь с другими устройствами появится в следующих сборках.",
                style = MaterialTheme.typography.bodySmall,
            )

            Button(onClick = { openBackgroundSettings(context) }) {
                Text("Разрешить работу в фоне")
            }
        }
    }

    private fun openBackgroundSettings(context: android.content.Context) {
        // На Xiaomi, Oppo, Vivo и Huawei фоновую работу включает конкретный
        // экран настроек, и без него клиент будет убиваться системой.
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", packageName, null)
        }
        runCatching { context.startActivity(intent) }
    }
}