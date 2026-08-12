package ru.aiagent.app.utils

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import ru.aiagent.core.agent.tools.escapeJson
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Аудио-инструменты (ТЗ §3.11.1, голос).
 *
 * Почему не переиспользуем движки из модуля `:voice`:
 *  - [ru.aiagent.voice.AndroidTts] озвучивает ТОЛЬКО в динамик (`speak`), у него нет синтеза в файл
 *    (`synthesizeToFile`), а инструменту нужен именно аудиофайл в песочнице. Поэтому TTS-в-файл
 *    реализован здесь напрямую на `android.speech.tts.TextToSpeech` (локально, без сети).
 *  - [ru.aiagent.voice.AndroidStt] распознаёт только живой микрофон (SpeechRecognizer) и не умеет
 *    транскрибировать готовый файл. Офлайн-STT из файла требует модели (Vosk/whisper.cpp), которой
 *    в проекте пока нет, серверного ASR-эндпоинта тоже нет — поэтому [TranscribeAudioTool] честно
 *    отказывает, а не имитирует результат (инвариант §7: не выдаём фейк).
 */

/**
 * text_to_speech — синтез текста в аудиофайл (WAV) в рабочей папке. Локально, офлайн. SAFE.
 * TextToSpeech инициализируется асинхронно — ждём onInit=SUCCESS, затем synthesizeToFile с
 * UtteranceProgressListener (onDone → resume). Движок всегда закрываем (shutdown).
 */
