package com.jabarys.playone

import android.os.Bundle
import com.jabarys.playone.data.XtreamClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.net.HttpURLConnection
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private val Navy = Color(0xFF160D1D)
private val Panel = Color(0xFF28172F)
private val Accent = Color(0xFFB7E532)
private val SoftText = Color(0xFFD0C6D6)

data class M3UChannel(val name: String, val url: String, val category: String = "live")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PlayOneApp() }
    }
}

@Composable
fun PlayOneApp() {
    var selected by remember { mutableStateOf("Início") }
    var playlistUrl by remember { mutableStateOf("") }
    var importMode by remember { mutableStateOf("M3U") }
    var serverUrl by remember { mutableStateOf("") }
    var xtreamUsername by remember { mutableStateOf("") }
    var xtreamPassword by remember { mutableStateOf("") }
    var xtreamClient by remember { mutableStateOf<XtreamClient?>(null) }
    var selectedSeriesId by remember { mutableStateOf<String?>(null) }
    var selectedSeriesName by remember { mutableStateOf("") }
    var seriesEpisodes by remember { mutableStateOf<List<XtreamClient.Episode>>(emptyList()) }
    var channels by remember { mutableStateOf<List<M3UChannel>>(emptyList()) }
    var currentChannel by remember { mutableStateOf<M3UChannel?>(null) }
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(player) { onDispose { player.release() } }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val menu = listOf("Início", "TV ao vivo", "Filmes", "Séries", "Favoritos", "Configurações")
    val liveChannels = channels.filter { it.category == "live" }
    val movieChannels = channels.filter { it.category == "movie" }
    val seriesChannels = channels.filter { it.category == "series" }

    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("play_one_jabarys", android.content.Context.MODE_PRIVATE)
        val savedUrl = prefs.getString("playlist_url", null)
        if (!savedUrl.isNullOrBlank()) {
            playlistUrl = savedUrl
            loading = true
            message = "Abrindo conteúdo salvo no aparelho..."
            try {
                val cached = withContext(Dispatchers.IO) { readPlaylistCache(context) }
                if (cached.isNotEmpty()) {
                    channels = cached
                    selected = "TV ao vivo"
                    message = "Lista aberta do aparelho: ${cached.size} itens. Não foi necessário baixar novamente."
                } else {
                    message = "Primeira carga da lista..."
                    val restored = withContext(Dispatchers.IO) { downloadM3U(savedUrl) }
                    withContext(Dispatchers.IO) { savePlaylistCache(context, restored) }
                    channels = restored
                    selected = "TV ao vivo"
                    message = "Lista carregada e salva: ${restored.size} itens."
                }
            } catch (e: Exception) {
                message = "Não foi possível abrir a lista: ${e.message ?: "erro de conexão"}"
            } finally {
                loading = false
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Navy, surface = Panel)) {
        Row(Modifier.fillMaxSize().background(Navy)) {
            Column(
                Modifier.width(76.dp).fillMaxHeight().background(Color(0xFF0B1220)).padding(vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Text("P1", color = Accent, fontSize = 25.sp, fontWeight = FontWeight.Black)
                menu.forEach { item ->
                    val icon = when (item) {
                        "Início" -> Icons.Default.Home
                        "TV ao vivo" -> Icons.Default.LiveTv
                        "Filmes" -> Icons.Default.Movie
                        "Séries" -> Icons.Default.VideoLibrary
                        "Favoritos" -> Icons.Default.Favorite
                        else -> Icons.Default.Settings
                    }
                    IconButton(onClick = { selected = item; if (item != "Séries") selectedSeriesId = null }) {
                        Icon(icon, contentDescription = item, tint = if (selected == item) Accent else SoftText)
                    }
                }
            }

            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 22.dp)) {
                Text("PLAY ONE JABARYS", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Sua central de entretenimento", color = SoftText, fontSize = 13.sp)
                Spacer(Modifier.height(24.dp))

                when (selected) {
                    "Início", "Configurações" -> {
                        Text(if (selected == "Início") "Bem-vindo!" else "Conectar serviço", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        Text("Adicione sua lista M3U ou conecte com os dados Xtream do seu provedor.", color = SoftText)
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            listOf("M3U", "Xtream Codes").forEach { mode ->
                                FilterChip(
                                    selected = importMode == mode,
                                    onClick = { importMode = mode; message = "" },
                                    label = { Text(mode) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Accent,
                                        selectedLabelColor = Navy,
                                        labelColor = Color.White
                                    )
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        if (importMode == "M3U") {
                            OutlinedTextField(
                                value = playlistUrl,
                                onValueChange = { playlistUrl = it; message = "" },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Endereço da lista M3U") },
                                placeholder = { Text("https://servidor.com/lista.m3u") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                    focusedBorderColor = Accent, unfocusedBorderColor = SoftText,
                                    focusedLabelColor = Accent, unfocusedLabelColor = SoftText,
                                    cursorColor = Accent
                                )
                            )
                        } else {
                            OutlinedTextField(
                                value = serverUrl,
                                onValueChange = { serverUrl = it; message = "" },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Servidor (URL)") },
                                placeholder = { Text("http://servidor:8080") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                    focusedBorderColor = Accent, unfocusedBorderColor = SoftText,
                                    focusedLabelColor = Accent, unfocusedLabelColor = SoftText,
                                    cursorColor = Accent
                                )
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = xtreamUsername,
                                onValueChange = { xtreamUsername = it; message = "" },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Usuário") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                    focusedBorderColor = Accent, unfocusedBorderColor = SoftText,
                                    focusedLabelColor = Accent, unfocusedLabelColor = SoftText,
                                    cursorColor = Accent
                                )
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = xtreamPassword,
                                onValueChange = { xtreamPassword = it; message = "" },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Senha") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                    focusedBorderColor = Accent, unfocusedBorderColor = SoftText,
                                    focusedLabelColor = Accent, unfocusedLabelColor = SoftText,
                                    cursorColor = Accent
                                )
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                loading = true
                                message = if (importMode == "M3U") "Conectando e importando a lista..." else "Validando conta e carregando categorias..."
                                scope.launch {
                                    try {
                                        if (importMode == "M3U") {
                                            val address = playlistUrl.trim()
                                            if (!(address.startsWith("http://") || address.startsWith("https://"))) {
                                                throw IllegalArgumentException("Digite um endereço M3U válido começando com http:// ou https://.")
                                            }
                                            val result = withContext(Dispatchers.IO) { downloadM3U(address) }
                                            withContext(Dispatchers.IO) { savePlaylistCache(context, result) }
                                            channels = result
                                            context.getSharedPreferences("play_one_jabarys", android.content.Context.MODE_PRIVATE).edit().putString("playlist_url", address).apply()
                                            xtreamClient = null
                                            message = "Lista importada: ${result.size} itens (TV: ${result.count { it.category == "live" }}, filmes: ${result.count { it.category == "movie" }}, séries: ${result.count { it.category == "series" }})."
                                        } else {
                                            if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
                                                throw IllegalArgumentException("Informe o endereço completo do servidor, começando com http:// ou https://.")
                                            }
                                            if (xtreamUsername.isBlank() || xtreamPassword.isBlank()) {
                                                throw IllegalArgumentException("Informe usuário e senha Xtream.")
                                            }
                                            val client = XtreamClient(serverUrl, xtreamUsername.trim(), xtreamPassword)
                                            val imported = withContext(Dispatchers.IO) {
                                                val account = client.accountInfo()
                                                if (!account.status.equals("Active", ignoreCase = true)) {
                                                    throw IllegalStateException("A conta retornou status: ${account.status}.")
                                                }
                                                client.liveCategories()
                                                client.movieCategories()
                                                client.seriesCategories()
                                                val live = client.liveStreams().map {
                                                    M3UChannel(it.name, client.liveUrl(it.id), "live")
                                                }
                                                val movies = client.movieStreams().map {
                                                    M3UChannel(it.name, client.movieUrl(it.id, it.containerExtension), "movie")
                                                }
                                                val series = client.series().map {
                                                    M3UChannel(it.name, "xtream-series://${it.id}", "series")
                                                }
                                                Triple(live, movies, series)
                                            }
                                            channels = imported.first + imported.second + imported.third
                                            xtreamClient = client
                                            selectedSeriesId = null
                                            seriesEpisodes = emptyList()
                                            message = "Xtream conectado! TV: ${imported.first.size}, filmes: ${imported.second.size}, séries: ${imported.third.size}."
                                        }
                                        if (channels.isNotEmpty()) selected = "TV ao vivo"
                                    } catch (e: Exception) {
                                        message = "Falha: ${e.message ?: e.javaClass.simpleName}"
                                    } finally {
                                        loading = false
                                    }
                                }
                            },
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Navy)
                        ) {
                            if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = Navy, strokeWidth = 2.dp)
                            else Icon(Icons.Default.PlaylistAdd, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (loading) "CONECTANDO..." else if (importMode == "M3U") "ADICIONAR LISTA" else "CONECTAR XTREAM")
                        }
                        if (message.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text(message, color = if (message.startsWith("Falha") || message.startsWith("Não") || message.startsWith("Digite")) Color(0xFFFF8A80) else Accent)
                        }
                        Spacer(Modifier.height(18.dp))
                        Text("PLAY ONE JABARYS • TV AO VIVO • FILMES • SÉRIES", color = SoftText, fontSize = 12.sp)
                    }
                    "TV ao vivo" -> {
                        Text("TV ao vivo", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        if (channels.isEmpty()) {
                            Text("Nenhum canal carregado. Adicione uma lista M3U em Início.", color = SoftText)
                        } else {
                            Text("${liveChannels.size} canais ao vivo", color = Accent)
                            Spacer(Modifier.height(8.dp))
                            currentChannel?.let { playing ->
                                Text("REPRODUZINDO: ${playing.name}", color = Color.White, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(6.dp))
                                AndroidView(
                                    factory = { viewContext ->
                                        PlayerView(viewContext).apply {
                                            this.player = player
                                            useController = true
                                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                        }
                                    },
                                    update = { it.player = player },
                                    modifier = Modifier.fillMaxWidth().height(220.dp).background(Color.Black)
                                )
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = {
                                    player.stop()
                                    currentChannel = null
                                }) { Text("PARAR REPRODUÇÃO", color = Accent) }
                            }
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(liveChannels) { channel ->
                                    Row(
                                        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(10.dp))
                                            .clickable {
                                                currentChannel = channel
                                                player.setMediaItem(MediaItem.fromUri(channel.url))
                                                player.prepare()
                                                player.playWhenReady = true
                                            }
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = "Reproduzir ${channel.name}", tint = Accent)
                                        Spacer(Modifier.width(12.dp))
                                        Text(channel.name, color = Color.White, fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                    }
                    "Filmes", "Séries" -> {
                        val content = if (selected == "Filmes") movieChannels else seriesChannels
                        val showingEpisodes = selected == "Séries" && selectedSeriesId != null
                        Text(if (showingEpisodes) selectedSeriesName else selected, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        if (showingEpisodes) {
                            TextButton(onClick = { selectedSeriesId = null; seriesEpisodes = emptyList() }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = null)
                                Text(" VOLTAR ÀS SÉRIES")
                            }
                            Text("${seriesEpisodes.size} episódios", color = Accent)
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(seriesEpisodes) { episode ->
                                    Row(
                                        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(10.dp))
                                            .clickable {
                                                val client = xtreamClient
                                                if (client != null) {
                                                    val mediaUrl = client.episodeUrl(episode.id, episode.containerExtension)
                                                    currentChannel = M3UChannel(episode.title, mediaUrl, "series")
                                                    player.setMediaItem(MediaItem.fromUri(mediaUrl))
                                                    player.prepare()
                                                    player.playWhenReady = true
                                                }
                                            }
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = "Reproduzir ${episode.title}", tint = Accent)
                                        Spacer(Modifier.width(12.dp))
                                        Text("T${episode.season} • E${episode.episodeNumber} — ${episode.title}", color = Color.White, fontSize = 14.sp)
                                    }
                                }
                            }
                        } else {
                            Text("${content.size} itens encontrados", color = Accent)
                            Spacer(Modifier.height(8.dp))
                            if (content.isEmpty()) {
                                Text("Nenhum item encontrado. Conecte uma lista M3U ou uma conta Xtream em Início.", color = SoftText)
                            } else {
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(content) { item ->
                                        Row(
                                            Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(10.dp))
                                                .clickable {
                                                    if (selected == "Séries" && item.url.startsWith("xtream-series://")) {
                                                        val id = item.url.substringAfter("xtream-series://")
                                                        val client = xtreamClient
                                                        if (client != null) {
                                                            loading = true
                                                            scope.launch {
                                                                try {
                                                                    val episodes = withContext(Dispatchers.IO) { client.seriesEpisodes(id) }
                                                                    seriesEpisodes = episodes
                                                                    selectedSeriesId = id
                                                                    selectedSeriesName = item.name
                                                                } catch (e: Exception) {
                                                                    message = "Não foi possível carregar os episódios: ${e.message}"
                                                                } finally {
                                                                    loading = false
                                                                }
                                                            }
                                                        }
                                                    } else {
                                                        currentChannel = item
                                                        player.setMediaItem(MediaItem.fromUri(item.url))
                                                        player.prepare()
                                                        player.playWhenReady = true
                                                    }
                                                }
                                                .padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(if (selected == "Séries") Icons.Default.VideoLibrary else Icons.Default.PlayArrow,
                                                contentDescription = item.name, tint = Accent)
                                            Spacer(Modifier.width(12.dp))
                                            Text(item.name, color = Color.White, fontSize = 14.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        Text(selected, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Text("Importe uma lista M3U para carregar seus conteúdos.", color = SoftText)
                    }
                }
            }
        }
    }
}

private fun savePlaylistCache(context: android.content.Context, channels: List<M3UChannel>) {
    val array = JSONArray()
    channels.forEach { channel ->
        array.put(JSONObject().put("name", channel.name).put("url", channel.url).put("category", channel.category))
    }
    val temp = File(context.filesDir, "playlist_cache.tmp")
    temp.writeText(array.toString(), Charsets.UTF_8)
    temp.renameTo(File(context.filesDir, "playlist_cache.json"))
}

private fun readPlaylistCache(context: android.content.Context): List<M3UChannel> {
    val file = File(context.filesDir, "playlist_cache.json")
    if (!file.exists()) return emptyList()
    val array = JSONArray(file.readText(Charsets.UTF_8))
    return buildList(array.length()) {
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            add(M3UChannel(
                item.optString("name", "Canal"),
                item.optString("url", ""),
                item.optString("category", "live")
            ))
        }
    }.filter { it.url.startsWith("http://") || it.url.startsWith("https://") }
}

private fun downloadM3U(address: String, retryCount: Int = 0): List<M3UChannel> {
    val cleanAddress = address.trim().removeSurrounding("\\\"").removeSurrounding("'")
    val playlistUrl = try {
        URL(cleanAddress)
    } catch (e: Exception) {
        throw IllegalArgumentException("Endereço M3U inválido. Confira se o link completo foi colado.")
    }

    var connection = createM3UConnection(playlistUrl, fallbackUserAgent = retryCount > 0)
    var stage = "aguardando resposta do servidor (tentativa 1 de 2)"
    try {
        // Alguns provedores demoram ou não respondem bem ao User-Agent VLC.
        // Se os cabeçalhos expirarem, tenta uma única vez com User-Agent OkHttp.
        val status = try {
            connection.responseCode
        } catch (firstTimeout: java.net.SocketTimeoutException) {
            connection.disconnect()
            stage = "aguardando resposta do servidor (tentativa 2 de 2)"
            connection = createM3UConnection(playlistUrl, fallbackUserAgent = true)
            connection.responseCode
        }
        // A resposta já chegou: concede mais tempo para listas grandes serem baixadas.
        connection.readTimeout = 300000
        if (status !in 200..299) {
            throw IllegalStateException("O servidor respondeu HTTP $status. Confira o link ou tente novamente.")
        }
        stage = "recebendo os dados da lista"
        val result = mutableListOf<M3UChannel>()
        var currentName = ""
        var currentMetadata = ""
        var firstLine = true
        var validHeader = false
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            stage = "lendo e organizando os canais"
            reader.forEachLine { raw ->
                val line = if (firstLine) {
                    firstLine = false
                    raw.removePrefix("\uFEFF").trim()
                } else raw.trim()

                if (!validHeader) {
                    if (line.isBlank()) return@forEachLine
                    if (!line.startsWith("#EXTM3U", ignoreCase = true)) {
                        throw IllegalArgumentException("O endereço respondeu, mas o conteúdo não é uma lista M3U.")
                    }
                    validHeader = true
                }

                when {
                    line.startsWith("#EXTINF", ignoreCase = true) -> {
                        currentMetadata = line
                        currentName = line.substringAfterLast(',', "Canal").trim().ifBlank { "Canal" }
                    }
                    line.isNotBlank() && !line.startsWith("#") -> {
                        val channelUrl = try {
                            URL(playlistUrl, line).toString()
                        } catch (_: Exception) {
                            line
                        }
                        if (channelUrl.startsWith("http://", true) || channelUrl.startsWith("https://", true)) {
                            val itemName = currentName.ifBlank { "Canal ${result.size + 1}" }
                            result.add(M3UChannel(itemName, channelUrl, classifyM3UContent(currentMetadata, itemName, channelUrl)))
                            currentName = ""
                            currentMetadata = ""
                        }
                    }
                }
            }
        }
        if (!validHeader) {
            throw IllegalArgumentException("A lista recebida está vazia ou não contém o cabeçalho M3U.")
        }
        if (result.isEmpty()) {
            throw IllegalArgumentException("A lista foi recebida, mas não contém canais HTTP/HTTPS reconhecíveis.")
        }
        return result
    } catch (e: java.net.SocketTimeoutException) {
        if (retryCount == 0) {
            connection.disconnect()
            return downloadM3U(address, retryCount = 1)
        }
        throw IllegalStateException("A conexão expirou novamente durante a importação. Tente mais tarde ou confira a estabilidade do servidor.", e)
    } catch (e: java.net.UnknownHostException) {
        throw IllegalStateException("O Android não conseguiu localizar o servidor do link M3U (DNS). Confira o endereço completo.", e)
    } catch (e: java.io.IOException) {
        if (retryCount == 0) {
            connection.disconnect()
            return downloadM3U(address, retryCount = 1)
        }
        throw IllegalStateException("A conexão foi interrompida novamente durante o download da lista. Verifique a rede ou o servidor.", e)
    } finally {
        connection.disconnect()
    }
}

