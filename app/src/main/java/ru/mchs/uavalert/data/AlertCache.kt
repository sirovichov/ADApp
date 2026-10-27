package ru.mchs.uavalert.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Простое локальное хранилище последних полученных предупреждений (SharedPreferences + JSON).
 * Позволяет показать данные даже при недоступности сети или сайта МЧС.
 */
class AlertCache(context: Context) {

    private val prefs = context.getSharedPreferences("alert_cache", Context.MODE_PRIVATE)

    fun save(alerts: List<UavAlert>) {
        val arr = JSONArray()
        alerts.forEach { a ->
            arr.put(JSONObject().apply {
                put("id", a.id)
                put("title", a.title)
                put("region", a.region)
                put("text", a.text)
                put("timestamp", a.timestamp)
                put("url", a.url)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun load(): List<UavAlert> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                UavAlert(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    region = o.optString("region"),
                    text = o.optString("text"),
                    timestamp = o.optLong("timestamp"),
                    url = o.optString("url")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private companion object {
        const val KEY = "cached_alerts"
    }
}
