package com.naua_braidwork.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.naua_braidwork.app.ui.theme.ThemeChoice

/**
 * Постоянные настройки приложения.
 *
 * Хранилище самое простое из возможных: `SharedPreferences`. Данных мало, они
 * пишутся редко, а лишняя зависимость в приложении, которое собирается только
 * на CI, стоила бы дороже двадцати строк кода.
 *
 * Значения отдаются Compose как состояние, а не читаются по месту: иначе
 * экран «Внешний вид» показал бы старое значение до перерисовки, и переключатель
 * выглядел бы сломанным.
 *
 * Отдельный объект, а не расширение `Context`, потому что он же и переживает
 * перезапуск Activity: хранить настройку в композитной функцииActivity значит
 * терять её при каждом пересоздании.
 */
class SettingsStore private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("braidwork", Context.MODE_PRIVATE)

    /** Выбранная тема. Читается один раз и дальше живёт в состоянии. */
    var theme: ThemeChoice by mutableStateOf(ThemeChoice.from(prefs.getString(KEY_THEME, null)))
        private set

    /** Показывалось ли приветствие. */
    var welcomeSeen: Boolean by mutableStateOf(prefs.getBoolean(KEY_WELCOME, false))
        private set

    // Имя не `setTheme`: закрытый сеттер свойства `theme` компилируется в
    // метод с таким же именем, и Kotlin увидит в файле два одинаковых метода.
    fun chooseTheme(value: ThemeChoice) {
        theme = value
        prefs.edit().putString(KEY_THEME, value.title).apply()
    }

    fun markWelcomeSeen() {
        welcomeSeen = true
        prefs.edit().putBoolean(KEY_WELCOME, true).apply()
    }

    companion object {
        private const val KEY_THEME = "theme"
        private const val KEY_WELCOME = "welcome_seen"

        @Volatile
        private var instance: SettingsStore? = null

        /**
         * Единственный экземпляр на процесс.
         *
         * Отдельный вызов на каждый экран означал бы, что у каждого экрана своя
         * копия настроек, и переключатель на одном экране не влиял бы на другой.
         */
        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(context).also { instance = it }
            }
    }
}
