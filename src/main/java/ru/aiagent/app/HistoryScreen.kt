package ru.aiagent.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.aiagent.app.history.CheckpointEngine
import ru.aiagent.app.history.CheckpointStore
import ru.aiagent.app.ui.P
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * История правок ИИ (FEAT-CHECKPOINTS): таймлайн снимков + одно нажатие «Откатить», плюс настройки
 * бюджета/ретенции и очистка. Локальные модели правят workspace — каждый ход снимается автоматически.
 */
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fmt = remember { SimpleDateFormat("dd.MM HH:mm", Locale("ru")) }

    var enabled by remember { mutableStateOf(AppSettings.historyEnabled(context)) }
    var budgetMb by remember { mutableStateOf(AppSettings.historyBudgetMb(context).toFloat()) }
    var retentionD by remember { mutableStateOf(AppSettings.historyRetentionDays(context).toFloat()) }
    var items by remember { mutableStateOf<List<CheckpointEngine.CheckpointMeta>>(emptyList()) }
    var usageMb by remember { mutableStateOf(0.0) }
    var reload by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var confirmId by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) {
        items = CheckpointStore.list(context)
        usageMb = CheckpointStore.usageBytes(context) / 1024.0 / 1024.0
    }

    Column(
        Modifier.fillMaxSize().background(P.Bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = P.Accent, fontSize = 30.sp, modifier = Modifier.clickable { onBack() }.padding(end = 12.dp))
            Text("История правок ИИ", color = P.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Каждый ход ИИ, меняющий файлы в рабочей папке, снимается автоматически. Если ИИ что-то сломал — откати одним нажатием.",
            color = P.TextSoft, fontSize = 12.sp,
        )

        Spacer(Modifier.height(16.dp))
        // Настройки
        Column(Modifier.fillMaxWidth().background(P.Surface, RoundedCornerShape(14.dp)).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Хранить историю правок", color = P.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = enabled,
                    onCheckedChange = { enabled = it; AppSettings.setHistoryEnabled(context, it) },
                    colors = SwitchDefaults.colors(checkedThumbColor = P.Accent, checkedTrackColor = P.Accent.copy(alpha = 0.4f)),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("Бюджет истории: ${budgetMb.toInt()} МБ", color = P.TextSoft, fontSize = 13.sp)
            Slider(
                value = budgetMb, onValueChange = { budgetMb = it },
                onValueChangeFinished = { AppSettings.setHistoryBudgetMb(context, budgetMb.toInt()); reload++ },
                valueRange = 50f..2000f,
                colors = SliderDefaults.colors(thumbColor = P.Accent, activeTrackColor = P.Accent, inactiveTrackColor = P.Line),
            )
            Text("Хранить: ${retentionD.toInt()} дн.", color = P.TextSoft, fontSize = 13.sp)
            Slider(
                value = retentionD, onValueChange = { retentionD = it },
                onValueChangeFinished = { AppSettings.setHistoryRetentionDays(context, retentionD.toInt()); reload++ },
                valueRange = 1f..30f,
                colors = SliderDefaults.colors(thumbColor = P.Accent, activeTrackColor = P.Accent, inactiveTrackColor = P.Line),
            )
            Spacer(Modifier.height(4.dp))
            Text("Занято: %.1f МБ из %d МБ".format(usageMb, budgetMb.toInt()), color = P.TextSoft, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            Row {
                Text(
                    "Сжать историю", color = P.Accent, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable(enabled = !busy) {
                        scope.launch { busy = true; val n = CheckpointStore.compact(context); busy = false; msg = "Пережато blob'ов: $n"; reload++ }
                    },
                )
                Spacer(Modifier.width(24.dp))
                Text(
                    "Очистить историю", color = P.Accent, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable(enabled = !busy) {
                        scope.launch { busy = true; CheckpointStore.clear(context); busy = false; msg = "История очищена"; reload++ }
                    },
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Text("Снимки (${items.size})", color = P.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))

        if (items.isEmpty()) {
            Text("Снимков пока нет — они появятся после того, как ИИ поработает с файлами.", color = P.TextSoft, fontSize = 13.sp)
        }
        items.forEach { cp ->
            Column(
                Modifier.fillMaxWidth().padding(vertical = 5.dp)
                    .background(P.Surface, RoundedCornerShape(12.dp)).padding(12.dp),
            ) {
                Text(cp.reason.ifBlank { "(без описания)" }, color = P.Text, fontSize = 14.sp, maxLines = 2)
                Spacer(Modifier.height(3.dp))
                val diff = buildList {
                    if (cp.added > 0) add("+${cp.added}")
                    if (cp.changed > 0) add("~${cp.changed}")
                    if (cp.removed > 0) add("−${cp.removed}")
                }.joinToString(" ")
                Text(
                    "${fmt.format(Date(cp.ts))} · ${cp.fileCount} файлов" + if (diff.isNotEmpty()) " · $diff" else "",
                    color = P.TextSoft, fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Откатить сюда", color = P.Accent, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable(enabled = !busy) { confirmId = cp.id },
                )
            }
        }

        msg?.let { Spacer(Modifier.height(12.dp)); Text(it, color = P.Text, fontSize = 13.sp) }
        Spacer(Modifier.height(24.dp))
    }

    confirmId?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmId = null },
            title = { Text("Откатить к этому снимку?") },
            text = { Text("Файлы вернутся к состоянию на момент снимка. Появившиеся позже — удалятся. Откат обратим (снимок текущего состояния сохранится).") },
            confirmButton = {
                TextButton(onClick = {
                    confirmId = null
                    scope.launch {
                        busy = true
                        val r = CheckpointStore.restore(context, id)
                        msg = if (r.ok) "Откат выполнен: ${r.message}" else "Ошибка: ${r.message}"
                        busy = false; reload++
                    }
                }) { Text("Откатить", color = P.Accent) }
            },
            dismissButton = { TextButton(onClick = { confirmId = null }) { Text("Отмена", color = P.TextSoft) } },
            containerColor = P.Surface,
        )
    }
}
