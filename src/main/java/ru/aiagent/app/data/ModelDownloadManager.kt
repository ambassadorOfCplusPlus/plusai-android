package ru.aiagent.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.aiagent.data.ModelDownloader
import java.io.File

/**
 * Синглтон-менеджер загрузок моделей. Свой scope переживает уход с экрана — загрузка НЕ
 * отменяется при навигации (U2). Прогресс и ошибки (в т.ч. «SHA-256 не совпал») — в
 * observable-состоянии, чтобы UI их показал, а не молча сбрасывал строку в «Скачать» (U3).
 */
object ModelDownloadManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val progress = mutableStateMapOf<String, Int>()  // имя файла -> %
    val errors = mutableStateMapOf<String, String>() // имя файла -> сообщение об ошибке
    var completed by mutableIntStateOf(0)            // счётчик завершений — для обновления списка
        private set

    fun isActive(file: String) = progress.containsKey(file)

    fun start(url: String, dest: File, sha256: String) {
        val name = dest.name
        if (progress.containsKey(name)) return // уже качается — не дублируем
        errors.remove(name)
        progress[name] = 0
        scope.launch {
            ModelDownloader().download(url, dest, sha256) { ev ->
                when (ev) {
                    is ModelDownloader.Event.Progress ->
                        progress[name] = if (ev.totalBytes > 0)
                            ((ev.bytesDownloaded * 100) / ev.totalBytes).toInt() else 0
                    is ModelDownloader.Event.Done -> { progress.remove(name); completed++ }
                    is ModelDownloader.Event.Failed -> { progress.remove(name); errors[name] = ev.message }
                }
            }
        }
    }
}
