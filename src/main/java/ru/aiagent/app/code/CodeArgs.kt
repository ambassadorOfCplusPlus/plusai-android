package ru.aiagent.app.code

import ru.aiagent.core.agent.tools.jsonField

/** Строковый аргумент из JSON (общий для code-инструментов) — единый [jsonField]. */
internal fun strArg(json: String, field: String): String? = jsonField(json, field)
