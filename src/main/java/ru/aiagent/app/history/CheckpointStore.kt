package ru.aiagent.app.history

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.aiagent.app.AppSettings
import ru.aiagent.app.security.CryptoBox
import java.io.File

/**
 * Обвязка автоотката (FEAT-CHECKPOINTS) для приложения: собирает [CheckpointEngine] с шифрованием
 * через [CryptoBox] (Android Keystore) и параметрами из [AppSettings]. Всё — вне главного потока.
 *
 * Хук в ход агента: [ensureBaseline] перед ходом (чтобы первый ход можно было откатить) и [snapshot]
 * после (снимок workspace = точка отката для этого хода). Настройки бюджета/ретенции берутся при
 * каждом вызове, поэтому изменение слайдера сразу влияет на следующую чистку.
 *
 * Каждый вызов строит НОВЫЙ [CheckpointEngine] (настройки могли измениться), поэтому его внутренний
 * @Synchronized не сериализует операции между собой. Общий [mutex] сериализует ВСЕ операции истории
 * (snapshot из хода агента vs restore/clear/compact из UI) — иначе гонка на journal.bin/blobs портит
 * историю (общий .tmp-файл, GC удаляет читаемые blob'ы).
 */
object CheckpointStore {
    private val mutex = Mutex()

    private fun engine(context: Context): CheckpointEngine {
        val hist = File(context.filesDir, "history")
        val ws = File(context.filesDir, "workspace")
        val budget = AppSettings.historyBudgetMb(context).toLong() * 1024L * 1024L
        val retention = AppSettings.historyRetentionDays(context).toLong() * 24L * 3600L * 1000L
        return CheckpointEngine(
            historyDir = hist, workspace = ws,
            encrypt = { CryptoBox.encrypt(it) }, decrypt = { CryptoBox.decrypt(it) },
            config = CheckpointEngine.Config(budgetBytes = budget, retentionMs = retention),
        )
    }

    /** Базовый снимок ДО первого хода (no-op, если история выключена или снимки уже есть). */
    suspend fun ensureBaseline(context: Context) = withContext(Dispatchers.IO) {
        if (AppSettings.historyEnabled(context)) mutex.withLock { runCatching { engine(context).ensureBaseline() } }
        Unit
    }

    /** Снимок ПОСЛЕ хода агента. reason — текст запроса пользователя (для таймлайна). */
    suspend fun snapshot(context: Context, reason: String) = withContext(Dispatchers.IO) {
        if (AppSettings.historyEnabled(context)) mutex.withLock { runCatching { engine(context).snapshot(reason) } }
        Unit
    }

    suspend fun list(context: Context): List<CheckpointEngine.CheckpointMeta> =
        withContext(Dispatchers.IO) { mutex.withLock { runCatching { engine(context).list() }.getOrDefault(emptyList()) } }

    suspend fun restore(context: Context, id: String): CheckpointEngine.RestoreResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching { engine(context).restore(id) }
                    .getOrElse { CheckpointEngine.RestoreResult(false, it.message ?: "ошибка отката") }
            }
        }

    suspend fun usageBytes(context: Context): Long =
        withContext(Dispatchers.IO) { mutex.withLock { runCatching { engine(context).usageBytes() }.getOrDefault(0L) } }

    suspend fun clear(context: Context) =
        withContext(Dispatchers.IO) { mutex.withLock { runCatching { engine(context).clear() } }; Unit }

    /** Двухтир: пережать историю на максимум сжатия (холодный тир). Возвращает число пережатых blob'ов. */
    suspend fun compact(context: Context): Int =
        withContext(Dispatchers.IO) { mutex.withLock { runCatching { engine(context).compact() }.getOrDefault(0) } }
}
