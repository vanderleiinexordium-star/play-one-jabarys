package com.jabarys.playone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Navy = Color(0xFF101827)
private val Panel = Color(0xFF1B2738)
private val Accent = Color(0xFF16A6A1)
private val SoftText = Color(0xFFB8C4D4)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PlayOneApp() }
    }
}

@Composable
fun PlayOneApp() {
    var selected by remember { mutableStateOf("Início") }
    val menu = listOf("Início", "TV ao vivo", "Filmes", "Séries", "Favoritos", "Configurações")

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            background = Navy,
            surface = Panel,
            onBackground = Color.White,
            onSurface = Color.White
        )
    ) {
        Row(
            modifier = Modifier.fillMaxSize().background(Navy)
        ) {
            Column(
                modifier = Modifier.width(76.dp).fillMaxHeight().background(Color(0xFF0B1220)).padding(vertical = 18.dp),
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

            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 22.dp)
            ) {
                Text("PLAY ONE JABARYS", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Sua central de entretenimento", color = SoftText, fontSize = 13.sp)
                Spacer(Modifier.height(28.dp))
                Text(selected, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))

                if (selected == "Início") {
                    Text("Bem-vindo!", color = Accent, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text("Adicione sua lista para começar a assistir.", color = SoftText)
                    Spacer(Modifier.height(22.dp))
                    FeatureCard("Adicionar lista", "Importe uma lista M3U ou configure seu acesso Xtream Codes.", Icons.Default.PlaylistAdd)
                    Spacer(Modifier.height(12.dp))
                    FeatureCard("Player integrado", "Reprodução de canais no próprio aplicativo.", Icons.Default.PlayCircle)
                } else if (selected == "Configurações") {
                    FeatureCard("Fontes de conteúdo", "M3U / M3U8 e Xtream Codes estarão disponíveis nesta área.", Icons.Default.Settings)
                    Spacer(Modifier.height(12.dp))
                    FeatureCard("Personalização", "Nome, aparência e preferências do aplicativo.", Icons.Default.Palette)
                } else {
                    Text("Nenhum conteúdo carregado.", color = SoftText)
                    Spacer(Modifier.height(8.dp))
                    Text("Adicione uma lista autorizada em Configurações para preencher esta seção.", color = SoftText)
                }
            }
        }
    }
}

@Composable
private fun FeatureCard(title: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(4.dp))
            Text(description, color = SoftText, fontSize = 13.sp)
        }
    }
}
