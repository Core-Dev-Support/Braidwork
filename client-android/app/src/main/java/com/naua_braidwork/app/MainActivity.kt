package com.naua_braidwork.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.systemBarsPadding
import com.naua_braidwork.app.ui.ErrorScreen
import com.naua_braidwork.app.ui.LinkScreen
import com.naua_braidwork.app.ui.describe
import com.naua_braidwork.app.ui.theme.BraidworkTheme

/**
 * Единственный экран приложения.
 *
 * Пока показывает состояние ядра и честный ноль устройств. Разметка и цвета
 * берутся из общей системы токенов, поэтому тот же экран на десктопе выглядит
 * так же.
 */
class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        ForegroundNotification.ensureChannel(this)
        startLinkService()

        // На Android 13+ без этого уведомления не показываются, а без него
        // система убьёт приложение вместе со всей связью.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            BraidworkTheme {
                Surface(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                    // Загрузка библиотек проверяется до первого кадра: если ядро
                    // не поднялось, показываем текст ошибки вместо закрытия.
                    // Проверка здесь, а не внутри экрана, потому что вызов
                    // composable-функции внутри try Kotlin не допускает.
                    val loadError = remember { runCatching { BraidworkCore.checkLoaded() } }
                    val problem = loadError.exceptionOrNull()

                    if (problem != null) {
                        ErrorScreen(describe(problem))
                    } else {
                        LinkScreen(onOpenBackgroundSettings = ::openBackgroundSettings)
                    }
                }
            }
        }
    }

    private fun startLinkService() {
        val intent = Intent(this, LinkService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Сервис может не запуститься с первого раза, если система ещё
            // считает приложение только что запущенным. Повторная попытка
            // через пару секунд дешевле, чем падение на белом экране.
            try {
                startForegroundService(intent)
            } catch (e: IllegalStateException) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    { runCatching { startForegroundService(intent) } },
                    2000,
                )
            }
        } else {
            startService(intent)
        }
    }

    /**
     * Открывает настройки приложения.
     *
     * Отдельного экрана для автозапуска у Xiaomi и Oppo через публичный API
     * нет: доступ к нему есть только у самого производителя. Поэтому ведём
     * в общие настройки приложения и объясняем в интерфейсе, что искать.
     */
    private fun openBackgroundSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        runCatching { startActivity(intent) }
    }
}