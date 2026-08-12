package ru.aiagent.app

import android.content.Context
import ru.aiagent.core.agent.AutonomyMode

/**
 * Настройки приложения (S9), персистентно в SharedPreferences. Режим автономии
 * читается агентным циклом (§3.6); озвучка — дефолт для чата.
 */
object AppSettings {
    private const val PREFS = "plusai_settings"
    private const val KEY_MODE = "autonomy_mode"
    private const val KEY_SPEAK = "speak_answers"

    fun mode(context: Context): AutonomyMode {
        val name = prefs(context).getString(KEY_MODE, AutonomyMode.NORMAL.name)
        return runCatching { AutonomyMode.valueOf(name!!) }.getOrDefault(AutonomyMode.NORMAL)
    }

    fun setMode(context: Context, mode: AutonomyMode) =
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()

    fun speakByDefault(context: Context): Boolean = prefs(context).getBoolean(KEY_SPEAK, false)
    fun setSpeakByDefault(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_SPEAK, v).apply()

    // Plan-режим (read-only) — опция, по умолчанию ВЫКЛ: скрыт из выбора автономии, пока не включён.
    // Строгая проверка целостности (анти-репак) при спаривании: по умолчанию ВЫКЛ (несоответствие подписи
    // предупреждает, не блокирует) — включать ПОСЛЕ вписывания релизного cert в native/integrity. Отладчик
    // блокируется независимо от этого флага.
    fun strictIntegrity(context: Context): Boolean = prefs(context).getBoolean("strict_integrity", false)
    fun setStrictIntegrity(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean("strict_integrity", v).apply()

    fun planEnabled(context: Context): Boolean = prefs(context).getBoolean("plan_enabled", false)
    fun setPlanEnabled(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean("plan_enabled", v).apply()

    /** Оформление (редизайн): светлая тема (false — тёмная по умолчанию) и выбранный акцент.
     *  Восстанавливается в MainActivity.onCreate → P.mode / P.accent. */
    fun lightTheme(context: Context): Boolean = prefs(context).getBoolean(KEY_LIGHT_THEME, false)
    fun setLightTheme(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_LIGHT_THEME, v).apply()

    fun accent(context: Context): String = prefs(context).getString(KEY_ACCENT, "CORAL") ?: "CORAL"
    fun setAccent(context: Context, v: String) =
        prefs(context).edit().putString(KEY_ACCENT, v).apply()

    private const val KEY_LIGHT_THEME = "light_theme"
    private const val KEY_ACCENT = "accent_choice"

    /** Режим чата: true — агент с инструментами, false — простой чат (стриминг, без tool-промпта). */
    fun agentMode(context: Context): Boolean = prefs(context).getBoolean(KEY_AGENT, false)
    fun setAgentMode(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_AGENT, v).apply()

    /** Режим «Размышление»: модель рассуждает пошагово перед ответом. */
    fun reasoning(context: Context): Boolean = prefs(context).getBoolean(KEY_REASONING, false)
    fun setReasoning(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_REASONING, v).apply()

    /** Режим «Проект»: постоянные инструкции (роль/контекст), которые идут в системный промпт
     * КАЖДОГО запроса, пока проект активен. Пусто — проект выключен. */
    fun projectInstructions(context: Context): String =
        prefs(context).getString("project_instructions", "") ?: ""
    fun setProjectInstructions(context: Context, v: String) =
        prefs(context).edit().putString("project_instructions", v).apply()

    /**
     * Оркестратор — своя конфигурация моделей: главная (планировщик/критик) + рабочие (исполнители).
     * Пусто → используется выбранный пресет. Рабочие храним через '\n'. Одна рабочая → она выполняет
     * весь план; несколько → шаги плана делятся между ними (см. OrchestratorEngine).
     */
    fun orchPlanner(context: Context): String? =
        prefs(context).getString("orch_planner", null)?.takeIf { it.isNotBlank() }
    fun orchExecutors(context: Context): List<String> =
        (prefs(context).getString("orch_executors", "") ?: "").split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    fun setOrchCustom(context: Context, planner: String?, executors: List<String>) =
        prefs(context).edit()
            .putString("orch_planner", planner?.takeIf { it.isNotBlank() })
            .putString("orch_executors", executors.filter { it.isNotBlank() }.joinToString("\n"))
            .apply()
    fun clearOrchCustom(context: Context) =
        prefs(context).edit().remove("orch_planner").remove("orch_executors").apply()

    /**
     * Свой сэмплинг вместо рекомендованного для модели. Выкл (дефолт) → сервер подставляет
     * рекомендованные для модели значения из каталога. Вкл → шлём [temperature] пользователя
     * (она выигрывает над рекомендованной). Диапазон 0.0–2.0.
     */
    fun customSampling(context: Context): Boolean = prefs(context).getBoolean(KEY_SAMPLING_ON, false)
    fun setCustomSampling(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_SAMPLING_ON, v).apply()

    fun temperature(context: Context): Float = prefs(context).getFloat(KEY_TEMPERATURE, 0.7f)
    fun setTemperature(context: Context, v: Float) =
        prefs(context).edit().putFloat(KEY_TEMPERATURE, v.coerceIn(0f, 2f)).apply()

    /** Разрешённый override температуры для запросов: null, если свой сэмплинг выключен (тогда сервер
     * подставит рекомендованную для модели). Единая точка правды для обеих облачных веток. */
    fun temperatureOverride(context: Context): Double? =
        if (customSampling(context)) temperature(context).toDouble() else null

    /** Автооткат правок ИИ (FEAT-CHECKPOINTS): вкл/выкл, бюджет истории (МБ), ретенция (дни). */
    fun historyEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_HISTORY_ON, true)
    fun setHistoryEnabled(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_HISTORY_ON, v).apply()

    fun historyBudgetMb(context: Context): Int = prefs(context).getInt(KEY_HISTORY_MB, 200)
    fun setHistoryBudgetMb(context: Context, v: Int) =
        prefs(context).edit().putInt(KEY_HISTORY_MB, v).apply()

    fun historyRetentionDays(context: Context): Int = prefs(context).getInt(KEY_HISTORY_DAYS, 7)
    fun setHistoryRetentionDays(context: Context, v: Int) =
        prefs(context).edit().putInt(KEY_HISTORY_DAYS, v).apply()

    /**
     * Удалённое управление ЭТИМ телефоном с ПК (REMOTE-XDEV): телефон становится хостом —
     * анонсит присутствие в E2E-релэй и исполняет команды агентом на локальной модели.
     * ВСЕГДА включено (без пользовательского тумблера): реальный приём команд всё равно требует
     * входа в аккаунт (RemoteAgentService стартует лишь при Account.current != null) и E2E-шифрования,
     * опасные операции отклоняются. Тумблер убран из настроек — не захламляет UI Фазы 1.
     */
    fun remoteHostEnabled(context: Context): Boolean = true
    fun setRemoteHostEnabled(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_REMOTE_HOST, v).apply()

    /** Бэкенд на ПК: ИИ-запросы уходят на десктоп через E2E-релэй вместо облака/локальной модели. */
    fun remoteBackendEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REMOTE_BACKEND, false)
    fun setRemoteBackendEnabled(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_REMOTE_BACKEND, v).apply()

