package ru.aiagent.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.aiagent.app.ui.P

/**
 * Онбординг первого запуска (DoD §3.16: онбординг обязателен): три страницы —
 * что умеет агент, локаль↔облако, приватность/режимы. Показывается один раз.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pages = listOf(
        OnbPage(
            Icons.Outlined.AutoAwesome, "Твой ИИ-агент",
            "Не просто чат: агент читает и создаёт документы, считает в Python, ищет по твоим " +
                "файлам и знаниям (RAG), ставит будильники и управляет телефоном.",
        ),
        OnbPage(
            Icons.Outlined.Cloud, "Локально и в облаке",
            "Локальная модель работает прямо на телефоне — бесплатно и без интернета. " +
                "Для сложных задач подключи облачные модели: аккаунт, кошелёк или подписка.",
        ),
        OnbPage(
            Icons.Outlined.Shield, "Ты управляешь",
            "Файлы и база знаний не покидают устройство без твоего действия. Опасные операции " +
                "агент выполняет только после подтверждения. Режим автономии выбираешь сам.",
        ),
    )
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == pages.lastIndex

    Column(
        Modifier.fillMaxSize().background(P.Bg).statusBarsPadding().navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Пропустить (кроме последней страницы, там кнопка «Начать»)
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
            Spacer(Modifier.weight(1f))
            if (!last) Text(
                "Пропустить", color = P.TextSoft, fontSize = 14.sp,
                modifier = Modifier.clickable { onDone() }.padding(6.dp),
            )
        }

        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { i ->
            val p = pages[i]
            Column(
                Modifier.fillMaxSize().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier.size(96.dp).background(P.AccentSoft, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(p.icon, null, tint = P.Accent, modifier = Modifier.size(44.dp))
                }
                Spacer(Modifier.height(28.dp))
                Text(p.title, color = P.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                Text(
                    p.body, color = P.TextSoft, fontSize = 15.sp,
                    textAlign = TextAlign.Center, lineHeight = 22.sp,
                )
            }
        }

        // Индикаторы страниц
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 22.dp)) {
            repeat(pages.size) { i ->
                Box(
                    Modifier.size(if (i == pager.currentPage) 10.dp else 8.dp)
                        .background(if (i == pager.currentPage) P.Accent else P.Line, CircleShape),
                )
            }
        }

        Box(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).height(52.dp)
                .background(P.Accent, RoundedCornerShape(16.dp))
                .clickable {
                    if (last) onDone()
                    else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (last) "Начать" else "Далее",
                color = P.Bg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private data class OnbPage(val icon: ImageVector, val title: String, val body: String)
