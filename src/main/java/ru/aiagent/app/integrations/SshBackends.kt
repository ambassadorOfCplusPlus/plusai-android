package ru.aiagent.app.integrations

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.aiagent.app.code.LibraryPackLoader
import ru.aiagent.app.code.RuntimePackManager

/** Достаёт [SshBackend] из установленного пака `ssh`. null → пак не скачан/битый (проси установить). */
object SshBackends {
    private const val IMPL = "ru.aiagent.app.integrations.SshBackendImpl"

    @Volatile
    private var cached: SshBackend? = null

    // Сериализует проверку-и-установку пака (параллельные ssh-вызовы иначе дважды тянут install на общий tmp).
    private val installMutex = Mutex()

    /** Backend с АВТО-скачиванием пака `ssh` при первом использовании (сверка SHA-256, fail-closed).
     *  null — если пак не опубликован/скачать не удалось. */
    suspend fun ensure(context: Context): SshBackend? {
        get(context)?.let { return it }
        installMutex.withLock {
            if (!RuntimePackManager.isInstalled(context, "ssh")) {
                val pack = RuntimePackManager.known.firstOrNull { it.id == "ssh" } ?: return null
                RuntimePackManager.install(context, pack).getOrNull() ?: return null
            }
            return get(context)
        }
    }

    /** Экземпляр [SshBackendImpl] из пака `ssh` (DexClassLoader, родитель = приложение → `as SshBackend`
     *  корректно, интерфейс тот же, что в APK), или null если пак не установлен. */
    @Synchronized
    fun get(context: Context): SshBackend? {
        cached?.let { return it }
        val cl = LibraryPackLoader.loader(context, "ssh") ?: return null
        return runCatching {
            cl.loadClass(IMPL).getDeclaredConstructor().newInstance() as SshBackend
        }.getOrNull()?.also { cached = it }
    }

    /** Сбросить кэш после удаления/переустановки пака `ssh`. */
    fun invalidate() { cached = null }
}
