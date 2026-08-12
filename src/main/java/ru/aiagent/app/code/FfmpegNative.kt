package ru.aiagent.app.code

import android.content.Context
import java.io.File

/**
 * Низкоуровневый JNI-мост к FFmpeg из СКАЧИВАЕМОГО пака `ffmpeg` (аналог [CInterp]/picoc и
 * [ru.aiagent.core.inference.LlamaNative]/llama.cpp). libplusffmpeg.so — тонкая обёртка, которая
 * зовёт ffmpeg main(argc, argv) в процессе и перехватывает stdout/stderr; сами библиотеки ffmpeg
 * (libav*, .so) лежат рядом в pack-папке. Всё грузится через System.load из filesDir — dlopen
 * скачанной .so на Android разрешён (это НЕ execve файла, поэтому SELinux/W^X не мешает, как у picoc).
 * Пак НЕ в APK — качается [RuntimePackManager] по требованию.
 *
 * Класс компилируется без .so: native-методы линкуются лениво, при первом [ensureLoaded].
 */
object FfmpegNative {
    @Volatile private var loaded = false
    private val lock = Any()

    /** Обёртка над ffmpeg main. */
    private const val WRAPPER_SO = "libplusffmpeg.so"

    fun packDir(context: Context): File = RuntimePackManager.installDir(context, "ffmpeg")

    /**
     * Загрузить библиотеки ffmpeg и обёртку, если пак установлен. false — пак не скачан/не загрузился.
     * Сначала грузим ВСЕ зависимые *.so из pack-папки (libav*, libsw* и т.п.), затем обёртку последней —
     * иначе dlopen обёртки не найдёт неразрешённые символы ffmpeg.
     */
    fun ensureLoaded(context: Context): Boolean = synchronized(lock) {
        if (loaded) return true
        val dir = packDir(context)
        val wrapper = File(dir, WRAPPER_SO)
        if (!wrapper.exists() || wrapper.length() == 0L) return false
        return try {
            // Зависимые библиотеки ffmpeg — в порядке имён; обёртку грузим последней.
            val libs = dir.listFiles { f -> f.isFile && f.name.endsWith(".so") && f.name != WRAPPER_SO }
                ?.sortedBy { it.name } ?: emptyList()
            for (so in libs) {
                // Будущие Android блокируют System.load писабельного файла — снимаем запись перед загрузкой.
                runCatching { if (so.canWrite()) so.setReadOnly() }
                runCatching { System.load(so.absolutePath) } // часть либ может подтянуться транзитивно — не валимся
            }
            runCatching { if (wrapper.canWrite()) wrapper.setReadOnly() }
            System.load(wrapper.absolutePath)
            loaded = true
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Запустить ffmpeg с готовым argv (без ведущего "ffmpeg"). Возвращает код возврата ffmpeg (0 = успех). */
    external fun run(args: Array<String>): Int

    /** Перехваченный stdout+stderr последнего [run] (ffmpeg пишет прогресс/ошибки в stderr). */
    external fun lastOutput(): String
}
