package com.gita.player

import android.app.Application
import android.content.ComponentName
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val favoritesStore = FavoritesStore(app)
    private var controller: MediaController? = null
    private var progressJob: Job? = null

    private val controllerFuture = MediaController.Builder(
        app,
        SessionToken(app, ComponentName(app, PlaybackService::class.java))
    ).buildAsync()

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs

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
                    updateCurrent()
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

    private fun syncWithController() {
        val c = controller ?: return
        val list = _songs.value
        if (list.isEmpty()) return
        if (c.mediaItemCount == 0) {
            c.setMediaItems(list.map { it.toMediaItem() })
            c.prepare()
        }
        _isPlaying.value = c.isPlaying
        if (c.isPlaying) startProgress()
        if (c.playWhenReady || c.currentPosition > 0) updateCurrent()
        refreshProgress()
    }

    fun loadSongsFromDevice() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { loadSongs(getApplication()) }
            _songs.value = list
            syncWithController()
        }
    }

    fun play(song: Song) {
        val c = controller ?: return
        val index = _songs.value.indexOf(song)
        if (index < 0) return
        _current.value = song
        c.seekTo(index, 0)
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