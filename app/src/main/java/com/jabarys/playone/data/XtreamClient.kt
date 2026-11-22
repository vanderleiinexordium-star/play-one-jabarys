package com.jabarys.playone.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Client for the Xtream Codes-compatible player_api.php interface.
 * All methods are synchronous and must be called from Dispatchers.IO.
 */
class XtreamClient(
    serverUrl: String,
    private val username: String,
    private val password: String
) {
    private val baseUrl = serverUrl.trim().trimEnd('/')

    data class Account(
        val status: String,
        val expiresAt: String?,
        val maxConnections: String?,
        val activeConnections: String?
    )

    data class Category(val id: String, val name: String)

    data class Stream(
        val id: String,
        val name: String,
        val icon: String?,
        val categoryId: String?,
        val containerExtension: String?,
        val epgChannelId: String?
    )

    data class Series(
        val id: String,
        val name: String,
        val cover: String?,
        val categoryId: String?,
        val plot: String?,
        val rating: String?
    )

    data class Episode(
        val id: String,
        val title: String,
        val season: Int,
        val episodeNumber: Int,
        val containerExtension: String?,
        val cover: String?
    )

    fun accountInfo(): Account {
        val root = request()
        val user = root.optJSONObject("user_info")
            ?: throw IllegalStateException("O servidor não retornou os dados da conta.")
        return Account(
            status = user.optString("status", "Unknown"),
            expiresAt = user.optString("exp_date").takeUnless { it.isBlank() || it == "null" },
            maxConnections = user.optString("max_connections").takeUnless { it.isBlank() || it == "null" },
            activeConnections = user.optString("active_cons").takeUnless { it.isBlank() || it == "null" }
        )
    }

    fun liveCategories(): List<Category> = categories("get_live_categories")
    fun movieCategories(): List<Category> = categories("get_vod_categories")
    fun seriesCategories(): List<Category> = categories("get_series_categories")

    fun liveStreams(categoryId: String? = null): List<Stream> =
        streams("get_live_streams", categoryId)

    fun movieStreams(categoryId: String? = null): List<Stream> =
        streams("get_vod_streams", categoryId)

    fun series(categoryId: String? = null): List<Series> {
        val array = requestArray("get_series", categoryId)
        return buildList(array.length()) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("series_id").ifBlank { item.optString("id") }
                if (id.isBlank()) continue
                add(
                    Series(
                        id = id,
                        name = item.optString("name", "Série"),
                        cover = item.optString("cover").nullable(),
                        categoryId = item.optString("category_id").nullable(),
                        plot = item.optString("plot").nullable(),
                        rating = item.optString("rating").nullable()
                    )
                )
            }
        }
    }

    fun seriesEpisodes(seriesId: String): List<Episode> {
        val root = request("get_series_info", extra = mapOf("series_id" to seriesId))
        val info = root.optJSONObject("info")
        val episodes = root.optJSONObject("episodes")
            ?: throw IllegalStateException("O servidor não retornou os episódios desta série.")
        val result = mutableListOf<Episode>()
        val seasons = episodes.keys()
        while (seasons.hasNext()) {
            val seasonKey = seasons.next()
            val seasonNumber = seasonKey.toIntOrNull() ?: 0
            val seasonItems = episodes.optJSONArray(seasonKey) ?: continue
            for (i in 0 until seasonItems.length()) {
                val item = seasonItems.optJSONObject(i) ?: continue
                val id = item.optString("id")
                if (id.isBlank()) continue
                result += Episode(
                    id = id,
                    title = item.optString("title", "Episódio ${i + 1}"),
                    season = item.optInt("season", seasonNumber),
                    episodeNumber = item.optInt("episode_num", i + 1),
                    containerExtension = item.optString("container_extension").nullable(),
                    cover = item.optString("info").let { _ ->
                        item.optJSONObject("info")?.optString("movie_image")?.nullable()
                    } ?: info?.optString("cover")?.nullable()
                )
            }
        }
        return result.sortedWith(compareBy<Episode> { it.season }.thenBy { it.episodeNumber })
    }

    fun liveUrl(streamId: String): String = contentUrl("live", streamId, null)
    fun movieUrl(streamId: String, extension: String?): String =
        contentUrl("movie", streamId, extension ?: "mp4")
    fun episodeUrl(episodeId: String, extension: String?): String =
        contentUrl("series", episodeId, extension ?: "mp4")

    private fun categories(action: String): List<Category> {
        val array = requestArray(action)
        return buildList(array.length()) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("category_id")
                if (id.isNotBlank()) add(Category(id, item.optString("category_name", "Sem categoria")))
            }
        }
    }

    private fun streams(action: String, categoryId: String?): List<Stream> {
        val array = requestArray(action, categoryId)
        return buildList(array.length()) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("stream_id")
                if (id.isBlank()) continue
                add(
                    Stream(
                        id = id,
                        name = item.optString("name", "Conteúdo"),
                        icon = item.optString("stream_icon").nullable(),
                        categoryId = item.optString("category_id").nullable(),
                        containerExtension = item.optString("container_extension").nullable(),
                        epgChannelId = item.optString("epg_channel_id").nullable()
                    )
                )
            }
        }
    }

    private fun requestArray(action: String, categoryId: String? = null): JSONArray {
        val extra = if (categoryId.isNullOrBlank()) emptyMap() else mapOf("category_id" to categoryId)
        return request(action, extra).let { throw IllegalStateException("Resposta inesperada para $action: ${it}") }
    }

    private fun request(action: String? = null, extra: Map<String, String> = emptyMap()): JSONObject {
        val params = linkedMapOf("username" to username, "password" to password)
        if (!action.isNullOrBlank()) params["action"] = action
        params.putAll(extra)
        val query = params.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        val url = URL("$baseUrl/player_api.php?$query")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "PLAY-ONE-JABARYS/1.0")
            setRequestProperty("Connection", "close")
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw IllegalStateException("Servidor Xtream respondeu HTTP $status.")
            }
            if (body.isBlank()) throw IllegalStateException("O servidor Xtream retornou uma resposta vazia.")
            return try {
                JSONObject(body)
            } catch (_: Exception) {
                throw IllegalStateException("Resposta inválida do servidor Xtream. Verifique servidor, usuário e senha.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun requestArray(action: String, categoryId: String? = null): JSONArray {
        val params = linkedMapOf("username" to username, "password" to password, "action" to action)
        if (!categoryId.isNullOrBlank()) params["category_id"] = categoryId
        val query = params.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        val connection = (URL("$baseUrl/player_api.php?$query").openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 90_000
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
            return try { JSONArray(body) } catch (_: Exception) {
                throw IllegalStateException("Resposta inválida ao carregar $action.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun contentUrl(type: String, id: String, extension: String?): String {
        val suffix = if (extension.isNullOrBlank()) "" else ".$extension"
        return "$baseUrl/$type/${encode(username)}/${encode(password)}/${encode(id)}$suffix"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun String.nullable(): String? = takeUnless { isBlank() || it == "null" }
}
