package com.gita.player

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val favoritesStore = FavoritesStore(app)
    private val settingsStore = SettingsStore(app)
    private var controller: MediaController? = null
    private var progressJob: Job? = null
    private var appliedKey: String? = null
    private var loaded = false
    private var restored = false
    private var lastSongId: Long? = null
    private var allDevice: List<Song> = emptyList()
    private var folderList: List<Song> = emptyList()

    // the songs currently loaded in the player (all songs, or just favorites)
    private var queue: List<Song> = emptyList()

    private val controllerFuture = MediaController.Builder(
        app,
        SessionToken(app, ComponentName(app, PlaybackService::class.java))
    ).buildAsync()

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs

    private val _removedSongs = MutableStateFlow<List<Song>>(emptyList())
    val removedSongs: StateFlow<List<Song>> = _removedSongs

    private val _current = MutableStateFlow<Song?>(null)
    val current: StateFlow<Song?> = _current

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration

    val favorites: StateFlow<Set<Long>> = favoritesStore.favoriteIds
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    // null until the saved values have been read
    val settings: StateFlow<AppSettings?> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val removedIds: StateFlow<Set<Long>?> = settingsStore.removed
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        controllerFuture.addListener({
            val c = controllerFuture.get()
            controller = c
            c.repeatMode = Player.REPEAT_MODE_ALL
            c.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _isPlaying.value = isPlaying
                    if (isPlaying) startProgress() else stopProgress()
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                        updateCurrent()
                    }
                    refreshProgress()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    refreshProgress()
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) {
                    refreshProgress()
                }
            })
            syncWithController()
        }, ContextCompat.getMainExecutor(app))

        // when a song is removed or restored, update the lists without reloading the device
        viewModelScope.launch {
            removedIds.collect {
                if (loaded) {
                    updateVisibleLists()
                    syncWithController()
                }
            }
        }

        // remember the last played song
        viewModelScope.launch {
            _current.collect { song ->
                if (song != null) {
                    lastSongId = song.id
                    settingsStore.setLastSong(song.id)
                }
            }
        }
    }

    private fun Song.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .build()
            )
            .build()

    private fun updateCurrent() {
        val id = controller?.currentMediaItem?.mediaId
        _current.value = _songs.value.firstOrNull { it.id.toString() == id }
    }

    private fun refreshProgress() {
        val c = controller ?: return
        _position.value = c.currentPosition.coerceAtLeast(0L)
        _duration.value =
            if (c.duration > 0) c.duration else (_current.value?.durationMs ?: 0L)
    }

    private fun startProgress() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (true) {
                refreshProgress()
                delay(300)
            }
        }
    }

    private fun stopProgress() {
        progressJob?.cancel()
        refreshProgress()
    }

    private fun updateVisibleLists() {
        val removed = removedIds.value ?: emptySet()
        _songs.value = folderList.filter { it.id !in removed }
        _removedSongs.value = allDevice.filter { it.id in removed }
    }

    // the queue, minus songs that are no longer visible; falls back to all songs
    private fun effectiveQueue(): List<Song> {
        val visible = _songs.value
        val ids = visible.map { it.id }.toSet()
        val q = queue.filter { it.id in ids }
        return if (q.isEmpty()) visible else q
    }

    // makes the player's queue match the target list, without interrupting playback
    private fun reconcile(c: MediaController, list: List<Song>) {
        val ids = list.map { it.id.toString() }.toSet()
        for (i in c.mediaItemCount - 1 downTo 0) {
            if (c.getMediaItemAt(i).mediaId !in ids) c.removeMediaItem(i)
        }
        list.forEachIndexed { index, song ->
            if (index >= c.mediaItemCount ||
                c.getMediaItemAt(index).mediaId != song.id.toString()
            ) {
                c.addMediaItem(index, song.toMediaItem())
            }
        }
    }

    private fun syncWithController(replace: Boolean = false) {
        val c = controller ?: return
        if (!loaded) return
        if (replace) {
            c.playWhenReady = false
            c.stop()
            c.clearMediaItems()
            queue = emptyList()
            _current.value = null
            _position.value = 0L
            _duration.value = 0L
        }

        val target = effectiveQueue()
        queue = target
        if (c.mediaItemCount == 0) {
            if (target.isNotEmpty()) {
                c.setMediaItems(target.map { it.toMediaItem() })
                c.prepare()
            }
        } else {
            reconcile(c, target)
            val cur = _current.value
            if (cur != null && target.none { it.id == cur.id }) {
                if (c.isPlaying) updateCurrent() else _current.value = null
            }
        }

        // first time after opening the app: show the last played song, from the start
        if (!restored) {
            restored = true
            if (!c.isPlaying) {
                val idx = target.indexOfFirst { it.id == lastSongId }
                if (idx >= 0 && idx < c.mediaItemCount) {
                    c.seekTo(idx, 0L)
                    _current.value = target[idx]
                }
            }
        }

        _isPlaying.value = c.isPlaying
        if (c.isPlaying) startProgress()
        if (c.playWhenReady || c.currentPosition > 0) updateCurrent()
        refreshProgress()
    }

    fun loadSongsFromDevice() {
        val s = settings.value ?: return
        if (removedIds.value == null) return
        viewModelScope.launch {
            if (!restored) lastSongId = settingsStore.lastSongId.first()

            val folder = if (s.folderOnly) s.folderId else null
            val result = withContext(Dispatchers.IO) {
                val all = loadSongs(getApplication())
                val ids = folder?.let { folderSongIds(getApplication(), it) }
                all to (if (ids != null) all.filter { it.id in ids } else all)
            }
            allDevice = result.first
            folderList = result.second
            loaded = true
            updateVisibleLists()

            val key = folder ?: ""
            val changed = appliedKey != null && appliedKey != key
            appliedKey = key
            syncWithController(replace = changed)
        }
    }

    fun setFolderOnly(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setFolderOnly(enabled) }
    }

    fun onFolderPicked(uri: Uri) {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val path = docId.substringAfter(':', "").trim('/')
        val name = if (path.isEmpty()) "Storage root" else path.substringAfterLast('/')
        viewModelScope.launch { settingsStore.setFolder(docId, name) }
    }

    fun removeSong(song: Song) {
        viewModelScope.launch { settingsStore.removeSong(song.id) }
    }

    fun restoreSong(song: Song) {
        viewModelScope.launch { settingsStore.restoreSong(song.id) }
    }

    // list = the songs shown on screen (all songs, or only favorites)
    fun play(song: Song, list: List<Song> = _songs.value) {
        val c = controller ?: return
        val index = list.indexOfFirst { it.id == song.id }
        if (index < 0) return
        if (list.map { it.id } != queue.map { it.id }) {
            queue = list
            c.setMediaItems(list.map { it.toMediaItem() }, index, 0L)
            c.prepare()
        } else {
            c.seekTo(index, 0L)
        }
        _current.value = song
        c.play()
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            if (_current.value == null) {
                _songs.value.firstOrNull()?.let { play(it) }
            } else {
                c.play()
            }
        }
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
        _position.value = ms
    }

    fun next() {
        controller?.let {
            it.seekToNext()
            it.play()
        }
    }

    fun previous() {
        controller?.let {
            it.seekToPrevious()
            it.play()
        }
    }

    fun toggleFavorite(song: Song) {
        viewModelScope.launch { favoritesStore.toggle(song.id) }
    }

    override fun onCleared() {
        MediaController.releaseFuture(controllerFuture)
        super.onCleared()
    }
}