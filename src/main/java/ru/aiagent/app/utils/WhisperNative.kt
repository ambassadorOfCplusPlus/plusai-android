package ru.aiagent.app.utils

import android.content.Context
import ru.aiagent.app.code.RuntimePackManager
import java.io.File

/**
 * JNI-мост к whisper.cpp из скачиваемого пака `whisper` (libwhisper.so + обёртка libpluswhisper.so).
 * Офлайн-распознавание речи из аудиофайла. Грузится через System.load из pack-папки — как ffmpeg/picoc
 * (dlopen скачанной .so на Android разрешён). Модель (ggml-*.bin) качается отдельно при первом использовании.
 */
object WhisperNative {
    @Volatile private var loaded = false
    private val lock = Any()
    private const val WRAPPER = "libpluswhisper.so"

    fun packDir(context: Context): File = RuntimePackManager.installDir(context, "whisper")

    /** Загрузить libwhisper.so и обёртку из пака. false — пак не скачан/битый. */
    fun ensureLoaded(context: Context): Boolean = synchronized(lock) {
        if (loaded) return true
        val dir = packDir(context)
        val wrapper = File(dir, WRAPPER)
        if (!wrapper.exists() || wrapper.length() == 0L) return false
        return try {
            // Сначала зависимые .so (libwhisper, libggml*), потом обёртка — иначе dlopen не найдёт символы.
            dir.listFiles { f -> f.isFile && f.name.endsWith(".so") && f.name != WRAPPER }
                ?.sortedBy { it.name }?.forEach { so ->
                    runCatching { if (so.canWrite()) so.setReadOnly() }
                    runCatching { System.load(so.absolutePath) }
                }
            runCatching { if (wrapper.canWrite()) wrapper.setReadOnly() }
            System.load(wrapper.absolutePath)
            loaded = true
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Распознать речь: модель (.bin) + WAV (16кГц mono 16-bit PCM) → текст. lang="ru"/"en"/"auto". */
    external fun transcribe(modelPath: String, wavPath: String, lang: String?): String
}
