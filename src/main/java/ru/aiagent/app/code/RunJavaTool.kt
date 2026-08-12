package ru.aiagent.app.code

import android.content.Context
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import ru.aiagent.core.agent.tools.escapeJson
import ru.aiagent.core.agent.tools.jsonField
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException

/**
 * Исполнение Java-кода на устройстве (S7) через BeanShell — интерпретатор Java,
 * работающий на ART без JDK/компилятора. Поддерживает основной синтаксис Java,
 * System.out.println и т.д. IMPORTANT: может создавать файлы.
 *
 * BeanShell вынесен в СКАЧИВАЕМЫЙ пак `java` (не в APK — экономия размера): классы грузятся
 * [LibraryPackLoader]-ом из пака и зовутся рефлексией (поверхность крошечная — Interpreter/eval).
 */
class RunJavaTool(private val context: Context) : AgentTool {
    override val id = "run_java"
    // Произвольный JVM-код без изоляции (см. RunPythonTool): IMPORTANT + alwaysConfirm.
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true
    override val schema =
        """выполнить java-код (BeanShell); args: {"code":"System.out.println(2+2);"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val code = jsonCode(argsJson) ?: return ToolResult.Failure("нет args.code")
        // BeanShell исполняет произвольный Java на ART и НЕ песочится (в отличие от Rhino/LuaJ). Значит
        // побег в процесс/рефлексию/загрузку классов режем ДО eval и НЕ зависимо от режима — иначе в bypass
        // (где alwaysConfirm обнуляется) код вида exec("…")/Runtime/reflect = молчаливый RCE. Создание файлов
        // — заявленная возможность инструмента, его не трогаем; целостность ФС стережёт DangerPolicy.
        forbiddenInJava(code)?.let { return ToolResult.Failure("запрещено песочницей: $it") }
        // BeanShell грузится из скачиваемого пака `java`. АВТО-докачка при первом использовании (как git_*
        // в GitBackends.ensure): пак не выведен в UI-загрузку, поэтому тянем его сами со сверкой SHA-256.
        if (!RuntimePackManager.isInstalled(context, "java")) {
            val pack = RuntimePackManager.known.firstOrNull { it.id == "java" }
                ?: return ToolResult.Failure("пак Java (BeanShell) недоступен")
            RuntimePackManager.install(context, pack).getOrElse {
                return ToolResult.Failure("не удалось скачать пак Java (BeanShell): ${it.message}")
            }
        }
        val cl = LibraryPackLoader.loader(context, "java")
            ?: return ToolResult.Failure("нужно скачать пак Java (BeanShell) — Настройки → модули кода")
        val baos = ByteArrayOutputStream()
        val ps = PrintStream(baos, true, "UTF-8")
        // Без глобального лока (иначе брошенный по тайм-ауту поток навсегда заблокировал бы
        // инструмент). System.out восстанавливаем в finally best-effort; редкий бесконечный
        // цикл (тайм-аут) может оставить перенаправление — приемлемо (приложение пишет в Log).
        val res = watchdog(30_000, "java") {
            val oldOut = System.out
            try {
                // bsh.Interpreter из пака — через рефлексию (класса нет на базовом classpath).
                val ic = cl.loadClass("bsh.Interpreter")
                val interp = ic.getDeclaredConstructor().newInstance()
                ic.getMethod("setOut", PrintStream::class.java).invoke(interp, ps)
                System.setOut(ps) // чтобы System.out.println из кода тоже попал в буфер
                ic.getMethod("eval", String::class.java).invoke(interp, code)
                baos.toString("UTF-8")
            } finally {
                if (System.out === ps) System.setOut(oldOut) // не перетереть чужой redirect
            }
        }
        return res.fold(
            onSuccess = { ToolResult.Success("""{"stdout":"${esc(it.ifBlank { "(выполнено, вывода нет)" }.take(8000))}"}""") },
            onFailure = {
                // рефлексия оборачивает ошибку eval в InvocationTargetException — разворачиваем для сообщения.
                val e = (it as? InvocationTargetException)?.targetException ?: it
                ToolResult.Failure("java: ${e.message?.take(300)}")
            },
        )
    }
}

/**
 * Денилист побега для BeanShell (несандбоксируемого). Возвращает имя запрещённой конструкции или null.
 * Сверяем и по исходному коду, и по варианту без пробелов (`Runtime . getRuntime` → одно слово), чтобы
 * пробельный обход не проскочил. Склейку строк статикой не побить — остаточный риск (см. DangerPolicy).
 */
internal fun forbiddenInJava(code: String): String? {
    val c = code.lowercase()
    val cNorm = c.replace(Regex("\\s+"), "")
    val banned = listOf(
        "runtime", "processbuilder", "system.exit", "classloader", "loadclass",
        "java.lang.reflect", "getdeclaredmethod", "getmethod", ".invoke(", "setaccessible",
        "exec(", // встроенная команда BeanShell exec(...) = запуск нативной команды
        "scriptengine", "defineclass", "sun.misc", "unsafe",
        // Нативный побег: System.load/loadLibrary грузит .so → JNI_OnLoad = произвольный нативный код.
        // BeanShell позволяет писать файлы (java.io.File не забанен) → мог записать .so и загрузить.
        "system.load", "loadlibrary", "nativeload",
    )
    return banned.firstOrNull { c.contains(it) || cNorm.contains(it.replace(" ", "")) }
}

/** Значение поля code — единый извлекатель [jsonField]. */
private fun jsonCode(json: String): String? = jsonField(json, "code")

private fun esc(s: String) = escapeJson(s)
