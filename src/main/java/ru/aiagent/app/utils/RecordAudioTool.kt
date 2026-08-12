package ru.aiagent.app.utils

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import ru.aiagent.core.agent.tools.escapeJson
import java.io.File
import java.io.RandomAccessFile

/**
 * record_audio — запись с микрофона в WAV-файл рабочей папки (песочницы). IMPORTANT.
 *
 * Пишем 16 кГц mono PCM 16-bit — ровно тот формат, который без конвертации ест whisper
 * ([TranscribeAudioTool]): записал → сразу можно транскрибировать.
 *
 * ПРИВАТНОСТЬ (§7): запись микрофона — чувствительное действие. Поэтому alwaysConfirm=true
 * (подтверждение в любом режиме, кроме bypass) и privateData=true (результат не уходит
 * облачной модели без явного действия пользователя).
 *
 * Путь к файлу резолвим ТЕМ ЖЕ механизмом песочницы, что и остальные аудио-инструменты
 * (лямбда resolve из AgentChatEngine, см. [audioTools]) — не изобретаем свою схему путей.
 *
 * Требуется runtime-разрешение RECORD_AUDIO (объявлено в манифесте; грант — на стороне UI).
 * Если разрешения нет / AudioRecord не инициализировался — честный отказ, без имитации.
 */
class RecordAudioTool(
    private val resolve: (String) -> File?,
) : AgentTool {
    override val id = "record_audio"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true // запись микрофона — всегда с подтверждением (кроме bypass)
    override val privateData = true // §7: запись не уходит облачной модели без действия пользователя
    override val usesFiles = true
    override val schema =
        """записать звук с микрофона в WAV-файл рабочей папки (16кГц mono, готов для transcribe_audio); """ +
            """args: {"seconds":15 (1..600, по умолч 15),"filename":"опц имя .wav"}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val o = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return@withContext ToolResult.Failure("битый JSON")
        val seconds = o.optInt("seconds", 15).coerceIn(1, 600)

        // Имя файла: гарантируем .wav, дефолт — по метке времени.
        var name = o.optString("filename").trim().ifBlank { "record_${System.currentTimeMillis()}.wav" }
        if (!name.endsWith(".wav", ignoreCase = true)) name += ".wav"
        val out = resolve(name) ?: return@withContext ToolResult.Failure("путь вне рабочей папки")
        out.parentFile?.mkdirs()

        // Формат: 16 кГц, mono, PCM 16-bit — совместим с whisper (transcribe_audio) без конвертации.
        val sampleRate = 16_000
        val channels = 1
        val bitsPerSample = 16
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            return@withContext ToolResult.Failure("не удалось начать запись: микрофон не поддерживает 16кГц mono")
        }
        // Буфер побольше минимального — запас против переполнения при рывках планировщика.
        val bufSize = minBuf * 2

        var recorder: AudioRecord? = null
        try {
            recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize,
                )
            } catch (e: SecurityException) {
                return@withContext ToolResult.Failure("нет разрешения на микрофон (RECORD_AUDIO)")
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                return@withContext ToolResult.Failure("не удалось начать запись (микрофон занят или нет доступа)")
            }

            try {
                recorder.startRecording()
            } catch (e: SecurityException) {
                return@withContext ToolResult.Failure("нет разрешения на микрофон (RECORD_AUDIO)")
            } catch (e: IllegalStateException) {
                return@withContext ToolResult.Failure("не удалось начать запись")
            }
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return@withContext ToolResult.Failure("не удалось начать запись (микрофон недоступен)")
            }

            // Пишем PCM в тело файла после 44-байтного заголовка, который допишем в конце (знаем размер).
            val bytesPerFrame = channels * bitsPerSample / 8
            val targetFrames = seconds.toLong() * sampleRate
            var framesCaptured = 0L
            val buf = ByteArray(bufSize)

            RandomAccessFile(out, "rw").use { raf ->
                raf.setLength(0)
                raf.seek(44) // место под заголовок
                while (framesCaptured < targetFrames) {
                    ensureActive() // «Стоп»/отмена → прекращаем запись, не держим микрофон до конца лимита
                    val n = recorder.read(buf, 0, buf.size)
                    if (n < 0) {
                        return@withContext ToolResult.Failure("ошибка чтения микрофона (код $n)")
                    }
                    if (n == 0) continue
                    // Не выходим за лимит секунд: пишем ровно столько кадров, сколько осталось.
                    val framesInChunk = n / bytesPerFrame
                    val framesLeft = targetFrames - framesCaptured
                    val bytesToWrite =
                        if (framesInChunk <= framesLeft) n
                        else (framesLeft * bytesPerFrame).toInt()
                    raf.write(buf, 0, bytesToWrite)
                    framesCaptured += bytesToWrite / bytesPerFrame
                }
                // Дописываем корректный WAV-заголовок (44 байта) с реальными размерами.
                val dataBytes = framesCaptured * bytesPerFrame
                raf.seek(0)
                raf.write(wavHeader(dataBytes.toInt(), sampleRate, channels, bitsPerSample))
            }

            if (framesCaptured <= 0L || out.length() <= 44L) {
                return@withContext ToolResult.Failure("запись пустая (микрофон не дал данных)")
            }
            val durationSec = framesCaptured.toDouble() / sampleRate
            return@withContext ToolResult.Success(
                """{"path":"${escapeJson(out.name)}","file":"${escapeJson(out.absolutePath)}",""" +
                    """"bytes":${out.length()},"seconds":${"%.2f".format(durationSec)},""" +
                    """"sample_rate":$sampleRate,"channels":$channels}""",
            )
        } catch (e: SecurityException) {
            return@withContext ToolResult.Failure("нет разрешения на микрофон (RECORD_AUDIO)")
        } catch (e: Exception) {
            return@withContext ToolResult.Failure("запись: ${e.message?.take(200)}")
        } finally {
            recorder?.let {
                runCatching { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() }
                runCatching { it.release() }
            }
        }
    }

    /** Стандартный 44-байтный WAV-заголовок (RIFF/WAVE, PCM). Все многобайтовые поля — little-endian. */
    private fun wavHeader(dataBytes: Int, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val h = ByteArray(44)
        fun putStr(off: Int, s: String) { for (i in s.indices) h[off + i] = s[i].code.toByte() }
        fun putInt(off: Int, v: Int) {
            h[off] = (v and 0xff).toByte()
            h[off + 1] = ((v shr 8) and 0xff).toByte()
            h[off + 2] = ((v shr 16) and 0xff).toByte()
            h[off + 3] = ((v shr 24) and 0xff).toByte()
        }
        fun putShort(off: Int, v: Int) {
            h[off] = (v and 0xff).toByte()
            h[off + 1] = ((v shr 8) and 0xff).toByte()
        }
        putStr(0, "RIFF")
        putInt(4, 36 + dataBytes) // размер файла минус 8
        putStr(8, "WAVE")
        putStr(12, "fmt ")
        putInt(16, 16) // размер fmt-чанка (PCM)
        putShort(20, 1) // audioFormat = 1 (PCM)
        putShort(22, channels)
        putInt(24, sampleRate)
        putInt(28, byteRate)
        putShort(32, blockAlign)
        putShort(34, bitsPerSample)
        putStr(36, "data")
        putInt(40, dataBytes)
        return h
    }
}

/** Сборка инструмента записи. Тот же механизм песочницы (resolve), что и у [audioTools]. */
fun recordAudioTool(context: Context, resolve: (String) -> File?): AgentTool =
    RecordAudioTool(resolve)
