package com.naua_braidwork.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.naua_braidwork.app.ui.screens.BackgroundScreen
import com.naua_braidwork.app.ui.screens.NetworkScreen
import com.naua_braidwork.app.ui.screens.PairScreen
import com.naua_braidwork.app.ui.screens.SecurityScreen
import com.naua_braidwork.app.ui.state.LinkMode
import com.naua_braidwork.app.ui.state.Sample
import com.naua_braidwork.app.ui.state.Toggles

/**
 * Маршруты подэкранов.
 *
 * Строки, а не перечисление с полями: у маршрута нет собственного заголовка —
 * заголовок берётся из описания экрана, и хранить его в двух местах значило бы
 * однажды забыть про это. Перечисление появится, когда появится что различать.
 */
object Route {
    const val PAIR = "pair"
    const val NETWORK = "network"
    const val SECURITY = "security"
    const val BACKGROUND = "background"
}

/**
 * Подэкран поверх раздела.
 *
 * Нижнего меню здесь нет: открытый подэкран занимает весь экран, иначе меню
 * предлагало бы переключиться на другой раздел, потеряв открытый экран.
 */
@Composable
fun SubScreen(
    route: String,
    deviceId: String,
    mode: LinkMode,
    onMode: (LinkMode) -> Unit,
    onBack: () -> Unit,
    onRow: (String) -> Unit,
    onOpenSystemSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Значения переключателей живут здесь, а не в описании экрана: описание
    // пересоздаётся на каждой перерисовке и нажатия бы потерялись.
    var securityToggles by remember { mutableStateOf(Toggles(emptyMap())) }
    var networkToggles by remember { mutableStateOf(Toggles(emptyMap())) }
    var backgroundToggles by remember { mutableStateOf(Toggles(emptyMap())) }
    var brand by remember { mutableStateOf("Xiaomi") }
    var pairMode by remember { mutableStateOf(0) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when (route) {
            Route.PAIR -> PairScreen(
                mode = pairMode,
                onMode = { pairMode = it },
                onBack = onBack,
            )

            Route.NETWORK -> NetworkScreen(
                state = Sample.networkState(mode, deviceId),
                modeId = mode.id,
                toggles = networkToggles,
                onBack = onBack,
                onRow = { onRow(route) },
                onToggle = { id, value -> networkToggles = networkToggles.with(id, value) },
                onMode = { onMode(LinkMode.from(it)) },
            )

            Route.SECURITY -> SecurityScreen(
                state = Sample.securityState(deviceId),
                toggles = securityToggles,
                onBack = onBack,
                onRow = { onRow(it.id) },
                onToggle = { id, value -> securityToggles = securityToggles.with(id, value) },
            )

            Route.BACKGROUND -> BackgroundScreen(
                state = Sample.backgroundState(brand),
                toggles = backgroundToggles,
                onBack = onBack,
                onRow = { onRow(it.id) },
                onToggle = { id, value -> backgroundToggles = backgroundToggles.with(id, value) },
                onBrand = { brand = it },
                onOpenSystemSettings = onOpenSystemSettings,
            )

            // Неизвестный маршрут означает ошибку в вызывающем коде, а не у
            // пользователя. Показываем пустой экран: так это заметно в
            // разработке и безопасно в сборке, отданной тестерам.
            else -> Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Такого экрана нет",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
    }
}

/** Открытие настроек фоновой работы: у каждой оболочки свой адрес. */
@Composable
fun rememberSystemSettingsOpener(): () -> Unit {
    val context = LocalContext.current
    return {
        // Точные экраны различаются у Xiaomi, Samsung и Huawei, а общего
        // адреса нет. Показываем настройки приложения: это единственное
        // место, которое есть на любом Android и ведёт туда же.
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", context.packageName, null),
        )
        runCatching { context.startActivity(intent) }
    }
}
