package ru.aiagent.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.aiagent.app.cloud.CloudEngine
import ru.aiagent.app.ui.P
import ru.aiagent.core.cloud.CloudProvider

/**
 * «Свой API» — БЕСПЛАТНО всем (paywall убран): свой ключ провайдера ИЛИ свой OpenAI-совместимый endpoint
 * (URL + ключ). Запросы идут НАПРЯМУЮ телефон→провайдер, минуя сервер владельца и кошелёк — это расход и
 * лимиты самого пользователя. Anthropic/OpenAI из РФ обычно требуют VPN (geo-block). §7 не зависит от
 * маршрута: приватные тулы отфильтрованы выше по стеку до выбора endpoint.
 */
@Composable
fun BYOKProScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }

    // Провайдер и его нативная прямая точка. VPN — там, где geo-block из РФ. CUSTOM — свой endpoint (URL).
    // ZEN — анонимный бесплатный тир (opencode.ai/zen/v1): ключ НЕ нужен, запрос напрямую.
    data class Prov(val p: CloudProvider, val title: String, val keyHint: String, val vpn: Boolean)
    val provs = listOf(
        Prov(CloudProvider.ZEN, "Бесплатные модели (без ключа)", "не нужен", false),
        Prov(CloudProvider.CUSTOM, "Свой endpoint (URL)", "ключ (можно пусто)", false),
        Prov(CloudProvider.ANTHROPIC, "Anthropic (Claude)", "sk-ant-…", true),
        Prov(CloudProvider.OPENAI, "OpenAI (GPT)", "sk-…", true),
        Prov(CloudProvider.DEEPSEEK, "DeepSeek", "sk-…", false),
        Prov(CloudProvider.QWEN, "Qwen", "sk-…", false),
    )
    var sel by remember { mutableStateOf(provs[0]) }
    val isCustom = sel.p == CloudProvider.CUSTOM
    val isZen = sel.p == CloudProvider.ZEN
    // Префилл из хранилища при смене провайдера.
    var key by remember(sel) {
        mutableStateOf(if (isCustom) CloudEngine.customEndpoint(context).key else CloudEngine.byokKey(context, sel.p) ?: "")
    }
    var customUrl by remember(sel) { mutableStateOf(CloudEngine.customEndpoint(context).url) }
    var customModel by remember(sel) { mutableStateOf(CloudEngine.customEndpoint(context).model) }
    var showKey by remember(sel) { mutableStateOf(false) } // ключ по умолчанию скрыт (защита от подглядывания/скриншотов)

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = P.Text, unfocusedTextColor = P.Text,
        focusedBorderColor = P.Accent, unfocusedBorderColor = P.Line, cursorColor = P.Accent,
    )

    Column(Modifier.fillMaxSize().background(P.Bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "назад", tint = P.Text,
                modifier = Modifier.size(38.dp).clickable { onBack() }.padding(6.dp))
            Text("Свой API — ключ или endpoint", color = P.Text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp))
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text(
                "Бесплатно. Свой ключ или свой OpenAI-совместимый endpoint — запросы идут НАПРЯМУЮ с телефона, " +
                    "минуя наш сервер и кошелёк. Комиссия 0%: платите только провайдеру (или ничего, если endpoint свой/бесплатный).",
                color = P.TextSoft, fontSize = 13.sp,
            )
            Spacer(Modifier.height(16.dp))
            // Выбор провайдера
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                provs.forEach { pr ->
                    val on = sel.p == pr.p
                    Box(
                        Modifier.padding(end = 8.dp).height(36.dp)
                            .background(if (on) P.Accent else P.Surface, RoundedCornerShape(10.dp))
                            .clickable { sel = pr }.padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(if (pr.p == CloudProvider.CUSTOM) "URL" else pr.p.name.take(4), color = if (on) P.Bg else P.Text, fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(sel.title, color = P.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            if (sel.vpn) {
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().background(P.Accent.copy(alpha = 0.14f), RoundedCornerShape(10.dp)).padding(12.dp)) {
                    Text(
                        "⚠ ${sel.title} из России обычно недоступен напрямую — включите VPN (сервер вне РФ), иначе запросы не пройдут.",
                        color = P.Accent, fontSize = 12.5.sp,
                    )
                }
            }
            // Свой endpoint: поле URL + модель.
            if (isCustom) {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = customUrl, onValueChange = { customUrl = it }, singleLine = true,
                    label = { Text("URL (напр. https://api.groq.com/openai)", color = P.TextSoft) },
                    colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = customModel, onValueChange = { customModel = it }, singleLine = true,
                    label = { Text("Модель (напр. llama-3.3-70b-versatile)", color = P.TextSoft) },
                    colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = key, onValueChange = { key = it }, singleLine = true,
                label = { Text("API-ключ (${sel.keyHint})", color = P.TextSoft) },
                // Секрет: по умолчанию маскируем, с переключателем «показать».
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    Text(
                        if (showKey) "Скрыть" else "Показать",
                        color = P.Accent, fontSize = 12.sp,
                        modifier = Modifier.clickable { showKey = !showKey }.padding(horizontal = 10.dp),
                    )
                },
                colors = fieldColors, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            // Для своего endpoint'а модель ОБЯЗАТЕЛЬНА: без неё в чат ушёл бы id из каталога RouterAI,
            // которого на чужом сервере нет → 400/404. Для BYOK-провайдера достаточно ключа.
            // Для free-тира Zen ключ НЕ нужен — включается одним нажатием (анонимный официальный тир).
            val canSave = if (isCustom) customUrl.isNotBlank() && customModel.isNotBlank()
            else if (isZen) true
            else key.isNotBlank()
            BuyBtn("Сохранить и подключить напрямую", Modifier.fillMaxWidth(), canSave) {
                if (isCustom && !isSafeCustomUrl(customUrl)) {
                    // Ключ уходит как Authorization: Bearer прямо на этот URL. По http (кроме localhost) его
                    // перехватят на канале → требуем https. Для self-hosted (LM Studio/vLLM) разрешаем localhost.
                    status = "URL должен быть https:// (http:// — только для localhost/127.0.0.1): иначе ключ уйдёт по незашифрованному каналу."
                } else {
                    if (isZen) {
                        CloudEngine.setZen(context, enabled = true)
                        status = "Бесплатный тир включён: DeepSeek V4 Flash (free), без ключа и без сервера."
                    } else if (isCustom) {
                        CloudEngine.setCustomEndpoint(context, enabled = true, url = customUrl.trim(), key = key.trim(), model = customModel.trim())
                        status = "Свой endpoint подключён. Модель: ${customModel.trim()}."
                    } else {
                        CloudEngine.setByokKey(context, sel.p, key.trim())
                        CloudEngine.setCustomEnabled(context, enabled = false) // уступаем приоритет, но НЕ стираем настроенный endpoint
                        status = "${sel.title} подключён напрямую. Выберите модель в чате."
                    }
                    // Прокси уступает приоритет (ходим напрямую), но Bearer кошелька СОХРАНЯЕМ — иначе при
                    // отключении своего endpoint облако сломалось бы до ре-логина.
                    CloudEngine.setProxyEnabled(context, enabled = false)
                }
            }
            status?.let { Spacer(Modifier.height(10.dp)); Text(it, color = P.Accent, fontSize = 13.sp) }
            Spacer(Modifier.height(16.dp))
            Text(
                "Ключи и URL хранятся в защищённом хранилище телефона (Keystore) и на сервер не отправляются.",
                color = P.TextSoft, fontSize = 12.sp,
            )
        }
    }
}

/**
 * Свой endpoint принимаем только по https (ключ уходит Bearer'ом); http — лишь для локального self-hosted.
 * Делегируем в McpClient.isSafeUrl: там хост РАЗБИРАЕТСЯ, а не сверяется префиксом. Прежняя проверка
 * `startsWith("http://localhost")` пропускала `http://localhost.evil.com` / `http://127.0.0.1.evil.com` /
 * `http://localhost@evil.com` — реальный хост чужой, и ключ пользователя уходил ему открытым текстом.
 */
private fun isSafeCustomUrl(url: String): Boolean =
    ru.aiagent.app.integrations.mcp.McpClient.isSafeUrl(url)

@Composable
private fun BuyBtn(label: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier.height(46.dp).background(if (enabled) P.Accent else P.Line, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = P.Bg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}
