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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.KeyboardArrowLeft
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.activity.result.IntentSenderRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

// A slow, eased glide to a song (long distances jump close first, then glide the rest)
private suspend fun LazyListState.glideTo(index: Int) {
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) {
        scrollToItem(index)
        return
    }
    val rowH = visible.first().size.toFloat()
    val wanted = info.viewportSize.height / 3f
    val first = visible.first()
    val last = visible.last()
    val near = 5

    val targetOffset: Float = when {
        index in first.index..last.index ->
            visible.first { it.index == index }.offset.toFloat()
        index < first.index ->
            if (first.index - index > near) {
                scrollToItem(index + near)
                -near * rowH
            } else {
                first.offset - (first.index - index) * rowH
            }
        else ->
            if (index - last.index > near) {
                scrollToItem(index - near)
                near * rowH
            } else {
                last.offset + (index - last.index) * rowH
            }
    }
    animateScrollBy(targetOffset - wanted, tween<Float>(550, easing = FastOutSlowInEasing))
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
    // true once the system prompt has been shown at least once:
    // tells "never asked yet" apart from "don't ask again"
    var askedBefore by remember { mutableStateOf(false) }
    var permanentlyDenied by remember { mutableStateOf(false) }

    val activity = context as? ComponentActivity

    fun canAskAgain(): Boolean =
        activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, permission) } == true

    fun openAppSettings() {
        try {
            context.startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)
                )
            )
        } catch (e: Exception) {
            // ignore: staying on the dialog is better than crashing
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        granted = it
        askedBefore = true
        if (!it) permanentlyDenied = !canAskAgain()
    }

    val settings by vm.settings.collectAsState()
    val removedIds by vm.removedIds.collectAsState()
    val prefs by vm.listPrefs.collectAsState()
    val backup by vm.backup.collectAsState()
    val removedReady = removedIds != null
    val prefsReady = prefs != null

    LaunchedEffect(granted, settings, removedReady, prefsReady) {
        if (granted && settings != null && removedReady && prefsReady) vm.loadSongsFromDevice()
    }

    // re-check permission whenever the app comes to the front. While access is
    // missing, the system prompt is shown again on every open (first time, or
    // denied but not permanently); a permanent denial shows the blocking dialog.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner.lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = ContextCompat.checkSelfPermission(context, permission) ==
                        PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    if (!askedBefore || canAskAgain()) {
                        launcher.launch(permission)
                    } else {
                        permanentlyDenied = true
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
    // cover edit toggle: on while this screen lives, back to off when the app is reopened
    var coverEditEnabled by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchSource by remember { mutableStateOf(Source.ALL) }
    var songPendingDelete by remember { mutableStateOf<Song?>(null) }
    var pendingLegacyDelete by remember { mutableStateOf<Song?>(null) }
    BackHandler(enabled = showPlayer) { showPlayer = false }
    BackHandler(enabled = songPendingDelete != null) { songPendingDelete = null }
    BackHandler(enabled = showSearch) { showSearch = false }
    BackHandler(enabled = showSettings) {
        vm.validateBackup()
        showSettings = false
    }
    BackHandler(enabled = showRemoved) { showRemoved = false }

    val playSource by vm.playSource.collectAsState()
    val restoreDone by vm.restoreDone.collectAsState()

    // portrait unless the user turned on landscape mode
    LaunchedEffect(prefs?.landscape) {
        val activity = context as? android.app.Activity ?: return@LaunchedEffect
        activity.requestedOrientation = if (prefs?.landscape == true) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // system delete confirmation (Android 11+): reload the list whatever the answer was
    val deleteRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) {
        songPendingDelete = null
        pendingLegacyDelete = null
        vm.loadSongsFromDevice()
    }

    // legacy write permission (Android 8-10): delete once it is granted
    val legacyWritePerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        val song = pendingLegacyDelete
        pendingLegacyDelete = null
        if (ok && song != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { context.contentResolver.delete(song.uri, null, null) }
                vm.loadSongsFromDevice()
            }
        }
    }

    // permanently deletes the song file from the device
    fun deleteSongForever(song: Song) {
        songPendingDelete = null
        if (Build.VERSION.SDK_INT >= 30) {
            val sender = android.provider.MediaStore.createDeleteRequest(
                context.contentResolver, listOf(song.uri)
            )
            deleteRequest.launch(IntentSenderRequest.Builder(sender).build())
        } else if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            scope.launch(Dispatchers.IO) {
                runCatching { context.contentResolver.delete(song.uri, null, null) }
                vm.loadSongsFromDevice()
            }
        } else {
            pendingLegacyDelete = song
            legacyWritePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    var viewInitialized by rememberSaveable { mutableStateOf(false) }
    var pendingScrollId by remember { mutableStateOf<Long?>(null) }
    var flashId by remember { mutableStateOf<Long?>(null) }
    var followArmed by remember { mutableStateOf(false) }
    var followFrom by remember { mutableStateOf<Long?>(null) }

    fun isOnScreen(index: Int): Boolean {
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        return item != null &&
                item.offset >= info.viewportStartOffset &&
                item.offset + item.size <= info.viewportEndOffset
    }

    // brings a song into view with a glide and a silver glint on its row
    suspend fun revealSong(id: Long, flashAlways: Boolean) {
        val index = shown.indexOfFirst { it.id == id }
        if (index < 0) return
        val onScreen = isOnScreen(index)
        if (!onScreen) listState.glideTo(index)
        if (!onScreen || flashAlways) flashId = id
    }

    // after reopening, show the list the last song was played from
    LaunchedEffect(restoreDone) {
        if (restoreDone && !viewInitialized) {
            favOnly = playSource == Source.FAV
            viewInitialized = true
        }
    }

    // after switching lists from the bottom bar, glide to the playing song
    LaunchedEffect(pendingScrollId, favOnly) {
        val id = pendingScrollId
        if (id != null) {
            withFrameNanos { }
            withFrameNanos { }
            revealSong(id, flashAlways = true)
            pendingScrollId = null
        }
    }

    // after next/previous, follow the new song in the list
    val onSkip: () -> Unit = {
        followFrom = current?.id
        followArmed = true
    }
    LaunchedEffect(current?.id, followArmed) {
        val cur = current
        if (followArmed && cur != null && cur.id != followFrom) {
            revealSong(cur.id, flashAlways = false)
            followArmed = false
        }
    }
    LaunchedEffect(followArmed) {
        if (followArmed) {
            delay(2000)
            followArmed = false
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
        // looking at the other list: switch to the playing list and glide to the song
        if (favOnly != wantFav) {
            favOnly = wantFav
            pendingScrollId = cur.id
            return@click
        }
        val index = shown.indexOfFirst { it.id == cur.id }
        if (isOnScreen(index)) {
            showPlayer = true
        } else {
            scope.launch { revealSong(cur.id, flashAlways = true) }
        }
    }

    // random song from the list that is open right now
    val onRandom: () -> Unit = random@{
        if (shown.isEmpty()) return@random
        val pool = if (shown.size > 1) shown.filter { it.id != current?.id } else shown
        val pick = pool.random()
        vm.play(pick, shown, if (favOnly) Source.FAV else Source.ALL)
        scope.launch { revealSong(pick.id, flashAlways = true) }
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
                Icon(
                    Icons.Filled.Search,
                    contentDescription = "Search",
                    tint = SilverDim,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 48.dp)
                        .size(30.dp)
                        .clickable {
                            searchSource = if (favOnly) Source.FAV else Source.ALL
                            showSearch = true
                        }
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 14.dp)
                    .clickable(enabled = !granted) {
                        if (androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                                context as android.app.Activity, permission)) {
                            launcher.launch(permission)
                        } else {
                            // asked before and blocked for good: send the user to the
                            // system settings page for this app's permissions
                            try {
                                context.startActivity(
                                    Intent(
                                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null)
                                    )
                                )
                            } catch (e: Exception) {
                                launcher.launch(permission)
                            }
                        }
                    }
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            ) {
                ModeChip(
                    "ALL",
                    selected = !favOnly,
                    onClick = { favOnly = false },
                    modifier = Modifier.weight(1f)
                )
                ModeChip(
                    "FAVORITES",
                    selected = favOnly,
                    onClick = { favOnly = true },
                    modifier = Modifier.weight(1f)
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(width = 52.dp, height = 36.dp)
                        .metal(RoundedCornerShape(8.dp))
                        .clickable(onClick = onRandom)
                ) {
                    ShuffleGlyph(Color(0xFF1A1A1A), Modifier.size(22.dp))
                }
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
                            highlight = song.id == flashId,
                            onHighlightDone = { if (flashId == song.id) flashId = null },
                            onPlay = {
                                vm.play(song, shown, if (favOnly) Source.FAV else Source.ALL)
                                flashId = song.id
                            },
                            onToggleFavorite = { vm.toggleFavorite(song) },
                            onRemove = { vm.removeSong(song) },
                            onDelete = { songPendingDelete = song }
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
                        onClick = {
                            onSkip()
                            vm.previous()
                        },
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
                        onClick = {
                            onSkip()
                            vm.next()
                        },
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
            Box(Modifier.imePadding()) {
                SettingsScreen(
                    settings = settings ?: AppSettings(),
                    prefs = prefs ?: ListPrefs(),
                    removedCount = removedSongs.size,
                    onToggle = { vm.setFolderOnly(it) },
                    onFolderPicked = { vm.onFolderPicked(it) },
                    onSortBy = { vm.setSortBy(it) },
                    onAscending = { vm.setAscending(it) },
                    onShuffle = { vm.setShuffle(it) },
                    onLandscape = { vm.setLandscape(it) },
                    backup = backup ?: BackupSettings(),
                    onBackupToggle = { vm.setBackupOn(it) },
                    onBackupSave = { url, token -> vm.saveBackup(url, token) },
                    onOpenRemoved = { showRemoved = true },
                    coverEditEnabled = coverEditEnabled,
                    onCoverEditToggle = { coverEditEnabled = it },
                    onClose = {
                        vm.validateBackup()
                        showSettings = false
                    }
                )
            }
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
            NowPlayingScreen(
                vm = vm,
                onClose = { showPlayer = false },
                onSkip = onSkip,
                coverEditEnabled = coverEditEnabled,
                onSearch = {
                    searchSource = playSource
                    showSearch = true
                }
            )
        }

        AnimatedVisibility(
            visible = showSearch,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it }
        ) {
            SearchScreen(
                vm = vm,
                source = searchSource,
                onSong = { song, list ->
                    vm.play(song, list, searchSource)
                    flashId = song.id
                    showPlayer = true
                    showSearch = false
                },
                onClose = { showSearch = false },
                onDelete = { songPendingDelete = it }
            )
        }

        // delete confirmation: the file is removed from the device for good
        if (songPendingDelete != null) {
            val target = songPendingDelete!!
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    // swallows touches silently: no ripple, so tapping the
                    // dimmed area shows no flash and changes nothing
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .padding(32.dp)
            ) {
                val panelShape = RoundedCornerShape(10.dp)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(panelShape)
                        .background(Panel)
                        .border(1.dp, EdgeBrush, panelShape)
                        .padding(24.dp)
                ) {
                    ChromeText(
                        text = "DELETE SONG?",
                        fontSize = 22.sp,
                        fontFamily = FontFamily.Serif,
                        letterSpacing = 3.sp,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = target.title,
                        color = Silver,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    Text(
                        text = "This will permanently delete the file from your device.",
                        color = SilverDim,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ModeChip(
                            label = "CANCEL",
                            selected = false,
                            onClick = { songPendingDelete = null },
                            modifier = Modifier.weight(1f)
                        )
                        ModeChip(
                            label = "DELETE",
                            selected = true,
                            onClick = { deleteSongForever(target) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // no music access: block the whole app behind a dialog that leads to
        // the exact place where the permission can be granted
        if (!granted) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    // swallows touches silently: no ripple, so tapping the
                    // dimmed area shows no flash and changes nothing
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .padding(32.dp)
            ) {
                val panelShape = RoundedCornerShape(10.dp)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(panelShape)
                        .background(Panel)
                        .border(1.dp, EdgeBrush, panelShape)
                        .padding(24.dp)
                ) {
                    ChromeText(
                        text = "MUSIC ACCESS NEEDED",
                        fontSize = 22.sp,
                        fontFamily = FontFamily.Serif,
                        letterSpacing = 3.sp,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Gita needs access to your music files to list and play songs. The app cannot be used without it.",
                        color = SilverDim,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp, bottom = 20.dp)
                    )
                    ModeChip(
                        label = if (permanentlyDenied) "OPEN SETTINGS" else "ALLOW ACCESS",
                        selected = true,
                        onClick = {
                            if (permanentlyDenied) openAppSettings()
                            else launcher.launch(permission)
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    )
                    if (!permanentlyDenied) {
                        Text(
                            text = "OPEN APP SETTINGS",
                            color = SilverDim,
                            fontSize = 12.sp,
                            letterSpacing = 2.sp,
                            modifier = Modifier
                                .padding(top = 14.dp)
                                .clickable { openAppSettings() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SearchScreen(
    vm: PlayerViewModel,
    source: Source,
    onSong: (Song, List<Song>) -> Unit,
    onDelete: (Song) -> Unit,
    onClose: () -> Unit
) {
    val songs by vm.songs.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val current by vm.current.collectAsState()

    // only the tab the search was opened from
    val tabSongs = if (source == Source.FAV) songs.filter { it.id in favorites } else songs

    var query by remember { mutableStateOf("") }
    val filtered = if (query.isBlank()) {
        tabSongs
    } else {
        tabSongs.filter {
            it.title.contains(query, ignoreCase = true) ||
                    it.artist.contains(query, ignoreCase = true)
        }
    }

    GitaBackground(Modifier.pointerInput(Unit) {}) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowLeft,
                    contentDescription = "Back",
                    tint = SilverDim,
                    modifier = Modifier
                        .size(36.dp)
                        .clickable(onClick = onClose)
                )
                ChromeText(
                    text = if (source == Source.FAV) "SEARCH FAVORITES" else "SEARCH",
                    fontSize = 26.sp,
                    fontFamily = FontFamily.Serif,
                    letterSpacing = 5.sp,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }

            val fieldShape = RoundedCornerShape(10.dp)
            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = Silver,
                    fontSize = 16.sp
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Silver),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp)
                    .clip(fieldShape)
                    .background(Panel)
                    .border(1.dp, EdgeBrush, fieldShape)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            "SONG OR ARTIST",
                            color = SilverDim,
                            fontSize = 14.sp,
                            letterSpacing = 2.sp
                        )
                    }
                    inner()
                }
            )

            Text(
                text = "${filtered.size} ${if (filtered.size == 1) "RESULT" else "RESULTS"}",
                color = SilverDim,
                fontSize = 12.sp,
                letterSpacing = 3.sp,
                modifier = Modifier.padding(top = 14.dp, bottom = 10.dp)
            )

            if (filtered.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    Text(
                        text = "NO MATCHES",
                        color = SilverDim,
                        fontSize = 12.sp,
                        letterSpacing = 3.sp
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    itemsIndexed(filtered, key = { _, song -> song.id }) { index, song ->
                        SongRow(
                            index = index,
                            song = song,
                            isCurrent = song == current,
                            isFavorite = song.id in favorites,
                            highlight = false,
                            onHighlightDone = {},
                            onPlay = { onSong(song, tabSongs) },
                            onToggleFavorite = { vm.toggleFavorite(song) },
                            onRemove = { vm.removeSong(song) },
                            onDelete = { onDelete(song) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ShuffleGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val a = Path().apply {
            moveTo(w * 0.05f, h * 0.30f)
            lineTo(w * 0.32f, h * 0.30f)
            lineTo(w * 0.62f, h * 0.70f)
            lineTo(w * 0.78f, h * 0.70f)
        }
        val b = Path().apply {
            moveTo(w * 0.05f, h * 0.70f)
            lineTo(w * 0.32f, h * 0.70f)
            lineTo(w * 0.62f, h * 0.30f)
            lineTo(w * 0.78f, h * 0.30f)
        }
        drawPath(a, color, style = stroke)
        drawPath(b, color, style = stroke)
        listOf(0.30f, 0.70f).forEach { y ->
            val head = Path().apply {
                moveTo(w * 0.78f, h * (y - 0.14f))
                lineTo(w * 0.96f, h * y)
                lineTo(w * 0.78f, h * (y + 0.14f))
                close()
            }
            drawPath(head, color)
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

// one entry in the row's overflow menu: its own tile with a gap around it,
// and a small shrink + brighten while pressed so the tapped entry is obvious
@Composable
private fun MenuTile(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = tween(120),
        label = "menuPress"
    )
    val tileBg by animateColorAsState(
        targetValue = if (pressed) Color(0xFF33332F) else Color(0xFF1D1D1D),
        animationSpec = tween(120),
        label = "menuPressBg"
    )
    val tileShape = RoundedCornerShape(8.dp)
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                color = Silver,
                fontSize = 14.sp
            )
        },
        onClick = onClick,
        interactionSource = interaction,
        modifier = Modifier
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clip(tileShape)
            .background(tileBg)
            .border(1.dp, Silver.copy(alpha = 0.16f), tileShape)
    )
}

@Composable
fun SongRow(
    index: Int,
    song: Song,
    isCurrent: Boolean,
    isFavorite: Boolean,
    highlight: Boolean,
    onHighlightDone: () -> Unit,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRemove: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    // a band of light sweeps across the row, like a glint on brushed metal
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(highlight) {
        if (highlight) {
            sweep.snapTo(0f)
            sweep.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
            sweep.snapTo(0f)
            onHighlightDone()
        }
    }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val p = sweep.value
                    if (p > 0f && p < 1f) {
                        val w = size.width
                        val cx = w * (-0.2f + 1.4f * p)
                        val half = w * 0.3f
                        drawRect(
                            Brush.horizontalGradient(
                                listOf(
                                    Color.Transparent,
                                    Silver.copy(alpha = 0.22f),
                                    Color.Transparent
                                ),
                                startX = cx - half,
                                endX = cx + half
                            )
                        )
                    }
                }
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
                    shape = RoundedCornerShape(10.dp),
                    containerColor = Panel,
                    modifier = Modifier.border(
                        1.5.dp,
                        Silver.copy(alpha = 0.55f),
                        RoundedCornerShape(10.dp)
                    )
                ) {
                    if (isFavorite) {
                        MenuTile("Remove from favorites") {
                            menuOpen = false
                            onToggleFavorite()
                        }
                    }
                    MenuTile("Remove from list") {
                        menuOpen = false
                        onRemove()
                    }
                    MenuTile("Delete song") {
                        menuOpen = false
                        onDelete()
                    }
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