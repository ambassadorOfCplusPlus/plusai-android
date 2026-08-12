package ru.aiagent.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.aiagent.app.cloud.Account
import ru.aiagent.app.ui.P

/**
 * Экран аккаунта: вход/регистрация на сервере владельца. После успеха токен
 * (Bearer кошелька) сохраняется и включается маршрут OwnerProxy — облако и баланс
 * привязаны к аккаунту. Если вход уже выполнен — показывает профиль и баланс.
 */
@Composable
fun AccountScreen(onBack: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    var session by remember { mutableStateOf(Account.current(context)) }

    Column(Modifier.fillMaxSize().background(P.Bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "назад", tint = P.Text,
                modifier = Modifier.size(38.dp).clickable { onBack() }.padding(6.dp))
            Text("Аккаунт", color = P.Text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp))
        }

        if (session != null) {
            ProfileView(session!!, onLogout = {
                Account.logout(context); session = null; onChanged()
            })
        } else {
            AuthForm(onSuccess = { session = Account.current(context); onChanged() })
        }
    }
}

private enum class AuthMode { LOGIN, REGISTER, RECOVER }

@Composable
private fun AuthForm(onSuccess: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(AuthMode.LOGIN) }
    var url by remember { mutableStateOf(Account.DEFAULT_URL) }
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var recoveryInput by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.AccountCircle, null, tint = P.Accent, modifier = Modifier.size(64.dp).padding(top = 8.dp))
        Text(
            when (mode) {
                AuthMode.REGISTER -> "Регистрация"
                AuthMode.RECOVER -> "Сброс пароля"
                else -> "Вход"
            },
            color = P.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 10.dp),
        )
        Text(
            when (mode) {
                AuthMode.RECOVER -> "Введи логин и нажми «Прислать код» — 6 цифр придут на подтверждённую почту аккаунта."
                else -> "Аккаунт хранит баланс и настройки на сервере Plus AI. Локальные модели работают и без входа."
            },
            color = P.TextSoft, fontSize = 13.sp, modifier = Modifier.padding(bottom = 14.dp),
        )

        Field(login, { login = it; error = null }, "Логин", KeyboardType.Text)
        if (mode == AuthMode.REGISTER) {
            Spacer(Modifier.height(10.dp))
            Field(email, { email = it }, "Почта (для восстановления пароля)", KeyboardType.Email)
        }
        if (mode == AuthMode.RECOVER) {
            Spacer(Modifier.height(10.dp))
            Field(recoveryInput, { recoveryInput = it; error = null }, "Код из письма (6 цифр)", KeyboardType.Number)
            Text(
                "Прислать код на почту",
                color = P.Accent, fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp).clickable(enabled = !busy && login.isNotBlank()) {
                    busy = true
                    scope.launch {
                        Account.requestEmailRecover(context, url, login)
                            .onSuccess { error = "Если почта привязана и подтверждена — код отправлен. Введи 6 цифр из письма." }
                            .onFailure { error = it.message ?: "ошибка" }
                        busy = false
                    }
                },
            )
        }
        Spacer(Modifier.height(10.dp))
        Field(
            password, { password = it; error = null },
            if (mode == AuthMode.RECOVER) "Новый пароль" else "Пароль",
            KeyboardType.Password, isPassword = true,
        )

        error?.let {
            Text(it, color = P.Accent, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp).fillMaxWidth())
        }

        Spacer(Modifier.height(18.dp))
        Box(
            Modifier.fillMaxWidth().height(50.dp)
                .background(if (busy) P.AccentSoft else P.Accent, RoundedCornerShape(14.dp))
                .clickable(enabled = !busy) {
                    error = null; busy = true
                    scope.launch {
                        val res = when (mode) {
                            AuthMode.REGISTER -> Account.register(context, url, login, password, email)
                            AuthMode.RECOVER -> Account.resetByEmail(context, url, login, recoveryInput.trim(), password)
                            else -> Account.login(context, url, login, password)
                        }
                        busy = false
                        res.onSuccess { onSuccess() }.onFailure { error = it.message ?: "ошибка" }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (busy) CircularProgressIndicator(color = P.Bg, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            else Text(
                when (mode) {
                    AuthMode.REGISTER -> "Создать аккаунт"
                    AuthMode.RECOVER -> "Сбросить пароль"
                    else -> "Войти"
                },
                color = P.Bg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            )
        }

        if (mode == AuthMode.REGISTER) {
            Text(
                "Создавая аккаунт, вы соглашаетесь с политикой конфиденциальности, включая отправку " +
                    "анонимной статистики использования (категория задачи и инструменты, без содержимого) " +
                    "для развития продукта.",
                color = P.TextSoft, fontSize = 12.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        Text(
            if (mode == AuthMode.LOGIN) "Нет аккаунта? Зарегистрироваться" else "Уже есть аккаунт? Войти",
            color = P.Accent, fontSize = 14.sp,
            modifier = Modifier.padding(top = 18.dp)
                .clickable { mode = if (mode == AuthMode.LOGIN) AuthMode.REGISTER else AuthMode.LOGIN; error = null },
        )
        if (mode == AuthMode.LOGIN) {
            Text(
                "Забыли пароль?", color = P.TextSoft, fontSize = 14.sp,
                modifier = Modifier.padding(top = 12.dp).clickable { mode = AuthMode.RECOVER; error = null },
            )
        }

        // Адрес сервера (обычно менять не нужно).
        Spacer(Modifier.height(24.dp))
        Field(url, { url = it }, "Адрес сервера", KeyboardType.Uri)
    }
}

@Composable
private fun ProfileView(session: Account.Session, onLogout: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<Account.BalanceInfo?>(null) }
    var balErr by remember { mutableStateOf<String?>(null) }
    var rates by remember { mutableStateOf(Account.SupportRates(0.005, 0.05, 0.005, 200, 500)) }
    var refresh by remember { mutableStateOf(0) }
    var provBals by remember { mutableStateOf<List<Account.ProviderBal>>(emptyList()) } // балансы каждого API (только админ)
    var provPref by remember { mutableStateOf<Account.PreferProvider?>(null) }           // ручной выбор провайдера (админ)

    androidx.compose.runtime.LaunchedEffect(session.token, refresh) {
        rates = Account.rates(context)
        Account.balance(context)
            .onSuccess { info = it; balErr = null }
            .onFailure { balErr = it.message }
        // Для админа — балансы каждого апстрима отдельно + текущий ручной выбор провайдера.
        if (info?.admin == true) {
            Account.providerBalances(context).onSuccess { provBals = it }
            Account.preferProvider(context).onSuccess { provPref = it }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
            Icon(Icons.Outlined.AccountCircle, null, tint = P.Accent, modifier = Modifier.size(52.dp))
            Column(Modifier.padding(start = 14.dp)) {
                Text(session.login, color = P.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (session.email.isNotBlank()) {
                    Text(
                        session.email + if (info?.emailVerified == true) " ✓" else "",
                        color = P.TextSoft, fontSize = 13.sp,
                    )
                }
            }
        }

        // Подтверждение почты (если сервер умеет письма и почта ещё не подтверждена).
        if (info?.betaMode != true && session.email.isNotBlank() && info?.mailEnabled == true && info?.emailVerified == false) {
            EmailVerifyCard(onVerified = { refresh++ })
            Spacer(Modifier.height(14.dp))
        }

        // Баланс и подписка.
        Card {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Кошелёк", color = P.TextSoft, fontSize = 14.sp)
                Text(
                    when {
                        info?.betaMode == true -> "бета-режим"
                        balErr != null -> "ошибка: ${balErr!!.take(40)}"
                        info == null -> "проверяю…"
                        info!!.admin -> "%.2f ₽ (баланс API)".format(info!!.balanceRub)
                        else -> "%.2f ₽".format(info!!.balanceRub)
                    },
                    color = P.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
            }
            info?.takeIf { it.subActive && it.betaMode != true }?.let { i ->
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Подписка", color = P.TextSoft, fontSize = 14.sp)
                    Text(
                        "%.2f из %.2f ₽ до %s".format(i.subLeftRub, i.subLimitRub, i.subExpires.take(10)),
                        color = P.Text, fontSize = 14.sp,
                    )
                }
            }
            // Скользящие окна трат (ТЗ §3.13.1) — сколько потрачено из потолка за 5ч/неделю (U5).
            // Каждое окно показываем по СВОЕМУ потолку: раньше оба висели под cap5hRub>0, и при cap_5h=0,
            // но cap_week>0 недельное окно скрывалось целиком (audit).
            info?.takeIf { !it.admin && !it.betaMode && (it.cap5hRub > 0 || it.capWeekRub > 0) }?.let { i ->
                Spacer(Modifier.height(8.dp))
                if (i.cap5hRub > 0) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Окно 5 часов", color = P.TextSoft, fontSize = 14.sp)
                        Text("%.2f из %.2f ₽".format(i.spent5hRub, i.cap5hRub), color = P.Text, fontSize = 14.sp)
                    }
                }
                if (i.capWeekRub > 0) {
                    if (i.cap5hRub > 0) Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Окно недели", color = P.TextSoft, fontSize = 14.sp)
                        Text("%.2f из %.2f ₽".format(i.spentWeekRub, i.capWeekRub), color = P.Text, fontSize = 14.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Обновить", color = P.Accent, fontSize = 14.sp,
            modifier = Modifier.padding(4.dp).clickable { refresh++ },
        )

        // Балансы КАЖДОГО апстрима отдельно — только админ (RouterAI/Kodik/Polza/OpenRouter/free-тиры).
        if (info?.admin == true && provBals.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Card {
                Text("Балансы провайдеров", color = P.TextSoft, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                for (b in provBals) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(b.name, color = P.Text, fontSize = 14.sp)
                        val (txt, low) = when {
                            b.error.isNotBlank() -> "ошибка: ${b.error.take(30)}" to false
                            b.note.isNotBlank() -> b.note to false
                            b.currency == "USD" -> "$%.2f".format(b.balance) to (b.balance < 5)
                            else -> "%.2f ₽".format(b.balance) to (b.balance < 50) // подсветка низкого баланса
                        }
                        Text(
                            txt, color = if (low) P.Accent else P.Text, fontSize = 14.sp,
                            fontWeight = if (low) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }

        // Выбор провайдера для АДМИН-трафика: «auto» = самый дешёвый (арбитраж), либо форс конкретного.
        if (info?.admin == true) {
            provPref?.let { pref ->
                Spacer(Modifier.height(14.dp))
                Card {
                    Text("Провайдер (маршрут админа)", color = P.TextSoft, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        for (name in pref.available) {
                            val on = (pref.current.ifBlank { "auto" }) == name
                            Box(
                                Modifier.padding(end = 8.dp).height(34.dp)
                                    .background(if (on) P.Accent else P.Surface, RoundedCornerShape(10.dp))
                                    .clickable {
                                        scope.launch { Account.setPreferProvider(context, name).onSuccess { provPref = it } }
                                    }.padding(horizontal = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                val label = if (name == "auto") "Авто (дёшево)" else name
                                Text(label, color = if (on) P.Bg else P.Text, fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("«Авто» берёт самый дешёвый апстрим. Выбор конкретного — форс (напр. чтобы обойти пустой баланс).",
                        color = P.TextSoft, fontSize = 11.sp)
                }
            }
        }

        // Пополнение и подписка — только для обычных аккаунтов (админ живёт на кредите API).
        if (info?.admin != true && info?.betaMode != true) {
            Spacer(Modifier.height(20.dp))
            PaymentsSection(rates = rates, onDone = { refresh++ })
        }

        Spacer(Modifier.height(20.dp))
        Card {
            Text("Сервер", color = P.TextSoft, fontSize = 13.sp)
            Text(session.url, color = P.Text, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        }

        Spacer(Modifier.height(28.dp))
        Box(
            Modifier.fillMaxWidth().height(48.dp)
                .background(P.Surface, RoundedCornerShape(14.dp))
                .clickable { onLogout() },
            contentAlignment = Alignment.Center,
        ) {
            Text("Выйти из аккаунта", color = P.Accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * Пополнение (ТЗ §3.13.1): сумма + видимый добровольный ползунок «поддержать проект»
 * (0.5–5%, дефолт 0.5% ≈ комиссия СБП) — и оформление подписки (тот же ползунок).
 * Скрытые ставки (10/15%) в UI не показываются — инвариант ТЗ.
 */
@Composable
private fun PaymentsSection(rates: Account.SupportRates, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var amount by remember { mutableStateOf("300") }
    var support by remember { mutableStateOf(rates.def.toFloat()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var touched by remember { mutableStateOf(false) } // трогал ли юзер ползунок
    // Границы санитайзим на КЛИЕНТЕ: кастомный/старый сервер (URL настраиваемый) мог бы прислать min>max,
    // и тогда coerceIn/Slider.valueRange бросили бы IllegalArgumentException → краш экрана «Аккаунт».
    val lo = minOf(rates.min, rates.max).toFloat()
    val hi = maxOf(rates.min, rates.max).toFloat().let { if (it > lo) it else lo + 0.001f }
    // Пришли ставки с сервера: если юзер уже двигал ползунок — НЕ сбрасываем его выбор на дефолт,
    // а лишь ужимаем в новые границы; иначе поздний ответ /v1/rates/public терял бы выбор.
    androidx.compose.runtime.LaunchedEffect(rates) {
        support = if (touched) support.coerceIn(lo, hi) else rates.def.toFloat().coerceIn(lo, hi)
    }

    val amountV = amount.replace(',', '.').toDoubleOrNull() ?: 0.0

    Card {
        Text("Пополнить кошелёк", color = P.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Field(amount, { amount = it; msg = null }, "Сумма, ₽", KeyboardType.Number)

        Spacer(Modifier.height(12.dp))
        Text(
            "Поддержать проект: %.1f%%".format(support * 100),
            color = P.TextSoft, fontSize = 13.sp,
        )
        androidx.compose.material3.Slider(
            value = support.coerceIn(lo, hi),
            onValueChange = { support = it; touched = true },
            valueRange = lo..hi,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = P.Accent, activeTrackColor = P.Accent, inactiveTrackColor = P.Line,
            ),
        )
        Text(
            "Добровольно: %.1f%% покрывает комиссию платёжки, больше — поддержка разработки. Будет зачислено %.2f ₽."
                .format(rates.min * 100, amountV * (1 - support)),
            color = P.TextSoft, fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))
        ActionButton("Пополнить", busy && msg == null, enabled = amountV > 0 && !busy) {
            busy = true; msg = null
            scope.launch {
                Account.topUp(context, amountV, support.toDouble())
                    .onSuccess { msg = "Зачислено. Баланс: %.2f ₽".format(it); onDone() }
                    .onFailure { msg = it.message ?: "ошибка" }
                busy = false
            }
        }

        msg?.let {
            Spacer(Modifier.height(10.dp))
            // PaymentsSection теперь эмитит только «Зачислено…» или ошибку (тарифы — в TariffsSection).
            Text(it, color = if (it.startsWith("Зачислено")) P.Text else P.Accent, fontSize = 13.sp)
        }
    }

    Spacer(Modifier.height(20.dp))
    TariffsSection(onDone = onDone)
}

/** Класс доступа тарифа → человекочитаемое описание. */
private fun classLabel(cls: String): String = when (cls) {
    "basic" -> "Базовые модели"
    "standard" -> "Дорогие модели (без флагманов)"
    "flagship" -> "Флагманские модели"
    "all" -> "Все модели без ограничений"
    else -> cls
}

/**
 * BILL-TIERS: выбор фиксированного тарифа (карточки). Цена фиксирована, различие — в доступе к
 * классам моделей. Превышение fair-use → доплата с кошелька; локальные модели всегда бесплатны.
 */
@Composable
private fun TariffsSection(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var plans by remember { mutableStateOf<List<Account.Plan>>(emptyList()) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        Account.fetchPlans(context).onSuccess { plans = it }
    }

    Card {
        Text("Тарифы подписки", color = P.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Фикс-цена в месяц и доступ к классу моделей. Локальные модели на телефоне — всегда бесплатно.",
            color = P.TextSoft, fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))
        if (plans.isEmpty()) {
            Text("Загрузка тарифов…", color = P.TextSoft, fontSize = 13.sp)
        }
        plans.forEach { p ->
            val discounted = p.yourPriceRub < p.priceRub - 0.001
            Box(
                Modifier.fillMaxWidth().padding(vertical = 6.dp)
                    .background(P.Surface, RoundedCornerShape(14.dp)).padding(14.dp),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, color = P.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (discounted) {
                            Text("%.0f ₽".format(p.priceRub), color = P.TextSoft, fontSize = 13.sp,
                                textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text("%.0f ₽/мес".format(p.yourPriceRub), color = P.Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(classLabel(p.cls), color = P.Text, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    ActionButton(
                        if (busyId == p.id) "Оформляем…" else "Оформить «${p.name}»",
                        busy = busyId == p.id, enabled = busyId == null,
                    ) {
                        busyId = p.id; msg = null
                        scope.launch {
                            Account.buyPlan(context, p.id)
                                .onSuccess { msg = "Тариф оформлен: $it"; onDone() }
                                .onFailure { msg = it.message ?: "ошибка" }
                            busyId = null
                        }
                    }
                }
            }
        }
        msg?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = if (it.startsWith("Тариф")) P.Text else P.Accent, fontSize = 13.sp)
        }
    }
}

/** Карточка «Подтверди почту»: отправка кода + ввод 6 цифр из письма. */
@Composable
private fun EmailVerifyCard(onVerified: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sent by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    Card {
        Text("Почта не подтверждена", color = P.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Подтверждённая почта позволяет сбрасывать пароль кодом из письма.",
            color = P.TextSoft, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(10.dp))
        if (sent) {
            Field(code, { code = it; msg = null }, "Код из письма (6 цифр)", KeyboardType.Number)
            Spacer(Modifier.height(10.dp))
            ActionButton("Подтвердить", busy, enabled = code.trim().length == 6 && !busy) {
                busy = true
                scope.launch {
                    Account.verifyEmail(context, code.trim())
                        .onSuccess { msg = "Почта подтверждена ✓"; onVerified() }
                        .onFailure { msg = it.message ?: "ошибка" }
                    busy = false
                }
            }
        } else {
            ActionButton("Прислать код на почту", busy, enabled = !busy) {
                busy = true
                scope.launch {
                    Account.requestEmailVerify(context)
                        .onSuccess { sent = true; msg = "Код отправлен — проверь почту." }
                        .onFailure { msg = it.message ?: "ошибка" }
                    busy = false
                }
            }
        }
        msg?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = if (it.contains("✓") || it.startsWith("Код отправлен")) P.Text else P.Accent, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ActionButton(label: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(46.dp)
            .background(if (enabled) P.Accent else P.AccentSoft, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(color = P.Bg, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        else Text(label, color = P.Bg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(P.Surface, RoundedCornerShape(14.dp)).padding(16.dp),
        content = content,
    )
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    keyboard: KeyboardType,
    isPassword: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, color = P.TextSoft) },
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = P.Text, unfocusedTextColor = P.Text,
            focusedBorderColor = P.Accent, unfocusedBorderColor = P.Line,
            cursorColor = P.Accent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
