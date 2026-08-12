package ru.aiagent.app.integrations

/**
 * ТОНКИЙ интерфейс SSH. Реализация ([SshBackendImpl], см. `packs/ssh/SshBackendImpl.kt`) использует
 * JSch НАПРЯМУЮ и живёт в СКАЧИВАЕМОМ паке `ssh` (~280 КБ вне APK) — как JGit (см. [GitBackend]).
 * Здесь, в :app, — только интерфейс: аргументы/возврат это примитивы/String/JSON, НИ ОДНОГО типа JSch,
 * иначе интерфейс притянул бы либу обратно в APK. Файл намеренно без android-импортов (загрузчик пака —
 * в [SshBackends]), чтобы сборка пака компилировала impl ровно против него (`packs/ssh/build.sh`).
 */
interface SshBackend {
    /**
     * Выполнить команду по SSH и вернуть результат.
     * [authJson] — JSON с КРЕДАМИ (резолвятся на устройстве из [ru.aiagent.app.SshStore], в модель не
     * попадают): {"password":"…"} ИЛИ {"privateKeyPem":"…","passphrase":"…"?}.
     * Возврат — JSON {"stdout":"…","stderr":"…","exit":N}. Бросает исключение при ошибке соединения/аутентификации.
     */
    fun run(host: String, port: Int, user: String, authJson: String, command: String, timeoutMs: Int): String
}
