package ru.aiagent.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground-сервис на ВРЕМЯ ГЕНЕРАЦИИ. Ответ генерируется в [AppScope] (переживает уничтожение экрана),
 * но если пользователь СВОРАЧИВАЕТ/ЗАКРЫВАЕТ приложение, Android под давлением памяти может убить процесс
 * — и генерация обрывается «на полуслове» (жалоба владельца: закрыл приложение → генерация отключилась).
 * Пока идёт хотя бы одна генерация, этот сервис держит процесс в foreground (с уведомлением), и система
 * его не усыпляет/не убивает — ответ доходит до конца и сохраняется, даже когда приложение закрыто.
 *
 * Счётчик [active]: begin() при старте генерации, finish() в finally корутины (в AppScope — выполнится
 * даже если экран уже уничтожен). Когда активных генераций 0 — сервис останавливается.
 */
class GenerationService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = buildNotification()
        // Android 10+ требует явный тип; dataSync — фоновая сетевая работа (стрим ответа от провайдера).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else
            startForeground(NOTIF_ID, notif)
        // Если к моменту старта генераций уже нет (быстрый finish) — не висим.
        if (active.get() <= 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        // WakeLock: держим CPU включённым на время генерации. Foreground не даёт убить процесс, но Doze/сон
        // при погасшем экране ТОРМОЗИТ CPU — локальная модель и агент-цикл вставали бы. Потолок 20 мин —
        // страховка от утечки (если finish не пришёл). ДОЛЖЕН быть БОЛЬШЕ, чем give-up опроса серверной
        // сессии (ServerGen ~16.7 мин = 40×25с), иначе wakelock отпустился бы раньше, чем poll добьётся ответа.
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PlusAI:generation").apply {
                setReferenceCounted(false)
                acquire(20 * 60 * 1000L)
            }
        }
        return START_NOT_STICKY // убили процесс — не перезапускаем пустой сервис
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Генерация ответа", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Plus AI дописывает ответ, пока приложение свёрнуто"
                    setShowBadge(false)
                },
            )
        }
        // Тап по уведомлению → возврат в приложение (к чату), не создавая второй экземпляр.
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Plus AI")
            .setContentText("Генерирую ответ…")
            .setSmallIcon(android.R.drawable.stat_notify_sync) // штатная иконка, без новых ресурсов
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        private const val CHANNEL = "generation"
        private const val NOTIF_ID = 4712
        private val active = AtomicInteger(0)

        /** Старт генерации: держим процесс в foreground. Вызывать из UI (приложение на переднем плане). */
        fun begin(context: Context) {
            if (active.incrementAndGet() == 1) {
                val i = Intent(context.applicationContext, GenerationService::class.java)
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        context.applicationContext.startForegroundService(i)
                    else
                        context.applicationContext.startService(i)
                }
            }
        }

        /** Конец генерации (вызывать в finally корутины — в AppScope, отработает даже при закрытом экране). */
        fun finish(context: Context) {
            if (active.decrementAndGet() <= 0) {
                active.set(0) // защита от рассинхрона (нельзя уйти в минус)
                runCatching {
                    context.applicationContext.stopService(Intent(context.applicationContext, GenerationService::class.java))
                }
            }
        }
    }
}
