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

    private val backupStore = BackupStore(app)
    private var backupStarted = false
    private var queuedHash: String? = null

    val backup: StateFlow<BackupSettings?> = backupStore.backup
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private var controller: MediaController? = null
    private var progressJob: Job? = null
    private var appliedKey: String? = null
    private var loaded = false
    private var restored = false
    private var lastSongId: Long? = null
    private var lastSource = Source.ALL
    private var allDevice: List<Song> = emptyList()
    private var folderList: List<Song> = emptyList()
    private var dateAdded: Map<Long, Long> = emptyMap()
    private var favSet: Set<Long> = emptySet()

    // what is really loaded in the player (in play order) and which list it came from
    private var queue: List<Song> = emptyList()

    private val _playSource = MutableStateFlow(Source.ALL)
    val playSource: StateFlow<Source> = _playSource

    // which list the player's queue came from
    private var queueSource: Source
        get() = _playSource.value
        set(value) {
            _playSource.value = value
        }

    // loop = repeat the current song (turns off on next/previous or when another song is picked)
    private val _loop = MutableStateFlow(false)
    val loop: StateFlow<Boolean> = _loop

    private val controllerFuture = MediaController.Builder(
        app,
        SessionToken(app, ComponentName(app, PlaybackService::class.java))
    ).buildAsync()

    // true once the app has worked out what to show after opening
    private val _restoreDone = MutableStateFlow(false)
    val restoreDone: StateFlow<Boolean> = _restoreDone

    // the songs shown on screen, always in the chosen sort order
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

    val listPrefs: StateFlow<ListPrefs?> = settingsStore.prefs
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val shuffleOn: Boolean
        get() = listPrefs.value?.shuffle == true

    init {
        controllerFuture.addListener({
            val c = controllerFuture.get()
            controller = c
            if (c.repeatMode != Player.REPEAT_MODE_ONE) c.repeatMode = Player.REPEAT_MODE_ALL
            _loop.value = c.repeatMode == Player.REPEAT_MODE_ONE
            c.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _isPlaying.value = isPlaying
                    if (isPlaying) startProgress() else stopProgress()
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED ||
                        c.isPlaying || c.playWhenReady
                    ) {
                        updateCurrent()
                    }
                    refreshProgress()
                }

                override fun onRepeatModeChanged(repeatMode: Int) {
                    _loop.value = repeatMode == Player.REPEAT_MODE_ONE
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

        // a song was removed or restored
        viewModelScope.launch {
            removedIds.collect {
                if (loaded) {
                    updateVisibleLists()
                    syncWithController()
                }
            }
        }

        // sort or shuffle changed
        viewModelScope.launch {
            var prev: ListPrefs? = null
            listPrefs.collect { p ->
                val old = prev
                prev = p
                if (loaded && p != null) {
                    updateVisibleLists()
                    syncWithController(reshuffle = old != null && old.shuffle != p.shuffle)
                }
            }
        }

        viewModelScope.launch {
            favorites.collect { favSet = it }
        }

        // remember the last played song and the list it was played from
        viewModelScope.launch {
            _current.collect { song ->
                if (song != null) {
                    lastSongId = song.id
                    settingsStore.setLast(song.id, queueSource.name)
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

    // ---------- sorting and lists ----------

    private fun sortSongs(list: List<Song>, prefs: ListPrefs): List<Song> {
        val byName = compareBy<Song, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
        val comparator: Comparator<Song> = when (prefs.sortBy) {
            SortBy.NAME -> byName
            SortBy.DATE -> compareBy<Song> { dateAdded[it.id] ?: 0L }.then(byName)
        }
        val sorted = list.sortedWith(comparator)
        return if (prefs.ascending) sorted else sorted.reversed()
    }

    private fun updateVisibleLists() {
        val removed = removedIds.value ?: emptySet()
        val prefs = listPrefs.value ?: ListPrefs()
        _songs.value = sortSongs(folderList.filter { it.id !in removed }, prefs)
        _removedSongs.value = allDevice.filter { it.id in removed }
        maybeBackup()
    }

    // the sorted songs of one list (all songs, or only favorites)
    private fun baseList(source: Source): List<Song> =
        if (source == Source.FAV) _songs.value.filter { it.id in favSet } else _songs.value

    // play order: the sorted list, or (shuffle on) the start song first and the rest random
    private fun buildOrder(base: List<Song>, startId: Long?): List<Song> {
        if (!shuffleOn) return base
        val start = base.firstOrNull { it.id == startId }
        val rest = base.filter { it.id != startId }.shuffled()
        return if (start != null) listOf(start) + rest else rest
    }

    private fun computeTarget(reshuffle: Boolean): List<Song> {
        val visible = _songs.value
        val base = baseList(queueSource).ifEmpty { visible }
        if (!shuffleOn) return base
        val ids = visible.map { it.id }.toSet()
        val kept = queue.filter { it.id in ids }
        if (reshuffle || kept.isEmpty()) {
            val curId = controller?.currentMediaItem?.mediaId?.toLongOrNull()
            return buildOrder(base, curId)
        }
        return kept
    }

    private fun playlistMatches(c: MediaController, list: List<Song>): Boolean =
        c.mediaItemCount == list.size &&
                list.indices.all { c.getMediaItemAt(it).mediaId == list[it].id.toString() }

    // makes the player's queue equal to the wanted list while the current song keeps playing
    private fun applyQueue(c: MediaController, wanted: List<Song>) {
        var target = wanted
        val curId = if (c.mediaItemCount > 0) c.currentMediaItem?.mediaId else null

        // keep the playing song even if it is not in the wanted list (e.g. un-favorited)
        if (curId != null && target.none { it.id.toString() == curId }) {
            val keep = _songs.value.firstOrNull { it.id.toString() == curId }
            if (keep != null) target = listOf(keep) + target
        }

        if (playlistMatches(c, target)) {
            queue = target
            return
        }

        if (c.mediaItemCount == 0 || curId == null) {
            if (target.isNotEmpty()) {
                c.setMediaItems(target.map { it.toMediaItem() })
                c.prepare()
            }
            queue = target
            return
        }

        val idx = target.indexOfFirst { it.id.toString() == curId }
        if (idx < 0) {
            // the playing song is gone: continue with the next one that is still available
            val oldPos = queue.indexOfFirst { it.id.toString() == curId }
            val next = if (oldPos >= 0) {
                queue.drop(oldPos + 1).firstOrNull { s -> target.any { it.id == s.id } }
            } else null
            val start = next?.let { n -> target.indexOfFirst { it.id == n.id } } ?: 0
            if (target.isNotEmpty()) {
                c.setMediaItems(target.map { it.toMediaItem() }, start, 0L)
                c.prepare()
            } else {
                c.clearMediaItems()
            }
            queue = target
            return
        }

        // keep only the playing item, then put the rest around it in the wanted order
        val curIndex = c.currentMediaItemIndex
        if (curIndex < c.mediaItemCount - 1) c.removeMediaItems(curIndex + 1, c.mediaItemCount)
        if (curIndex > 0) c.removeMediaItems(0, curIndex)
        if (idx > 0) {
            c.addMediaItems(0, target.subList(0, idx).map { it.toMediaItem() })
        }
        if (idx < target.size - 1) {
            c.addMediaItems(
                c.mediaItemCount,
                target.subList(idx + 1, target.size).map { it.toMediaItem() }
            )
        }
        queue = target
    }

    private fun syncWithController(replace: Boolean = false, reshuffle: Boolean = false) {
        val c = controller ?: return
        if (!loaded) return

        if (replace) {
            c.playWhenReady = false
            c.stop()
            c.clearMediaItems()
            queue = emptyList()
            queueSource = Source.ALL
            _current.value = null
            _position.value = 0L
            _duration.value = 0L
        }

        // first time after opening the app
        if (!restored) {
            restored = true
            if (c.isPlaying) {
                // music is already playing: adopt that queue
                val byId = _songs.value.associateBy { it.id.toString() }
                queue = (0 until c.mediaItemCount).mapNotNull { byId[c.getMediaItemAt(it).mediaId] }
                queueSource = lastSource
            } else {
                // show the last played song, from the start, in the list it was played from
                val last = _songs.value.firstOrNull { it.id == lastSongId }
                if (last != null) {
                    var source = lastSource
                    var base = baseList(source)
                    if (base.none { it.id == last.id }) {
                        source = Source.ALL
                        base = baseList(Source.ALL)
                    }
                    queueSource = source
                    val order = buildOrder(base, last.id)
                    val idx = order.indexOfFirst { it.id == last.id }
                    queue = order
                    c.setMediaItems(order.map { it.toMediaItem() }, idx, 0L)
                    c.prepare()
                    _current.value = last
                    _isPlaying.value = false
                    refreshProgress()
                    _restoreDone.value = true
                    return
                }
            }
        }

        _restoreDone.value = true
        applyQueue(c, computeTarget(reshuffle))

        val cur = _current.value
        if (cur != null && queue.none { it.id == cur.id }) {
            if (c.isPlaying) updateCurrent() else _current.value = null
        }
        _isPlaying.value = c.isPlaying
        if (c.isPlaying) startProgress()
        if (c.playWhenReady || c.currentPosition > 0) updateCurrent()
        refreshProgress()
    }

    fun loadSongsFromDevice() {
        val s = settings.value ?: return
        if (removedIds.value == null || listPrefs.value == null) return
        viewModelScope.launch {
            if (!restored) {
                lastSongId = settingsStore.lastSongId.first()
                lastSource = settingsStore.lastSource.first()
                    ?.let { name -> runCatching { Source.valueOf(name) }.getOrNull() }
                    ?: Source.ALL
            }
            favSet = favoritesStore.favoriteIds.first()

            val folder = if (s.folderOnly) s.folderId else null
            val result = withContext(Dispatchers.IO) {
                val all = loadSongs(getApplication())
                val ids = folder?.let { folderSongIds(getApplication(), it) }
                Triple(
                    all,
                    if (ids != null) all.filter { it.id in ids } else all,
                    songDateAdded(getApplication())
                )
            }
            allDevice = result.first
            folderList = result.second
            dateAdded = result.third
            loaded = true
            updateVisibleLists()

            val key = folder ?: ""
            val changed = appliedKey != null && appliedKey != key
            appliedKey = key
            syncWithController(replace = changed)
        }
    }

    // ---------- song-name backup (Google Sheets) ----------

    // when the names of the All tab changed, queue a sync for the next time Wi-Fi is available
    private fun maybeBackup() {
        viewModelScope.launch {
            val b = backupStore.backup.first()
            if (!b.on || b.requestUrl() == null) return@launch

            val app = getApplication<Application>()
            if (!backupStarted) {
                backupStarted = true
                BackupScheduler.start(app)
            }

            val names = BackupNames.names(_songs.value)
            if (names.isEmpty()) return@launch
            val hash = BackupNames.fingerprint(names)
            if (hash != b.lastHash && hash != queuedHash) {
                queuedHash = hash
                backupStore.setPending(true)
                BackupScheduler.syncSoon(app)
            }
        }
    }

    fun setBackupOn(on: Boolean) {
        viewModelScope.launch {
            backupStore.setOn(on)
            if (!on) {
                BackupScheduler.stop(getApplication<Application>())
                backupStarted = false
                queuedHash = null
            }
        }
    }

    // both the URL and the token are needed; if either is empty the option turns off
    fun saveBackup(url: String, token: String) {
        viewModelScope.launch {
            val u = url.trim()
            val t = token.trim()
            if (u.isEmpty() || t.isEmpty()) {
                backupStore.setOn(false)
                BackupScheduler.stop(getApplication<Application>())
                backupStarted = false
                queuedHash = null
                return@launch
            }
            backupStore.setCredentials(u, t)
            backupStarted = false
            queuedHash = null
            maybeBackup()
        }
    }

    // called when Settings closes: the option cannot stay on without a URL
    fun validateBackup() {
        viewModelScope.launch {
            val b = backupStore.backup.first()
            if (b.on && b.requestUrl() == null) setBackupOn(false)
        }
    }

    // ---------- settings actions ----------

    fun setFolderOnly(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setFolderOnly(enabled) }
    }

    fun onFolderPicked(uri: Uri) {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val path = docId.substringAfter(':', "").trim('/')
        val name = if (path.isEmpty()) "Storage root" else path.substringAfterLast('/')
        viewModelScope.launch { settingsStore.setFolder(docId, name) }
    }

    fun setSortBy(value: SortBy) {
        viewModelScope.launch { settingsStore.setSortBy(value) }
    }

    fun setAscending(value: Boolean) {
        viewModelScope.launch { settingsStore.setAscending(value) }
    }

    fun setShuffle(value: Boolean) {
        viewModelScope.launch { settingsStore.setShuffle(value) }
    }

    fun setLandscape(value: Boolean) {
        viewModelScope.launch { settingsStore.setLandscape(value) }
    }

    fun removeSong(song: Song) {
        viewModelScope.launch { settingsStore.removeSong(song.id) }
    }

    fun restoreSong(song: Song) {
        viewModelScope.launch { settingsStore.restoreSong(song.id) }
    }

    // ---------- playback ----------

    // list = the songs shown on screen, source = All or Favorites
    fun play(song: Song, list: List<Song> = _songs.value, source: Source = Source.ALL) {
        val c = controller ?: return

        if (list.none { it.id == song.id }) return
        if (c.repeatMode == Player.REPEAT_MODE_ONE) c.repeatMode = Player.REPEAT_MODE_ALL

        val order = buildOrder(list, song.id)
        val index = order.indexOfFirst { it.id == song.id }
        queueSource = source
        queue = order
        if (!playlistMatches(c, order)) {
            c.setMediaItems(order.map { it.toMediaItem() }, index, 0L)
            c.prepare()
        } else {
            c.seekTo(index, 0L)
        }
        _current.value = song
        c.play()
        viewModelScope.launch { settingsStore.setLast(song.id, source.name) }
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
            if (it.repeatMode == Player.REPEAT_MODE_ONE) it.repeatMode = Player.REPEAT_MODE_ALL
            it.seekToNext()
            it.play()
        }
    }

    fun previous() {
        controller?.let {
            if (it.repeatMode == Player.REPEAT_MODE_ONE) it.repeatMode = Player.REPEAT_MODE_ALL
            it.seekToPrevious()
            it.play()
        }
    }

    fun toggleLoop() {
        val c = controller ?: return
        c.repeatMode =
            if (c.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_ALL
            else Player.REPEAT_MODE_ONE
    }

    fun toggleFavorite(song: Song) {
        viewModelScope.launch { favoritesStore.toggle(song.id) }
    }

    override fun onCleared() {
        MediaController.releaseFuture(controllerFuture)
        super.onCleared()
    }
}