class TextToSpeechTool(
    private val context: Context,
    private val resolve: (String) -> File?,
) : AgentTool {
    override val id = "text_to_speech"
    override val danger = DangerLevel.SAFE
    override val usesFiles = true
    override val schema =
        """озвучить текст в аудиофайл (WAV, офлайн); args: {"text":"…","out":"speech.wav","lang":"ru"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val o = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return ToolResult.Failure("битый JSON")
        val text = o.optString("text").ifBlank { return ToolResult.Failure("нужен args.text") }
        val lang = o.optString("lang", "ru").ifBlank { "ru" }
        val out = resolve(o.optString("out").ifBlank { "speech.wav" })
            ?: return ToolResult.Failure("путь вне рабочей папки")
        out.parentFile?.mkdirs()

        var tts: TextToSpeech? = null
        try {
            // 1) Асинхронная инициализация движка: ждём onInit.
            val initStatus = suspendCancellableCoroutine<Int> { cont ->
                val engine = TextToSpeech(context) { status ->
                    if (cont.isActive) cont.resume(status)
                }
                tts = engine
                cont.invokeOnCancellation { engine.shutdown() }
            }
            if (initStatus != TextToSpeech.SUCCESS) {
                return ToolResult.Failure("TTS-движок недоступен на устройстве")
            }
            val engine = tts ?: return ToolResult.Failure("TTS не инициализирован")

            // Язык эффективен только после успешного init.
            val langRes = engine.setLanguage(Locale(lang))
            if (langRes == TextToSpeech.LANG_MISSING_DATA || langRes == TextToSpeech.LANG_NOT_SUPPORTED) {
                return ToolResult.Failure("язык '$lang' не поддерживается TTS (нет голосовых данных)")
            }

            // 2) Синтез в файл: ждём onDone/onError по utteranceId.
            val uid = "plusai_tts"
            val ok = suspendCancellableCoroutine<Boolean> { cont ->
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    @Deprecated("оставлен для совместимости со старым API")
                    override fun onError(utteranceId: String?) {
                        if (cont.isActive) cont.resume(false)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (cont.isActive) cont.resume(false)
                    }
                })
                val params = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uid)
                }
                val queued = engine.synthesizeToFile(text, params, out, uid)
                if (queued != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
            }
            if (!ok || !out.exists() || out.length() == 0L) {
                return ToolResult.Failure("не удалось синтезировать речь в файл")
            }
            return ToolResult.Success(
                """{"path":"${escapeJson(out.name)}","file":"${escapeJson(out.absolutePath)}",""" +
                    """"bytes":${out.length()},"lang":"${escapeJson(lang)}"}""",
            )
        } catch (e: Exception) {
            return ToolResult.Failure("TTS: ${e.message}")
        } finally {
            tts?.shutdown()
        }
    }
}

/**
 * transcribe_audio — распознавание речи из аудиофайла в текст. SAFE.
 *
 * ЧЕСТНАЯ ОЦЕНКА: на устройстве нет движка, умеющего транскрибировать готовый файл.
 * Android SpeechRecognizer работает только с живым микрофоном; офлайн-STT из файла требует
 * модели (Vosk/whisper.cpp), серверного ASR-эндпоинта в проекте тоже нет. Поэтому инструмент
 * возвращает понятный отказ и НЕ имитирует результат (§7). Когда появится офлайн-движок или
 * серверный ASR — здесь вызываем его.
 */
class TranscribeAudioTool(
    private val context: Context,
    private val resolve: (String) -> File?,
) : AgentTool {
    override val id = "transcribe_audio"
    override val danger = DangerLevel.SAFE
    override val usesFiles = true
    override val schema =
        """распознать речь из аудиофайла в текст (офлайн, whisper); args: {"path":"audio.mp3","lang":"ru"} — lang опц (auto по умолчанию); поддерживает любые аудио (конвертирует через ffmpeg)"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val o = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return@withContext ToolResult.Failure("битый JSON")
        val file = resolve(o.optString("path"))
            ?: return@withContext ToolResult.Failure("путь вне рабочей папки")
        if (!file.exists() || !file.isFile) return@withContext ToolResult.Failure("нет файла: ${file.name}")
        val lang = o.optString("lang", "auto").ifBlank { "auto" }

        // 1) Движок whisper — из скачиваемого пака. АВТО-докачка при первом использовании (как git_*/run_java):
        // пак не выведен в UI-загрузку, поэтому тянем его сами со сверкой SHA-256 перед загрузкой .so.
        if (!ru.aiagent.app.code.RuntimePackManager.isInstalled(context, "whisper")) {
            val pack = ru.aiagent.app.code.RuntimePackManager.known.firstOrNull { it.id == "whisper" }
                ?: return@withContext ToolResult.Failure("пак Whisper недоступен")
            ru.aiagent.app.code.RuntimePackManager.install(context, pack).getOrElse {
                return@withContext ToolResult.Failure("не удалось скачать пак Whisper: ${it.message}")
            }
        }
        if (!WhisperNative.ensureLoaded(context)) {
            return@withContext ToolResult.Failure("нужно скачать пак Whisper — Настройки → модули кода")
        }
        // 2) Модель ggml (tiny, мультиязычная ~75 МБ) — качаем при первом использовании в pack-папку.
        val model = File(WhisperNative.packDir(context), "ggml-tiny.bin")
        if (!model.exists() || model.length() < 1_000_000L) {
            val dl = downloadModel(model)
            if (dl != null) return@withContext ToolResult.Failure("модель whisper не скачалась: $dl")
        }
        // 3) Приводим любое аудио к 16кГц mono WAV через ffmpeg-пак (whisper ест только это).
        val wav = File(context.cacheDir, "whisper_in_${System.currentTimeMillis()}.wav")
        val conv = convertTo16kWav(file, wav)
        if (conv != null) return@withContext ToolResult.Failure(conv)
        // 4) Распознаём.
        return@withContext try {
            val text = WhisperNative.transcribe(model.absolutePath, wav.absolutePath, if (lang == "auto") null else lang)
            if (text.startsWith("[ошибка]")) ToolResult.Failure("whisper: ${text.removePrefix("[ошибка]").trim()}")
            else ToolResult.Success("""{"text":"${escapeJson(text.trim())}","lang":"$lang"}""")
        } catch (t: Throwable) {
            ToolResult.Failure("whisper: ${t.message?.take(200)}")
        } finally {
            runCatching { wav.delete() }
        }
    }

    /** Скачать ggml-tiny (мультиязычную) с HuggingFace. Возвращает null при успехе или текст ошибки. */
    private fun downloadModel(dst: File): String? {
        return try {
            val url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"
            val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 120_000; instanceFollowRedirects = true
            }
            try {
                if (c.responseCode !in 200..299) return "HTTP ${c.responseCode}"
                val expected = c.contentLengthLong // -1, если сервер не отдал Content-Length
                val tmp = File(dst.parentFile, dst.name + ".part")
                val written = c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                // ЦЕЛОСТНОСТЬ: обрезанная загрузка (обрыв сети) даёт короткий, но >1МБ файл → битая модель
                // уронила бы нативный whisper. Сверяем прочитанное с Content-Length; несовпало — чистим и падаем.
                if (expected >= 0 && written != expected) {
                    tmp.delete()
                    return "загрузка неполная ($written из $expected байт) — попробуйте ещё раз"
                }
                if (!tmp.renameTo(dst)) { tmp.copyTo(dst, overwrite = true); tmp.delete() }
                null
            } finally { c.disconnect() }
        } catch (t: Throwable) { t.message?.take(120) ?: "сеть недоступна" }
    }

    /** Конвертировать аудио в 16кГц mono 16-bit WAV через ffmpeg-пак. null при успехе или текст ошибки. */
    private fun convertTo16kWav(src: File, dst: File): String? {
        if (!ru.aiagent.app.code.FfmpegNative.ensureLoaded(context)) {
            return "для транскрипции нужен пак FFmpeg (конвертация аудио) — Настройки → модули кода"
        }
        return try {
            val rc = ru.aiagent.app.code.FfmpegNative.run(
                arrayOf("-y", "-i", src.absolutePath, "-ar", "16000", "-ac", "1", "-f", "wav", dst.absolutePath),
            )
            if (rc != 0 || !dst.exists() || dst.length() < 44) "не удалось декодировать аудио (ffmpeg код $rc)" else null
        } catch (t: Throwable) { "конвертация аудио: ${t.message?.take(120)}" }
    }
}

