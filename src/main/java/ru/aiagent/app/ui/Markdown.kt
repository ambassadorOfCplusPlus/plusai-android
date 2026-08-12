package ru.aiagent.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Лёгкий рендер Markdown под то, что реально шлют модели (особенно DeepSeek): заголовки #/##/###,
 * **жирный**, *курсив*, `код`, ```блоки```, списки (-,*,+,1.), цитаты >, --- разделитель.
 * Не полноценный CommonMark — цель: чтобы спецсимволы не торчали в тексте, а форматирование читалось.
 * Терпим к незакрытым маркерам (стриминг): недобитый ** просто печатается как есть.
 */
@Composable
fun MarkdownText(
    text: String,
    color: Color,
    muted: Color,
    codeBg: Color,
    accent: Color,
    fontSize: TextUnit = 15.sp,
    lineHeight: TextUnit = 22.sp,
) {
    val blocks = remember(text) { parseBlocks(text) }
    Column {
        for (b in blocks) {
            when (b) {
                is MdBlock.Code -> Row(
                    Modifier.padding(vertical = 3.dp)
                        .background(codeBg, RoundedCornerShape(8.dp))
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(b.text, color = color, fontSize = (fontSize.value - 1).sp,
                        fontFamily = FontFamily.Monospace, lineHeight = (fontSize.value + 5).sp)
                }
                is MdBlock.Heading -> Text(
                    inline(b.text, color, codeBg),
                    color = color,
                    fontSize = (fontSize.value + (7 - b.level * 2).coerceAtLeast(1)).sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = (fontSize.value + 9).sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                )
                is MdBlock.Rule -> Text("──────────", color = muted, fontSize = fontSize,
                    modifier = Modifier.padding(vertical = 2.dp))
                is MdBlock.Bullet -> Row(Modifier.padding(start = (b.indent * 14).dp, top = 1.dp, bottom = 1.dp)) {
                    Text(if (b.ordered) "${b.num}. " else "•  ", color = accent, fontSize = fontSize, lineHeight = lineHeight)
                    Text(inline(b.text, color, codeBg), color = color, fontSize = fontSize, lineHeight = lineHeight)
                }
                is MdBlock.Quote -> Row(Modifier.padding(vertical = 1.dp)) {
                    Text("▍ ", color = muted, fontSize = fontSize, lineHeight = lineHeight)
                    Text(inline(b.text, muted, codeBg), color = muted, fontSize = fontSize,
                        fontStyle = FontStyle.Italic, lineHeight = lineHeight)
                }
                is MdBlock.Table -> {
                    // Ширина колонки — по самому длинному значению в ней (адаптивно), с мин/макс.
                    // Вся таблица в горизонтальном скролле: широкие таблицы не сжимаются в кашу.
                    val colW = (0 until b.headers.size).map { c ->
                        var mx = b.headers.getOrElse(c) { "" }.length
                        for (r in b.rows) mx = maxOf(mx, r.getOrElse(c) { "" }.length)
                        (mx.coerceIn(3, 26) * 8).dp
                    }
                    val cellFs = (fontSize.value - 1).sp
                    val cellLh = (fontSize.value + 3).sp
                    Column(
                        Modifier.padding(vertical = 4.dp)
                            .horizontalScroll(rememberScrollState())
                            .background(codeBg.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                            .padding(4.dp),
                    ) {
                        Row {
                            for (c in b.headers.indices) Text(
                                inline(b.headers[c], color, codeBg), color = color,
                                fontSize = cellFs, fontWeight = FontWeight.Bold, lineHeight = cellLh,
                                modifier = Modifier.width(colW[c]).padding(horizontal = 6.dp, vertical = 4.dp),
                            )
                        }
                        for (r in b.rows) Row(Modifier.padding(top = 1.dp)) {
                            for (c in b.headers.indices) Text(
                                inline(r.getOrElse(c) { "" }, color, codeBg), color = color,
                                fontSize = cellFs, lineHeight = cellLh,
                                modifier = Modifier.width(colW[c]).padding(horizontal = 6.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
                is MdBlock.Para -> if (b.text.isNotBlank()) Text(
                    inline(b.text, color, codeBg), color = color, fontSize = fontSize, lineHeight = lineHeight,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
    }
}

private sealed interface MdBlock {
    data class Para(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String, val ordered: Boolean, val num: Int, val indent: Int) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

/** Строка-разделитель таблицы: |---|:--:|---| (только |, -, :, пробелы, минимум один -). */
private fun isTableSep(line: String?): Boolean {
    val s = line?.trim() ?: return false
    return s.contains('-') && s.contains('|') && s.all { it == '|' || it == '-' || it == ':' || it == ' ' }
}

private fun splitRow(line: String): List<String> =
    line.trim().trim('|').split('|').map { it.trim() }

private fun parseBlocks(src: String): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    val lines = src.replace("\r\n", "\n").split("\n")
    var i = 0
    val para = StringBuilder()
    fun flushPara() {
        if (para.isNotBlank()) out.add(MdBlock.Para(para.toString().trim()))
        para.setLength(0)
    }
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trim()
        when {
            t.startsWith("```") -> { // код-блок до закрывающего ```
                flushPara()
                val sb = StringBuilder(); i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) { sb.appendLine(lines[i]); i++ }
                out.add(MdBlock.Code(sb.toString().trimEnd('\n')))
            }
            Regex("^#{1,6}\\s+").containsMatchIn(t) -> {
                flushPara()
                val level = t.takeWhile { it == '#' }.length
                out.add(MdBlock.Heading(level, t.drop(level).trim()))
            }
            t.startsWith("|") && isTableSep(lines.getOrNull(i + 1)) -> { // markdown-таблица
                flushPara()
                val headers = splitRow(t)
                var j = i + 2 // пропускаем заголовок + строку-разделитель
                val rows = ArrayList<List<String>>()
                while (j < lines.size && lines[j].trim().startsWith("|")) { rows.add(splitRow(lines[j])); j++ }
                out.add(MdBlock.Table(headers, rows))
                i = j - 1 // внешний i++ переставит на j
            }
            t == "---" || t == "***" || t == "___" -> { flushPara(); out.add(MdBlock.Rule) }
            t.startsWith("> ") -> { flushPara(); out.add(MdBlock.Quote(t.removePrefix("> ").trim())) }
            Regex("^[-*+]\\s+").containsMatchIn(t) -> {
                flushPara()
                val indent = (line.takeWhile { it == ' ' }.length) / 2
                out.add(MdBlock.Bullet(t.replaceFirst(Regex("^[-*+]\\s+"), ""), ordered = false, num = 0, indent = indent))
            }
            Regex("^\\d+[.)]\\s+").containsMatchIn(t) -> {
                flushPara()
                val indent = (line.takeWhile { it == ' ' }.length) / 2
                val num = t.takeWhile { it.isDigit() }.toIntOrNull() ?: 1
                out.add(MdBlock.Bullet(t.replaceFirst(Regex("^\\d+[.)]\\s+"), ""), ordered = true, num = num, indent = indent))
            }
            t.isEmpty() -> flushPara()
            else -> { if (para.isNotEmpty()) para.append(' '); para.append(t) }
        }
        i++
    }
    flushPara()
    return out
}

// Инлайн-разметка: **жирный**, *курсив* или _курсив_, `код`. Терпима к незакрытым маркерам (стриминг).
private fun inline(s: String, color: Color, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("**", i) || s.startsWith("__", i) -> {
                val marker = s.substring(i, i + 2)
                val end = s.indexOf(marker, i + 2)
                if (end < 0) { append(s.substring(i)); i = s.length }
                else { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(i + 2, end)) }; i = end + 2 }
            }
            (s[i] == '*' || s[i] == '_') -> {
                val marker = s[i]
                val end = s.indexOf(marker, i + 1)
                if (end < 0) { append(s.substring(i)); i = s.length }
                else { withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.substring(i + 1, end)) }; i = end + 1 }
            }
            s[i] == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end < 0) { append(s.substring(i)); i = s.length }
                else {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(s.substring(i + 1, end)) }
                    i = end + 1
                }
            }
            else -> { append(s[i]); i++ }
        }
    }
}
