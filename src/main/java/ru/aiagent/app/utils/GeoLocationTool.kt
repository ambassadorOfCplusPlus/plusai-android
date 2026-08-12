package ru.aiagent.app.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult

/**
 * geolocate — текущее местоположение устройства (последнее известное от GPS/сети). Локально, ничего
 * не уходит на сервер; координаты возвращаются агенту как контекст. Нужно разрешение на геолокацию.
 */
class GeoLocationTool(private val context: Context) : AgentTool {
    override val id = "geolocate"
    override val danger = DangerLevel.IMPORTANT // личные данные — подтверждение (кроме bypass)
    override val alwaysConfirm = true
    override val schema = """определить текущее местоположение устройства (широта/долгота, точность); args: {}"""

    override suspend fun invoke(argsJson: String): ToolResult {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse)
            return ToolResult.Failure("нет разрешения на геолокацию — выдай в системных настройках приложения (Разрешения → Местоположение)")
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return ToolResult.Failure("сервис геолокации недоступен")
        // Берём самое СВЕЖЕЕ последнее-известное среди доступных провайдеров (GPS точнее, сеть быстрее).
        val loc = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> runCatching { if (lm.isProviderEnabled(p)) lm.getLastKnownLocation(p) else null }.getOrNull() }
            .maxByOrNull { it.time }
            ?: return ToolResult.Failure("местоположение пока недоступно — включи геолокацию (GPS) и открой карту, чтобы обновить")
        val ageSec = (System.currentTimeMillis() - loc.time) / 1000
        return ToolResult.Success(
            org.json.JSONObject()
                .put("lat", loc.latitude).put("lon", loc.longitude)
                .put("accuracy_m", loc.accuracy.toInt()).put("provider", loc.provider ?: "?")
                .put("age_sec", ageSec).toString(),
        )
    }
}
