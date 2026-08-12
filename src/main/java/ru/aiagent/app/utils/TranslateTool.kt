package ru.aiagent.app.utils

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import ru.aiagent.core.agent.tools.escapeJson
import ru.aiagent.core.agent.tools.jsonField

/**
 * translate — перевод текста НА УСТРОЙСТВЕ (ML Kit, офлайн, бесплатно). Первый перевод для пары языков
 * скачивает модель (~30 МБ, нужна сеть один раз), дальше — офлайн. from:auto определяет язык сам.
 */
class TranslateTool(@Suppress("unused") private val context: Context) : AgentTool {
    override val id = "translate"
    override val danger = DangerLevel.SAFE
    override val schema =
        """перевести текст (на устройстве, офлайн); args: {"text":"...","to":"en","from":"auto"} — from по умолчанию auto (язык определится сам); коды языков ISO (ru, en, de, fr, es, zh, …)"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val text = jsonField(argsJson, "text") ?: return@withContext ToolResult.Failure("нет args.text")
        val to = jsonField(argsJson, "to") ?: return@withContext ToolResult.Failure("нужен args.to (код языка, напр. en)")
        val fromArg = jsonField(argsJson, "from")?.takeIf { !it.equals("auto", true) }

        // Исходный язык: задан явно или определяем ML Kit-ом по тексту.
        val srcTag = fromArg ?: run {
            val id = LanguageIdentification.getClient()
            val tag = runCatching { Tasks.await(id.identifyLanguage(text)) }.getOrDefault("und")
            id.close()
            if (tag == "und") return@withContext ToolResult.Failure("не удалось определить язык — укажи from явно")
            tag
        }
        val src = TranslateLanguage.fromLanguageTag(srcTag)
            ?: return@withContext ToolResult.Failure("язык источника «$srcTag» не поддерживается")
        val tgt = TranslateLanguage.fromLanguageTag(to)
            ?: return@withContext ToolResult.Failure("язык перевода «$to» не поддерживается")
        if (src == tgt) return@withContext ToolResult.Success("""{"from":"$srcTag","to":"$to","text":"${escapeJson(text)}"}""")

        val translator = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build(),
        )
        try {
            // Модель пары языков: скачиваем при первом использовании (иначе перевод падает).
            runCatching { Tasks.await(translator.downloadModelIfNeeded()) }
                .onFailure { return@withContext ToolResult.Failure("не удалось скачать модель перевода (нужна сеть один раз): ${it.message?.take(80)}") }
            val out = Tasks.await(translator.translate(text))
            ToolResult.Success("""{"from":"$srcTag","to":"$to","text":"${escapeJson(out)}"}""")
        } catch (t: Throwable) {
            ToolResult.Failure("перевод: ${t.message?.take(120)}")
        } finally {
            translator.close()
        }
    }
}
