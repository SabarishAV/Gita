package com.gita.player

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope

data class SongDetails(val art: Bitmap?, val album: String?, val year: String?)

private fun decodeScaled(bytes: ByteArray, target: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= target) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}

fun loadSongDetails(context: Context, uri: Uri): SongDetails {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(context, uri)
        SongDetails(
            art = r.embeddedPicture?.let { decodeScaled(it, 1000) },
            album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
            year = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
        )
    } catch (e: Exception) {
        SongDetails(null, null, null)
    } finally {
        r.release()
    }
}

// press and hold to show the full text, release to hide it
private fun Modifier.holdToReveal(text: String, onHold: (String?) -> Unit): Modifier =
    pointerInput(text) {
        detectTapGestures(
            onPress = {
                tryAwaitRelease()
                onHold(null)
            },
            onLongPress = { onHold(text) }
        )
    }

@Composable
fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction
        ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Canvas(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .height(32.dp)
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    if (durationMs > 0) {
                        val f = (offset.x / size.width).coerceIn(0f, 1f)
                        onSeek((f * durationMs).toLong())
                    }
                }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        dragFraction?.let { onSeek((it * durationMs).toLong()) }
                        dragFraction = null
                    },
                    onDragCancel = { dragFraction = null },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                )
            }
    ) {
        val w = size.width
        val midY = size.height / 2
        val x = w * fraction
        val stroke = 3.dp.toPx()

        drawLine(Color(0xFF2E2E2B), Offset(0f, midY), Offset(w, midY), stroke)
        drawLine(Silver, Offset(0f, midY), Offset(x, midY), stroke)

        // metallic knob
        drawCircle(Color(0xFF050505), 13.dp.toPx(), Offset(x, midY))
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0xFFC2C2BE), Color(0xFF9A9A96), Color(0xFF5A5A56)),
                center = Offset(x - 3.dp.toPx(), midY - 3.dp.toPx()),
                radius = 14.dp.toPx()
            ),
            radius = 10.dp.toPx(),
            center = Offset(x, midY)
        )
    }
}

