package ru.aiagent.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.aiagent.app.cloud.Account
import androidx.activity.compose.rememberLauncherForActivityResult
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import ru.aiagent.app.remote.DeviceIdentity
import ru.aiagent.app.remote.E2ECrypto
import ru.aiagent.app.remote.RelayClient
import ru.aiagent.app.remote.RelayDevice
import ru.aiagent.app.remote.RemoteIds
import ru.aiagent.app.remote.Sas
import ru.aiagent.app.remote.VerifiedPeers
import ru.aiagent.app.remote.Wire
import ru.aiagent.app.ui.P

/**
 * Вкладка «ПК»: список включённых ПК того же аккаунта и чат с их локальным агентом
 * через E2E-релэй. Команды и ответы шифруются (P-256 ECDH + AES-GCM); сервер лишь ретранслятор.
 */
@Composable
fun PcScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember { Account.current(context) }

    val crypto = remember { DeviceIdentity.fromPrefs(context) } // стабильный ключ (одна сверка SAS на обе роли)
    val peers = remember { VerifiedPeers.fromPrefs(context) }
    val deviceId = remember { RemoteIds.phoneBase(context) }
    val hostId = remember { RemoteIds.phoneHost(context) } // свой же телефон-хост исключаем из целей
    val relay = remember(session) { session?.let { RelayClient(it.url.trimEnd('/'), it.token) } }

    val devices = remember { mutableStateListOf<RelayDevice>() }
    var selected by remember { mutableStateOf<RelayDevice?>(null) }
    var key by remember { mutableStateOf<ByteArray?>(null) }
    val messages = remember { mutableStateListOf<Pair<String, String>>() } // role -> text
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var peerVerified by remember { mutableStateOf(false) } // ключи с выбранным ПК сверены (SAS)?

    suspend fun refresh() {
        val r = relay ?: return
        try {
            // Контроллер анонсит ПУСТОЕ имя — чтобы НЕ появляться как «цель» в списках других устройств.
            // Цели — только пиры с именем (хосты) и не наш собственный телефон-хост.
            val peers = withContext(Dispatchers.IO) { r.announce(deviceId, "", crypto.publicKeyBase64()) }
            val targets = peers.filter { it.name.isNotBlank() && it.id != hostId }
            devices.clear(); devices.addAll(targets)
            status = if (targets.isEmpty()) "нет включённых ПК (запустите десктоп и включите «Удалённо»)" else ""
        } catch (e: Exception) {
            status = "ошибка: ${e.message}"
        }
    }

    LaunchedEffect(relay) { if (relay != null) refresh() }

    // Long-poll ответов от выбранного ПК. since стартует с 0 → при открытии/переподключении
    // забираем всю удерживаемую сервером историю задачи (догон живого прогресса).
    LaunchedEffect(selected) {
        val r = relay ?: return@LaunchedEffect
        val pc = selected ?: return@LaunchedEffect
        val k = key ?: return@LaunchedEffect
        var since = 0L
        while (isActive && selected?.id == pc.id) {
            try {
                // Держим присутствие контроллера (TTL 60с): poll блокирует ~25с, поэтому повторный
                // announce перед каждым poll не даёт id «уснуть». Иначе ответ ПК на долгую (>60с)
                // задачу пришёл бы на протухший id и Send вернул бы «офлайн» — ответ терялся бы.
                withContext(Dispatchers.IO) { r.announce(deviceId, "", crypto.publicKeyBase64()) }
                val msgs = withContext(Dispatchers.IO) { r.poll(deviceId, since) }
                if (selected?.id != pc.id) break // пользователь переключил ПК за время poll — не льём чужие сообщения
                for (m in msgs) {
                    if (m.seq > since) since = m.seq // курсор двигаем по ВСЕМ (в т.ч. чужим) — иначе повтор
                    if (m.from != pc.id) continue
                    val plain = try { E2ECrypto.decrypt(k, m.payload) } catch (e: Exception) { null }
                    if (plain == null) { messages.add("pc" to "[не расшифровано]"); continue }
                    val (type, text) = Wire.parse(plain)
                    // Служебные типы сверки не показываем как сообщения. need-verify = ПК ещё не сверил
                    // НАС у себя (сверка на телефоне доверия ПК к нам не даёт — это делает человек на ПК).
                    if (type == Wire.NEED_VERIFY) {
                        status = "ПК ещё не подтвердил сверку у себя — на ПК выполните: plusai peer verify"; continue
                    }
                    if (type == Wire.VERIFY_ACK) continue
                    messages.add((if (type == "step") "step" else "pc") to text)
                }
            } catch (e: Exception) {
                status = "poll: ${e.message}"
                kotlinx.coroutines.delay(2000)
            }
        }
    }

    // deriveKey парсит публичный ключ пира и делает ECDH — держим его вне main-потока и в try/catch:
    // битый/чужого формата pubkey иначе роняет приложение прямо по тапу (InvalidKeySpecException).
    fun connectTo(target: RelayDevice) {
        scope.launch {
            try {
                val k = withContext(Dispatchers.IO) { crypto.deriveKey(target.pubkey) }
                // TOFU-пиннинг ключа ПК; смена (после переустановки/подмены) — требует ручного «Забыть».
                if (!peers.pin(target.id, target.pubkey)) {
                    status = "⚠ ключ ${target.name.ifBlank { target.id }} ИЗМЕНИЛСЯ — забудьте устройство и спарьтесь заново"
                    return@launch
                }
                peerVerified = peers.get(target.id)?.verified == true
                key = k
                messages.clear()
                selected = target
            } catch (e: Exception) {
                status = "не удалось подключиться к ${target.name.ifBlank { target.id }}: ${e.message}"
            }
        }
    }

    // QR-сверка (Ф3): сканируем QR ПК (его identity-pubkey), сверяем с ключом из announce. Совпал —
    // ключ ПК подлинный (реле не подменило), помечаем доверенным и шлём verify-ack (взаимно).
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val scanned = result.contents
        val pc = selected
        if (scanned != null && pc != null) {
            // Constant-time сравнение ключей (гигиена, паритет с десктопом): таймингового оракула тут нет
            // (сверка локальная), но сравниваем как секрет. (audit)
            if (java.security.MessageDigest.isEqual(
                    scanned.trim().toByteArray(Charsets.UTF_8), pc.pubkey.trim().toByteArray(Charsets.UTF_8))) {
                // Гейт целостности перед установлением ДОВЕРИЯ к пиру (audit): репак/активный отладчик →
                // не спариваемся (ключи могли быть скомпрометированы). На debug .so нет → soft-degrade
                // (не мешает разработке); на release без вписанного cert — блок (форсинг-функция).
                if (!ru.aiagent.app.security.NativeIntegrity.trustedForSensitive(context, ru.aiagent.app.AppSettings.strictIntegrity(context))) {
                    status = "⚠ Целостность приложения не подтверждена (репак/отладчик) — спаривание отклонено для безопасности."
                } else {
                    peers.markVerified(pc.id, pc.pubkey)
                    peerVerified = true
                    val k = key; val r = relay
                    if (k != null && r != null) scope.launch {
                        runCatching { withContext(Dispatchers.IO) { r.send(deviceId, pc.id, E2ECrypto.encrypt(k, Wire.make(Wire.VERIFY_ACK, ""))) } }
                    }
                    status = "ключ ПК подтверждён по QR. Теперь подтвердите и на ПК: plusai peer verify"
                }
            } else {
                status = "⚠ QR не совпал с ключом ПК — возможна MITM-подмена, не доверяйте"
            }
        }
    }

    // Авто-подключение к единственному ПК: диалог открывается сразу, без ручного выбора (только диалоги).
    LaunchedEffect(devices.size) {
        if (selected == null && devices.size == 1) connectTo(devices.first())
    }

    Column(Modifier.fillMaxSize().background(P.Bg).statusBarsPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Пилюля «Телефон | ПК»: тап «Телефон» возвращает в чат телефона (меняется только диалог).
            DevicePill(pcActive = true, onPhone = onBack, onPc = {})
            Spacer(Modifier.weight(1f))
            Icon(Icons.Outlined.Refresh, "Обновить", tint = P.TextSoft,
                modifier = Modifier.clickable { scope.launch { refresh() } })
        }

        if (session == null) {
            Text("Войдите в аккаунт, чтобы видеть свои ПК.", color = P.TextSoft,
                modifier = Modifier.padding(top = 24.dp))
            return
        }

        if (selected == null) {
            Text("Ваши ПК", color = P.TextSoft, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(devices) { d ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .background(P.Surface, RoundedCornerShape(10.dp))
                            .clickable { connectTo(d) }
                            .padding(14.dp)
                    ) {
                        Text(d.name.ifBlank { d.id }, color = P.Text, modifier = Modifier.weight(1f))
                        // «Забыть» — сброс сопряжения: после переустановки ПК его ключ меняется, TOFU
                        // отклоняет подключение; забываем запись, чтобы спариться заново с нуля.
                        Text("забыть", color = P.TextSoft, fontSize = 12.sp,
                            modifier = Modifier.padding(end = 12.dp).clickable { peers.forget(d.id); status = "сопряжение с ${d.name.ifBlank { d.id }} сброшено" })
                        Text("подключиться", color = P.Accent, fontSize = 13.sp)
                    }
                }
            }
            if (status.isNotBlank()) Text(status, color = P.TextSoft, fontSize = 12.sp)
        } else {
            Text("Чат с ${selected!!.name.ifBlank { selected!!.id }} · E2E", color = P.TextSoft,
                fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))

            // Панель первичной сверки ключей (SAS): пока ПК не сверен, показываем 6-значный код —
            // пользователь сверяет его с кодом на ПК (`plusai peers`) и, если совпал, жмёт «доверять».
            // Это защищает от MITM реле НА ПЕРВОМ контакте (TOFU-пиннинг сам первый ключ не проверяет).
            if (!peerVerified) {
                val sas = remember(selected) { Sas.code(crypto.publicKeyBase64(), selected!!.pubkey) }
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        .background(P.SurfaceSoft, RoundedCornerShape(10.dp)).padding(12.dp)
                ) {
                    Text("Сверьте ключи с ПК", color = P.Text, fontSize = 13.sp)
                    Text("Надёжнее — отсканировать QR ПК (сверяет ВЕСЬ ключ). Код ниже — запасной способ.",
                        color = P.TextSoft, fontSize = 12.sp)
                    Text(sas, color = P.Accent, fontSize = 24.sp, modifier = Modifier.padding(vertical = 4.dp))
                    Text("Запасной 6-значный код: совпадает с кодом на ПК (там: plusai peers)? Короткий код " +
                        "теоретически подбираем скомпрометированным реле — при сомнении сверяйте по QR.",
                        color = P.TextSoft, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        Button(onClick = {
                            val pc = selected; val k = key; val r = relay
                            if (pc != null) {
                                if (!ru.aiagent.app.security.NativeIntegrity.trustedForSensitive(context, ru.aiagent.app.AppSettings.strictIntegrity(context))) {
                                    // Репак/отладчик → не устанавливаем доверие (см. QR-путь). (audit)
                                    status = "⚠ Целостность приложения не подтверждена (репак/отладчик) — спаривание отклонено для безопасности."
                                } else {
                                    peers.markVerified(pc.id, pc.pubkey)
                                    peerVerified = true
                                    // verify-ack — лишь подсказка ПК (он доверие ставит своим человеком: MITM
                                    // мог бы подделать verify-ack, поэтому авто-доверия по нему НЕТ).
                                    if (k != null && r != null) scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) { r.send(deviceId, pc.id, E2ECrypto.encrypt(k, Wire.make(Wire.VERIFY_ACK, ""))) }
                                        }
                                    }
                                    status = "Готово на телефоне. Теперь подтвердите и на ПК: plusai peer verify"
                                }
                            }
                        }) { Text("Совпадает — доверять") }
                        OutlinedButton(onClick = {
                            scanLauncher.launch(
                                ScanOptions().setOrientationLocked(false).setBeepEnabled(false)
                                    .setPrompt("Наведите на QR на экране ПК"),
                            )
                        }) { Text("Сканировать QR") }
                    }
                }
            }

            LazyColumn(Modifier.weight(1f)) {
                items(messages) { (role, text) ->
                    if (role == "step") {
                        // Живой прогресс: компактная приглушённая строка без плашки.
                        Text("· $text", color = P.TextSoft, fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp))
                    } else {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                .background(if (role == "me") P.SurfaceSoft else P.Surface, RoundedCornerShape(10.dp))
                                .padding(12.dp)
                        ) {
                            Text(if (role == "me") "я" else "ПК", color = P.TextSoft, fontSize = 11.sp)
                            Text(text, color = P.Text)
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text("Спросите ПК…", color = P.TextSoft) })
                Button(onClick = {
                    val text = input.trim(); val r = relay; val pc = selected; val k = key
                    if (text.isNotEmpty() && r != null && pc != null && k != null) {
                        input = ""
                        messages.add("me" to text)
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { r.send(deviceId, pc.id, E2ECrypto.encrypt(k, Wire.make("cmd", text))) }
                            } catch (e: Exception) {
                                status = "send: ${e.message}"
                            }
                        }
                    }
                }) { Text("→") }
            }
            // Ссылка на список нужна только если ПК несколько (иначе диалог один — не мешаем).
            if (devices.size > 1) {
                Text("← другие ПК", color = P.TextSoft, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp).clickable { selected = null; key = null })
            }
        }
    }
}

/**
 * Сегментированная пилюля «Телефон | ПК» — переключает показываемый диалог (телефон ↔ ПК),
 * а не отдельную «командную» поверхность. Одна и та же в чате телефона и на экране ПК.
 */
@Composable
fun DevicePill(pcActive: Boolean, onPhone: () -> Unit, onPc: () -> Unit) {
    Row(
        Modifier
            .background(P.Surface, RoundedCornerShape(10.dp))
            .padding(2.dp),
    ) {
        DeviceSeg("Телефон", active = !pcActive, onClick = onPhone)
        DeviceSeg("ПК", active = pcActive, onClick = onPc)
    }
}

@Composable
private fun DeviceSeg(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (active) P.Text else P.TextSoft,
        fontSize = 13.sp,
        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
        modifier = Modifier
            .background(if (active) P.SurfaceSoft else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}
