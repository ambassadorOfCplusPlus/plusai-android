package ru.aiagent.app.code

import android.content.Context
import dalvik.system.DexClassLoader
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import ru.aiagent.core.agent.tools.escapeJson
import ru.aiagent.core.agent.tools.jsonField
import java.io.File

/**
 * run_kotlin — исполнить Kotlin-код НА ТЕЛЕФОНЕ (модуль `kotlin`, [CodeModules.Kind.TOOLCHAIN_PACK]).
 *
 * Почему это возможно без десктопа (и почему clang/go — нет): цепочка `kotlinc → JVM-байткод → D8 →
 * DEX → DexClassLoader`. Финальный шаг — загрузка dex класслоадером, а НЕ `execve` скачанного бинарника,
 * поэтому SELinux/W^X на Android 10+ не мешает (так же грузятся dynamic feature modules). Нативным
 * тулчейнам нужен именно execve — их вынесли в десктоп Ф2.
 *
 * АРХИТЕКТУРА (тот же принцип, что у run_c → нативный пак): всю хрупкую обвязку компилятора и D8 несёт
 * САМ ПАК, а не это приложение. Пак поставляет дексованный драйвер-класс с фиксированной сигнатурой:
 *
 *   package ru.aiagent.kotlinpack
 *   object KotlinRunner {                    // (или class со статическим методом)
 *       @JvmStatic fun run(code: String, workspaceDir: String): String
 *   }
 *
 * `run` внутри пака: K2JVMCompiler компилирует `code` в .class (в кэш), D8 дексует .class → classes.dex,
 * дочерний DexClassLoader грузит его и вызывает `main`, stdout перехватывается и возвращается строкой;
 * при ошибке компиляции — бросает исключение с диагностикой. Приложение здесь делает лишь СТАБИЛЬНУЮ
 * рефлексию по одному методу — детали компилятора/D8 (которые меняются от версии к версии) в приложении
 * не захардкожены. Драйвер собирается и проверяется на машине владельца, где эти API реально доступны.
 *
 * КОНТРАКТ ПАКА (kotlin.zip, распаковывается в filesDir/runtimes/kotlin):
 *   - один или несколько дексованных контейнеров .jar / .dex / .apk (dex внутри): Android-совместимый
 *     kotlin-compiler-embeddable (пропатченный под ART, как в AndroidIDE) + kotlin-stdlib + D8 (r8) +
 *     класс-драйвер ru.aiagent.kotlinpack.KotlinRunner;
 *   - раздаётся сервером с обязательным SHA-256 (fail-closed, см. RuntimePackManager) — иначе подменённый
 *     dex исполнился бы как RCE.
 *
 * IMPORTANT + alwaysConfirm: исполнение произвольного JVM-кода без изоляции (как run_java/run_python) —
 * подтверждение ВСЕГДА, кроме bypass. Инструмент выдаётся агенту только когда пак установлен (CodeModules).
 */