@Composable
fun NowPlayingScreen(
    vm: PlayerViewModel,
    onClose: () -> Unit,
    onSkip: () -> Unit = {},
    coverEditEnabled: Boolean = false
) {
    val current by vm.current.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val position by vm.position.collectAsState()
    val duration by vm.duration.collectAsState()
    val loop by vm.loop.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val context = LocalContext.current

    val song = current ?: return
    var coverVersion by remember(song.id) { mutableStateOf(0) }
    val details by produceState<SongDetails?>(initialValue = null, key1 = song.id, key2 = coverVersion) {
        value = null
        value = withContext(Dispatchers.IO) { loadSongDetails(context, song.uri) }
    }
    val total = if (duration > 0) duration else song.durationMs

    val albumLine = listOfNotNull(
        details?.album?.takeIf { it.isNotBlank() },
        details?.year?.takeIf { it.isNotBlank() }?.let { "($it)" }
    ).joinToString(" ")

    var holding by remember { mutableStateOf(false) }
    var heldText by remember { mutableStateOf("") }
    val onHold: (String?) -> Unit = { text ->
        if (text != null) {
            heldText = text
            holding = true
        } else {
            holding = false
        }
    }

    val scope = rememberCoroutineScope()
    var pendingImage by remember { mutableStateOf<ByteArray?>(null) }
    var pendingMime by remember { mutableStateOf("image/jpeg") }
    var pendingRemove by remember { mutableStateOf(false) }

    fun runPendingEdit() {
        val image = pendingImage
        val remove = pendingRemove
        pendingImage = null
        pendingRemove = false
        if (image == null && !remove) return
        scope.launch(Dispatchers.IO) {
            if (remove) {
                removeSongCover(context, song.uri)
            } else if (image != null) {
                setSongCover(context, song.uri, image, pendingMime)
            }
            withContext(Dispatchers.Main) { coverVersion++ }
        }
    }

    val storagePerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) runPendingEdit() }

    val writeRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> if (result.resultCode == android.app.Activity.RESULT_OK) runPendingEdit() }

    // editing an audio file needs write access to it
    fun ensureWriteAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            val sender = MediaStore.createWriteRequest(context.contentResolver, listOf(song.uri))
            writeRequest.launch(IntentSenderRequest.Builder(sender).build())
        } else {
            storagePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) {
                    pendingMime = context.contentResolver.getType(uri) ?: "image/jpeg"
                    pendingImage = bytes
                    pendingRemove = false
                    withContext(Dispatchers.Main) { ensureWriteAccess() }
                }
            }
        }
    }

    GitaBackground(Modifier.pointerInput(Unit) {}) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .pointerInput(Unit) {
                    // swipe down to close
                    var total = 0f
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = { if (total > 120.dp.toPx()) onClose() },
                        onDragCancel = { total = 0f },
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            total += dy
                        }
                    )
                }
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.fillMaxWidth()) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Close",
                    tint = SilverDim,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(32.dp)
                        .clickable(onClick = onClose)
                )
                ChromeText(
                    text = "GITA",
                    fontSize = 30.sp,
                    fontFamily = FontFamily.Serif,
                    letterSpacing = 6.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
                Box(Modifier.align(Alignment.CenterEnd)) {
                    HeartIcon(
                        filled = song.id in favorites,
                        onClick = { vm.toggleFavorite(song) }
                    )
                }
            }

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                val side = minOf(maxWidth * 0.92f, maxHeight)
                val artShape = RoundedCornerShape(4.dp)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(side)
                        .clip(artShape)
                        .background(Color(0xFF101010))
                        .border(2.dp, MetalBrush, artShape)
                ) {
                    val art = details?.art
                    if (art != null) {
                        Image(
                            bitmap = art.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        VinylFallback(playing = isPlaying)
                    }
                }
            }

            ChromeText(
                text = song.title,
                fontSize = 26.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.holdToReveal(song.title, onHold)
            )
            Text(
                text = song.artist,
                color = Silver,
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .holdToReveal(song.artist, onHold)
            )
            if (albumLine.isNotEmpty()) {
                Text(
                    text = albumLine,
                    color = SilverDim,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (coverEditEnabled && details != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    val chipShape = RoundedCornerShape(8.dp)
                    val hasArt = details?.art != null
                    if (hasArt) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .clip(chipShape)
                                .background(Panel)
                                .border(1.dp, EdgeBrush, chipShape)
                                .clickable {
                                    pendingRemove = true
                                    pendingImage = null
                                    ensureWriteAccess()
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "REMOVE COVER",
                                color = SilverDim,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                letterSpacing = 2.sp
                            )
                        }
                    } else {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .clip(chipShape)
                                .background(Panel)
                                .border(1.dp, EdgeBrush, chipShape)
                                .clickable {
                                    pickImage.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "SET COVER",
                                color = SilverDim,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                letterSpacing = 2.sp
                            )
                        }
                    }
                }
            }

            SeekBar(
                positionMs = position,
                durationMs = total,
                onSeek = { vm.seekTo(it) },
                modifier = Modifier.padding(top = 20.dp)
            )
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                Text(formatDuration(position), color = SilverDim, fontSize = 12.sp)
                Text(formatDuration(total), color = SilverDim, fontSize = 12.sp)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 20.dp)
            ) {
                MetalButton(Glyph.Prev, onClick = {
                    onSkip()
                    vm.previous()
                })
                MetalRoundButton(
                    glyph = if (isPlaying) Glyph.Pause else Glyph.Play,
                    onClick = { vm.togglePlayPause() }
                )
                MetalButton(Glyph.Next, onClick = {
                    onSkip()
                    vm.next()
                })
            }

            ModeChip(
                label = "LOOP",
                selected = loop,
                onClick = { vm.toggleLoop() },
                modifier = Modifier.padding(top = 16.dp, bottom = 12.dp).width(120.dp)
            )
        }

        // full name, shown while the title or artist is held down
        AnimatedVisibility(
            visible = holding,
            enter = fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.92f),
            exit = fadeOut(tween(150))
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize().padding(32.dp)
            ) {
                val shape = RoundedCornerShape(10.dp)
                Box(
                    Modifier
                        .clip(shape)
                        .background(Panel)
                        .border(1.dp, EdgeBrush, shape)
                        .padding(22.dp)
                ) {
                    Text(
                        text = heldText,
                        color = Silver,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 22.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}