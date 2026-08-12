package ru.aiagent.app.integrations

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import java.io.File

/**
 * Git на устройстве через JGit (чистый Java, без git-бинаря). Клон/статус/коммит/пуш по HTTPS.
 * Приватные репозитории и пуш — по GitHub-токену (тот же, что у github_*-инструментов). Всё в песочнице.
 *
 * ВАЖНО: сам JGit (~3 МБ) вынесен из APK в СКАЧИВАЕМЫЙ пак `jgit`. Эти инструменты НЕ трогают
 * org.eclipse.jgit напрямую — только через тонкий интерфейс [GitBackend] (см. его doc, почему так, а
 * не compileOnly со статическими импортами). Пока пак не скачан — [backend] == null и инструмент
 * возвращает подсказку «нужно скачать пак». Один и тот же текст подсказки для всех — [NEED_PACK].
 */
private const val NEED_PACK = "нужно скачать пак Git (JGit) — Настройки → модули кода"

/** Логин/токен GitHub для приватных операций (может быть null, если GitHub не подключён). */
private fun creds(context: Context): Pair<String?, String?> {
    val tok = GitHubAuth.token(context) ?: return null to null
    return (GitHubAuth.login(context) ?: "x-access-token") to tok
}

private fun jf(json: String, name: String): String? =
    runCatching { JSONObject(json).optString(name).takeIf { it.isNotBlank() } }.getOrNull()

/** git_clone — клонировать репозиторий в папку рабочей области. */
class GitCloneTool(private val context: Context, private val resolve: (String) -> File?) : AgentTool {
    override val id = "git_clone"
    override val danger = DangerLevel.IMPORTANT
    override val usesFiles = true
    override val schema = """клонировать git-репозиторий (HTTPS); args: {"url":"https://github.com/user/repo","dir":"repo"}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val git = GitBackends.ensure(context) ?: return@withContext ToolResult.Failure(NEED_PACK)
        val url = jf(argsJson, "url") ?: return@withContext ToolResult.Failure("нет args.url")
        val dir = jf(argsJson, "dir") ?: url.substringAfterLast('/').removeSuffix(".git").ifBlank { "repo" }
        val dst = resolve(dir) ?: return@withContext ToolResult.Failure("dir вне разрешённой папки")
        if (dst.exists() && (dst.list()?.isNotEmpty() == true)) return@withContext ToolResult.Failure("папка $dir не пуста")
        try {
            // Токен GitHub отдаём ТОЛЬКО на github.com (audit): иначе model-supplied url на чужой хост
            // получал бы наш логин+токен (JGit сдаёт Basic-креды по 401-челленджу) → утечка токена.
            // Не-github url клонируем анонимно (публичные репо работают; приватный чужой хост — не наш токен).
            val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()
            val isGitHub = host == "github.com" || host?.endsWith(".github.com") == true
            val (login, tok) = if (isGitHub) creds(context) else (null to null)
            git.clone(url, dst.absolutePath, login, tok)
            ToolResult.Success("""{"cloned":"$url","dir":"$dir"}""")
        } catch (t: Throwable) {
            ToolResult.Failure("git clone: ${t.message?.take(160)}")
        }
    }
}

/** git_status — изменённые/новые файлы в репозитории. */
class GitStatusTool(private val context: Context, private val resolve: (String) -> File?) : AgentTool {
    override val id = "git_status"
    override val danger = DangerLevel.SAFE
    override val usesFiles = true
    override val schema = """статус git-репозитория; args: {"dir":"repo"}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val git = GitBackends.ensure(context) ?: return@withContext ToolResult.Failure(NEED_PACK)
        val dst = resolve(jf(argsJson, "dir") ?: ".") ?: return@withContext ToolResult.Failure("dir вне разрешённой папки")
        try {
            ToolResult.Success(git.status(dst.absolutePath))
        } catch (t: Throwable) {
            ToolResult.Failure("git status: ${t.message?.take(120)}")
        }
    }
}

