package com.gita.player

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File

private fun audioExtension(mime: String?): String = when (mime?.lowercase()) {
    "audio/mpeg", "audio/mp3" -> "mp3"
    "audio/flac", "audio/x-flac" -> "flac"
    "audio/mp4", "audio/x-m4a", "audio/aac", "audio/mp4a-latm" -> "m4a"
    "audio/ogg", "audio/vorbis", "audio/oga", "audio/x-vorbis+ogg" -> "ogg"
    "audio/opus" -> "opus"
    "audio/wav", "audio/x-wav" -> "wav"
    "audio/x-ms-wma", "audio/wma" -> "wma"
    else -> "mp3"
}

private fun queryColumn(context: Context, uri: Uri, column: String): String? =
    context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }

// copies the song to a temp file, edits its tag in place, writes the new bytes
// back to the same file so every other app sees the change, then rescans it
private fun rewriteSong(context: Context, songUri: Uri, edit: (File) -> Unit): Boolean = try {
    val mime = queryColumn(context, songUri, MediaStore.Audio.Media.MIME_TYPE)
    val tmp = File.createTempFile("gita_edit_", ".${audioExtension(mime)}", context.cacheDir)
    var ok = false
    try {
        context.contentResolver.openInputStream(songUri)?.use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }
        edit(tmp)
        context.contentResolver.openOutputStream(songUri, "w")?.use { out ->
            tmp.inputStream().use { it.copyTo(out) }
        }
        ok = true
    } finally {
        tmp.delete()
    }
    if (ok) {
        val path = queryColumn(context, songUri, MediaStore.Audio.Media.DATA)
        if (!path.isNullOrEmpty()) {
            MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf(mime), null)
        }
        context.contentResolver.notifyChange(songUri, null)
    }
    ok
} catch (e: Exception) {
    false
}

fun setSongCover(context: Context, songUri: Uri, image: ByteArray, imageMime: String): Boolean =
    rewriteSong(context, songUri) { tmp ->
        val audio = AudioFileIO.read(tmp)
        val tag = audio.getTagOrCreateAndSetDefault()
        runCatching { tag.deleteArtworkField() }
        val art = ArtworkFactory.getNew()
        art.setBinaryData(image)
        art.setMimeType(imageMime)
        tag.setField(art)
        audio.commit()
    }

fun removeSongCover(context: Context, songUri: Uri): Boolean =
    rewriteSong(context, songUri) { tmp ->
        val audio = AudioFileIO.read(tmp)
        val tag = audio.getTagOrCreateAndSetDefault()
        runCatching { tag.deleteArtworkField() }
        audio.commit()
    }
