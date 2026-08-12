package ru.aiagent.app.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Управление телефоном через Accessibility (S11, ТЗ §3.11). Полноценная подключаемая
 * функция: пользователь сам включает службу в системных настройках. Действия проходят
 * под режимами normal/auto/bypass + классификатором опасности (гейт — в PhoneControl).
 *
 * Служба off по умолчанию; включается только вручную пользователем.
 */
class PhoneControlService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* пассивно; читаем по запросу */ }
    override fun onInterrupt() {}

    /** Текстовый снимок экрана: видимые узлы с текстом и их координаты. */
    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "(экран недоступен)"
        val sb = StringBuilder()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            node ?: return
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val label = (text.ifEmpty { desc })
            if (label.isNotEmpty()) {
                val r = android.graphics.Rect().also { node.getBoundsInScreen(it) }
                sb.append("• \"$label\" @(${r.centerX()},${r.centerY()})")
                    .append(if (node.isClickable) " [клик]" else "").append('\n')
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return sb.toString().ifEmpty { "(на экране нет текстовых элементов)" }
    }

    fun tap(x: Int, y: Int, onDone: (Boolean) -> Unit) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) = onDone(true)
            override fun onCancelled(d: GestureDescription?) = onDone(false)
        }, null)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long, onDone: (Boolean) -> Unit) {
        val path = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) = onDone(true)
            override fun onCancelled(d: GestureDescription?) = onDone(false)
        }, null)
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    companion object {
        @Volatile
        var instance: PhoneControlService? = null
            private set

        val isEnabled: Boolean get() = instance != null
    }
}
