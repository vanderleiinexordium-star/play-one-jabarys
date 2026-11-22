package com.jabarys.playone.data

import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Client for Xtream Codes-compatible player_api.php servers.
 * Call from Dispatchers.IO; credentials are never written to logs.
 */
class XtreamClient(serverUrl: String, private val username: String, private val password: String) {
    private val baseUrl = serverUrl.trim().trimEnd('/')

    data class Account(val status: String, val expiresAt: String?, val maxConnections: String?, val activeConnections: String?)
    data class Category(val id: String, val name: String)
    data class Stream(
        val id: String, val name: String, val icon: String?,
        val categoryId: String?, val containerExtension: String?, val epgChannelId: String?,
        val tvArchive: Boolean = false, val tvArchiveDuration: Int = 0
    )
    data class Series(
        val id: String, val name: String, val cover: String?,
        val categoryId: String?, val plot: String?, val rating: String?
    )
    data class Episode(
        val id: String, val title: String, val season: Int, val episodeNumber: Int,
        val containerExtension: String?, val cover: String?
    )
    data class Programme(
        val title: String, val description: String, val start: String,
        val end: String, val startTimestamp: Long, val endTimestamp: Long
    )

    fun accountInfo(): Account {
        val user = requestObject().optJSONObject("user_info")
            ?: throw IllegalStateException("O servidor não retornou os dados da conta.")
        return Account(
            status = user.optString("status", "Unknown"),
            expiresAt = user.optString("exp_date").nullable(),
            maxConnections = user.optString("max_connections").nullable(),
            activeConnections = user.optString("active_cons").nullable()
        )
    }

    fun liveCategories() = categories("get_live_categories")
    fun movieCategories() = categories("get_vod_categories")
    fun seriesCategories() = categories("get_series_categories")
    fun liveStreams(categoryId: String? = null) = streams("get_live_streams", categoryId)
    fun movieStreams(categoryId: String? = null) = streams("get_vod_streams", categoryId)

    fun series(categoryId: String? = null): List<Series> {
        val array = requestArray("get_series", categoryId)
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("series_id").ifBlank { item.optString("id") }
                if (id.isBlank()) continue
                add(Series(
                    id, item.optString("name", "Série"), item.optString("cover").nullable(),
                    item.optString("category_id").nullable(), item.optString("plot").nullable(),
                    item.optString("rating").nullable()
                ))
            }
        }
    }

    fun seriesEpisodes(seriesId: String): List<Episode> {
        val root = requestObject("get_series_info", mapOf("series_id" to seriesId))
        val episodes = root.optJSONObject("episodes")
            ?: throw IllegalStateException("O servidor não retornou os episódios desta série.")
        val result = mutableListOf<Episode>()
        val seasons = episodes.keys()
        while (seasons.hasNext()) {
            val seasonKey = seasons.next()
            val seasonNumber = seasonKey.toIntOrNull() ?: 0
            val items = episodes.optJSONArray(seasonKey) ?: continue
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val id = item.optString("id")
                if (id.isBlank()) continue
                val info = item.optJSONObject("info")
                result += Episode(
                    id, item.optString("title", "Episódio ${i + 1}"),
                    item.optInt("season", seasonNumber), item.optInt("episode_num", i + 1),
                    item.optString("container_extension").nullable(),
                    info?.optString("movie_image")?.nullable()
                )
            }
        }
        return result.sortedWith(compareBy<Episode> { it.season }.thenBy { it.episodeNumber })
    }

    fun liveUrl(streamId: String) = contentUrl("live", streamId, "ts")

    fun shortEpg(streamId: String, limit: Int = 10): List<Programme> {
        val body = get(
            mapOf(
                "username" to username,
                "password" to password,
                "action" to "get_short_epg",
                "stream_id" to streamId,
                "limit" to limit.coerceIn(1, 50).toString()
            ),
            45_000
        )
        val array = JSONObject(body).optJSONArray("epg_listings") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(
                    Programme(
                        title = decodeEpg(item.optString("title")),
                        description = decodeEpg(item.optString("description")),
                        start = item.optString("start", ""),
                        end = item.optString("end", ""),
                        startTimestamp = item.optString("start_timestamp").toLongOrNull() ?: 0L,
                        endTimestamp = item.optString("stop_timestamp").toLongOrNull() ?: 0L
                    )
                )
            }
        }
    }

    fun catchupUrl(streamId: String, startTimestampSeconds: Long, durationMinutes: Int, extension: String = "ts"): String {
        val start = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US)
            .format(Date(startTimestampSeconds * 1000L))
        return "$baseUrl/timeshift/${encode(username)}/${encode(password)}/${durationMinutes.coerceAtLeast(1)}/$start/${encode(streamId)}.$extension"
    }

    fun xmltvUrl(): String =
        "$baseUrl/xmltv.php?username=${encode(username)}&password=${encode(password)}"
    fun movieUrl(streamId: String, extension: String?) = contentUrl("movie", streamId, extension ?: "mp4")
    fun episodeUrl(episodeId: String, extension: String?) = contentUrl("series", episodeId, extension ?: "mp4")

    private fun categories(action: String): List<Category> {
        val array = requestArray(action)
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("category_id")
                if (id.isNotBlank()) add(Category(id, item.optString("category_name", "Sem categoria")))
            }
        }
    }

    private fun streams(action: String, categoryId: String?): List<Stream> {
        val array = requestArray(action, categoryId)
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("stream_id")
                if (id.isBlank()) continue
                add(Stream(
                    id, item.optString("name", "Conteúdo"), item.optString("stream_icon").nullable(),
                    item.optString("category_id").nullable(),
                    item.optString("container_extension").nullable(),
                    item.optString("epg_channel_id").nullable(),
                    item.optString("tv_archive") == "1" || item.optBoolean("tv_archive"),
                    item.optString("tv_archive_duration").toIntOrNull()?.coerceAtLeast(0) ?: 0
                ))
            }
        }
    }

    private fun requestObject(action: String? = null, extra: Map<String, String> = emptyMap()): JSONObject {
        val params = linkedMapOf("username" to username, "password" to password)
        if (!action.isNullOrBlank()) params["action"] = action
        params.putAll(extra)
        val body = get(params, 60_000)
        return try { JSONObject(body) } catch (_: Exception) {
            throw IllegalStateException("Resposta inválida do servidor Xtream.")
        }
    }

    private fun requestArray(action: String, categoryId: String? = null): JSONArray {
        val params = linkedMapOf("username" to username, "password" to password, "action" to action)
        if (!categoryId.isNullOrBlank()) params["category_id"] = categoryId
        val body = get(params, 90_000)
        return try { JSONArray(body) } catch (_: Exception) {
            throw IllegalStateException("Resposta inválida ao carregar $action.")
        }
    }

    private fun get(params: Map<String, String>, timeout: Int): String {
        val query = params.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        val connection = (URL("$baseUrl/player_api.php?$query").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = timeout
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "PLAY-ONE-JABARYS/1.0")
            setRequestProperty("Connection", "close")
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException("Servidor Xtream respondeu HTTP $status.")
            if (body.isBlank()) throw IllegalStateException("O servidor Xtream retornou uma resposta vazia.")
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun contentUrl(type: String, id: String, extension: String?): String {
        val suffix = if (extension.isNullOrBlank()) "" else ".$extension"
        return "$baseUrl/$type/${encode(username)}/${encode(password)}/${encode(id)}$suffix"
    }

    private fun decodeEpg(value: String): String {
        if (value.isBlank()) return ""
        return try {
            String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Exception) {
            value
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun String.nullable(): String? = takeUnless { isBlank() || it == "null" }
}
