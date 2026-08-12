package ru.aiagent.app.remote

/**
 * TOFU-пиннинг публичного ключа пира. Публичные ключи приходят ОТ реле — стороны, которой E2E не
 * доверяет. Если реле подменит ключ известного пира (MITM), выведенный ECDH-секрет утёк бы к
 * атакующему. Первый ключ пира фиксируем; СМЕНУ отклоняем. Побочно: легальная переустановка
 * устройства требует ручного пересопряжения. Паритет с десктопным RemoteHost.
 */
object TofuPin {
    /** Принять ли предъявленный ключ: неизвестный пир (known == null) или тот же ключ — да; смена — нет. */
    fun accept(known: String?, incoming: String): Boolean = known == null || known == incoming
}

/**
 * Анти-replay по отпечатку шифртекста (FIFO-окно). Реле присваивает seq и ему НЕ доверяем: захваченный
 * валидный шифртекст, переотправленный с новым seq, прошёл бы seq-проверку. Дедуп по самому payload:
 * GCM-nonce случаен на каждое шифрование, поэтому у повтора payload идентичен байт-в-байт, а у нового
 * легитимного сообщения — иной. Паритет с десктопным RemoteHost.
 */
class ReplayGuard(private val capacity: Int = 512) {
    private val seen = HashSet<String>()
    private val order = ArrayDeque<String>()

    /** true — этот отпечаток уже видели (повтор, отбросить). Иначе запоминает и возвращает false. */
    @Synchronized
    fun seenBefore(fingerprint: String): Boolean {
        if (!seen.add(fingerprint)) return true
        order.addLast(fingerprint)
        while (order.size > capacity) seen.remove(order.removeFirst())
        return false
    }
}
