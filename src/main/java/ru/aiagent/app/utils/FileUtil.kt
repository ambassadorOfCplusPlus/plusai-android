package ru.aiagent.app.utils

import java.io.File

/**
 * Атомарная запись текста: пишем во ВРЕМЕННЫЙ файл и переименовываем поверх — краш посреди записи не оставит
 * файл обрезанным. tmp-имя УНИКАЛЬНО (nanoTime): безопасно даже без внешнего лока (в отличие от фиксированного
 * `name.tmp`, где два писателя затёрли бы друг друга). Дедупит паттерн из MemoryStore/SkillStore/ConversationStore.
 */
internal fun File.atomicWriteText(text: String) {
    val tmp = File(parentFile, "$name.${System.nanoTime()}.tmp")
    try {
        // Данные ещё и сбрасываем на диск: без fsync переименование может оказаться недолговечным, и
        // после внезапной перезагрузки файл возвращается к старому содержимому либо оказывается пустым.
        java.io.FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray())
            out.flush()
            runCatching { out.fd.sync() }
        }
        if (tmp.renameTo(this)) return
        // rename не удался (SAF/FUSE-эмуляция /storage/emulated/0, файл занят, отказ SELinux на replace).
        // Повтор через второй tmp в ТОМ ЖЕ каталоге бессмысленен — он упрётся в ту же причину, и данные
        // не будут записаны ВООБЩЕ, а вызывающий сочтёт запись успешной (память/навыки тихо перестают
        // сохраняться). Поэтому пишем напрямую: узкое окно усечения при крахе — меньшее зло, чем
        // гарантированная потеря записи.
        writeDirect(text)
    } catch (t: Throwable) {
        runCatching { writeDirect(text) } // лучше записать напрямую, чем потерять данные
    } finally {
        runCatching { tmp.delete() }
    }
}

private fun File.writeDirect(text: String) {
    java.io.FileOutputStream(this).use { out ->
        out.write(text.toByteArray())
        out.flush()
        runCatching { out.fd.sync() }
    }
}