    /**
     * Генерация картинок (generate_image через image-модели RouterAI по прокси владельца). Функция
     * ПЛАТНАЯ (тот же кошелёк) → по умолчанию ВЫКЛючена: пользователь сам включает и выбирает модель.
     */
    fun imageGenEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_IMAGE_GEN, false)
    fun setImageGenEnabled(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_IMAGE_GEN, v).apply()

    /** Выбранная image-модель для generate_image (id из каталога image-моделей). */
    fun imageModel(context: Context): String =
        prefs(context).getString(KEY_IMAGE_MODEL, "google/gemini-2.5-flash-image") ?: "google/gemini-2.5-flash-image"
    fun setImageModel(context: Context, id: String) =
        prefs(context).edit().putString(KEY_IMAGE_MODEL, id).apply()

    private const val KEY_IMAGE_GEN = "image_gen_enabled"
    private const val KEY_IMAGE_MODEL = "image_gen_model"

    /** Онбординг показан (первый запуск). */
    fun onboardingDone(context: Context): Boolean = prefs(context).getBoolean(KEY_ONBOARDING, false)
    fun setOnboardingDone(context: Context) =
        prefs(context).edit().putBoolean(KEY_ONBOARDING, true).apply()

    /** Последняя выбранная модель — чтобы не сбрасывалась на дефолт при перезаходе. */
    fun lastLocalModel(context: Context): String? = prefs(context).getString(KEY_MODEL_FILE, null)
    fun lastCloudModel(context: Context): String? = prefs(context).getString(KEY_CLOUD_MODEL, null)
    fun lastModelLabel(context: Context): String? = prefs(context).getString(KEY_MODEL_LABEL, null)
    fun saveModel(context: Context, localFile: String?, cloudId: String?, label: String) =
        prefs(context).edit()
            .putString(KEY_MODEL_FILE, localFile).putString(KEY_CLOUD_MODEL, cloudId)
            .putString(KEY_MODEL_LABEL, label).apply()

    private const val KEY_AGENT = "agent_mode"
    private const val KEY_REASONING = "reasoning_mode"
    private const val KEY_SAMPLING_ON = "custom_sampling"
    private const val KEY_TEMPERATURE = "sampling_temperature"
    private const val KEY_ONBOARDING = "onboarding_done"
    private const val KEY_MODEL_FILE = "last_model_file"
    private const val KEY_CLOUD_MODEL = "last_cloud_model"
    private const val KEY_MODEL_LABEL = "last_model_label"
    private const val KEY_DISABLED_TOOLS = "disabled_tools"
    private const val KEY_DISABLED_MODULES = "disabled_modules"
    private const val KEY_FILE_ACCESS = "file_access_enabled"
    private const val KEY_OUTSIDE_SANDBOX = "outside_sandbox_enabled"
    private const val KEY_HISTORY_ON = "history_enabled"
    private const val KEY_HISTORY_MB = "history_budget_mb"
    private const val KEY_HISTORY_DAYS = "history_retention_days"

    /** Отключённые пользователем инструменты (id). Кому не нужны — выключает в Настройках. */
    fun disabledTools(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_DISABLED_TOOLS, emptySet()) ?: emptySet()

    fun setToolEnabled(context: Context, id: String, enabled: Boolean) {
        val cur = disabledTools(context).toMutableSet()
        if (enabled) cur.remove(id) else cur.add(id)
        prefs(context).edit().putStringSet(KEY_DISABLED_TOOLS, cur).apply()
    }

    /** Отключённые языковые МОДУЛИ (id, напр. "lua"/"java"): их раннеры не выдаются агенту. Кому не
     *  нужны Lua/Java/… — выключает модуль в Настройках (меньше инструментов = точнее выбор у модели). */
    fun disabledModules(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_DISABLED_MODULES, null) ?: ru.aiagent.app.code.CodeModules.defaultDisabled

    fun setModuleEnabled(context: Context, id: String, enabled: Boolean) {
        val cur = disabledModules(context).toMutableSet()
        if (enabled) cur.remove(id) else cur.add(id)
        prefs(context).edit().putStringSet(KEY_DISABLED_MODULES, cur).apply()
    }

    /** ЗАПРЕЩЁННЫЕ пользователем инструменты (id) — жёсткий BLOCK в агенте, даже в bypass. */
    fun blockedTools(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_BLOCKED_TOOLS, emptySet()) ?: emptySet()

    fun setToolBlocked(context: Context, id: String, blocked: Boolean) {
        val cur = blockedTools(context).toMutableSet()
        if (blocked) cur.add(id) else cur.remove(id)
        prefs(context).edit().putStringSet(KEY_BLOCKED_TOOLS, cur).apply()
    }

    private const val KEY_BLOCKED_TOOLS = "blocked_tools"

    /**
     * Возможности расширения по id: тумблеры «Камера / Микрофон-аудио / Интерактив / Сеть».
     * Пользователь ставит расширение («плюсик») и явно ВКЛЮЧАЕТ ему нужные возможности. По умолчанию
     * всё выключено — сторонний код не получает доступ к камере/микрофону/интерактиву/сети, пока
     * пользователь сам не разрешит. cap ∈ {"camera","audio","interactive","network"}.
     * Ключ: "ext_cap_<extId>_<cap>".
     */
    fun extCapEnabled(context: Context, extId: String, cap: String): Boolean =
        prefs(context).getBoolean("ext_cap_${extId}_$cap", false)

    fun setExtCap(context: Context, extId: String, cap: String, v: Boolean) =
        prefs(context).edit().putBoolean("ext_cap_${extId}_$cap", v).apply()

    /** Доступ агента к файлам (глобальный тумблер). По умолчанию включён. */
    fun fileAccessEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_FILE_ACCESS, true)
    fun setFileAccessEnabled(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_FILE_ACCESS, v).apply()

    /** Режим «вне песочницы»: агент работает со всей файловой системой (не только рабочая папка/выбранные
     *  папки). Действует ТОЛЬКО вместе с режимом автономии bypass. По умолчанию ВЫКЛючен (опасно). */
    fun outsideSandboxEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_OUTSIDE_SANDBOX, false)
    fun setOutsideSandboxEnabled(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_OUTSIDE_SANDBOX, v).apply()

    /**
     * Как облачная модель работает с RAG (базой знаний из твоих документов). §7-выбор владельца:
     *  - "hybrid" (по умолчанию): облако НЕ видит сырые документы. Локальная модель читает найденные
     *    фрагменты и синтезирует ответ — наружу уходит только он. Нужна установленная локальная модель.
     *  - "direct": облачная модель получает сами найденные фрагменты (до 4×600 симв. + имена файлов).
     *  - "local": RAG доступен только локальной модели (строгий §7, облаку недоступен).
     */
    fun ragCloudMode(context: Context): String =
        prefs(context).getString("rag_cloud_mode", "hybrid") ?: "hybrid"
    fun setRagCloudMode(context: Context, mode: String) =
        prefs(context).edit().putString("rag_cloud_mode", mode).apply()

    /**
     * Как облачная модель работает с ПОЧТОЙ (§7-выбор владельца, как у RAG):
     *  - "local" (строгий §7): почтовые инструменты облаку недоступны (письма не уходят провайдеру).
     *  - "hybrid" (по умолчанию): облаку доступны, но возвращают НЕ сырое письмо, а ответ локальной
     *    модели по нему (тело письма остаётся на устройстве).
     *  - "direct": облачная модель получает сами письма (тело уходит провайдеру).
     */
    fun mailCloudMode(context: Context): String =
        prefs(context).getString("mail_cloud_mode", "hybrid") ?: "hybrid"
    fun setMailCloudMode(context: Context, mode: String) =
        prefs(context).edit().putString("mail_cloud_mode", mode).apply()

    /**
     * Как облачная модель работает с Яндекс.Диском (§7-выбор владельца, как у файлов/почты):
     *  - "local": диск облаку недоступен. "hybrid" (по умолч.): облако видит список файлов и грузит,
     *    а содержимое файла читает локальная модель (облаку — ответ, не сырьё). "direct": файлы уходят облаку.
     */
    fun diskCloudMode(context: Context): String =
        prefs(context).getString("disk_cloud_mode", "hybrid") ?: "hybrid"
    fun setDiskCloudMode(context: Context, mode: String) =
        prefs(context).edit().putString("disk_cloud_mode", mode).apply()

    /** Как облачная модель работает с КАЛЕНДАРЁМ (§7, как у почты). local/hybrid(дефолт)/direct. */
    fun calendarCloudMode(context: Context): String =
        prefs(context).getString("calendar_cloud_mode", "hybrid") ?: "hybrid"
    fun setCalendarCloudMode(context: Context, mode: String) =
        prefs(context).edit().putString("calendar_cloud_mode", mode).apply()

    /** Доступ облачной модели к ЭКРАНУ телефона (read_screen). local (дефолт — облаку экран не виден) /
     *  direct (облако видит содержимое экрана). Действия (tap/swipe) отдельно — они не §7. */
    fun screenCloudMode(context: Context): String =
        prefs(context).getString("screen_cloud_mode", "local") ?: "local"
    fun setScreenCloudMode(context: Context, mode: String) =
        prefs(context).edit().putString("screen_cloud_mode", mode).apply()

    /** Фоновая индексация вложений почты (OCR + зашифрованный локальный индекс). По умолчанию ВЫКЛ. */
    fun emailIndexEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_MAIL_INDEX, false)
    fun setEmailIndexEnabled(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_MAIL_INDEX, v).apply()

    private const val KEY_MAIL_INDEX = "email_index_enabled"

    /** BYOK-Pro разблокирован (разовая покупка): свой ключ провайдера напрямую, без комиссии. */
    fun byokProUnlocked(context: Context): Boolean = prefs(context).getBoolean(KEY_BYOK_PRO, false)
    fun setByokProUnlocked(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_BYOK_PRO, v).apply()

    private const val KEY_BYOK_PRO = "byok_pro_unlocked"
    private const val KEY_REMOTE_HOST = "remote_host_enabled"
    private const val KEY_REMOTE_BACKEND = "remote_backend_enabled"

    /** Обучающие подсказки: показана ли подсказка [id] (показываем один раз при первом включении). */
    fun hintShown(context: Context, id: String): Boolean = prefs(context).getStringSet(KEY_HINTS, emptySet())!!.contains(id)
    fun markHint(context: Context, id: String) {
        val s = prefs(context).getStringSet(KEY_HINTS, emptySet())!!.toMutableSet(); s.add(id)
        prefs(context).edit().putStringSet(KEY_HINTS, s).apply()
    }

    /**
     * Разрешённые агенту папки (гранулярный доступ вместо «все файлы»). Пусто → всё хранилище
     * (если выдан системный доступ). Непусто → агент видит ТОЛЬКО эти папки (+ рабочую).
     */
    fun allowedFolders(context: Context): Set<String> = prefs(context).getStringSet(KEY_ALLOWED_DIRS, emptySet())!!
    fun addAllowedFolder(context: Context, path: String) {
        val s = allowedFolders(context).toMutableSet(); s.add(path)
        prefs(context).edit().putStringSet(KEY_ALLOWED_DIRS, s).apply()
    }
    fun removeAllowedFolder(context: Context, path: String) {
        val s = allowedFolders(context).toMutableSet(); s.remove(path)
        prefs(context).edit().putStringSet(KEY_ALLOWED_DIRS, s).apply()
    }

    private const val KEY_HINTS = "hints_shown"
    private const val KEY_ALLOWED_DIRS = "allowed_folders"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
