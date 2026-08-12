package ru.aiagent.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Исключение приложения из оптимизации батареи — недостающее звено надёжной фоновой генерации.
 *
 * Foreground-сервис ([GenerationService]) + PARTIAL_WAKE_LOCK держат генерацию, пока приложение свёрнуто,
 * НО агрессивные OEM-прошивки (MIUI/EMUI/OneUI/ColorOS) всё равно усыпляют или убивают процесс, если
 * приложение не в системном списке «без ограничений батареи». Именно так теряется сессия в фоне
 * (жалоба: «в фоне генерация обрывается»). Termux держит сессию надёжно ровно потому, что пользователь
 * добавляет его в это исключение. Просим то же самое — один раз, в момент, когда это реально нужно.
 */
object BatteryOptimization {
    private const val PREFS = "plusai_battery"
    private const val KEY_ASKED = "asked"

    /** Уже без ограничений батареи? (до Android 6 понятия нет — считаем, что да.) */
    fun isExempt(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(true)
    }

    /** Спрашивали ли уже (чтобы не долбить на каждую генерацию). */
    fun asked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)

    fun markAsked(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).apply()

    /** Открыть системный диалог «разрешить работу без ограничений батареи». */
    @SuppressLint("BatteryLife")
    fun request(context: Context) {
        markAsked(context)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(direct) }.onFailure {
            // Фолбэк: общий экран списка оптимизации батареи, если прямой интент недоступен.
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}
