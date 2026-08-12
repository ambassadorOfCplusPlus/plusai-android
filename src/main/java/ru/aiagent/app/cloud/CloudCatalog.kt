package ru.aiagent.app.cloud

/**
 * Каталог облачных моделей RouterAI (routerai.ru). Цены — вход/выход ₽ за 1M токенов
 * (на момент интеграции; сервер владельца может отдавать актуальные). Дефолт — Flash
 * (самая дешёвая: 9/18 ₽). Все — через провайдера DEEPSEEK-клиента (OpenAI-совместимый).
 */
data class CloudModel(val id: String, val name: String, val price: String, val note: String)

val ROUTERAI_MODELS = listOf(
    CloudModel("deepseek/deepseek-v4-flash", "DeepSeek V4 Flash (free)", "0/0 ₽", "бесплатная · быстрая"),
    CloudModel("qwen/qwen3-next-80b-a3b-thinking", "Qwen3 Next (free)", "0/0 ₽", "бесплатная · думающая"),
)

val DEFAULT_CLOUD_MODEL = ROUTERAI_MODELS.first().id