private fun createM3UConnection(url: URL, fallbackUserAgent: Boolean): HttpURLConnection {
    return (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = 60000
        // Timeout inicial para receber os cabeçalhos HTTP. O download do corpo
        // recebe 5 minutos depois que o servidor confirma a resposta.
        readTimeout = 60000
        instanceFollowRedirects = true
        requestMethod = "GET"
        setRequestProperty(
            "User-Agent",
            if (fallbackUserAgent) "okhttp/4.12.0" else "VLC/3.0.20 LibVLC/3.0.20"
        )
        setRequestProperty(
            "Accept",
            if (fallbackUserAgent) "*/*"
            else "application/vnd.apple.mpegurl, application/x-mpegURL, audio/mpegurl, */*"
        )
        setRequestProperty("Accept-Encoding", "identity")
        setRequestProperty("Connection", "close")
        setRequestProperty("Cache-Control", "no-cache")
    }
}

private fun classifyM3UContent(metadata: String, name: String, address: String): String {
    // The provider's group prefix is the strongest signal. Channel names can
    // contain words such as "FILMES" or "SÉRIES" even when they are live TV.
    val group = Regex("""group-title=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        .find(metadata)?.groupValues?.getOrNull(1).orEmpty().trim()
    val normalizedGroup = java.text.Normalizer.normalize(group, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()

    // Examples: "C • ESPN", "F • Lançamentos", "S • Amazon Prime Video".
    // Accept common bullet and dash variants.
    val explicitGroupType = Regex("""^\s*([cfs])\s*[•·●\-–—:]\s*""", RegexOption.IGNORE_CASE)
        .find(normalizedGroup)?.groupValues?.getOrNull(1)
    when (explicitGroupType) {
        "c" -> return "live"
        "f" -> return "movie"
        "s" -> return "series"
    }

    val type = Regex("""tvg-type=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        .find(metadata)?.groupValues?.getOrNull(1).orEmpty().lowercase()
    val text = normalizedGroup + " " + type + " " + name.lowercase()
    val path = try { URL(address).path.lowercase() } catch (_: Exception) { address.lowercase() }

    // Use metadata and URL patterns only when no known C/F/S prefix exists.
    if (type.contains("series") || type.contains("serie") ||
        text.contains("temporada") || text.contains("season") ||
        text.contains("episodio") || text.contains("episode") ||
        path.contains("/series/")) return "series"

    if (type.contains("movie") || type.contains("filme") || type.contains("vod") ||
        text.contains("filme") || text.contains("movie") || text.contains("cinema") ||
        path.contains("/movie/")) return "movie"

    val episodePattern = Regex("""(s\d{1,2}e\d{1,3}|\b\d{1,2}x\d{1,3}\b|temporada\s*\d+)""", RegexOption.IGNORE_CASE)
    if (episodePattern.containsMatchIn(name)) return "series"

    return "live"
}
