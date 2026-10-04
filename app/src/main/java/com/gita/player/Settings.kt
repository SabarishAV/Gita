package com.gita.player

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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

enum class SortBy { NAME, DATE }

// which list a song was played from
enum class Source { ALL, FAV }

data class AppSettings(
    val folderOnly: Boolean = false,
    val folderId: String? = null,
    val folderName: String? = null
)

data class ListPrefs(
    val sortBy: SortBy = SortBy.NAME,
    val ascending: Boolean = true,
    val shuffle: Boolean = false
)

private val Context.settingsDataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private val onlyKey = booleanPreferencesKey("folder_only")
    private val idKey = stringPreferencesKey("folder_id")
    private val nameKey = stringPreferencesKey("folder_name")
    private val removedKey = stringSetPreferencesKey("removed_ids")
    private val sortKey = stringPreferencesKey("sort_by")
    private val ascKey = booleanPreferencesKey("sort_ascending")
    private val shuffleKey = booleanPreferencesKey("shuffle")
    private val lastSongKey = longPreferencesKey("last_song")
    private val lastSourceKey = stringPreferencesKey("last_source")

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        AppSettings(p[onlyKey] ?: false, p[idKey], p[nameKey])
    }

    val removed: Flow<Set<Long>> = context.settingsDataStore.data.map { p ->
        (p[removedKey] ?: emptySet()).mapNotNull { it.toLongOrNull() }.toSet()
    }

    val prefs: Flow<ListPrefs> = context.settingsDataStore.data.map { p ->
        ListPrefs(
            sortBy = runCatching { SortBy.valueOf(p[sortKey] ?: "NAME") }
                .getOrDefault(SortBy.NAME),
            ascending = p[ascKey] ?: true,
            shuffle = p[shuffleKey] ?: false
        )
    }

    val lastSongId: Flow<Long?> = context.settingsDataStore.data.map { it[lastSongKey] }
    val lastSource: Flow<String?> = context.settingsDataStore.data.map { it[lastSourceKey] }

    suspend fun setLast(id: Long, source: String) {
        context.settingsDataStore.edit {
            it[lastSongKey] = id
            it[lastSourceKey] = source
        }
    }

    suspend fun setSortBy(value: SortBy) {
        context.settingsDataStore.edit { it[sortKey] = value.name }
    }

    suspend fun setAscending(value: Boolean) {
        context.settingsDataStore.edit { it[ascKey] = value }
    }

    suspend fun setShuffle(value: Boolean) {
        context.settingsDataStore.edit { it[shuffleKey] = value }
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

// song id -> time the file was added to the device
fun songDateAdded(context: Context): Map<Long, Long> {
    val map = HashMap<Long, Long>()
    context.contentResolver.query(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATE_ADDED),
        null,
        null,
        null
    )?.use { c ->
        val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val dateCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
        while (c.moveToNext()) map[c.getLong(idCol)] = c.getLong(dateCol)
    }
    return map
}

@Composable
private fun SettingsPanel(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Panel)
            .border(1.dp, EdgeBrush, shape)
            .padding(18.dp),
        content = content
    )
}

@Composable
fun SettingsScreen(
    settings: AppSettings,
    prefs: ListPrefs,
    removedCount: Int,
    onToggle: (Boolean) -> Unit,
    onFolderPicked: (Uri) -> Unit,
    onSortBy: (SortBy) -> Unit,
    onAscending: (Boolean) -> Unit,
    onShuffle: (Boolean) -> Unit,
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
                    .padding(top = 24.dp)
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ---- Folder ----
                SettingsPanel {
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

                // ---- Sort ----
                SettingsPanel {
                    Text(
                        text = "Sort songs",
                        color = Silver,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = "Applies to All and Favorites",
                        color = SilverDim,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp, bottom = 16.dp)
                    )

                    Text(
                        text = "SORT BY",
                        color = SilverDim,
                        fontSize = 11.sp,
                        letterSpacing = 2.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ModeChip(
                            "NAME",
                            selected = prefs.sortBy == SortBy.NAME,
                            onClick = { onSortBy(SortBy.NAME) },
                            modifier = Modifier.weight(1f)
                        )
                        ModeChip(
                            "DATE ADDED",
                            selected = prefs.sortBy == SortBy.DATE,
                            onClick = { onSortBy(SortBy.DATE) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Text(
                        text = "ORDER",
                        color = SilverDim,
                        fontSize = 11.sp,
                        letterSpacing = 2.sp,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ModeChip(
                            "ASCENDING",
                            selected = prefs.ascending,
                            onClick = { onAscending(true) },
                            modifier = Modifier.weight(1f)
                        )
                        ModeChip(
                            "DESCENDING",
                            selected = !prefs.ascending,
                            onClick = { onAscending(false) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Text(
                        text = when {
                            prefs.sortBy == SortBy.NAME && prefs.ascending -> "A to Z"
                            prefs.sortBy == SortBy.NAME -> "Z to A"
                            prefs.ascending -> "Oldest added first"
                            else -> "Newest added first"
                        },
                        color = SilverDim,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }

                // ---- Shuffle ----
                SettingsPanel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Shuffle",
                                color = Silver,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "Play in random order from the song you pick. The list order stays the same.",
                                color = SilverDim,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 2.dp, end = 12.dp)
                            )
                        }
                        MetalToggle(checked = prefs.shuffle, onCheckedChange = onShuffle)
                    }
                }

                // ---- Removed songs ----
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
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