package ru.aiagent.app.integrations

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.aiagent.app.SshStore
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult

/**
 * SSH на устройстве через JSch. Сам JSch вынесен из APK в СКАЧИВАЕМЫЙ пак `ssh` (как JGit) — тулзы
 * трогают его только через тонкий [SshBackend]. Пока пак не скачан — [SshBackends.ensure] == null и
 * тул возвращает [NEED_SSH_PACK]. КРЕДЫ берутся из [SshStore] по ИМЕНИ подключения, в args (и в облако)
 * не попадают (§7).
 */
private const val NEED_SSH_PACK = "нужно скачать пак SSH (JSch) — Настройки → модули кода"

private fun jf(json: String, name: String): String? =
    runCatching { JSONObject(json).optString(name).takeIf { it.isNotBlank() } }.getOrNull()

/** ssh_run — выполнить команду на сохранённом SSH-подключении (по имени; креды на устройстве). */
class SshRunTool(private val context: Context) : AgentTool {
    override val id = "ssh_run"
    override val danger = DangerLevel.DANGEROUS // произвольная команда на удалённой машине
    // §7: вывод удалённой команды (файлы/env/секреты/дампы) НЕ должен уходить облачной модели. Как у
    // дисков/GitHub/мессенджеров — приватный тул исключается из облачных путей (см. runCloud filterNot privateData).
    override val privateData = true
    override val schema =
        """выполнить команду по SSH на СОХРАНЁННОМ подключении (по имени; список — ssh_list); """ +
            """args: {"connection":"имя","command":"uname -a"}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val name = jf(argsJson, "connection") ?: return@withContext ToolResult.Failure("нет args.connection (см. ssh_list)")
        val command = jf(argsJson, "command") ?: return@withContext ToolResult.Failure("нет args.command")
        val conn = SshStore.get(context, name)
            ?: return@withContext ToolResult.Failure("нет подключения «$name». Добавь в Настройки → SSH. Список: ssh_list")
        val backend = SshBackends.ensure(context) ?: return@withContext ToolResult.Failure(NEED_SSH_PACK)
        try {
            val out = backend.run(
                conn.optString("host"), conn.optInt("port", 22), conn.optString("user"),
                SshStore.authJson(conn), command, 20000,
            )
            ToolResult.Success(out)
        } catch (t: Throwable) {
            ToolResult.Failure("ssh: ${t.message?.take(180)}")
        }
    }
}

/** ssh_list — перечислить сохранённые SSH-подключения (только имена; креды не отдаём). */
class SshListTool(private val context: Context) : AgentTool {
    override val id = "ssh_list"
    override val danger = DangerLevel.SAFE
    override val privateData = true // имена подключений тоже держим на устройстве (defense-in-depth)
    override val schema = """список сохранённых SSH-подключений (имена для ssh_run); args: {}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val names = SshStore.names(context)
        val arr = names.joinToString(",") { "\"$it\"" }
        return ToolResult.Success("""{"connections":[$arr]}""")
    }
}

/** Набор SSH-инструментов (показываем, только когда есть хотя бы одно сохранённое подключение). */
fun sshTools(context: Context): List<AgentTool> = listOf(SshRunTool(context), SshListTool(context))