/**
 * speech_to_text — распознавание речи С МИКРОФОНА (живой ввод) в текст. SAFE.
 *
 * В отличие от [TranscribeAudioTool] (готовый файл), здесь есть рабочий движок:
 * Android [SpeechRecognizer] слушает микрофон в реальном времени. На устройствах с
 * поддержкой on-device распознавания (API 33+, установлен языковой пакет) работает офлайн —
 * мы запрашиваем это через [RecognizerIntent.EXTRA_PREFER_OFFLINE]; иначе система может
 * уйти в облако штатным распознавателем Google.
 *
 * ВАЖНО (инвариант платформы): SpeechRecognizer можно создавать/стартовать/уничтожать ТОЛЬКО
 * на главном потоке — поэтому вся работа обёрнута в withContext(Dispatchers.Main).
 * Результат снимаем через suspendCancellableCoroutine + RecognitionListener
 * (onResults → resume текстом, onError → осмысленный Failure). Таймаут — args.seconds.
 *
 * Требуется разрешение RECORD_AUDIO (уже объявлено в манифесте; runtime-грант — на стороне UI).
 * Аналог движка — [ru.aiagent.voice.AndroidStt] в модуле :voice; здесь сделано по той же схеме,
 * но синхронным одноразовым вызовом (одна фраза → текст), удобным как инструмент агента.
 */
