package ru.aiagent.app.utils

import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/**
 * Координатор системного выбора файла (SAF). Как и NFC, headless невозможен: диалог
 * ACTION_OPEN_DOCUMENT показывает только активность на переднем плане. Схема та же, что у [NfcBridge]:
 *
 *   инструмент file_picker → [request] выставляет pending-запрос → выводит MainActivity вперёд →
 *   Activity.onResume видит [hasPending] и запускает OpenDocument-launcher → пользователь выбирает файл →
 *   колбэк launcher-а зовёт [deliver] с Uri (или null при отмене) → инструмент копирует файл в песочницу.
 *
 * Одновременно один pending-запрос. `types` — фильтр MIME для диалога (напр. "image/*"), по умолчанию "*/*".
 */
object FilePickerBridge {
    private class Pending(val types: Array<String>, val deferred: CompletableDeferred<Uri?>)

    @Volatile private var pending: Pending? = null
    @Volatile private var launched = false // защита от повторного запуска на каждый onResume

    fun hasPending(): Boolean = pending != null

    /** MIME-типы для диалога текущего запроса (для launcher.launch). */
    fun pendingTypes(): Array<String> = pending?.types ?: arrayOf("*/*")

    /** Уже запускали диалог для текущего запроса? (onResume не должен дёргать launch повторно). */
    fun consumeLaunch(): Boolean = synchronized(this) {
        if (pending == null || launched) return false
        launched = true
        true
    }

    @Synchronized
    fun request(types: Array<String>): Deferred<Uri?> {
        cancel("вытеснен новым выбором файла")
        val d = CompletableDeferred<Uri?>()
        pending = Pending(types, d)
        launched = false
        return d
    }

    /** Вызывается из MainActivity колбэком OpenDocument (uri или null при отмене). */
    @Synchronized
    fun deliver(uri: Uri?) {
        val p = pending ?: return
        pending = null
        launched = false
        if (!p.deferred.isCompleted) p.deferred.complete(uri)
    }

    @Synchronized
    fun cancel(reason: String) {
        val p = pending ?: return
        pending = null
        launched = false
        if (!p.deferred.isCompleted) p.deferred.completeExceptionally(PickCancelled(reason))
    }

    class PickCancelled(message: String) : Exception(message)
}
