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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.naua_braidwork.app.ui.BraidworkApp
import com.naua_braidwork.app.ui.ErrorScreen
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

        // Ставится до всего остального: если падение случится при запуске,
        // перехватчик уже должен работать.
        CrashLog.install(this)
        val previousCrash = CrashLog.read(this)
        if (previousCrash != null) CrashLog.clear(this)

        // Полноэкранный режим: окно растягивается под статус-бар и под системную
        // навигацию, а цвета обеих панелей становятся прозрачными. Отступы
        // внутри приложения берутся из окна, поэтому интерфейс рисуется под
        // ними, а не начинается под ними.
        WindowCompat.setDecorFitsSystemWindows(window, false)
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
            // Настройки живут в хранилище, а не в состоянии Activity: иначе
            // выбор темы пропал бы при повороте экрана и при пересоздании.
            val settings = remember { SettingsStore.get(this) }
            val dark = settings.theme.resolve(isSystemInDarkTheme())

            BraidworkTheme(darkTheme = dark) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Загрузка библиотек проверяется до первого кадра: если ядро
                    // не поднялось, показываем текст ошибки вместо закрытия.
                    // Проверка здесь, а не внутри экрана, потому что вызов
                    // composable-функции внутри try Kotlin не допускает.
                    val loadError = remember { runCatching { BraidworkCore.checkLoaded() } }
                    val problem = loadError.exceptionOrNull()

                    if (previousCrash != null) {
                        // Падение прошлого запуска важнее текущего состояния:
                        // сначала чиним то, что уже упало.
                        ErrorScreen(previousCrash)
                    } else if (problem != null) {
                        ErrorScreen(describe(problem))
                    } else {
                        // Ключи читаются с диска, а не создаются заново при
                        // каждом запуске: идентификатор выводится из ключа
                        // подписи, и новый ключ означал бы новое устройство.
                        val identity = remember {
                            DeviceIdentityStore(filesDir).loadOrCreate()
                        }
                        when (val loaded = identity) {
                            is DeviceIdentityStore.Result.Ready ->
                                BraidworkApp(
                                    deviceId = loaded.identity.deviceId,
                                    fingerprint = loaded.identity.fingerprint,
                                    settings = settings,
                                )

                            is DeviceIdentityStore.Result.Created ->
                                BraidworkApp(
                                    deviceId = loaded.identity.deviceId,
                                    fingerprint = loaded.identity.fingerprint,
                                    settings = settings,
                                )

                            // Повреждённый файл ключей — не повод создать новые.
                            // Пользователь должен узнать об этом иначе, решив,
                            // что его телефон забыл все устройства.
                            is DeviceIdentityStore.Result.Broken ->
                                ErrorScreen(
                                    "Не читаются ключи устройства\n\n" +
                                        "Идентификатор выводится из ключа подписи, поэтому " +
                                        "файл ключей — это и есть устройство. Новые ключи " +
                                        "сделали бы все ранее сопряжённые устройства чужими, " +
                                        "поэтому приложение их не создаёт само.\n\n" +
                                        loaded.reason,
                                )
                        }
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