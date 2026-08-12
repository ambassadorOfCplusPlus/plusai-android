package ru.aiagent.app.integrations

import android.content.Context
import ru.aiagent.app.cloud.SecureKeys

/**
 * Общая обвязка OAuth-коннекторов: хранение доступа в Keystore и обновление истёкшего access.
 *
 * ЗАЧЕМ. Раньше каждый коннектор (Google Диск, Dropbox, OneDrive, Яндекс Диск) нёс собственную
 * копию одного и того же: объект-хранилище с парой ключей prefs, `account/save/updateAccess/
 * clear/isConnected` и приватную функцию «обновить токен и вернуть свежий аккаунт». Четыре копии
 * расходились в мелочах и каждый новый коннектор начинался с копипасты. Сам сетевой flow всегда
 * был общим — сервер-брокер [OAuthBroker], — так что различие сводилось к трём строкам:
 * имя провайдера и два ключа prefs.
 */
data class OAuthAccount(val access: String, val refresh: String?)

/**
 * Хранилище OAuth-доступа одного коннектора. [keyAccess]/[keyRefresh] — имена ключей в Keystore;
 * передаются явно и ДОЛЖНЫ совпадать с историческими, иначе обновление приложения молча разлогинит
 * пользователей (токены остались бы лежать под старыми именами).
 *
 * Имена методов сохранены прежними (`isConnected`/`save`/`clear`) — по ним ходят экран интеграций
 * и сборка набора инструментов.
 */
open class OAuthStore(
    val provider: String,
    private val keyAccess: String,
    private val keyRefresh: String,
) {
    fun account(context: Context): OAuthAccount? {
        val s = SecureKeys.get(context)
        val a = s.getString(keyAccess, null)?.takeIf { it.isNotBlank() } ?: return null
        return OAuthAccount(a, s.getString(keyRefresh, null))
    }

    fun save(context: Context, access: String, refresh: String?) {
        SecureKeys.get(context).edit().putString(keyAccess, access).putString(keyRefresh, refresh).apply()
        ru.aiagent.app.cloud.AutoSync.schedulePush(context)
    }

    /** Обновить access (refresh перезаписываем только если провайдер прислал новый). */
    fun updateAccess(context: Context, newAccess: String, newRefresh: String?) {
        SecureKeys.get(context).edit().putString(keyAccess, newAccess)
            .apply { if (newRefresh != null) putString(keyRefresh, newRefresh) }.apply()
        ru.aiagent.app.cloud.AutoSync.schedulePush(context)
    }

    fun clear(context: Context) {
        SecureKeys.get(context).edit().remove(keyAccess).remove(keyRefresh).apply()
        ru.aiagent.app.cloud.AutoSync.schedulePush(context)
    }

    fun isConnected(context: Context): Boolean = account(context) != null

    /**
     * Обменять refresh на свежий access через сервер-брокер, сохранить и вернуть новый аккаунт.
     * null — refresh отсутствует или обмен не удался (вызывающий отдаёт исходную ошибку).
     */
    suspend fun refreshed(context: Context, acc: OAuthAccount): OAuthAccount? {
        if (acc.refresh.isNullOrBlank()) return null
        val tok = OAuthBroker.refresh(context, provider, acc.refresh).getOrNull() ?: return null
        updateAccess(context, tok.access, tok.refresh.takeIf { it.isNotBlank() })
        return account(context)
    }

    /**
     * То же, но для клиентов, которые сообщают о провале исключением, а не HTTP-кодом (Яндекс Диск):
     * обновляемся, только если ошибка похожа на авторизационную — иначе сеть/404 жгли бы refresh.
     */
    suspend fun refreshedIfAuthError(context: Context, acc: OAuthAccount, err: Throwable): OAuthAccount? {
        if (!looksLikeAuthError(err.message)) return null
        return refreshed(context, acc)
    }

    /** Сессия работы с коннектором, или null — не подключён (вызывающий отдаёт «подключите в Настройках»). */
    fun session(context: Context): OAuthSession? = account(context)?.let { OAuthSession(this, context, it) }
}

/**
 * Сессия одного инструмента: держит актуальный access и сама чинит протухший.
 *
 * ЗАЧЕМ. Повтор «401 → обновить токен → повторить запрос» раньше был расписан руками у каждого
 * вызова (около двадцати мест на четыре коннектора), причём в туле из нескольких запросов подряд
 * приходилось ещё и протаскивать свежий аккаунт в следующие вызовы (`acc = fresh`). Сессия хранит
 * обновлённый аккаунт у себя, поэтому все последующие запросы автоматически идут со свежим токеном.
 *
 * Повтор ровно один: если и после обновления 401 — токен отозван, и цикл ничего не спасёт.
 */
class OAuthSession(
    private val store: OAuthStore,
    private val context: Context,
    private var acc: OAuthAccount,
) {
    /** Текущий access — для запросов, которые не умеют сообщать об отказе (или его не ожидают). */
    val access: String get() = acc.access

    /**
     * Запрос, сообщающий об отказе HTTP-кодом. [codeOf] достаёт код из результата: у клиентов это
     * либо `Pair<Int, String>` (код и тело), либо просто `Int`.
     */
    suspend fun <T> call(codeOf: (T) -> Int, block: suspend (String) -> T): T =
        retryOnUnauthorized(acc.access, { codeOf(it) == 401 }, refreshToken, block)

    /**
     * Запрос, сообщающий об отказе исключением внутри [Result] (Яндекс Диск): повторяем, только если
     * ошибка похожа на авторизационную — иначе сеть/404 жгли бы refresh впустую.
     */
    suspend fun <T> callResult(block: suspend (String) -> Result<T>): Result<T> =
        retryOnUnauthorized(acc.access, { looksLikeAuthError(it.exceptionOrNull()?.message) }, refreshToken, block)

    /** Обновление токена с запоминанием свежего аккаунта — отсюда и «сессия». */
    private val refreshToken: suspend () -> String? =
        { store.refreshed(context, acc)?.also { acc = it }?.access }
}

/**
 * Ядро автоповтора, вынесенное из [OAuthSession] без зависимостей от Android — чтобы поведение
 * («ровно один повтор», «неудачный refresh отдаёт исходный ответ») было покрыто unit-тестами.
 *
 * [isUnauthorized] решает по результату, что токен протух; [refresh] возвращает свежий токен или
 * null, если обновиться нечем. Повтор ровно один: если и после обновления отказ — токен отозван.
 */
internal suspend fun <T> retryOnUnauthorized(
    token: String,
    isUnauthorized: (T) -> Boolean,
    refresh: suspend () -> String?,
    block: suspend (String) -> T,
): T {
    val first = block(token)
    if (!isUnauthorized(first)) return first
    val fresh = refresh() ?: return first
    return block(fresh)
}

/**
 * Похожа ли ошибка на авторизационную. Нужна там, где клиент сообщает об отказе исключением, а не
 * HTTP-кодом: на сетевой ошибке или 404 обновлять токен нельзя — это впустую жжёт refresh.
 */
internal fun looksLikeAuthError(message: String?): Boolean {
    val m = (message ?: "").lowercase()
    return m.contains("401") || m.contains("auth") || m.contains("unauthorized") || m.contains("expired")
}
