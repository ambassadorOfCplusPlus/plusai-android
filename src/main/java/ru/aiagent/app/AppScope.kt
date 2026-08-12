package ru.aiagent.app

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Область корутин уровня ПРИЛОЖЕНИЯ (переживает уничтожение экранов). Генерация ответа запускается здесь,
 * а НЕ в rememberCoroutineScope чата: иначе переход на другую вкладку уничтожает ChatScreen → его scope
 * отменяется → генерация «испаряется» на полуслове, а HTTP-запрос рвётся (провайдер может не досчитать
 * ответ, а списание/сохранение результата теряются). В app-scope запрос доходит до конца: результат
 * сохраняется в ConversationStore (persist в finally) и корректно тарифицируется, даже если экран закрыт.
 *
 * SupervisorJob: падение одной генерации не рушит остальные. CoroutineExceptionHandler: неперехваченное
 * исключение в job (напр. гонка snapshot-состояния при переключении диалога) логируется, а НЕ роняет
 * приложение — в app-scope нет родителя-обработчика, иначе uncaught-исключение = краш процесса.
 * Живёт всё время процесса (не отменяем).
 */
object AppScope {
    private val handler = CoroutineExceptionHandler { _, e ->
        Log.e("AppScope", "необработанное исключение в фоновой генерации", e)
    }
    val io: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + handler)
}
