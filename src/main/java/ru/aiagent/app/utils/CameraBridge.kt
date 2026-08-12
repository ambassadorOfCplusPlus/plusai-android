package ru.aiagent.app.utils

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import java.io.File

/**
 * Координатор системной съёмки фото. Как и NFC/file_picker, headless невозможен: приложение камеры
 * (ACTION_IMAGE_CAPTURE / контракт TakePicture) показывает свой UI только поверх foreground-Activity.
 * Схема та же, что у [FilePickerBridge]:
 *
 *   инструмент camera_capture → [request] выставляет pending-запрос с целевым файлом → выводит
 *   MainActivity вперёд → Activity.onResume видит [hasPending] и запускает TakePicture-launcher с
 *   content://-Uri целевого файла ([pendingUri]) → пользователь снимает кадр → колбэк launcher-а зовёт
 *   [deliver] с признаком успеха → инструмент проверяет, что файл записан, и возвращает путь в песочнице.
 *
 * Одновременно один pending-запрос. Камера пишет прямо в наш файл через FileProvider (CAMERA-разрешение
 * нашему приложению не нужно — снимает стороннее приложение камеры).
 */
object CameraBridge {
    private class Pending(val target: File, val deferred: CompletableDeferred<Boolean>)

    @Volatile private var pending: Pending? = null
    @Volatile private var launched = false // защита от повторного запуска на каждый onResume

    fun hasPending(): Boolean = pending != null

    /** Целевой файл текущего запроса (куда камера должна сохранить кадр), либо null. */
    fun pendingFile(): File? = pending?.target

    /**
     * content://-Uri целевого файла через FileProvider — его отдаём приложению камеры на запись.
     * Возвращает null, если нет активного запроса или Uri построить не удалось.
     */
    fun pendingUri(context: Context): Uri? {
        val file = pending?.target ?: return null
        return runCatching {
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        }.getOrNull()
    }

    /** Уже запускали камеру для текущего запроса? (onResume не должен дёргать launch повторно). */
    fun consumeLaunch(): Boolean = synchronized(this) {
        if (pending == null || launched) return false
        launched = true
        true
    }

    @Synchronized
    fun request(targetFile: File): Deferred<Boolean> {
        cancel("вытеснен новой съёмкой")
        val d = CompletableDeferred<Boolean>()
        pending = Pending(targetFile, d)
        launched = false
        return d
    }

    /** Вызывается из MainActivity колбэком TakePicture (true — кадр снят, false — отменён/ошибка). */
    @Synchronized
    fun deliver(success: Boolean) {
        val p = pending ?: return
        pending = null
        launched = false
        if (!p.deferred.isCompleted) p.deferred.complete(success)
    }

    @Synchronized
    fun cancel(reason: String) {
        val p = pending ?: return
        pending = null
        launched = false
        if (!p.deferred.isCompleted) p.deferred.completeExceptionally(CaptureCancelled(reason))
    }

    class CaptureCancelled(message: String) : Exception(message)
}
