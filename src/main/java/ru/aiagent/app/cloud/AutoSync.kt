package ru.aiagent.app.cloud

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.aiagent.app.AppScope
import ru.aiagent.app.SecretStore
import ru.aiagent.app.SshStore
import ru.aiagent.app.data.Conversation
import ru.aiagent.app.data.ConversationStore
import ru.aiagent.app.data.StoredMsg
import ru.aiagent.app.integrations.DiscordAuth
import ru.aiagent.app.integrations.DriveAuth
import ru.aiagent.app.integrations.DropboxAuth
import ru.aiagent.app.integrations.GitHubAuth
import ru.aiagent.app.integrations.MailAuth
import ru.aiagent.app.integrations.OneDriveAuth
import ru.aiagent.app.integrations.OAuthStore
import ru.aiagent.app.integrations.DiskAuth
import ru.aiagent.core.cloud.CloudProvider
import java.util.concurrent.atomic.AtomicBoolean

/**
 * АВТО-СИНХ устройства под одним аккаунтом (E2E). Всё, что настроено/написано на одном устройстве,
 * автоматически уезжает на сервер (шифрованным) и приходит на остальные: настройки облака и ключи,
 * интеграции, тайны, SSH, история чатов. Ключ — от ПАРОЛЯ АККАУНТА (тот же, что при входе): на другом
 * устройстве тот же аккаунт + тот же пароль дают тот же ключ, поэтому ничего настраивать не нужно.
 *
 * Формат (совместим с десктоп/CLI, там тот же SyncClient/DeriveKey):
 *   profile/v1 — настройки и ключи одним блобом (LWW: последний push побеждает);
 *   chat/<id>   — история одной беседы (LWW по updatedAt, чтобы не потерять свежее).
 *
 * Сервер (§7) видит только непрозрачные шифроблобы. При смене пароля аккаунта ключ меняется, старые
 * блобы становятся нечитаемыми — синк сбрасывается (POST /v1/sync/reset) и начинается заново.
 */
object AutoSync {
    private const val PROFILE_ID = "profile/v1"
    private const val CHAT_PREFIX = "chat/"
    private const val KEY_CURSOR = "sync_cursor"
    private const val PROFILE_CACHE = "sync_profile_cache"
    private const val DEBOUNCE_MS = 3000L

    private val handler = Handler(Looper.getMainLooper())
    private val pushPending = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    @Volatile private var pullOnStartDone = false

    /** Применение чужого профиля/чатов — НЕ порождает эхо-push (иначе pull → apply → push → бесконечно). */
    @Volatile internal var applying = false

    /** Ключ синка от пароля аккаунта (пусто — пароль не сохранён/не вошли). */
    fun syncKey(context: Context): ByteArray? {
        val sess = Account.current(context) ?: return null
        val pass = Account.accountPassword(context)
        if (sess.login.isBlank() || pass.isBlank()) return null
        return SyncCrypto.deriveKey(pass, sess.login)
    }

    /** Синк активен: есть аккаунт, сохранённый пароль и ключ. */
    fun enabled(context: Context): Boolean = syncKey(context) != null

    /** Отложенный push: изменения (настройки/беседа/интеграция) копятся и уезжают разом. */
    fun schedulePush(context: Context) {
        if (applying) return
        if (!enabled(context)) return
        if (!pushPending.compareAndSet(false, true)) return
        handler.postDelayed({
            pushPending.set(false)
            val app = context.applicationContext
            AppScope.io.launch { pushNow(app) }
        }, DEBOUNCE_MS)
    }

    /** Немедленная синхронизация «в обе стороны» (pull + push). Для кнопки в настройках. */
    suspend fun syncNow(context: Context) {
        if (!enabled(context)) return
        pullNow(context)
        pushNow(context)
    }

