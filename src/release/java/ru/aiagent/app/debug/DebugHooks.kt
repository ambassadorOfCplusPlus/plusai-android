package ru.aiagent.app.debug

import android.app.Activity

/**
 * RELEASE-вариант: полная заглушка. Дебаг-консоль физически отсутствует в релизной сборке
 * (реализация лежит только в src/debug), поэтому сторонний код не сможет её дёрнуть.
 * MainActivity/AppShell вызывают эти методы безусловно — в релизе они ничего не делают.
 */
object DebugHooks {
    fun onActivityCreate(activity: Activity) {}
    fun publishRoute(route: String) {}
}