class SpeechToTextTool(
    private val context: Context,
) : AgentTool {
    override val id = "speech_to_text"
    override val danger = DangerLevel.SAFE
    override val schema =
        """распознать речь с микрофона в реальном времени (офлайн при поддержке устройства); """ +
            """args: {"seconds":10,"lang":"ru"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val o = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return ToolResult.Failure("битый JSON")
        val seconds = o.optInt("seconds", 10).coerceIn(1, 120)
        val lang = o.optString("lang", "ru").ifBlank { "ru" }

        // SpeechRecognizer create/start/destroy — строго на главном потоке.
        return withContext(Dispatchers.Main) {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                return@withContext ToolResult.Failure("распознавание речи недоступно на устройстве")
            }
            var recognizer: SpeechRecognizer? = null
            try {
                // Таймаут: если за seconds пользователь не заговорил / нет результата — отменяем.
                val outcome: Result<String>? = withTimeoutOrNull(seconds * 1000L) {
                    suspendCancellableCoroutine<Result<String>> { cont ->
                        val rec = SpeechRecognizer.createSpeechRecognizer(context)
                        recognizer = rec
                        rec.setRecognitionListener(object : RecognitionListener {
                            override fun onResults(results: Bundle?) {
                                val text = results
                                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                    ?.firstOrNull().orEmpty()
                                if (cont.isActive) cont.resume(Result.success(text))
                            }

                            override fun onError(error: Int) {
                                if (cont.isActive) {
                                    cont.resume(Result.failure(Exception(sttErrorMessage(error))))
                                }
                            }

                            override fun onReadyForSpeech(p0: Bundle?) {}
                            override fun onBeginningOfSpeech() {}
                            override fun onRmsChanged(p0: Float) {}
                            override fun onBufferReceived(p0: ByteArray?) {}
                            override fun onEndOfSpeech() {}
                            override fun onPartialResults(p0: Bundle?) {}
                            override fun onEvent(p0: Int, p1: Bundle?) {}
                        })
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                            )
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag(lang))
                            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                        }
                        rec.startListening(intent)
                        // Отмена (таймаут/cancel корутины) — тоже на main-потоке, гасим сессию.
                        cont.invokeOnCancellation {
                            runCatching { rec.cancel() }
                            runCatching { rec.destroy() }
                        }
                    }
                }
                when {
                    outcome == null ->
                        ToolResult.Failure("таймаут распознавания ($seconds c): речь не услышана")
                    outcome.isFailure ->
                        ToolResult.Failure("STT: ${outcome.exceptionOrNull()?.message}")
                    outcome.getOrDefault("").isBlank() ->
                        ToolResult.Failure("речь не распознана")
                    else ->
                        ToolResult.Success(
                            """{"text":"${escapeJson(outcome.getOrThrow())}",""" +
                                """"lang":"${escapeJson(lang)}"}""",
                        )
                }
            } finally {
                runCatching { recognizer?.destroy() }
            }
        }
    }

    /** BCP-47 тег для EXTRA_LANGUAGE: "ru" → "ru-RU"; уже полные ("en-US") — как есть. */
    private fun langTag(lang: String): String = when {
        lang.contains('-') -> lang
        lang.equals("ru", ignoreCase = true) -> "ru-RU"
        else -> Locale(lang).toLanguageTag()
    }

    private fun sttErrorMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "ошибка записи звука"
        SpeechRecognizer.ERROR_CLIENT -> "ошибка клиента распознавания"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "нет разрешения на микрофон (RECORD_AUDIO)"
        SpeechRecognizer.ERROR_NETWORK -> "сеть недоступна"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "таймаут сети"
        SpeechRecognizer.ERROR_NO_MATCH -> "речь не распознана"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "распознаватель занят"
        SpeechRecognizer.ERROR_SERVER -> "ошибка сервера распознавания"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "речь не услышана"
        else -> "ошибка распознавания ($code)"
    }
}

/** Сборка аудио-инструментов. */
fun audioTools(context: Context, resolve: (String) -> File?): List<AgentTool> =
    listOf(
        TextToSpeechTool(context, resolve),
        SpeechToTextTool(context),
    )
