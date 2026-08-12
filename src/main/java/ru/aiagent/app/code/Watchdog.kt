package ru.aiagent.app.code

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Watchdog для потенциально зависающего кода (Chaquopy/BeanShell/Rhino/Lua): блокирующий вызов
 * нельзя прервать кооперативной отменой корутины, поэтому гоняем его на отдельном потоке и жёстко
 * ограничиваем по времени. При превышении — ПЫТАЕМСЯ реально остановить убежавший код (иначе daemon
 * жёг бы CPU вечно, и повторные вызовы копили бы потоки):
 *  1) interrupt() — кооперативно прерывает блокирующий IO/sleep;
 *  2) при отсутствии реакции — жёсткий stop() (ThreadDeath на safepoint убивает tight-loop
 *     JVM-интерпретаторов BeanShell/Rhino/Lua). Обёрнут в runCatching: на новых Android stop()
 *     может кинуть UnsupportedOperationException — тогда не хуже прежнего (daemon-поток отваливается).
 * Нативный CPython (Chaquopy) так не убить — там таймаут задаётся ВНУТРИ раннера (PythonTool).
 */
fun <T> watchdog(timeoutMs: Long, label: String, block: () -> T): Result<T> {
    val holder = AtomicReference<Result<T>>()
    val done = CountDownLatch(1)
    val worker = Thread {
        holder.set(runCatching { block() })
        done.countDown()
    }.apply { isDaemon = true; name = "watchdog-$label" }.also { it.start() }

    if (done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
        return holder.get()
    }
    runCatching { worker.interrupt() }
    if (!done.await(200, TimeUnit.MILLISECONDS)) {
        @Suppress("DEPRECATION")
        runCatching { worker.stop() } // последний рубеж против убежавшего JVM-кода
    }
    return Result.failure(RuntimeException("превышен лимит ${timeoutMs / 1000}с — код прерван"))
}
