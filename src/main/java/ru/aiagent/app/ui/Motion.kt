package ru.aiagent.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Набор сдержанных анимаций редизайна Plus AI. Навешиваются на уже существующие флаги/списки
 * в ChatScreen.kt — новую бизнес-логику не добавляют.
 */

/** Появление сообщения: fade + подъём на 10.dp, ~350ms ease-out. Ключ по индексу/id, чтобы играло 1 раз. */
@Composable
fun Modifier.messageEnter(key: Any? = Unit): Modifier {
    var shown by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) { shown = true }
    val p by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(350),
        label = "msgEnter",
    )
    return this
        .alpha(p)
        .graphicsLayer { translationY = (1f - p) * 24f }
}

/** Нажатие: плавный scale до 0.88 (120ms). Использовать вместе с clickable(interactionSource=...). */
@Composable
fun Modifier.pressable(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val s by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = tween(120),
        label = "press",
    )
    return this.scale(s)
}

/** Индикатор «думает»: три пульсирующие точки акцентом + подпись. */
@Composable
fun ThinkingDots(label: String = "думает…") {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) { i -> Dot(delayMs = i * 180) }
        }
        Spacer(Modifier.width(5.dp))
        Text(label, color = P.TextSoft, fontSize = 13.5.sp)
    }
}

@Composable
private fun Dot(delayMs: Int) {
    val t = rememberInfiniteTransition(label = "dot")
    val a by t.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(550, delayMillis = delayMs), repeatMode = RepeatMode.Reverse,
        ),
        label = "dotA",
    )
    Spacer(
        Modifier.size(7.dp).alpha(a).clip(CircleShape).background(P.Accent),
    )
}

/** Мигающая каретка стриминга — ставить в конец текста ответа, пока идёт генерация. */
@Composable
fun TypingCaret() {
    val t = rememberInfiniteTransition(label = "caret")
    val a by t.animateFloat(
        initialValue = 1f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1000), repeatMode = RepeatMode.Reverse),
        label = "caretA",
    )
    Spacer(Modifier.width(8.dp).height(17.dp).alpha(a).background(P.Accent))
}
