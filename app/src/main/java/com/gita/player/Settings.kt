package com.gita.player

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AppSettings(
    val folderOnly: Boolean = false,
    val folderId: String? = null,
    val folderName: String? = null
)

private val Context.settingsDataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private val onlyKey = booleanPreferencesKey("folder_only")
    private val idKey = stringPreferencesKey("folder_id")
    private val nameKey = stringPreferencesKey("folder_name")
    private val removedKey = stringSetPreferencesKey("removed_ids")

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        AppSettings(p[onlyKey] ?: false, p[idKey], p[nameKey])
    }

    // IDs of songs the user removed from the list
    val removed: Flow<Set<Long>> = context.settingsDataStore.data.map { p ->
        (p[removedKey] ?: emptySet()).mapNotNull { it.toLongOrNull() }.toSet()
    }

    private val lastSongKey = longPreferencesKey("last_song")

    val lastSongId: Flow<Long?> = context.settingsDataStore.data.map { it[lastSongKey] }

    suspend fun setLastSong(id: Long) {
        context.settingsDataStore.edit { it[lastSongKey] = id }
    }

    suspend fun setFolderOnly(enabled: Boolean) {
        context.settingsDataStore.edit { p ->
            p[onlyKey] = enabled
            if (!enabled) {
                p.remove(idKey)
                p.remove(nameKey)
            }
        }
    }

    suspend fun setFolder(id: String, name: String) {
        context.settingsDataStore.edit { p ->
            p[idKey] = id
            p[nameKey] = name
        }
    }

    suspend fun removeSong(id: Long) {
        context.settingsDataStore.edit { p ->
            p[removedKey] = (p[removedKey] ?: emptySet()) + id.toString()
        }
    }

    suspend fun restoreSong(id: Long) {
        context.settingsDataStore.edit { p ->
            p[removedKey] = (p[removedKey] ?: emptySet()) - id.toString()
        }
    }
}

// IDs of all songs stored inside the chosen folder (subfolders included)
fun folderSongIds(context: Context, folderId: String): Set<Long> {
    val volume = folderId.substringBefore(':')
    val path = folderId.substringAfter(':', "").trim('/')
    val root = if (volume == "primary") "/storage/emulated/0/" else "/storage/$volume/"
    val prefix = root + if (path.isEmpty()) "" else "$path/"
    val escaped = prefix
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    val ids = mutableSetOf<Long>()
    context.contentResolver.query(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Audio.Media._ID),
        "${MediaStore.Audio.Media.DATA} LIKE ? ESCAPE '\\'",
        arrayOf("$escaped%"),
        null
    )?.use { c ->
        val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        while (c.moveToNext()) ids += c.getLong(idCol)
    }
    return ids
}

@Composable
fun SettingsScreen(
    settings: AppSettings,
    removedCount: Int,
    onToggle: (Boolean) -> Unit,
    onFolderPicked: (Uri) -> Unit,
    onOpenRemoved: () -> Unit,
    onClose: () -> Unit
) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) onFolderPicked(uri) }

    val on = settings.folderOnly
    val panelShape = RoundedCornerShape(10.dp)

    GitaBackground(Modifier.pointerInput(Unit) {}) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Box(Modifier.fillMaxWidth()) {
                Icon(
                    Icons.Filled.KeyboardArrowLeft,
                    contentDescription = "Back",
                    tint = SilverDim,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(36.dp)
                        .clickable(onClick = onClose)
                )
                ChromeText(
                    text = "SETTINGS",
                    fontSize = 26.sp,
                    fontFamily = FontFamily.Serif,
                    letterSpacing = 5.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            Column(
                Modifier
                    .padding(top = 28.dp)
                    .fillMaxWidth()
                    .clip(panelShape)
                    .background(Panel)
                    .border(1.dp, EdgeBrush, panelShape)
                    .padding(18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Only songs from a folder",
                            color = Silver,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "Show just the music in one folder",
                            color = SilverDim,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    MetalToggle(checked = on, onCheckedChange = onToggle)
                }

                HorizontalDivider(
                    color = Silver.copy(alpha = 0.15f),
                    modifier = Modifier.padding(vertical = 16.dp)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.alpha(if (on) 1f else 0.35f)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "FOLDER",
                            color = SilverDim,
                            fontSize = 11.sp,
                            letterSpacing = 2.sp
                        )
                        Text(
                            text = settings.folderName ?: "No folder selected",
                            color = Silver,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(width = 96.dp, height = 40.dp)
                            .metal(RoundedCornerShape(8.dp))
                            .clickable(enabled = on) { launcher.launch(null) }
                    ) {
                        Text(
                            text = "BROWSE",
                            color = Color(0xFF1A1A1A),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            letterSpacing = 2.sp
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .fillMaxWidth()
                    .clip(panelShape)
                    .background(Panel)
                    .border(1.dp, EdgeBrush, panelShape)
                    .clickable(onClick = onOpenRemoved)
                    .padding(18.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Removed songs",
                        color = Silver,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = "Songs you took out of your list",
                        color = SilverDim,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Text(
                    text = "$removedCount",
                    color = Silver,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Icon(
                    Icons.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = SilverDim,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
fun RemovedSongsScreen(
    songs: List<Song>,
    onRestore: (Song) -> Unit,
    onClose: () -> Unit
) {
    GitaBackground(Modifier.pointerInput(Unit) {}) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Box(Modifier.fillMaxWidth()) {
                Icon(
                    Icons.Filled.KeyboardArrowLeft,
                    contentDescription = "Back",
                    tint = SilverDim,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(36.dp)
                        .clickable(onClick = onClose)
                )
                ChromeText(
                    text = "REMOVED",
                    fontSize = 26.sp,
                    fontFamily = FontFamily.Serif,
                    letterSpacing = 5.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            Text(
                text = if (songs.size == 1) "1 SONG" else "${songs.size} SONGS",
                color = SilverDim,
                fontSize = 12.sp,
                letterSpacing = 3.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 16.dp)
            )
            HorizontalDivider(color = Silver.copy(alpha = 0.3f))

            if (songs.isEmpty()) {
                Text(
                    text = "NOTHING REMOVED",
                    color = SilverDim,
                    fontSize = 12.sp,
                    letterSpacing = 3.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp)
                )
            } else {
                LazyColumn {
                    items(songs, key = { it.id }) { song ->
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = song.title,
                                        color = Silver,
                                        fontSize = 17.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = song.artist,
                                        color = SilverDim,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .padding(start = 12.dp)
                                        .size(width = 88.dp, height = 34.dp)
                                        .metal(RoundedCornerShape(8.dp))
                                        .clickable { onRestore(song) }
                                ) {
                                    Text(
                                        text = "RESTORE",
                                        color = Color(0xFF1A1A1A),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        letterSpacing = 1.sp
                                    )
                                }
                            }
                            HorizontalDivider(color = Silver.copy(alpha = 0.15f))
                        }
                    }
                }
            }
        }
    }
}