class RunKotlinTool(private val context: Context) : AgentTool {
    override val id = "run_kotlin"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true
    override val schema =
        """выполнить Kotlin-код (компиляция на устройстве); вывод — println(...) внутри fun main(); args: {"code":"fun main(){ println(2+2) }"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val code = jsonField(argsJson, "code") ?: return ToolResult.Failure("нет args.code")
        if (!RuntimePackManager.isInstalled(context, "kotlin")) {
            return ToolResult.Failure("модуль Kotlin не установлен — включите/скачайте его в Настройках → Модули")
        }
        // Kotlin-код компилируется в JVM-байткод и грузится DexClassLoader'ом с правами приложения — как
        // BeanShell (run_java), он НЕ песочится. Значит побег в процесс/рефлексию режем ДО компиляции и
        // НЕ зависимо от режима: иначе в bypass (где alwaysConfirm обнуляется) reflective exec = тихий RCE.
        // Раньше у run_kotlin денилиста не было (в отличие от run_java) — дыра, закрыта здесь.
        forbiddenInKotlin(code)?.let { return ToolResult.Failure("запрещено песочницей: $it") }
        // Компиляция kotlinc на устройстве — тяжёлая (секунды на холодную), потому лимит крупнее раннеров.
        return watchdog(90_000, "kotlin") {
            val runner = runnerClass(context)
            val workspace = File(context.filesDir, "workspace").apply { mkdirs() }.absolutePath
            // packDir (kotlin-home) — реальный installDir пака. Драйвер под DexClassLoader на ART НЕ может
            // определить его сам (codeSource.location=null → падал на fallback /data/local/tmp/kpack,
            // «Kotlin home does not exist»). Передаём явно; дублируем через системное свойство — вдруг
            // на устройстве стоит промежуточная сборка драйвера, читающая только его.
            val packDir = RuntimePackManager.installDir(context, "kotlin").absolutePath
            System.setProperty("plusai.kotlin.home", packDir)
            // Предпочитаем 3-арг run(code, workspace, packDir); старый драйвер (2-арг) — совместимый fallback.
            val run3 = runCatching {
                runner.getMethod("run", String::class.java, String::class.java, String::class.java)
            }.getOrNull()
            val out = if (run3 != null) run3.invoke(null, code, workspace, packDir)
            else runner.getMethod("run", String::class.java, String::class.java).invoke(null, code, workspace)
            // Драйвер возвращает готовый stdout (или бросает исключение с диагностикой компилятора).
            (out as? String).orEmpty()
        }.fold(
            onSuccess = { ToolResult.Success("""{"stdout":"${escapeJson(it.ifBlank { "(выполнено, вывода нет)" }.take(8000))}"}""") },
            // getCause: рефлексия оборачивает исключения драйвера в InvocationTargetException — разворачиваем.
            onFailure = { ToolResult.Failure("kotlin: ${((it as? java.lang.reflect.InvocationTargetException)?.cause ?: it).message?.take(400)}") },
        )
    }

    private companion object {
        // Загрузка компилятора дорогая — держим DexClassLoader/класс-драйвер закэшированным. Инвалидация
        // по метке .ready (переустановка пака меняет её), чтобы новый пак подхватился без рестарта.
        @Volatile private var cached: Pair<Long, Class<*>>? = null

        @Synchronized
        fun runnerClass(context: Context): Class<*> {
            val dir = RuntimePackManager.installDir(context, "kotlin")
            val stamp = File(dir, ".ready").lastModified()
            cached?.let { (s, c) -> if (s == stamp) return c }
            // Грузим ТОЛЬКО runtime.jar (в нём dex компилятора+r8+драйвера + .class/ресурсы). Остальное в
            // паке (lib/kotlin-stdlib.jar, android.jar) — classpath-ФАЙЛЫ для компилятора, НЕ dex: если сунуть
            // их в DexClassLoader, он упадёт (нет classes.dex). minSdk 29: DexClassLoader принимает .jar с dex.
            val runtime = File(dir, "runtime.jar")
            if (!runtime.exists()) error("пак kotlin повреждён (нет runtime.jar) — переустановите модуль")
            // Android 10+ (API 29): ART отклоняет загрузку dex из ЗАПИСЫВАЕМОГО файла ("Writable dex file
            // '…' is not allowed" — защита W^X от подмены кода после верификации). Пак распаковывается с
            // правом записи, поэтому снимаем его ДО DexClassLoader. Тот же снимок и с оптимизированным dex
            // в codeCacheDir — он создаётся ART-ом и уже read-only, трогать не нужно.
            if (runtime.canWrite()) runtime.setReadOnly()
            val loader = DexClassLoader(runtime.absolutePath, context.codeCacheDir.absolutePath, null, RunKotlinTool::class.java.classLoader)
            val c = loader.loadClass("ru.aiagent.kotlinpack.KotlinRunner")
            cached = stamp to c
            return c
        }
    }
}

/**
 * Денилист побега для Kotlin-раннера (несандбоксируемого — как BeanShell). Возвращает имя запрещённой
 * конструкции или null. Сверяем и по исходному коду, и по варианту без пробелов (`Runtime . getRuntime`
 * → одно слово), чтобы пробельный обход не проскочил. Склейку строк статикой не побить — остаточный
 * риск (см. DangerPolicy). Зеркалит forbiddenInJava + Kotlin-специфика (Runtime/ProcessBuilder/
 * exitProcess/Class.forName/DexClassLoader).
 */
internal fun forbiddenInKotlin(code: String): String? {
    val c = code.lowercase()
    val cNorm = c.replace(Regex("\\s+"), "")
    val banned = listOf(
        "runtime", "processbuilder", "exitprocess", "system.exit", "classloader", "loadclass",
        "class.forname", "java.lang.reflect", "getdeclaredmethod", "getmethod", ".invoke(", "setaccessible",
        "exec(", "dexclassloader", "pathclassloader", "scriptengine", "defineclass", "sun.misc", "unsafe",
        "processimpl",
        // Нативный побег: System.load/loadLibrary грузит .so → JNI_OnLoad = произвольный нативный код
        // с правами приложения (в песочнице был отдельной дырой — код мог записать .so в filesDir и
        // загрузить, минуя весь остальной денилист). CInterp доказывает, что это работает на цели.
        "system.load", "loadlibrary", "nativeload",
    )
    return banned.firstOrNull { c.contains(it) || cNorm.contains(it.replace(" ", "")) }
}