/** git_commit — добавить все изменения и закоммитить. */
class GitCommitTool(private val context: Context, private val resolve: (String) -> File?) : AgentTool {
    override val id = "git_commit"
    override val danger = DangerLevel.IMPORTANT
    override val usesFiles = true
    override val schema = """закоммитить все изменения; args: {"dir":"repo","message":"сообщение коммита"}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val git = GitBackends.ensure(context) ?: return@withContext ToolResult.Failure(NEED_PACK)
        val dst = resolve(jf(argsJson, "dir") ?: ".") ?: return@withContext ToolResult.Failure("dir вне разрешённой папки")
        val msg = jf(argsJson, "message") ?: return@withContext ToolResult.Failure("нужен args.message")
        try {
            val who = GitHubAuth.login(context) ?: "Plus AI"
            val hash = git.commit(dst.absolutePath, msg, who)
            ToolResult.Success("""{"committed":"$hash","message":"${escapeJ(msg)}"}""")
        } catch (t: Throwable) {
            ToolResult.Failure("git commit: ${t.message?.take(120)}")
        }
    }
}

/** git_push — отправить коммиты на удалённый репозиторий (по GitHub-токену). */
class GitPushTool(private val context: Context, private val resolve: (String) -> File?) : AgentTool {
    override val id = "git_push"
    override val danger = DangerLevel.DANGEROUS // изменяет удалённый репозиторий
    override val usesFiles = true
    override val schema = """отправить коммиты в удалённый репозиторий; args: {"dir":"repo"}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val git = GitBackends.ensure(context) ?: return@withContext ToolResult.Failure(NEED_PACK)
        val dst = resolve(jf(argsJson, "dir") ?: ".") ?: return@withContext ToolResult.Failure("dir вне разрешённой папки")
        val (login, tok) = creds(context)
        if (tok == null) return@withContext ToolResult.Failure("нет GitHub-токена — подключи GitHub в Интеграциях")
        try {
            git.push(dst.absolutePath, login, tok)
            ToolResult.Success("""{"pushed":true}""")
        } catch (t: Throwable) {
            ToolResult.Failure("git push: ${t.message?.take(120)}")
        }
    }
}

/** git_pull — забрать изменения с удалённого (fetch+merge) по GitHub-токену. */
class GitPullTool(private val context: Context, private val resolve: (String) -> File?) : AgentTool {
    override val id = "git_pull"
    override val danger = DangerLevel.IMPORTANT // меняет рабочее дерево
    override val usesFiles = true
    override val schema = """забрать изменения с удалённого (fetch+merge); args: {"dir":"repo"}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val git = GitBackends.ensure(context) ?: return@withContext ToolResult.Failure(NEED_PACK)
        val dst = resolve(jf(argsJson, "dir") ?: ".") ?: return@withContext ToolResult.Failure("dir вне разрешённой папки")
        val (login, tok) = creds(context)
        if (tok == null) return@withContext ToolResult.Failure("нет GitHub-токена — подключи GitHub в Интеграциях")
        try {
            ToolResult.Success(git.pull(dst.absolutePath, login, tok))
        } catch (t: Throwable) {
            ToolResult.Failure("git pull: ${t.message?.take(120)}")
        }
    }
}

/** git_log — история коммитов. */
class GitLogTool(private val context: Context, private val resolve: (String) -> File?) : AgentTool {
    override val id = "git_log"
    override val danger = DangerLevel.SAFE
    override val usesFiles = true
    override val schema = """история коммитов; args: {"dir":"repo","limit":20}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val git = GitBackends.ensure(context) ?: return@withContext ToolResult.Failure(NEED_PACK)
        val dst = resolve(jf(argsJson, "dir") ?: ".") ?: return@withContext ToolResult.Failure("dir вне разрешённой папки")
        val limit = runCatching { JSONObject(argsJson).optInt("limit", 20) }.getOrDefault(20).coerceIn(1, 500)
        try {
            ToolResult.Success(git.log(dst.absolutePath, limit))
        } catch (t: Throwable) {
            ToolResult.Failure("git log: ${t.message?.take(120)}")
        }
    }
}

