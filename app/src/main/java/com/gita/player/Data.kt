package com.gita.player

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val uri: Uri
)

fun loadSongs(context: Context): List<Song> {
    val songs = mutableListOf<Song>()
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.DURATION
    )
    val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
    val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

    context.contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val artist = cursor.getString(artistCol)
            songs.add(
                Song(
                    id = id,
                    title = cursor.getString(titleCol) ?: "Unknown title",
                    artist = if (artist == null || artist == "<unknown>") "Unknown artist" else artist,
                    durationMs = cursor.getLong(durationCol),
                    uri = ContentUris.withAppendedId(collection, id)
                )
            )
        }
    }
    return songs
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "gita_prefs")

class FavoritesStore(private val context: Context) {
    private val key = stringSetPreferencesKey("favorite_ids")

    val favoriteIds: Flow<Set<Long>> = context.dataStore.data.map { prefs ->
        prefs[key]?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
    }

    suspend fun toggle(songId: Long) {
        context.dataStore.edit { prefs ->
            val current = prefs[key] ?: emptySet()
            val id = songId.toString()
            prefs[key] = if (id in current) current - id else current + id
        }
    }
}