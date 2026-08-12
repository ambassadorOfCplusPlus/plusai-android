package ru.aiagent.app.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * Телефон-СТОРОНА связки «управление с ПК» (REMOTE-XDEV) — зеркало десктопного RemoteHost:
 * анонсит присутствие, слушает релэй, расшифровывает команды с ПК, исполняет их через
 * [handler] (агент на локальной модели телефона) и шифрует ответ обратно. Ключи с пирами
 * выводятся ECDH по их публичным ключам из announce. Сервер — только ретранслятор шифртекста.
 *
 * @param handler исполнитель команды: получает текст команды и `emit(type,text)` для стриминга
 *   прогресса (шаги/финал по мере выполнения). Гоняется прямо в poll-корутине.
 * @param initialSince стартовый курсор (персист): чтобы при рестарте НЕ переисполнить уже
 *   обработанные команды из удерживаемой сервером истории.
 * @param onCursor вызывается при продвижении курсора — сохранить, чтобы пережить рестарт.
 */
class RemoteHost(
    private val relay: RelayClient,
    private val crypto: E2ECrypto,
    private val peers: VerifiedPeers,
    private val deviceId: String,
    private val deviceName: String,
    private val handler: suspend (String, suspend (String, String) -> Unit) -> Unit,
    private val log: (String) -> Unit = {},
    private val onPeers: (List<String>) -> Unit = {},
    private val initialSince: Long = 0,
    private val onCursor: (Long) -> Unit = {},
) {
    // ConcurrentHashMap: refreshPeers() зовётся из ДВУХ мест (первичный announce + периодический,
    // и внутри handle при новом пире) — обычный HashMap дал бы гонку чтения/записи ключей.
    private val keys = ConcurrentHashMap<String, ByteArray>()
    // Пир→его pubkey, по которому выведен keys[id]: чтобы не пересчитывать ECDH+HKDF каждые ~25с
    // для неизменного ключа (дорогая крипта на фоновом сервисе — расход батареи). Он же — «запинненный»
    // (TOFU) ключ пира: смену относительно него отклоняем.
    private val keyPubkeys = ConcurrentHashMap<String, String>()
    // Анти-replay: отпечатки уже обработанных шифртекстов (FIFO-окно). Паритет с десктопом.
    private val replay = ReplayGuard()

    /** Основная петля: держится, пока живёт корутина (её отменяет остановка сервиса). */
    suspend fun run() {
        log("удалённое управление включено")
        try {
            refreshPeers()
        } catch (e: Exception) {
            log("announce: ${e.message}")
        }
        var since = initialSince
        while (coroutineContext.isActive) {
            try {
                // Повторный announce перед каждым poll: presence-TTL на сервере 60с, а poll блокирует
                // ~25с — значит цикл announce→poll даёт ритм ~25с < 60с, и ПК не теряет телефон.
                try { refreshPeers() } catch (e: Exception) { log("announce: ${e.message}") }
                val msgs = withContext(Dispatchers.IO) { relay.poll(deviceId, since) }
                for (m in msgs) {
                    // Дубль (сервер после self-heal/сброса вернул уже обработанное с seq<=курсора) — ПРОПУСКАЕМ,
                    // иначе та же команда переисполняется каждый poll (бесконечный цикл опасной операции).
                    if (m.seq <= since) continue
                    // Курсор двигаем и персистим ДО обработки: если хост упадёт в середине команды,
                    // после рестарта мы её не переисполним (потерять безопаснее, чем повторить опасное).
                    since = m.seq; onCursor(since)
                    handle(m)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log("poll: ${e.message}")
                delay(2000)
            }
        }
        log("удалённое управление выключено")
    }

    private suspend fun refreshPeers() {
        val devices = withContext(Dispatchers.IO) { relay.announce(deviceId, deviceName, crypto.publicKeyBase64()) }
        for (p in devices) {
            if (p.pubkey.isBlank()) continue
            // TOFU + ПЕРСИСТ: первый ключ пира фиксируется в VerifiedPeers, СМЕНА отклоняется (возможна
            // MITM-подмена реле). Персист (в отличие от прежнего in-memory) переживает рестарт сервиса.
            if (!peers.pin(p.id, p.pubkey)) {
                log("⚠ публичный ключ пира ${p.id} ИЗМЕНИЛСЯ — отклонено (возможна MITM-подмена реле). " +
                    "Если вы переустановили устройство — забудьте сопряжение и заведите заново.")
                continue
            }
            if (keyPubkeys[p.id] == p.pubkey) continue // ключ уже выведен, не пересчитываем
            // Один битый ключ не роняет весь refresh.
            try {
                keys[p.id] = withContext(Dispatchers.IO) { crypto.deriveKey(p.pubkey) }
                keyPubkeys[p.id] = p.pubkey
            } catch (e: Exception) { log("плохой ключ пира ${p.id}: ${e.message}") }
        }
        // Показываем в UI только «хостов»/контроллеров с именем — пустое имя = служебный контроллер.
        onPeers(devices.filter { it.name.isNotBlank() }.map { it.name })
    }

    private suspend fun handle(m: RelayEnvelope) {
        var key = keys[m.from]
        if (key == null) {
            refreshPeers() // новый пир — обновим ключи
            key = keys[m.from]
            if (key == null) { log("неизвестный пир ${m.from}"); return }
        }

        val parsed = try {
            Wire.parse(E2ECrypto.decrypt(key, m.payload))
        } catch (e: Exception) {
            log("не удалось расшифровать сообщение"); return
        }
        // verify-ack — ТОЛЬКО подсказка, НЕ основание доверять. «Расшифровали общим ключом ⇒ MITM нет»
        // ЛОЖНО против активного реле: оно держит валидный ECDH-сеанс с каждой стороной и может само
        // прислать расшифровываемый verify-ack, получив verified без человеческой сверки (обход
        // enforcement). Доверие ставит ТОЛЬКО наш человек, сверив SAS/QR на ЭТОЙ стороне.
        if (parsed.first == Wire.VERIFY_ACK) {
            log("ℹ пир ${m.from} сообщает, что сверил нас — подтвердите сверку и на этой стороне"); return
        }

        // Хост исполняет ТОЛЬКО команды. step/final/err — это ОТВЕТЫ хоста контроллеру; приняв их за cmd
        // (при одном общем ключе и отражении сообщения реле), хост исполнил бы собственный ответ как
        // команду. Игнорируем всё, кроме cmd. Паритет с десктопом.
        if (parsed.first != Wire.CMD) {
            log("← сообщение типа «${parsed.first}» проигнорировано (хост принимает только cmd)"); return
        }
        val cmd = parsed.second

        // ENFORCEMENT: неверифицированный пир (SAS ещё не сверен) — команды НЕ исполняем. Иначе MITM на
        // первом контакте выполнял бы команды и получал их результат. Просим контроллер провести сверку.
        if (peers.get(m.from)?.verified != true) {
            val sas = keyPubkeys[m.from]?.let { Sas.code(crypto.publicKeyBase64(), it) } ?: "??????"
            log("⛔ пир ${m.from} не верифицирован. SAS: $sas — сверьте на втором устройстве")
            try {
                withContext(Dispatchers.IO) { relay.send(deviceId, m.from, E2ECrypto.encrypt(key, Wire.make(Wire.NEED_VERIFY, ""))) }
            } catch (e: Exception) { log("need-verify: ${e.message}") }
            return
        }

        // Анти-replay ПОСЛЕ успешной расшифровки (аутентифицированное сообщение) — иначе поток мусорных
        // payload'ов от чужака вытеснил бы настоящие отпечатки. Повтор → молча отбрасываем.
        if (replay.seenBefore(payloadFingerprint(m.payload))) {
            log("↩ повтор сообщения отклонён (анти-replay)"); return
        }

        log("← команда получена (${cmd.length} симв.)") // не логируем сам текст команды (может быть приватным)
        // emit шлёт типизированный шаг/финал по мере выполнения (живой прогресс на контроллере).
        val safeKey = key
        val emit: suspend (String, String) -> Unit = { type, text ->
            try {
                withContext(Dispatchers.IO) { relay.send(deviceId, m.from, E2ECrypto.encrypt(safeKey, Wire.make(type, text))) }
            } catch (e: Exception) {
                log("send: ${e.message}")
            }
        }
        try {
            handler(cmd, emit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emit("err", "ошибка: ${e.message}")
        }
    }

    // Отпечаток шифртекста для анти-replay: SHA-256, чтобы окно не держало полные (возможно крупные)
    // payload'ы. GCM-nonce случаен, поэтому у повтора байты идентичны, а отпечаток совпадает.
    private fun payloadFingerprint(payload: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
}
