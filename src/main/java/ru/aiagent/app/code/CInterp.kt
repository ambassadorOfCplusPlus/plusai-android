package ru.aiagent.app.code

import android.content.Context
import java.io.File

/**
 * Мост к picoc (интерпретатор C) из СКАЧИВАЕМОГО пака `c`. libcpico.so грузится через
 * System.load из filesDir (dlopen скачанной .so разрешён — это не execve файла), C-код
 * интерпретируется в процессе (без JIT/нативного exec → без W^X-проблем). Пак — не в APK.
 */
object CInterp {
    @Volatile private var loaded = false
    private val lock = Any()

    fun soFile(context: Context): File = File(RuntimePackManager.installDir(context, "c"), "libcpico.so")

    /** Загрузить libcpico.so, если пак установлен. false — пак не скачан/не загрузился. */
    fun ensureLoaded(context: Context): Boolean = synchronized(lock) {
        if (loaded) return true
        val so = soFile(context)
        if (!so.exists() || so.length() == 0L) return false
        // Будущие Android блокируют System.load писабельного файла — снимаем запись перед загрузкой.
        runCatching { if (so.canWrite()) so.setReadOnly() }
        return try {
            System.load(so.absolutePath); loaded = true; true
        } catch (_: Throwable) {
            false
        }
    }

    /** Нативный вызов: возвращает stdout как БАЙТЫ (вывод C может быть не-UTF8/с NUL). */
    private external fun runBytes(src: String): ByteArray

    /**
     * Выполнить C-исходник, вернуть перехваченный stdout. Сериализовано: stdout перенаправляется
     * процессно-глобально в нативе, поэтому одновременный запуск двух run сломал бы захват.
     */
    fun run(src: String): String = synchronized(lock) {
        String(runBytes(src), Charsets.UTF_8) // невалидные байты → U+FFFD, без крэша
    }
}
