package ru.aiagent.app.integrations

/**
 * ТОНКИЙ интерфейс git-операций. Реализация ([GitBackendImpl], см. `packs/jgit/GitBackendImpl.kt`)
 * использует org.eclipse.jgit НАПРЯМУЮ и живёт в СКАЧИВАЕМОМ паке `jgit` (d8 от jgit.jar + рантайм-депы
 * + сам impl), поэтому ~3 МБ JGit НЕ лежат в APK. Здесь, в :app, — только интерфейс: все
 * аргументы/возврат это примитивы/String/JSON, НИ ОДНОГО типа JGit, иначе интерфейс притянул бы
 * org.eclipse.jgit обратно в APK.
 *
 * Этот файл НАМЕРЕННО без android-импортов (загрузчик пака вынесен в [GitBackends]) — тогда сборка пака
 * компилирует [GitBackendImpl] ровно против него (`packs/jgit/build.sh`), без Android/LibraryPackLoader.
 *
 * Почему не «оставить `import org.eclipse.jgit…` в [GitTools] и грузить JGit из пака» (вариант
 * compileOnly со статическими импортами): статические ссылки на классы JGit резолвятся ОПРЕДЕЛЯЮЩИМ
 * загрузчиком класса GitTools = загрузчиком приложения (PathClassLoader). Делегирование идёт
 * ребёнок→родитель, а НЕ наоборот, поэтому загрузчик приложения НЕ видит классы из DexClassLoader-а
 * пака → первый же вызов Git.cloneRepository() бросил бы NoClassDefFoundError. Thread.contextClassLoader
 * это не лечит (JVM берёт определяющий загрузчик, не контекстный). Рабочий приём — вынести ВСЕ прямые
 * обращения к JGit в [GitBackendImpl], который сам грузится DexClassLoader-ом пака (у него JGit в
 * classpath) и виден приложению лишь через этот интерфейс (его-то загружает родитель = приложение).
 *
 * Все методы бросают исключение при ошибке — вызывающий [GitTools] ловит Throwable и отдаёт Failure.
 * `dirPath` — абсолютный путь рабочей папки (её резолвит sandbox в [GitTools]).
 */
interface GitBackend {
    /** git clone url → dirPath. login/token (может быть null) — для приватных репозиториев по HTTPS. */
    fun clone(url: String, dirPath: String, login: String?, token: String?)

    /** JSON {"modified":[…],"added":[…],"untracked":[…],"removed":[…],"branch":"…"} */
    fun status(dirPath: String): String

    /** add -A + commit; вернуть короткий хэш (10 симв.). authorName — имя автора. */
    fun commit(dirPath: String, message: String, authorName: String): String

    fun push(dirPath: String, login: String?, token: String?)

    /** JSON {"pulled":bool,"status":"…"} */
    fun pull(dirPath: String, login: String?, token: String?): String

    /** JSON-массив [{"hash","author","date","message"}], не более limit записей. */
    fun log(dirPath: String, limit: Int): String

    /** Рабочий diff как текст (вызывающий сам обрежет длину). */
    fun diff(dirPath: String): String

    /** JSON {"current":"…","branches":[…]} */
    fun branchList(dirPath: String): String

    fun branchCreate(dirPath: String, name: String)

    fun checkout(dirPath: String, branch: String, create: Boolean)
}
