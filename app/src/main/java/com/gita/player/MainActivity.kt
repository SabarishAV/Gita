package com.gita.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val viewModel: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent { GitaApp(viewModel) }
    }
}

@Composable
fun GitaApp(vm: PlayerViewModel) {
    val context = LocalContext.current
    val permission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    LaunchedEffect(Unit) { if (!granted) launcher.launch(permission) }
    LaunchedEffect(granted) { if (granted) vm.loadSongsFromDevice() }

    val songs by vm.songs.collectAsState()
    val current by vm.current.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()

    var showPlayer by remember { mutableStateOf(false) }
    BackHandler(enabled = showPlayer) { showPlayer = false }

    GitaBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Text(
                text = "GITA",
                color = Silver,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 40.sp,
                letterSpacing = 8.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = if (granted) "${songs.size} TRACKS" else "PERMISSION NEEDED TO READ MUSIC",
                color = SilverDim,
                fontSize = 12.sp,
                letterSpacing = 3.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 16.dp)
            )
            HorizontalDivider(color = Silver.copy(alpha = 0.3f))

            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                    SongRow(
                        index = index,
                        song = song,
                        isCurrent = song == current,
                        isFavorite = song.id in favorites,
                        onPlay = { vm.play(song) },
                        onToggleFavorite = { vm.toggleFavorite(song) }
                    )
                }
            }

            val barShape = RoundedCornerShape(8.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(barShape)
                    .background(Panel)
                    .border(1.dp, SilverDark, barShape)
                    .clickable(enabled = current != null) { showPlayer = true }
                    .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = current?.title ?: "NO TRACK LOADED",
                        color = Silver,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = current?.artist ?: "PRESS PLAY",
                        color = SilverDim,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                MetalRoundButton(
                    glyph = if (isPlaying) Glyph.Pause else Glyph.Play,
                    onClick = { vm.togglePlayPause() },
                    diameter = 52.dp
                )
            }
        }

        AnimatedVisibility(
            visible = showPlayer,
            enter = slideInVertically { it },
            exit = slideOutVertically { it }
        ) {
            NowPlayingScreen(vm = vm, onClose = { showPlayer = false })
        }
    }
}

@Composable
fun SongRow(
    index: Int,
    song: Song,
    isCurrent: Boolean,
    isFavorite: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onPlay)
                .padding(vertical = 12.dp)
        ) {
            Text(
                text = if (isCurrent) "▶" else "%02d".format(index + 1),
                color = if (isCurrent) Silver else SilverDim,
                fontSize = 13.sp,
                modifier = Modifier.padding(end = 14.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    color = Silver,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${song.artist} · ${formatDuration(song.durationMs)}",
                    color = SilverDim,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            HeartIcon(filled = isFavorite, onClick = onToggleFavorite)
        }
        HorizontalDivider(color = Silver.copy(alpha = 0.15f))
    }
}

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}