package ru.aiagent.app.integrations

import android.content.Context
import org.json.JSONObject
import ru.aiagent.app.cloud.SecureKeys
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import ru.aiagent.integrations.GitHubClient

/**
 * Инструменты GitHub для агента (S10). Токен хранится в зашифрованном хранилище
 * (Keystore), получен через OAuth Device Flow из экрана «Интеграции». Чтение —
 * SAFE; запись (создать issue) — IMPORTANT с обязательным подтверждением.
 */

/** Хранилище GitHub-токена (в тех же EncryptedSharedPreferences, что и ключи облака). */
object GitHubAuth {
    private const val KEY = "gh_token"
    private const val KEY_LOGIN = "gh_login"

    fun token(context: Context): String? =
        SecureKeys.get(context).getString(KEY, null)?.takeIf { it.isNotBlank() }

    fun login(context: Context): String? = SecureKeys.get(context).getString(KEY_LOGIN, null)

    fun save(context: Context, token: String, login: String) {
        SecureKeys.get(context).edit().putString(KEY, token).putString(KEY_LOGIN, login).apply()
        ru.aiagent.app.cloud.AutoSync.schedulePush(context)
    }

    fun clear(context: Context) {
        SecureKeys.get(context).edit().remove(KEY).remove(KEY_LOGIN).apply()
        ru.aiagent.app.cloud.AutoSync.schedulePush(context)
    }

    fun isConnected(context: Context): Boolean = token(context) != null
}

private fun field(json: String, name: String): String? =
    runCatching { JSONObject(json).optString(name).takeIf { it.isNotBlank() } }.getOrNull()

private fun needToken(context: Context): String? = GitHubAuth.token(context)

private const val NOT_CONNECTED = "GitHub не подключён — открой Настройки → Интеграции и войди"

/** github_repos — список репозиториев пользователя (по свежести). */
class GitHubReposTool(private val context: Context) : AgentTool {
    override val id = "github_repos"
    override val danger = DangerLevel.SAFE
    // §7: приватные репозитории/код/issues НЕ отдаём облачной модели (как GDrive/OneDrive/Discord).
    // Раньше флаг был забыт → при облачной модели содержимое приватных репо уходило провайдеру.
    override val privateData = true
    override val schema =
        """мои репозитории GitHub (аккаунт УЖЕ подключён, логин/ник спрашивать НЕ нужно); args: {"limit":10}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val token = needToken(context) ?: return ToolResult.Failure(NOT_CONNECTED)
        val limit = field(argsJson, "limit")?.toIntOrNull() ?: 10
        return GitHubClient.listRepos(token, limit)
            .fold({ ToolResult.Success(it) }, { ToolResult.Failure("github_repos: ${it.message}") })
    }
}

/** github_issues — открытые issues репозитория. */
class GitHubIssuesTool(private val context: Context) : AgentTool {
    override val id = "github_issues"
    override val danger = DangerLevel.SAFE
    // §7: приватные репозитории/код/issues НЕ отдаём облачной модели (как GDrive/OneDrive/Discord).
    // Раньше флаг был забыт → при облачной модели содержимое приватных репо уходило провайдеру.
    override val privateData = true
    override val schema = """открытые issues репозитория; args: {"repo":"owner/name","limit":10}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val token = needToken(context) ?: return ToolResult.Failure(NOT_CONNECTED)
        val repo = field(argsJson, "repo") ?: return ToolResult.Failure("нужен args.repo = owner/name")
        val limit = field(argsJson, "limit")?.toIntOrNull() ?: 10
        return GitHubClient.listIssues(token, repo, limit)
            .fold({ ToolResult.Success(it) }, { ToolResult.Failure("github_issues: ${it.message}") })
    }
}

/** github_read_file — прочитать файл из репозитория. */
class GitHubReadFileTool(private val context: Context) : AgentTool {
    override val id = "github_read_file"
    override val danger = DangerLevel.SAFE
    // §7: приватные репозитории/код/issues НЕ отдаём облачной модели (как GDrive/OneDrive/Discord).
    // Раньше флаг был забыт → при облачной модели содержимое приватных репо уходило провайдеру.
    override val privateData = true
    override val schema = """прочитать файл из репозитория GitHub; args: {"repo":"owner/name","path":"src/Main.kt"}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val token = needToken(context) ?: return ToolResult.Failure(NOT_CONNECTED)
        val repo = field(argsJson, "repo") ?: return ToolResult.Failure("нужен args.repo")
        val path = field(argsJson, "path") ?: return ToolResult.Failure("нужен args.path")
        return GitHubClient.readFile(token, repo, path)
            .fold({ ToolResult.Success(JSONObject().put("content", it).toString()) },
                { ToolResult.Failure("github_read_file: ${it.message}") })
    }
}

/** github_search_code — поиск кода. */
class GitHubSearchCodeTool(private val context: Context) : AgentTool {
    override val id = "github_search_code"
    override val danger = DangerLevel.SAFE
    // §7: приватные репозитории/код/issues НЕ отдаём облачной модели (как GDrive/OneDrive/Discord).
    // Раньше флаг был забыт → при облачной модели содержимое приватных репо уходило провайдеру.
    override val privateData = true
    override val schema = """поиск кода на GitHub; args: {"query":"repo:owner/name Foo","limit":10}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val token = needToken(context) ?: return ToolResult.Failure(NOT_CONNECTED)
        val query = field(argsJson, "query") ?: return ToolResult.Failure("нужен args.query")
        val limit = field(argsJson, "limit")?.toIntOrNull() ?: 10
        return GitHubClient.searchCode(token, query, limit)
            .fold({ ToolResult.Success(it) }, { ToolResult.Failure("github_search_code: ${it.message}") })
    }
}

/** github_create_issue — создать issue (запись → всегда подтверждение). */
class GitHubCreateIssueTool(private val context: Context) : AgentTool {
    override val id = "github_create_issue"
    override val danger = DangerLevel.IMPORTANT
    override val alwaysConfirm = true // запись в чужой репозиторий — подтверждение всегда, кроме bypass
    override val schema =
        """создать issue в репозитории GitHub; args: {"repo":"owner/name","title":"...","body":"..."}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val token = needToken(context) ?: return ToolResult.Failure(NOT_CONNECTED)
        val repo = field(argsJson, "repo") ?: return ToolResult.Failure("нужен args.repo")
        val title = field(argsJson, "title") ?: return ToolResult.Failure("нужен args.title")
        val body = field(argsJson, "body") ?: ""
        return GitHubClient.createIssue(token, repo, title, body)
            .fold({ ToolResult.Success(it) }, { ToolResult.Failure("github_create_issue: ${it.message}") })
    }
}

/** Все GitHub-инструменты (подключаются только если пользователь вошёл). */
fun gitHubTools(context: Context): List<AgentTool> = listOf(
    GitHubReposTool(context),
    GitHubIssuesTool(context),
    GitHubReadFileTool(context),
    GitHubSearchCodeTool(context),
    GitHubCreateIssueTool(context),
)
