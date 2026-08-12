package ru.aiagent.app.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.aiagent.app.AgentChatEngine
import ru.aiagent.app.AppSettings
import ru.aiagent.app.cloud.Account
import ru.aiagent.core.agent.AgentEvent
import ru.aiagent.core.agent.AutonomyMode

/**
 * Foreground-сервис «управление телефоном с ПК» (REMOTE-XDEV). Пока включён — телефон анонсит
 * присутствие в E2E-релэй и исполняет присланные с ПК команды агентом на ЛОКАЛЬНОЙ модели.
 *
 * Foreground обязателен: без него Android усыпляет процесс, телефон пропадает по TTL и с ПК
 * недостижим. Команды исполняются в режиме AUTO с confirm=false — обычные действия делаются сами,
 * а опасные/важные отклоняются (защита от удалённого RCE, как на десктопе). Локальная модель =
 * приватность §7 сохраняется: данные с телефона не уходят в облако при удалённом исполнении.
 */
class RemoteAgentService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = buildNotification("Готов принимать команды с ПК")
        // Android 10+ (Q) требует явный тип foreground-сервиса; dataSync — фоновая сетевая работа (long-poll).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else
            startForeground(NOTIF_ID, notif)

        val session = Account.current(this)
        if (session == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val crypto = DeviceIdentity.fromPrefs(this) // стабильный ключ (одна сверка SAS на обе роли)
        val peers = VerifiedPeers.fromPrefs(this)
        val relay = RelayClient(session.url.trimEnd('/'), session.token)
        val hostId = RemoteIds.phoneHost(this)
        val ctx = applicationContext
        val prefs = getSharedPreferences("plusai_remote", Context.MODE_PRIVATE)

        val host = RemoteHost(
            relay = relay,
            crypto = crypto,
            peers = peers,
            deviceId = hostId,
            deviceName = "Телефон",
            handler = { cmd, emit -> runAgent(ctx, cmd, emit) },
            log = { android.util.Log.i(TAG, it) },
            onPeers = { names ->
                val text = if (names.isEmpty()) "Ждёт ПК того же аккаунта…"
                else "На связи: " + names.joinToString(", ")
                notify(buildNotification(text))
            },
            initialSince = prefs.getLong("host_since", 0L),
            onCursor = { prefs.edit().putLong("host_since", it).apply() },
        )
        scope.launch { host.run() }
        return START_STICKY
    }

    /**
     * Исполнить команду агентным циклом на локальной модели, стримя прогресс через [emit]:
     * шаги (вызовы инструментов) по мере выполнения и финальный ответ в конце.
     */
    private suspend fun runAgent(context: Context, cmd: String, emit: suspend (String, String) -> Unit) {
        var finalSent = false
        var lastInfo = ""
        // NORMAL + confirm=false: агент читает/ищет/считает сам, а ВСЁ, что требует подтверждения
        // (правки файлов, важные и опасные операции), отклоняется — удалённо подтвердить нельзя.
        // Раньше был AUTO, но AUTO авто-разрешает IMPORTANT (напр. запись файлов create_document),
        // и обещание «важные отклоняются» не выполнялось (анти-RCE). Совпадает с десктопным хостом.
        AgentChatEngine.run(context, cmd, recentContext = "", mode = AutonomyMode.NORMAL, confirm = { false })
            .collect { ev ->
                when (ev) {
                    is AgentEvent.ToolCall -> emit("step", "🔧 ${ev.toolId}")
                    is AgentEvent.Clarification -> { lastInfo = "Вопрос: ${ev.question}"; emit("step", lastInfo) }
                    is AgentEvent.EscalationSuggested -> {
                        lastInfo = "Локальная модель предлагает облако: ${ev.reason}"; emit("step", lastInfo)
                    }
                    is AgentEvent.Answer -> { emit("final", ev.text); finalSent = true }
                    else -> {}
                }
            }
        if (!finalSent) emit("final", lastInfo.ifBlank { "(агент не дал ответа)" })
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private var lastNotifyTime = 0L

    private fun notify(n: Notification) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyTime < 5000) return
        lastNotifyTime = now
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, n)
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Удалённое управление", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Телефон принимает команды агента с ПК"
                },
            )
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Plus AI · управление с ПК")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth) // штатная иконка, без новых ресурсов
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "RemoteAgentSvc"
        private const val CHANNEL = "remote_agent"
        private const val NOTIF_ID = 4711

        /** Запустить/остановить хост согласно настройке (вызывается из UI-тумблера и при старте). */
        fun sync(context: Context) {
            if (AppSettings.remoteHostEnabled(context) && Account.current(context) != null) start(context)
            else stop(context)
        }

        fun start(context: Context) {
            val i = Intent(context, RemoteAgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteAgentService::class.java))
        }
    }
}
