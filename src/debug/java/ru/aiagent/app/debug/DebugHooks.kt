package ru.aiagent.app.debug

import android.app.Activity

/**
 * DEBUG-вариант: поднимает [DebugServer] (localhost-консоль для headless-отладки с ПК) и
 * прокидывает в него текущий экран. Присутствует ТОЛЬКО в debug-сборке (src/debug), в релизе —
 * заглушка (src/release). MainActivity/AppShell зовут это безусловно.
 */
object DebugHooks {
    fun onActivityCreate(activity: Activity) = DebugServer.attach(activity)
    fun publishRoute(route: String) { DebugServer.currentRoute = route }
}
