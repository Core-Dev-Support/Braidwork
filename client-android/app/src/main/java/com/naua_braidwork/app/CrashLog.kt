package com.naua_braidwork.app

import android.content.Context
import java.io.File

/**
 * Сохраняет последнее падение в файл.
 *
 * Экран с ошибкой ловит только то, что происходит до первого кадра. Падение
 * в сервисе, в приёмнике системного вещания или в самом запуске процесса до
 * `onCreate` туда не попадает — а на телефоне это выглядит одинаково: приложение
 * просто исчезает.
 *
 * Файл читается при следующем запуске, поэтому падение видно и тогда, когда
 * перезапустить приложение нечем.
 *
 * Ошибка пишется целиком: стек и цепочка причин, потому что верхняя строка
 * сообщения в Android часто ничего не объясняет.
 */
object CrashLog {

    private const val FILE = "last-crash.txt"

    /** Читает сохранённое падение, если оно было. */
    fun read(context: Context): String? = runCatching {
        File(context.filesDir, FILE)
            .takeIf { it.isFile && it.length() > 0 }
            ?.readText()
    }.getOrNull()

    /** Убирает сохранённое падение: прочитали — хватит показывать снова. */
    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }

    /**
     * Ставит перехватчик.
     *
     * Прежний обработчик не забывается: падение передаётся дальше по цепочке,
     * чтобы система вела себя как обычно.
     */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                File(app.filesDir, FILE).writeText(describe(thread, error))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun describe(thread: Thread, error: Throwable): String {
        val out = StringBuilder()
        out.append("Поток: ").append(thread.name).append("\n\n")
        error.stackTrace.forEach { out.append("    at ").append(it).append('\n') }

        var current = error.cause
        var depth = 0
        while (current != null && depth < 5) {
            out.append("\nпричина: ")
                .append(current::class.java.name)
                .append(": ")
                .append(current.message)
                .append('\n')
            current.stackTrace.take(12).forEach { out.append("    at ").append(it).append('\n') }
            current = current.cause
            depth++
        }
        return out.toString()
    }
}