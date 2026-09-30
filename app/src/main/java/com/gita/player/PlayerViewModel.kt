package com.gita.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val favoritesStore = FavoritesStore(app)
    private val player = ExoPlayer.Builder(app).build()

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs

    private val _current = MutableStateFlow<Song?>(null)
    val current: StateFlow<Song?> = _current

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    val favorites: StateFlow<Set<Long>> = favoritesStore.favoriteIds
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                _current.value = _songs.value.getOrNull(player.currentMediaItemIndex)
            }
        })
    }

    fun loadSongsFromDevice() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { loadSongs(getApplication()) }
            _songs.value = list
            player.setMediaItems(list.map { MediaItem.fromUri(it.uri) })
            player.prepare()
        }
    }

    fun play(song: Song) {
        val index = _songs.value.indexOf(song)
        if (index < 0) return
        _current.value = song
        player.seekTo(index, 0)
        player.play()
    }

    fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            if (_current.value == null) {
                _songs.value.firstOrNull()?.let { play(it) }
            } else {
                player.play()
            }
        }
    }

    fun toggleFavorite(song: Song) {
        viewModelScope.launch { favoritesStore.toggle(song.id) }
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}