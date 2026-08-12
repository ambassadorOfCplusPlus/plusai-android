package ru.aiagent.app.code

import android.content.Context
import dalvik.system.DexClassLoader
import java.io.File

/**
 * Загрузчик JVM-БИБЛИОТЕК из скачиваемых паков (не в APK — экономия базового размера). Пак содержит либо
 * голый `classes.dex` (d8 от jar библиотеки — если ей не нужны ресурсы, напр. BeanShell), либо АРХИВ
 * `impl.jar` = { classes.dex + ресурсы (.properties и т.п.) } — если библиотека читает ресурсы через
 * ClassLoader.getResourceAsStream (напр. JGit грузит переводы JGitText.properties при инициализации, а
 * bare-dex ресурсы НЕ несёт → TranslationBundleLoadingException). [DexClassLoader], которому дан путь к
 * такому jar, отдаёт и классы (из вложенного classes.dex), и ресурсы (из остальных записей архива).
 *
 * Скачивается/сверяется/распаковывается через [RuntimePackManager]. Родитель загрузчика — загрузчик
 * приложения (чтобы либа видела framework/androidx и общие интерфейсы вроде GitBackend). Целостность
 * стережёт RuntimePackManager (SHA-256, fail-closed). Dex/jar делаем read-only перед загрузкой — иначе
 * ART на Android 10+ отклоняет «Writable dex file is not allowed» (W^X).
 */
object LibraryPackLoader {
    private val cache = HashMap<String, ClassLoader>()

    /** Загрузчик классов библиотеки из пака [packId], или null если пак не установлен/битый. */
    @Synchronized
    fun loader(context: Context, packId: String): ClassLoader? {
        cache[packId]?.let { return it }
        if (!RuntimePackManager.isInstalled(context, packId)) return null
        val dir = RuntimePackManager.installDir(context, packId)
        // Предпочитаем архив impl.jar (classes.dex + ресурсы); иначе — голый classes.dex.
        val jar = dir.listFiles { f -> f.isFile && f.name.endsWith(".jar") }?.firstOrNull()
        val src = jar ?: File(dir, "classes.dex")
        if (!src.exists() || src.length() == 0L) return null
        runCatching { if (src.canWrite()) src.setReadOnly() } // W^X: dex/jar должен быть read-only
        val opt = File(context.codeCacheDir, "libpack_$packId").apply { mkdirs() }
        return runCatching {
            DexClassLoader(src.absolutePath, opt.absolutePath, null, context.classLoader)
        }.getOrNull()?.also { cache[packId] = it }
    }

    /** Сбросить закэшированный загрузчик пака [id] — после удаления/переустановки пака, иначе остался бы
     *  старый DexClassLoader (старый код) поверх уже удалённых/обновлённых файлов. */
    @Synchronized
    fun invalidate(id: String) { cache.remove(id) }
}
