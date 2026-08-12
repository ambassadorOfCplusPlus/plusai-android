package ru.aiagent.app.code

import android.content.Context

/**
 * Каталог языковых МОДУЛЕЙ (расширений) исполнения кода. Идея: не всем нужны Lua/Java/TypeScript —
 * лишние раннеры «топят» выбор инструмента у модели, поэтому модуль можно ВЫКЛЮЧИТЬ (тогда его
 * инструменты не выдаются агенту). Тяжёлые/нативные рантаймы НЕ лежат в APK, а качаются паком по
 * требованию (см. [RuntimePackManager]) — как и модели. Часть языков доступна только на десктопе Ф2.
 */
object CodeModules {
    enum class Kind {
        BUNDLED,       // JVM-библиотека уже в APK (Python/JS/Lua/Java/SQL) — доступен всегда
        NATIVE_PACK,   // нативный .so, качается паком (C/picoc)
        ASSET_PACK,    // ассет-пак (напр. компилятор TypeScript) — качается
        TOOLCHAIN_PACK, // компилятор в JVM-байткоде, качается паком и грузится через DexClassLoader
                        // (Kotlin: kotlinc → JVM-байткод → D8 → DEX → DexClassLoader). Это НЕ execve,
                        // поэтому SELinux/W^X на Android 10+ не мешает (в отличие от нативных clang/go).
        LIBRARY_PACK,   // JVM-БИБЛИОТЕКА (напр. BeanShell), вынесенная из APK: dex качается паком и
                        // грузится DexClassLoader-ом ([LibraryPackLoader]), инструмент зовёт её рефлексией.
        DESKTOP_ONLY,  // только десктоп Ф2 (C++/Go/Rust) — нужен execve нативного тулчейна, на телефоне запрещён
    }

    data class Module(
        val id: String,            // для скачиваемых = id пака в RuntimePackManager
        val name: String,
        val kind: Kind,
        val approxMb: Int,         // ориентировочный размер (0 = уже в APK)
        val toolIds: List<String>, // какие инструменты даёт (для настроек/справки)
    )

    val all: List<Module> = listOf(
        Module("python", "Python (pandas, matplotlib)", Kind.BUNDLED, 0, listOf("run_python")),
        Module("javascript", "JavaScript (Rhino)", Kind.BUNDLED, 0, listOf("run_javascript", "repl_eval")),
        Module("typescript", "TypeScript", Kind.ASSET_PACK, 8, listOf("run_typescript")),
        Module("sql", "SQL (SQLite)", Kind.BUNDLED, 0, listOf("run_sql")),
        Module("lua", "Lua (LuaJ)", Kind.BUNDLED, 0, listOf("run_lua")),
        Module("java", "Java (BeanShell)", Kind.LIBRARY_PACK, 1, listOf("run_java")),
        Module("c", "C (picoc)", Kind.NATIVE_PACK, 1, listOf("run_c")),
        // Kotlin реально исполним на телефоне (не десктоп-only): kotlinc→D8→DexClassLoader, без execve.
        // ПРОВЕРЕНО на ART. Пак ~103 МБ (Android-совместимая сборка компилятора + stdlib + D8), качается.
        Module("kotlin", "Kotlin (kotlinc → DEX)", Kind.TOOLCHAIN_PACK, 103, listOf("run_kotlin")),
        // FFmpeg — нативный медиа-движок. Тот же принцип, что у C(picoc): .so грузится через System.load
        // из pack-папки (execve на Android запрещён, а dlopen скачанной .so — нет). Не бандлится в APK.
        Module("ffmpeg", "FFmpeg (конвертация/обрезка аудио-видео)", Kind.NATIVE_PACK, 40, listOf("ffmpeg_run")),
        Module("cpp", "C/C++ (clang) — десктоп Ф2", Kind.DESKTOP_ONLY, 120, emptyList()),
        Module("go", "Go — десктоп Ф2", Kind.DESKTOP_ONLY, 90, emptyList()),
        Module("rust", "Rust — десктоп Ф2", Kind.DESKTOP_ONLY, 150, emptyList()),
    )

    /** По умолчанию ВСЕ включены — пользователь отключает ненужное в настройках. */
    val defaultDisabled: Set<String> = emptySet()

    fun byId(id: String): Module? = all.firstOrNull { it.id == id }

    /** Доступен на устройстве: BUNDLED — всегда; PACK — если скачан; DESKTOP_ONLY — нет на телефоне. */
    fun available(context: Context, id: String): Boolean = when (byId(id)?.kind) {
        Kind.BUNDLED -> true
        Kind.NATIVE_PACK, Kind.ASSET_PACK, Kind.TOOLCHAIN_PACK, Kind.LIBRARY_PACK -> RuntimePackManager.isInstalled(context, id)
        Kind.DESKTOP_ONLY, null -> false
    }

    /** Включён пользователем (не в disabledModules). */
    fun enabled(context: Context, id: String): Boolean =
        id !in ru.aiagent.app.AppSettings.disabledModules(context)

    /** Активен = доступен И включён → его инструменты выдаём агенту. */
    fun active(context: Context, id: String): Boolean = available(context, id) && enabled(context, id)
}
