package ru.mchs.uavalert.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Репозиторий, который берёт данные об оперативной обстановке с официального сайта МЧС России
 * (mchs.gov.ru) и фильтрует сообщения, связанные с угрозой/атакой БПЛА.
 *
 * Источник данных: публичный JSON-API портала МЧС, отдающий ленту новостей
 * региональных управлений. Если структура ответа изменится, парсер безопасно
 * вернёт пустой список вместо падения приложения.
 */
class MchsRepository {

    companion object {
        // Основная лента сводок МЧС (оперативные уведомления об угрозе БПЛА
        // публикуются в разделе «Информирование и оповещение населения»)
        private const val API_URL =
            "https://www.mchs.gov.ru/api/news/list?itemsPerPage=50&sortBy=publishDate&sortDir=desc"

        // Ключевые слова, по которым определяем сообщение об атаке/угрозе БПЛА
        private val UAV_KEYWORDS = listOf(
            "бпла", "бплат", "дрон", "квадрокоптер", "беспилотн",
            "уаф", "unmanned", "воронка", "сбит", "обнаружен"
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Загружает последние предупреждения об угрозе БПЛА с сайта МЧС.
     * @return список алертов, отсортированный по времени (новые сверху)
     */
    suspend fun fetchUavAlerts(): List<UavAlert> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(API_URL)
            .header("User-Agent", "MchsUavAlert/1.0 (Android)")
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw java.io.IOException("Неожиданный ответ сервера МЧС: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw java.io.IOException("Пустой ответ сервера")
            parseAlerts(body)
        }
    }

    /**
     * Парсит JSON-ответ API МЧС и оставляет только записи про БПЛА.
     * Метод вынесен отдельно и не бросает исключений при нестандартных полях —
     * это защищает приложение при изменениях вёрстки на стороне сайта.
     */
    private fun parseAlerts(json: String): List<UavAlert> {
        val result = mutableListOf<UavAlert>()
        try {
            val root = JSONObject(json)
            val items = root.optJSONArray("items") ?: root.optJSONArray("rows") ?: return emptyList()

            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val title = unescapeHtml(item.optString("title").ifBlank { item.optString("name") })
                val text = unescapeHtml(item.optString("announceText").ifBlank { item.optString("text") })
                val region = extractRegion(item)
                val url = item.optString("url").ifBlank { item.optString("link") }
                val dateStr = item.optString("publishDate").ifBlank { item.optString("date") }
                val ts = parseDate(dateStr)

                val fullText = (title + " " + text).lowercase(Locale.ROOT)
                val isUavAlert = UAV_KEYWORDS.any { fullText.contains(it) }

                if (isUavAlert && title.isNotBlank()) {
                    result += UavAlert(
                        id = url.ifBlank { "$title|$ts" },
                        title = title,
                        region = region.ifBlank { inferRegionFromText(fullText) },
                        text = text.ifBlank { title },
                        timestamp = ts,
                        url = url
                    )
                }
            }
        } catch (_: Exception) {
            // Неизвестный формат ответа — возвращаем то, что успели разобрать
        }
        return result.sortedByDescending { it.timestamp }
    }

    private fun extractRegion(item: JSONObject): String {
        // Регион может лежать в поле subdivision / tags / category
        item.optString("subdivisionName").let { if (it.isNotBlank()) return unescapeHtml(it) }
        val tags = item.optJSONArray("tags")
        if (tags != null) {
            for (j in 0 until tags.length()) {
                val tag = tags.optString(j)
                if (tag.contains("область", true) || tag.contains("край", true) ||
                    tag.contains("республика", true) || tag.contains("город", true)
                ) return unescapeHtml(tag)
            }
        }
        return ""
    }

    /** Пытаемся определить регион по тексту, если тег отсутствует. */
    private fun inferRegionFromText(lowerText: String): String {
        val regions = listOf(
            "москва", "московская область", "краснодарский край", "ростовская область",
            "белгородская область", "брянская область", "воронежская область",
            "курская область", "орловская область", "крым", "севастополь",
            "санкт-петербург", "тульская область", "калужская область"
        )
        return regions.firstOrNull { lowerText.contains(it) }
            ?.replaceFirstChar { c -> c.uppercase(Locale.ROOT) } ?: ""
    }

    private fun parseDate(value: String): Long {
        if (value.isBlank()) return System.currentTimeMillis()
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss",          // ISO без таймзоны
            "dd.MM.yyyy HH:mm",               // формат, часто используемый на mchs.gov.ru
            "yyyy-MM-dd HH:mm:ss"
        )
        for (p in patterns) {
            try {
                return SimpleDateFormat(p, Locale.ROOT).parse(value)?.time ?: continue
            } catch (_: Exception) { /* пробуем следующий формат */ }
        }
        // Возможно, передано число epoch-секунд
        value.toLongOrNull()?.let { return if (it > 1_000_000_000_000L) it else it * 1000L }
        return System.currentTimeMillis()
    }

    /** Удаляет HTML-теги и сущности из текста новости. */
    private fun unescapeHtml(input: String): String = input
        .replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ")
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .trim()

    }
