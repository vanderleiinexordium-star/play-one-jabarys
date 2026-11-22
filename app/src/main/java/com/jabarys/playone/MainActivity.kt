package com.jabarys.playone

import android.os.Bundle
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

private val Navy = Color(0xFF101827)
private val Panel = Color(0xFF1B2738)
private val Accent = Color(0xFF16A6A1)
private val SoftText = Color(0xFFB8C4D4)

data class M3UChannel(val name: String, val url: String)

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
    var currentChannel by remember { mutableStateOf<M3UChannel?>(null) }
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(player) { onDispose { player.release() } }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val menu = listOf("Início", "TV ao vivo", "Filmes", "Séries", "Favoritos", "Configurações")

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
                    IconButton(onClick = { selected = item }) {
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
                                            channels = result
                                            message = if (result.isEmpty()) "A lista abriu, mas nenhum canal válido foi encontrado." else "Lista importada: ${result.size} canais."
                                            if (result.isNotEmpty()) selected = "TV ao vivo"
                                        } catch (e: Exception) {
                                            message = "Não foi possível carregar a lista. Confira o endereço e tente novamente."
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
                        Text("Após importar, os canais aparecerão em TV ao vivo.", color = SoftText, fontSize = 13.sp)
                    }
                    "TV ao vivo" -> {
                        Text("TV ao vivo", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        if (channels.isEmpty()) {
                            Text("Nenhum canal carregado. Adicione uma lista M3U em Início.", color = SoftText)
                        } else {
                            Text("${channels.size} canais carregados", color = Accent)
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
                                items(channels) { channel ->
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
                    else -> {
                        Text(selected, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Text("Importe uma lista M3U para carregar seus canais.", color = SoftText)
                    }
                }
            }
        }
    }
}

private fun downloadM3U(address: String): List<M3UChannel> {
    val connection = (URL(address).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000
        readTimeout = 20000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "PLAY-ONE-JABARYS/1.0")
    }
    try {
        val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (!text.trimStart().startsWith("#EXTM3U")) {
            throw IllegalArgumentException("Arquivo não é uma lista M3U")
        }
        val result = mutableListOf<M3UChannel>()
        var currentName = ""
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    currentName = line.substringAfterLast(',', "Canal").trim().ifBlank { "Canal" }
                }
                line.isNotBlank() && !line.startsWith("#") && (line.startsWith("http://") || line.startsWith("https://")) -> {
                    result.add(M3UChannel(currentName.ifBlank { "Canal ${result.size + 1}" }, line))
                    currentName = ""
                }
            }
        }
        return result
    } finally {
        connection.disconnect()
    }
}
