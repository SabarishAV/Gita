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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

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

    val settings by vm.settings.collectAsState()
    val removedIds by vm.removedIds.collectAsState()
    val prefs by vm.listPrefs.collectAsState()
    val removedReady = removedIds != null
    val prefsReady = prefs != null

    LaunchedEffect(Unit) { if (!granted) launcher.launch(permission) }
    LaunchedEffect(granted, settings, removedReady, prefsReady) {
        if (granted && settings != null && removedReady && prefsReady) vm.loadSongsFromDevice()
    }

    val songs by vm.songs.collectAsState()
    val removedSongs by vm.removedSongs.collectAsState()
    val current by vm.current.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()

    var favOnly by rememberSaveable { mutableStateOf(false) }
    val shown = if (favOnly) songs.filter { it.id in favorites } else songs

    var showPlayer by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showRemoved by remember { mutableStateOf(false) }
    BackHandler(enabled = showPlayer) { showPlayer = false }
    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = showRemoved) { showRemoved = false }

    val playSource by vm.playSource.collectAsState()
    val restoreDone by vm.restoreDone.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var viewInitialized by rememberSaveable { mutableStateOf(false) }
    var pendingScrollId by remember { mutableStateOf<Long?>(null) }

    // after reopening, show the list the last song was played from
    LaunchedEffect(restoreDone) {
        if (restoreDone && !viewInitialized) {
            favOnly = playSource == Source.FAV
            viewInitialized = true
        }
    }

    // after switching lists from the bottom bar, jump to the playing song
    LaunchedEffect(pendingScrollId, favOnly) {
        val id = pendingScrollId
        if (id != null) {
            val index = shown.indexOfFirst { it.id == id }
            if (index >= 0) listState.scrollToItem((index - 2).coerceAtLeast(0))
            pendingScrollId = null
        }
    }

    val onBarClick: () -> Unit = click@{
        val cur = current ?: return@click
        val wantFav = playSource == Source.FAV
        val target = if (wantFav) songs.filter { it.id in favorites } else songs

        // the song is not in the list it was played from any more: open the details page
        if (target.none { it.id == cur.id }) {
            showPlayer = true
            return@click
        }
        // looking at the other list: switch to the playing list and jump to the song
        if (favOnly != wantFav) {
            favOnly = wantFav
            pendingScrollId = cur.id
            return@click
        }
        val index = shown.indexOfFirst { it.id == cur.id }
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        val onScreen = item != null &&
                item.offset >= info.viewportStartOffset &&
                item.offset + item.size <= info.viewportEndOffset
        if (onScreen) {
            showPlayer = true
        } else {
            scope.launch { listState.animateScrollToItem((index - 2).coerceAtLeast(0)) }
        }
    }

    GitaBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Box(Modifier.fillMaxWidth()) {
                ChromeText(
                    text = "GITA",
                    fontSize = 40.sp,
                    fontFamily = FontFamily.Serif,
                    letterSpacing = 8.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = "Settings",
                    tint = SilverDim,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(30.dp)
                        .clickable { showSettings = true }
                )
            }
            Text(
                text = if (granted) {
                    "${shown.size} ${if (favOnly) "FAVORITES" else "TRACKS"}"
                } else {
                    "PERMISSION NEEDED TO READ MUSIC"
                },
                color = SilverDim,
                fontSize = 12.sp,
                letterSpacing = 3.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 14.dp)
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            ) {
                ModeChip("ALL", selected = !favOnly, onClick = { favOnly = false }, modifier = Modifier.weight(1f))
                ModeChip("FAVORITES", selected = favOnly, onClick = { favOnly = true }, modifier = Modifier.weight(1f))
            }
            HorizontalDivider(color = Silver.copy(alpha = 0.3f))

            if (favOnly && shown.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    Text(
                        text = "NO FAVORITES YET",
                        color = SilverDim,
                        fontSize = 12.sp,
                        letterSpacing = 3.sp
                    )
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                    itemsIndexed(shown, key = { _, song -> song.id }) { index, song ->
                        SongRow(
                            index = index,
                            song = song,
                            isCurrent = song == current,
                            isFavorite = song.id in favorites,
                            onPlay = { vm.play(song, shown, if (favOnly) Source.FAV else Source.ALL) },
                            onToggleFavorite = { vm.toggleFavorite(song) },
                            onRemove = { vm.removeSong(song) }
                        )
                    }
                }
            }

            val barShape = RoundedCornerShape(10.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(barShape)
                    .background(Panel)
                    .border(1.dp, EdgeBrush, barShape)
                    .clickable(enabled = current != null, onClick = onBarClick)
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetalButton(
                        Glyph.Prev,
                        onClick = { vm.previous() },
                        width = 44.dp,
                        height = 38.dp
                    )
                    MetalRoundButton(
                        glyph = if (isPlaying) Glyph.Pause else Glyph.Play,
                        onClick = { vm.togglePlayPause() },
                        diameter = 50.dp
                    )
                    MetalButton(
                        Glyph.Next,
                        onClick = { vm.next() },
                        width = 44.dp,
                        height = 38.dp
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showSettings,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it }
        ) {
            SettingsScreen(
                settings = settings ?: AppSettings(),
                prefs = prefs ?: ListPrefs(),
                removedCount = removedSongs.size,
                onToggle = { vm.setFolderOnly(it) },
                onFolderPicked = { vm.onFolderPicked(it) },
                onSortBy = { vm.setSortBy(it) },
                onAscending = { vm.setAscending(it) },
                onShuffle = { vm.setShuffle(it) },
                onOpenRemoved = { showRemoved = true },
                onClose = { showSettings = false }
            )
        }

        AnimatedVisibility(
            visible = showRemoved,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it }
        ) {
            RemovedSongsScreen(
                songs = removedSongs,
                onRestore = { vm.restoreSong(it) },
                onClose = { showRemoved = false }
            )
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
fun ModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(36.dp)
            .then(
                if (selected) {
                    Modifier.metal(shape)
                } else {
                    Modifier
                        .clip(shape)
                        .background(Panel)
                        .border(1.dp, EdgeBrush, shape)
                }
            )
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            color = if (selected) Color(0xFF1A1A1A) else SilverDim,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            letterSpacing = 2.sp
        )
    }
}

@Composable
fun SongRow(
    index: Int,
    song: Song,
    isCurrent: Boolean,
    isFavorite: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRemove: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

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
            Box {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clickable { menuOpen = true }
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "More",
                        tint = SilverDim,
                        modifier = Modifier.size(22.dp)
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    modifier = Modifier
                        .background(Panel)
                        .border(1.dp, EdgeBrush, RoundedCornerShape(8.dp))
                ) {
                    if (isFavorite) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Remove from favorites",
                                    color = Silver,
                                    fontSize = 14.sp
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onToggleFavorite()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "Remove from list",
                                color = Silver,
                                fontSize = 14.sp
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onRemove()
                        }
                    )
                }
            }
        }
        HorizontalDivider(color = Silver.copy(alpha = 0.15f))
    }
}

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}