/** git_diff — несохранённые (рабочие) изменения в виде текста. */
class GitDiffTool(private val context: Context, private val resolve: (String) -> File?) : AgentTool {
    override val id = "git_diff"
    override val danger = DangerLevel.SAFE
    override val usesFiles = true
    override val schema = """несохранённые изменения (diff); args: {"dir":"repo"}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val git = GitBackends.ensure(context) ?: return@withContext ToolResult.Failure(NEED_PACK)
        val dst = resolve(jf(argsJson, "dir") ?: ".") ?: return@withContext ToolResult.Failure("dir вне разрешённой папки")
        try {
            val text = git.diff(dst.absolutePath)
            val trimmed = if (text.length > 8000) text.take(8000) + "\n… (обрезано)" else text
            ToolResult.Success(JSONObject().put("diff", trimmed).toString())
        } catch (t: Throwable) {
            ToolResult.Failure("git diff: ${t.message?.take(120)}")
        }
    }
}

/** git_branch — список веток или создание новой. */
class GitBranchTool(private val context: Context, private val resolve: (String) -> File?) : AgentTool {
    override val id = "git_branch"
    override val danger = DangerLevel.SAFE // список; создание не опасно
    override val usesFiles = true
    override val schema = """список веток или создание; args: {"dir":"repo","create":"имя_опц"}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val git = GitBackends.ensure(context) ?: return@withContext ToolResult.Failure(NEED_PACK)
        val dst = resolve(jf(argsJson, "dir") ?: ".") ?: return@withContext ToolResult.Failure("dir вне разрешённой папки")
        val create = jf(argsJson, "create")
        try {
            if (create != null) {
                git.branchCreate(dst.absolutePath, create)
                ToolResult.Success("""{"created":"${escapeJ(create)}"}""")
            } else {
                ToolResult.Success(git.branchList(dst.absolutePath))
            }
        } catch (t: Throwable) {
            ToolResult.Failure("git branch: ${t.message?.take(120)}")
        }
    }
}

/** git_checkout — переключиться на ветку (опц. создать). */
class GitCheckoutTool(private val context: Context, private val resolve: (String) -> File?) : AgentTool {
    override val id = "git_checkout"
    override val danger = DangerLevel.IMPORTANT // меняет рабочее дерево
    override val usesFiles = true
    override val schema = """переключиться на ветку; args: {"dir":"repo","branch":"имя","create":false}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val git = GitBackends.ensure(context) ?: return@withContext ToolResult.Failure(NEED_PACK)
        val dst = resolve(jf(argsJson, "dir") ?: ".") ?: return@withContext ToolResult.Failure("dir вне разрешённой папки")
        val branch = jf(argsJson, "branch") ?: return@withContext ToolResult.Failure("нужен args.branch")
        val create = runCatching { JSONObject(argsJson).optBoolean("create", false) }.getOrDefault(false)
        try {
            git.checkout(dst.absolutePath, branch, create)
            ToolResult.Success("""{"branch":"${escapeJ(branch)}","created":$create}""")
        } catch (t: Throwable) {
            ToolResult.Failure("git checkout: ${t.message?.take(120)}")
        }
    }
}

private fun escapeJ(s: String) = ru.aiagent.core.agent.tools.escapeJson(s)

fun gitTools(context: Context, resolve: (String) -> File?): List<AgentTool> = listOf(
    GitCloneTool(context, resolve), GitStatusTool(context, resolve), GitCommitTool(context, resolve), GitPushTool(context, resolve),
    GitPullTool(context, resolve), GitLogTool(context, resolve), GitDiffTool(context, resolve), GitBranchTool(context, resolve), GitCheckoutTool(context, resolve),
)
