package com.jabarys.playone

import android.os.Bundle
import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream

private val Navy = Color(0xFF080808)
private val Panel = Color(0xFF171717)
private val Accent = Color(0xFFE50914)
private val SoftText = Color(0xFFB3B3B3)
private val GROUP_TITLE_REGEX = Regex("""group-title=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
private val TVG_TYPE_REGEX = Regex("""tvg-type=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
private val EPISODE_PATTERN = Regex("""(s\d{1,2}e\d{1,3}|\b\d{1,2}x\d{1,3}\b|temporada\s*\d+)""", RegexOption.IGNORE_CASE)
private val SERIES_WORDS = listOf("serie", "série", "series", "séries", "temporada", "season", "episodio", "episódio", "episode")
private val MOVIE_WORDS = listOf("filme", "filmes", "movie", "movies", "vod", "cinema", "film")

data class M3UChannel(val name: String, val url: String, val category: String = "live", val groupTitle: String = "Sem categoria")

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
    var channels by remember { mutableStateOf<List<M3UChannel>>(emptyList()) }
    val context = LocalContext.current
    var currentChannel by remember { mutableStateOf<M3UChannel?>(null) }
    var isFullscreen by remember { mutableStateOf(false) }
    var selectedGroup by remember { mutableStateOf("Todas") }
    var searchQuery by remember { mutableStateOf("") }
    val prefs = remember { context.getSharedPreferences("play_one_jabarys", android.content.Context.MODE_PRIVATE) }
    var favoriteUrls by remember { mutableStateOf(prefs.getStringSet("favorite_urls", emptySet()).orEmpty().toSet()) }
    var favoriteFilter by remember { mutableStateOf("Todos") }
    BackHandler(enabled = isFullscreen) { isFullscreen = false }
    var useAlternativePlayer by remember { mutableStateOf(context.getSharedPreferences("play_one_jabarys", android.content.Context.MODE_PRIVATE).getBoolean("alternative_player", false)) }
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(player) { onDispose { player.release() } }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val menu = listOf("Início", "Buscar", "TV ao vivo", "Filmes", "Séries", "Favoritos", "Configurações")
    val categorized = remember(channels) {
        Triple(
            channels.filter { it.category == "live" },
            channels.filter { it.category == "movie" },
            channels.filter { it.category == "series" }
        )
    }
    val liveChannels = categorized.first
    val movieChannels = categorized.second
    val seriesChannels = categorized.third
    val activeBase = when (selected) {
        "TV ao vivo" -> liveChannels
        "Filmes" -> movieChannels
        "Séries" -> seriesChannels
        else -> emptyList()
    }
    val groups = remember(activeBase) {
        listOf("Todas") + activeBase.map { it.groupTitle.ifBlank { "Sem categoria" } }.distinct().sorted()
    }
    val filteredChannels = remember(activeBase, selectedGroup) {
        if (selectedGroup == "Todas") activeBase
        else activeBase.filter { (it.groupTitle.ifBlank { "Sem categoria" }) == selectedGroup }
    }

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
        if (isFullscreen && currentChannel != null) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                if (useAlternativePlayer) {
                    AlternativeMediaPlayerView(url = currentChannel!!.url, modifier = Modifier.fillMaxSize())
                } else {
                    AndroidView(
                        factory = { viewContext ->
                            PlayerView(viewContext).apply {
                                this.player = player
                                useController = true
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                        },
                        update = { it.player = player },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                IconButton(onClick = { isFullscreen = false }, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                    Icon(Icons.Default.FullscreenExit, contentDescription = "Sair da tela cheia", tint = Color.White)
                }
            }
        } else Row(Modifier.fillMaxSize().background(Navy)) {
            Column(
                Modifier.width(92.dp).fillMaxHeight().background(Color(0xFF070D16)).padding(horizontal = 8.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("P1", color = Accent, fontSize = 27.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(4.dp))
                menu.forEach { item ->
                    val icon = when (item) {
                        "Início" -> Icons.Default.Home
                        "Buscar" -> Icons.Default.Search
                        "TV ao vivo" -> Icons.Default.LiveTv
                        "Filmes" -> Icons.Default.Movie
                        "Séries" -> Icons.Default.VideoLibrary
                        "Favoritos" -> Icons.Default.Favorite
                        else -> Icons.Default.Settings
                    }
                    Column(
                        Modifier.fillMaxWidth()
                            .background(if (selected == item) Color(0xFF17343B) else Color.Transparent, RoundedCornerShape(14.dp))
                            .clickable { selected = item }
                            .padding(vertical = 9.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(icon, contentDescription = item, tint = if (selected == item) Accent else SoftText, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.height(4.dp))
                        Text(item, color = if (selected == item) Color.White else SoftText, fontSize = 9.sp, maxLines = 1, fontWeight = if (selected == item) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }

            Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("PLAY ONE JABARYS", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                        Text("SUA CENTRAL DE ENTRETENIMENTO", color = SoftText, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp)
                    }
                    Box(Modifier.background(Color(0xFF12343A), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 7.dp)) {
                        Text("PLAY ONE", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                }
                Spacer(Modifier.height(18.dp))
                HorizontalDivider(color = Color(0xFF1D2B3D), thickness = 1.dp)
                Spacer(Modifier.height(20.dp))

                when (selected) {
                    "Início", "Configurações" -> {
                        Text(if (selected == "Início") "Bem-vindo!" else "Adicionar lista M3U", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        Text("Cole abaixo o endereço completo da sua lista M3U ou M3U8 autorizada.", color = SoftText)
                        Spacer(Modifier.height(14.dp))
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
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                val address = playlistUrl.trim()
                                if (!(address.startsWith("http://") || address.startsWith("https://"))) {
                                    message = "Digite um endereço M3U válido começando com http:// ou https://."
                                } else {
                                    loading = true
                                    message = "Carregando lista..."
                                    scope.launch {
                                        try {
                                            val result = withContext(Dispatchers.IO) { downloadM3U(address) }
                                            withContext(Dispatchers.IO) { savePlaylistCache(context, result) }
                                            channels = result
                                            context.getSharedPreferences("play_one_jabarys", android.content.Context.MODE_PRIVATE).edit().putString("playlist_url", address).apply()
                                            message = if (result.isEmpty()) "A lista abriu, mas nenhum conteúdo válido foi encontrado." else "Lista importada e salva: ${result.size} itens (TV: ${result.count { it.category == "live" }}, filmes: ${result.count { it.category == "movie" }}, séries: ${result.count { it.category == "series" }})."
                                            if (result.isNotEmpty()) selected = "TV ao vivo"
                                        } catch (e: Exception) {
                                            message = "Falha: ${e.message ?: e.javaClass.simpleName}"
                                        } finally {
                                            loading = false
                                        }
                                    }
                                }
                            },
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent)
                        ) {
                            if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                            else Icon(Icons.Default.PlaylistAdd, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (loading) "Importando..." else "ADICIONAR LISTA")
                        }
                        if (message.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text(message, color = if (message.startsWith("Não") || message.startsWith("Digite")) Color(0xFFFF8A80) else Accent)
                        }
                        Spacer(Modifier.height(18.dp))
                        Spacer(Modifier.height(18.dp))
                        Text("PLAYER DE REPRODUÇÃO", color = Color.White, fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(if (useAlternativePlayer) "Player alternativo (Android)" else "ExoPlayer (principal)", color = Color.White)
                                Text("Você pode alternar quando algum canal apresentar falha.", color = SoftText, fontSize = 12.sp)
                            }
                            Switch(
                                checked = useAlternativePlayer,
                                onCheckedChange = {
                                    useAlternativePlayer = it
                                    context.getSharedPreferences("play_one_jabarys", android.content.Context.MODE_PRIVATE)
                                        .edit().putBoolean("alternative_player", it).apply()
                                    player.stop()
                                }
                            )
                        }
                        Text("A lista fica salva neste aparelho e será carregada novamente quando abrir o aplicativo.", color = SoftText, fontSize = 13.sp)
                    }
                    "Buscar" -> {
                        Text("Buscar conteúdo", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("Canais, filmes e séries...") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Accent) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                focusedBorderColor = Accent, unfocusedBorderColor = SoftText, cursorColor = Accent
                            )
                        )
                        Spacer(Modifier.height(10.dp))
                        val results = remember(channels, searchQuery) {
                            if (searchQuery.isBlank()) emptyList() else channels.filter {
                                it.name.contains(searchQuery.trim(), ignoreCase = true) ||
                                    it.groupTitle.contains(searchQuery.trim(), ignoreCase = true)
                            }
                        }
                        Text(if (searchQuery.isBlank()) "Pesquise em todo o catálogo." else "${results.size} resultados", color = Accent)
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(results, key = { index, item -> "search-${item.url}#${index}" }) { _, item ->
                                CatalogRow(item, favoriteUrls.contains(item.url),
                                    onToggleFavorite = {
                                        val next = favoriteUrls.toMutableSet()
                                        if (!next.add(item.url)) next.remove(item.url)
                                        favoriteUrls = next
                                        prefs.edit().putStringSet("favorite_urls", next).apply()
                                    },
                                    onPlay = {
                                        currentChannel = item
                                        if (!useAlternativePlayer) {
                                            player.setMediaItem(MediaItem.fromUri(item.url))
                                            player.prepare()
                                            player.playWhenReady = true
                                        } else player.stop()
                                    }
                                )
                            }
                        }
                    }
                    "TV ao vivo" -> {
                        Text("TV ao vivo", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        if (channels.isEmpty()) {
                            Text("Nenhum canal carregado. Adicione uma lista M3U em Início.", color = SoftText)
                        } else {
                            Text("${filteredChannels.size} de ${liveChannels.size} canais ao vivo", color = Accent)
                            CategoryFilters(activeBase, groups, selectedGroup) { selectedGroup = it }
                            Spacer(Modifier.height(8.dp))
                            currentChannel?.let { playing ->
                                Text("REPRODUZINDO: ${playing.name}", color = Color.White, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(6.dp))
                                if (useAlternativePlayer) {
                                    AlternativeMediaPlayerView(
                                        url = playing.url,
                                        modifier = Modifier.fillMaxWidth().height(220.dp).background(Color.Black)
                                    )
                                } else {
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
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Button(
                                        onClick = { isFullscreen = true },
                                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                    ) {
                                        Icon(Icons.Default.Fullscreen, contentDescription = null, tint = Color.White)
                                        Spacer(Modifier.width(6.dp))
                                        Text("MAXIMIZAR TELA", color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                    TextButton(onClick = {
                                        player.stop()
                                        currentChannel = null
                                    }) { Text("PARAR REPRODUÇÃO", color = Accent) }
                                }
                            }
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                itemsIndexed(
                                    items = filteredChannels,
                                    key = { index, channel -> "${channel.url}#$index" }
                                ) { _, channel ->
                                    CatalogRow(channel, favoriteUrls.contains(channel.url),
                                        onToggleFavorite = {
                                            val next = favoriteUrls.toMutableSet()
                                            if (!next.add(channel.url)) next.remove(channel.url)
                                            favoriteUrls = next
                                            prefs.edit().putStringSet("favorite_urls", next).apply()
                                        },
                                        onPlay = {
                                            currentChannel = channel
                                            if (!useAlternativePlayer) {
                                                player.setMediaItem(MediaItem.fromUri(channel.url))
                                                player.prepare()
                                                player.playWhenReady = true
                                            } else player.stop()
                                        }
                                    )
                                }
                            }
                        }
                    }
                    "Filmes", "Séries" -> {
                        val content = filteredChannels
                        val totalContent = activeBase.size
                        Text(selected, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        Text("${content.size} de $totalContent itens", color = Accent)
                        CategoryFilters(activeBase, groups, selectedGroup) { selectedGroup = it }
                        Spacer(Modifier.height(8.dp))
                        if (content.isEmpty()) {
                            Text("Nenhum item identificado nesta categoria. A classificação depende das informações group-title e do endereço fornecidos pela lista M3U.", color = SoftText)
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                itemsIndexed(
                                    items = content,
                                    key = { index, item -> "${item.url}#$index" }
                                ) { _, item ->
                                    CatalogRow(item, favoriteUrls.contains(item.url),
                                        onToggleFavorite = {
                                            val next = favoriteUrls.toMutableSet()
                                            if (!next.add(item.url)) next.remove(item.url)
                                            favoriteUrls = next
                                            prefs.edit().putStringSet("favorite_urls", next).apply()
                                        },
                                        onPlay = {
                                            currentChannel = item
                                            if (!useAlternativePlayer) {
                                                player.setMediaItem(MediaItem.fromUri(item.url))
                                                player.prepare()
                                                player.playWhenReady = true
                                            } else player.stop()
                                        }
                                    )
                                }
                            }
                            currentChannel?.let { playing ->
                                Spacer(Modifier.height(10.dp))
                                if (useAlternativePlayer) {
                                    AlternativeMediaPlayerView(playing.url, Modifier.fillMaxWidth().height(220.dp).background(Color.Black))
                                } else {
                                    AndroidView(
                                        factory = { viewContext -> PlayerView(viewContext).apply {
                                            this.player = player
                                            useController = true
                                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                        } },
                                        update = { it.player = player },
                                        modifier = Modifier.fillMaxWidth().height(220.dp).background(Color.Black)
                                    )
                                }
                                Button(
                                        onClick = { isFullscreen = true },
                                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                    ) {
                                        Icon(Icons.Default.Fullscreen, contentDescription = null, tint = Color.White)
                                        Spacer(Modifier.width(6.dp))
                                        Text("MAXIMIZAR TELA", color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                            }
                        }
                    }
                    "Favoritos" -> {
                        Text("Minha lista", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Todos", "TV", "Filmes", "Séries").forEach { filter ->
                                FilterChip(
                                    selected = favoriteFilter == filter,
                                    onClick = { favoriteFilter = filter },
                                    label = { Text(filter) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent, selectedLabelColor = Color.White)
                                )
                            }
                        }
                        val favoriteItems = remember(channels, favoriteUrls, favoriteFilter) {
                            channels.filter { item ->
                                favoriteUrls.contains(item.url) && when (favoriteFilter) {
                                    "TV" -> item.category == "live"
                                    "Filmes" -> item.category == "movie"
                                    "Séries" -> item.category == "series"
                                    else -> true
                                }
                            }
                        }
                        Text("${favoriteItems.size} favoritos", color = Accent)
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(favoriteItems, key = { index, item -> "fav-${item.url}#${index}" }) { _, item ->
                                CatalogRow(item, true,
                                    onToggleFavorite = {
                                        val next = favoriteUrls.toMutableSet()
                                        next.remove(item.url)
                                        favoriteUrls = next
                                        prefs.edit().putStringSet("favorite_urls", next).apply()
                                    },
                                    onPlay = {
                                        currentChannel = item
                                        if (!useAlternativePlayer) {
                                            player.setMediaItem(MediaItem.fromUri(item.url))
                                            player.prepare()
                                            player.playWhenReady = true
                                        } else player.stop()
                                    }
                                )
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

@Composable
private fun CatalogRow(
    item: M3UChannel,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onPlay: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp))
            .clickable(onClick = onPlay)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(42.dp).background(Color(0xFF292020), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Reproduzir ${item.name}", tint = Accent)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(item.groupTitle.ifBlank { "Sem categoria" }, color = SoftText, fontSize = 11.sp, maxLines = 1)
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (isFavorite) "Remover dos favoritos" else "Adicionar aos favoritos",
                tint = if (isFavorite) Accent else SoftText)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = SoftText)
    }
}

@Composable
private fun CategoryFilters(
    items: List<M3UChannel>,
    groups: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val counts = remember(items) { items.groupingBy { it.groupTitle.ifBlank { "Sem categoria" } }.eachCount() }
    val selectedCount = if (selected == "Todas") items.size else counts[selected] ?: 0

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("CATEGORIA", color = SoftText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Icon(Icons.Default.FilterList, contentDescription = null, tint = Accent)
                Spacer(Modifier.width(6.dp))
                Text("$selected ($selectedCount)", color = Color.White, maxLines = 1)
                Icon(Icons.Default.ArrowDropDown, contentDescription = "Escolher categoria", tint = Accent)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                groups.forEach { group ->
                    val count = if (group == "Todas") items.size else counts[group] ?: 0
                    DropdownMenuItem(
                        text = {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(group, color = if (selected == group) Accent else Color.White)
                                Text(count.toString(), color = SoftText)
                            }
                        },
                        onClick = { onSelect(group); expanded = false },
                        leadingIcon = if (selected == group) {
                            { Icon(Icons.Default.Check, contentDescription = null, tint = Accent) }
                        } else null
                    )
                }
            }
        }
        Text("${groups.size - 1} categorias", color = SoftText, fontSize = 11.sp)
    }
}

@Composable
private fun AlternativeMediaPlayerView(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val mediaPlayer = remember(url) { MediaPlayer() }
    var status by remember(url) { mutableStateOf("Preparando player alternativo...") }

    AndroidView(
        factory = { viewContext ->
            SurfaceView(viewContext).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        try {
                            mediaPlayer.setDisplay(holder)
                            mediaPlayer.setDataSource(url)
                            mediaPlayer.setOnPreparedListener {
                                status = ""
                                it.start()
                            }
                            mediaPlayer.setOnErrorListener { _, what, extra ->
                                status = "Este canal não é compatível com o player alternativo ($what/$extra)."
                                true
                            }
                            mediaPlayer.setOnInfoListener { _, what, _ ->
                                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) status = "Carregando vídeo..."
                                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) status = ""
                                true
                            }
                            mediaPlayer.prepareAsync()
                        } catch (e: Exception) {
                            status = "Falha no player alternativo: ${e.message ?: "erro"}"
                        }
                    }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        if (mediaPlayer.isPlaying) mediaPlayer.pause()
                    }
                })
            }
        },
        modifier = modifier
    )
    if (status.isNotBlank()) {
        Text(status, color = Color.White, fontSize = 12.sp)
    }
    DisposableEffect(mediaPlayer) {
        onDispose {
            try { mediaPlayer.setOnPreparedListener(null); mediaPlayer.setOnErrorListener(null); mediaPlayer.stop() } catch (_: Exception) {}
            mediaPlayer.release()
        }
    }
}

