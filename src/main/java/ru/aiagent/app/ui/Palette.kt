package ru.aiagent.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Единая точка правды по цвету для всех экранов (редизайн Plus AI).
 *
 * Тёплая тёмная тема по умолчанию + светлая на выбор + переключаемый акцент.
 * DROP-IN: имена P.Bg / P.Surface / P.Text / P.TextSoft / P.Accent / P.AccentSoft / P.Line
 * сохранены — существующие экраны править не нужно. Добавлены P.Card2 / P.Faint / P.Ok.
 *
 * Свойства реактивны: они читают backing-state (P.mode / P.accent) внутри get(), поэтому
 * смена темы/акцента автоматически перекомпоновывает всё, что читает P.*.
 * Сохраняйте выбор в AppSettings и восстанавливайте в MainActivity.onCreate:
 *     P.mode = if (AppSettings.lightTheme(ctx)) ThemeMode.LIGHT else ThemeMode.DARK
 *     P.accent = AccentChoice.valueOf(AppSettings.accent(ctx))
 */
enum class ThemeMode { DARK, LIGHT }

enum class AccentChoice(val dark: Color, val light: Color) {
    CORAL(Color(0xFFE07A54), Color(0xFFC6613E)),
    AMBER(Color(0xFFE0A24E), Color(0xFFB3812F)),
    INDIGO(Color(0xFF8F90F2), Color(0xFF5B5BD6)),
    TEAL(Color(0xFF4FC0A8), Color(0xFF1F9E86)),
}

object P {
    var mode by mutableStateOf(ThemeMode.DARK)
    var accent by mutableStateOf(AccentChoice.CORAL)

    private val dark get() = mode == ThemeMode.DARK

    // Нейтрали
    val Bg: Color get() = if (dark) Color(0xFF17150F) else Color(0xFFF6F3EC)
    val Surface: Color get() = if (dark) Color(0xFF1F1C15) else Color(0xFFFFFFFF)
    val SurfaceSoft: Color get() = if (dark) Color(0xFF26221A) else Color(0xFFF0ECE2)
    val Card: Color get() = if (dark) Color(0xFF26221A) else Color(0xFFFFFFFF)
    val Card2: Color get() = if (dark) Color(0xFF302A20) else Color(0xFFF0ECE2)
    val Line: Color get() = if (dark) Color(0xFF34302A) else Color(0xFFE6E0D3)
    val Text: Color get() = if (dark) Color(0xFFEFECE4) else Color(0xFF221F19)
    val TextSoft: Color get() = if (dark) Color(0xFFA49C8D) else Color(0xFF6D675C)
    val Faint: Color get() = if (dark) Color(0xFF756E60) else Color(0xFF9C9384)
    val Ok: Color get() = if (dark) Color(0xFF6FBF87) else Color(0xFF3A9D5C)

    // Акцент
    val Accent: Color get() = if (dark) accent.dark else accent.light
    val AccentSoft: Color get() = Accent.copy(alpha = 0.15f)
}
