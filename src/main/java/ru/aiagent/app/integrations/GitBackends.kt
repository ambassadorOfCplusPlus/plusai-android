package ru.aiagent.app.integrations

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.aiagent.app.code.LibraryPackLoader
import ru.aiagent.app.code.RuntimePackManager

/** Достаёт [GitBackend] из установленного пака `jgit`. null → пак не скачан/битый (проси установить). */
object GitBackends {
    private const val IMPL = "ru.aiagent.app.integrations.GitBackendImpl"

    @Volatile
    private var cached: GitBackend? = null

    // Сериализует проверку-и-установку пака: без него параллельные git-вызовы дважды тянут install
    // на общий tmp-zip (гонка). get() остаётся @Synchronized — это дёшево и защищает кэш отдельно.
    private val installMutex = Mutex()

    /**
     * Backend с АВТО-скачиванием пака `jgit` (~1.2 МБ) при первом использовании — чтобы git-инструменты
     * работали без ручного захода в настройки. Если пак не установлен, тянем его со сверкой SHA-256
     * (fail-closed), затем грузим. null — если и скачать не удалось (нет сети/сервер недоступен).
     */
    suspend fun ensure(context: Context): GitBackend? {
        get(context)?.let { return it }
        installMutex.withLock {
            // Повторная проверка под локом: пока ждали, другой вызов мог уже установить пак.
            if (!RuntimePackManager.isInstalled(context, "jgit")) {
                val pack = RuntimePackManager.known.firstOrNull { it.id == "jgit" } ?: return null
                RuntimePackManager.install(context, pack).getOrNull() ?: return null
            }
            return get(context)
        }
    }

    /**
     * Экземпляр реализации из пака `jgit`, или null если пак не установлен. Класс [GitBackendImpl]
     * грузится DexClassLoader-ом пака (родитель = загрузчик приложения), поэтому он видит и JGit из
     * пака, и интерфейс [GitBackend] из приложения — приведение `as GitBackend` корректно (это тот же
     * Class, что и в APK: DexClassLoader делегирует его загрузку родителю-приложению).
     */
    @Synchronized
    fun get(context: Context): GitBackend? {
        cached?.let { return it }
        val cl = LibraryPackLoader.loader(context, "jgit") ?: return null
        return runCatching {
            cl.loadClass(IMPL).getDeclaredConstructor().newInstance() as GitBackend
        }.getOrNull()?.also { cached = it }
    }

    /** Сбросить закэшированный backend — после удаления/переустановки пака `jgit`, иначе остался бы
     *  экземпляр из старого DexClassLoader (старый код) поверх уже удалённых файлов. */
    fun invalidate() { cached = null }
}