private fun savePlaylistCache(context: android.content.Context, channels: List<M3UChannel>) {
    // Formato binário sequencial evita criar um JSONArray gigante na memória.
    val target = File(context.filesDir, "playlist_cache.bin")
    val temp = File(context.filesDir, "playlist_cache.tmp")
    DataOutputStream(BufferedOutputStream(temp.outputStream())).use { out ->
        out.writeInt(-20261123)
        out.writeInt(channels.size)
        channels.forEach { channel ->
            out.writeUTF(channel.name)
            out.writeUTF(channel.url)
            out.writeUTF(channel.category)
            out.writeUTF(channel.groupTitle)
        }
    }
    if (target.exists() && !target.delete()) throw IllegalStateException("Não foi possível substituir o cache local.")
    if (!temp.renameTo(target)) throw IllegalStateException("Não foi possível salvar o cache local.")
}

private fun readPlaylistCache(context: android.content.Context): List<M3UChannel> {
    val binary = File(context.filesDir, "playlist_cache.bin")
    if (binary.exists() && binary.length() > 0L) {
        try {
            DataInputStream(BufferedInputStream(binary.inputStream())).use { input ->
                val header = input.readInt()
                val hasGroups = header == -20261123
                val count = if (hasGroups) input.readInt() else header
                if (count < 0 || count > 2_000_000) return emptyList()
                return buildList(count) {
                    repeat(count) {
                        val name = input.readUTF()
                        val url = input.readUTF()
                        val category = input.readUTF()
                        val groupTitle = if (hasGroups) input.readUTF() else "Sem categoria"
                        if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
                            add(M3UChannel(name, url, category, groupTitle))
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Tenta recuperar o cache JSON antigo abaixo.
        }
    }

    // Compatibilidade com versões anteriores que salvavam JSON.
    val legacy = File(context.filesDir, "playlist_cache.json")
    if (!legacy.exists() || legacy.length() == 0L) return emptyList()
    return try {
        val array = JSONArray(legacy.readText(Charsets.UTF_8))
        buildList(array.length()) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val url = item.optString("url", "")
                if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
                    add(M3UChannel(item.optString("name", "Canal"), url, item.optString("category", "live"), item.optString("groupTitle", "Sem categoria")))
                }
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun downloadM3U(address: String): List<M3UChannel> {
    val cleanAddress = address.trim().removeSurrounding("\\\"").removeSurrounding("'")
    val playlistUrl = try {
        URL(cleanAddress)
    } catch (e: Exception) {
        throw IllegalArgumentException("Endereço M3U inválido. Confira se o link completo foi colado.")
    }

    val connection = (playlistUrl.openConnection() as HttpURLConnection).apply {
        connectTimeout = 60000
        readTimeout = 300000
        instanceFollowRedirects = true
        requestMethod = "GET"
        setRequestProperty("User-Agent", "PLAY-ONE-JABARYS/1.0")
        setRequestProperty("Accept", "application/vnd.apple.mpegurl, application/x-mpegURL, audio/mpegurl, */*")
        setRequestProperty("Connection", "close")
        setRequestProperty("Cache-Control", "no-cache")
    }
    var stage = "aguardando resposta do servidor"
    try {
        val status = connection.responseCode
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
                            val groupTitle = GROUP_TITLE_REGEX.find(currentMetadata)?.groupValues?.getOrNull(1)?.trim().orEmpty().ifBlank { "Sem categoria" }
                            result.add(M3UChannel(itemName, channelUrl, classifyM3UContent(currentMetadata, itemName, channelUrl), groupTitle))
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
        throw IllegalStateException("Tempo esgotado enquanto o aplicativo estava $stage. Isso ajuda a identificar em qual etapa a conexão trava.", e)
    } catch (e: java.net.UnknownHostException) {
        throw IllegalStateException("O Android não conseguiu localizar o servidor do link M3U (DNS). Confira o endereço completo; se ele abre em outro player, pode haver diferença no link ou no DNS usado pelo aplicativo.", e)
    } finally {
        connection.disconnect()
    }
}


private fun classifyM3UContent(metadata: String, name: String, address: String): String {
    val group = GROUP_TITLE_REGEX.find(metadata)?.groupValues?.getOrNull(1).orEmpty().lowercase()
    val type = TVG_TYPE_REGEX.find(metadata)?.groupValues?.getOrNull(1).orEmpty().lowercase()
    val text = "$group $type $name".lowercase()
    val path = try { URL(address).path.lowercase() } catch (_: Exception) { address.lowercase() }

    if (SERIES_WORDS.any { text.contains(it) } || path.contains("/series/") || EPISODE_PATTERN.containsMatchIn(name)) return "series"
    if (MOVIE_WORDS.any { text.contains(it) } || path.contains("/movie/") ||
        path.endsWith(".mkv") || path.endsWith(".mp4") || path.endsWith(".avi") ||
        path.endsWith(".mov") || path.endsWith(".wmv")) return "movie"
    return "live"
}