    /** Забрать с сервера всё новое (с последнего курсора) и применить на устройстве. */
    suspend fun pullNow(context: Context) {
        val key = syncKey(context) ?: return
        if (!busy.compareAndSet(false, true)) return
        try {
            val sess = Account.current(context) ?: return
            var since = SecureKeys.get(context).getLong(KEY_CURSOR, 0L)
            val store = ConversationStore(context)
            var guard = 0
            while (guard++ < 1000) {
                val page = syncPullPage(context, sess, key, since) ?: return
                var done = true
                for (it in page.items) {
                    applyItem(context, store, it)
                    if (it.version > since) since = it.version
                    done = done && it.version <= since
                }
                SecureKeys.get(context).edit().putLong(KEY_CURSOR, since).apply()
                if (page.done || page.items.isEmpty()) break
                since = page.cursor
            }
        } finally {
            busy.set(false)
        }
    }

    private data class PullPage(val items: List<SyncItem>, val cursor: Long, val done: Boolean)
    private data class SyncItem(val id: String, val version: Long, val deleted: Boolean, val data: String)

    private suspend fun syncPullPage(
        context: Context, sess: Account.Session, key: ByteArray, since: Long,
    ): PullPage? = withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val u = java.net.URL("${sess.url.trimEnd('/')}/v1/sync/pull?since=$since&limit=500")
            val c = (u.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer ${sess.token}")
                connectTimeout = 10000; readTimeout = 15000
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            if (code !in 200..299) return@runCatching null
            val o = JSONObject(body)
            val arr = o.optJSONArray("items") ?: JSONArray()
            val items = (0 until arr.length()).mapNotNull { i ->
                val it = arr.getJSONObject(i)
                SyncItem(
                    id = it.optString("id"),
                    version = it.optLong("version"),
                    deleted = it.optBoolean("deleted", false),
                    data = it.optString("data"),
                )
            }
            PullPage(items, o.optLong("cursor", since), o.optBoolean("done", true))
        }.getOrNull()
    }

    private fun applyItem(context: Context, store: ConversationStore, it: SyncItem) {
        if (it.id == PROFILE_ID) {
            if (it.deleted) return
            val text = SyncCrypto.decrypt(syncKey(context) ?: return, it.data) ?: return
            applying = true
            try { applyProfile(context, text) } finally { applying = false }
            return
        }
        if (it.id.startsWith(CHAT_PREFIX)) {
            if (it.deleted) {
                store.delete(it.id.removePrefix(CHAT_PREFIX))
                return
            }
            val text = SyncCrypto.decrypt(syncKey(context) ?: return, it.data) ?: return
            applying = true
            try { applyChat(context, store, it.id.removePrefix(CHAT_PREFIX), text) } finally { applying = false }
        }
    }

    /** Применить чужой профиль на устройстве (облако/ключи, интеграции, тайны, SSH). */
    private fun applyProfile(context: Context, json: String) {
        val p = runCatching { JSONObject(json) }.getOrNull() ?: return
        // Кэш последнего виденного блоба: секции, которые этот клиент не понимает (например, free-тир
        // с десктопа), сохраняются и переживают следующий push — иначе пуш с Android затёр бы их (LWW).
        SecureKeys.get(context).edit().putString(PROFILE_CACHE, p.toString()).apply()
        applyCloud(context, p.optJSONObject("cloud"))
        applyIntegrations(context, p.optJSONObject("integrations"))
        applySecrets(context, p.optJSONObject("secrets"))
        applySsh(context, p.optJSONObject("ssh"))
    }

    private fun applyCloud(context: Context, cloud: JSONObject?) {
        if (cloud == null) return
        val s = SecureKeys.get(context)
        // Прокси владельца: включаем только если он был включён на том устройстве (токен кошелька
        // свой на каждом устройстве, но url/провайдер полезны). Токен на другой девайс не тащим.
        if (cloud.optBoolean("proxy_enabled", false)) {
            CloudEngine.setProxyEnabled(context, true)
            if (cloud.has("proxy_url")) {
                val prov = runCatching {
                    CloudProvider.valueOf(cloud.optString("proxy_provider", CloudProvider.DEEPSEEK.name))
                }.getOrDefault(CloudProvider.DEEPSEEK)
                s.edit()
                    .putString("proxy_url", cloud.optString("proxy_url"))
                    .putString("proxy_provider", prov.name)
                    .apply()
                CloudModels.invalidate()
            }
        }
        // BYOK-ключи (совместимы на любом устройстве — это ключи пользователя).
        CloudProvider.values().forEach { p ->
            if (p == CloudProvider.CUSTOM) return@forEach
            val k = cloud.optString("byok_${p.name}")
            if (k.isNotBlank()) CloudEngine.setByokKey(context, p, k)
        }
        // «Свой API».
        if (cloud.has("custom_url")) {
            CloudEngine.setCustomEndpoint(
                context,
                enabled = cloud.optBoolean("custom_enabled", false),
                url = cloud.optString("custom_url"),
                key = cloud.optString("custom_key").takeIf { it.isNotBlank() },
                model = cloud.optString("custom_model").takeIf { it.isNotBlank() },
            )
        }
        // Free-тир Zen.
        if (cloud.has("zen_enabled")) {
            CloudEngine.setZen(context, cloud.optBoolean("zen_enabled", false))
        }
    }

    private fun applyIntegrations(context: Context, intg: JSONObject?) {
        if (intg == null) return
        // GitHub.
        if (intg.has("github")) {
            val gh = intg.optJSONObject("github")
            if (gh != null && gh.optString("token").isNotBlank()) {
                GitHubAuth.save(context, gh.optString("token"), gh.optString("login"))
            } else {
                GitHubAuth.clear(context)
            }
        }
        // Discord.
        if (intg.has("discord")) {
            val dc = intg.optJSONObject("discord")
            if (dc != null && dc.optString("token").isNotBlank()) DiscordAuth.save(context, dc.optString("token"))
            else DiscordAuth.clear(context)
        }
        // OAuth-диски.
        val oauth = intg.optJSONObject("oauth") ?: return
        applyOAuth(context, DriveAuth, oauth, "gdrive")
        applyOAuth(context, DropboxAuth, oauth, "dropbox")
        applyOAuth(context, OneDriveAuth, oauth, "onedrive")
        applyOAuth(context, DiskAuth, oauth, "yandexdisk")
        // Почта.
        val mail = oauth.optJSONObject("mail") ?: return
        val existing = MailAuth.accounts(context).associateBy { it.email }
        val incoming = mutableListOf<MailAuth.Account>()
        mail.keys().forEach { email ->
            val o = mail.optJSONObject(email) ?: return@forEach
            val acc = MailAuth.Account(
                provider = o.optString("provider"),
                email = email,
                secret = o.optString("secret"),
                host = o.optString("host"),
                oauth = o.optBoolean("oauth"),
                refresh = o.optString("refresh").takeIf { it.isNotBlank() },
            )
            incoming += acc
            MailAuth.add(context, acc)
        }
        // Аккаунты, которых больше нет в профиле, — удаляем (чтобы деактивация на одном девайсе
        // распространялась), но НЕ трогаем те, что появились локально после pull.
        val incomingEmails = incoming.map { it.email }.toSet()
        existing.keys.filter { it !in incomingEmails }.forEach { MailAuth.remove(context, it) }
    }

    private fun applyOAuth(context: Context, store: OAuthStore, oauth: JSONObject, key: String) {
        val o = oauth.optJSONObject(key) ?: return
        val access = o.optString("access")
        if (access.isBlank()) { store.clear(context); return }
        val refresh = o.optString("refresh").takeIf { it.isNotBlank() }
        // Ограничение: не затираем ЛОКАЛЬНЫЙ свежий access безвредно-безопасно не получится (нет времени
        // обновления) — OAuth-токены короткоживущие, поэтому приносим ТОЛЬКО refresh-цепочку, если
        // локального доступа нет (иначе сломанный access с сервера заблокировал бы рабочую сессию).
        if (store.account(context) == null || refresh != null) {
            store.save(context, access, refresh)
        }
    }

    private fun applySecrets(context: Context, sec: JSONObject?) {
        if (sec == null) return
        val s = SecureKeys.get(context).edit()
        if (sec.has("enabled")) s.putBoolean("secrets_enabled", sec.optBoolean("enabled"))
        if (sec.has("autodetect")) s.putBoolean("secrets_autodetect", sec.optBoolean("autodetect"))
        if (sec.has("payload")) s.putString("secrets_payload", sec.optString("payload"))
        s.apply()
        SecretStore.reconnect(context)
    }

    private fun applySsh(context: Context, ssh: JSONObject?) {
        if (ssh == null) return
        val incoming = mutableSetOf<String>()
        ssh.keys().forEach { name ->
            val o = ssh.optJSONObject(name) ?: return@forEach
            incoming += name
            SshStore.put(
                context, name,
                o.optString("host"), o.optInt("port", 22), o.optString("user"),
                o.optString("password").takeIf { it.isNotBlank() },
                o.optString("privateKeyPem").takeIf { it.isNotBlank() },
                o.optString("passphrase").takeIf { it.isNotBlank() },
            )
        }
        SshStore.names(context).filter { it !in incoming }.forEach { SshStore.remove(context, it) }
    }

    private fun applyChat(context: Context, store: ConversationStore, id: String, json: String) {
        val c = runCatching { JSONObject(json) }.getOrNull() ?: return
        val serverUpdated = c.optLong("updatedAt", 0)
        val local = store.load(id)
        if (local != null && local.updatedAt > serverUpdated) return // локальная свежее — она уедет push'ем
        val arr = c.optJSONArray("messages") ?: JSONArray()
        val msgs = mutableListOf<StoredMsg>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            msgs += StoredMsg(m.optString("kind"), m.optString("text"))
        }
        if (msgs.isEmpty()) return
        store.save(Conversation(id, c.optString("title"), serverUpdated, msgs))
    }

    /** Собрать локальное состояние и выгрузить: профиль + все беседы. */
    suspend fun pushNow(context: Context) {
        val key = syncKey(context) ?: return
        if (!busy.compareAndSet(false, true)) return
        try {
            val sess = Account.current(context) ?: return
            val items = mutableListOf<Pair<String, String>>() // (id, открытый текст)
            items += PROFILE_ID to buildProfile(context)
            val store = ConversationStore(context)
            store.list().forEach { meta ->
                val conv = store.load(meta.id) ?: return@forEach
                if (conv.messages.isEmpty()) return@forEach
                val o = JSONObject()
                    .put("title", conv.title)
                    .put("updatedAt", conv.updatedAt)
                    .put("messages", JSONArray().apply {
                        conv.messages.forEach { m -> put(JSONObject().put("kind", m.kind).put("text", m.text)) }
                    })
                items += "$CHAT_PREFIX${meta.id}" to o.toString()
            }
            // Пушим пачками по одному (курсор после каждого), чтобы одно большое сообщение не
            // утянуло весь батч в ошибку лимита сервера (MaxItemBytes 512КБ).
            for ((id, text) in items) {
                val body = JSONObject()
                body.put("items", JSONArray().apply {
                    put(JSONObject().put("id", id).put("deleted", false).put("data", SyncCrypto.encrypt(key, text)))
                })
                val (code, resp) = pushHttp(context, sess, body)
                if (code in 200..299) {
                    runCatching { JSONObject(resp).optLong("cursor") }.getOrNull()?.let {
                        SecureKeys.get(context).edit().putLong(KEY_CURSOR, it).apply()
                    }
                }
            }
        } finally {
            busy.set(false)
        }
    }

    private suspend fun pushHttp(context: Context, sess: Account.Session, body: JSONObject): Pair<Int, String> =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val u = java.net.URL("${sess.url.trimEnd('/')}/v1/sync/push")
                val bytes = body.toString().toByteArray()
                val c = (u.openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer ${sess.token}")
                    connectTimeout = 10000; readTimeout = 20000
                }
                c.outputStream.use { it.write(bytes) }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                c.disconnect()
                code to text
            }.getOrDefault(0 to "")
        }

    /** Собрать блоб профиля (всё, что пользователь настроил на этом устройстве). Начинаем с кэша
     * последнего виденного блоба и перезаписываем СВОИ секции — незнакомые (free и пр.) остаются. */
    private fun buildProfile(context: Context): String {
        val s = SecureKeys.get(context)
        val p = runCatching { s.getString(PROFILE_CACHE, null)?.let { JSONObject(it) } }.getOrNull()
            ?: JSONObject().put("v", 1)
        val cloud = JSONObject()
        val (proxyOn, proxyUrl, _) = CloudEngine.proxyConfig(context)
        cloud.put("proxy_enabled", proxyOn).put("proxy_url", proxyUrl)
        cloud.put("proxy_provider", s.getString("proxy_provider", CloudProvider.DEEPSEEK.name) ?: CloudProvider.DEEPSEEK.name)
        CloudProvider.values().forEach { prov ->
            if (prov != CloudProvider.CUSTOM) {
                CloudEngine.byokKey(context, prov)?.takeIf { it.isNotBlank() }
                    ?.let { cloud.put("byok_${prov.name}", it) }
            }
        }
        val ce = CloudEngine.customEndpoint(context)
        cloud.put("custom_enabled", ce.enabled).put("custom_url", ce.url)
            .put("custom_key", ce.key).put("custom_model", ce.model)
        cloud.put("zen_enabled", CloudEngine.zenEnabled(context))
        p.put("cloud", cloud)

        val intg = JSONObject()
        GitHubAuth.token(context)?.let { intg.put("github", JSONObject().put("token", it).put("login", GitHubAuth.login(context) ?: "")) }
        DiscordAuth.token(context)?.let { intg.put("discord", JSONObject().put("token", it)) }
        val oauth = JSONObject()
        oauth.put("gdrive", oauthEntry(DriveAuth.account(context)))
        oauth.put("dropbox", oauthEntry(DropboxAuth.account(context)))
        oauth.put("onedrive", oauthEntry(OneDriveAuth.account(context)))
        oauth.put("yandexdisk", oauthEntry(DiskAuth.account(context)))
        val mail = JSONObject()
        MailAuth.accounts(context).forEach { a ->
            mail.put(a.email, JSONObject()
                .put("provider", a.provider).put("secret", a.secret)
                .put("host", a.host).put("oauth", a.oauth)
                .apply { a.refresh?.let { put("refresh", it) } })
        }
        oauth.put("mail", mail)
        intg.put("oauth", oauth)
        p.put("integrations", intg)

        p.put("secrets", JSONObject()
            .put("enabled", SecretStore.enabled(context))
            .put("autodetect", SecretStore.autodetect(context))
            .put("payload", s.getString("secrets_payload", "") ?: ""))

        val ssh = JSONObject()
        SshStore.names(context).forEach { name ->
            SshStore.get(context, name)?.let { ssh.put(name, it) }
        }
        p.put("ssh", ssh)
        return p.toString()
    }

    private fun oauthEntry(acc: ru.aiagent.app.integrations.OAuthAccount?): JSONObject =
        if (acc == null) JSONObject()
        else JSONObject().put("access", acc.access).apply { acc.refresh?.let { put("refresh", it) } }

    /** Pull при старте приложения — один раз за процесс, тихо. */
    fun pullOnStart(context: Context) {
        if (pullOnStartDone) return
        pullOnStartDone = true
        if (!enabled(context)) return
        val app = context.applicationContext
        AppScope.io.launch { pullNow(app) }
    }
}
