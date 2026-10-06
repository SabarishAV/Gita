package com.gita.player

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

// ---------------------------------------------------------------------------
// Saved backup settings
// ---------------------------------------------------------------------------

data class BackupSettings(
    val on: Boolean = false,
    val url: String? = null,
    val token: String? = null,
    val pending: Boolean = false,
    val lastSync: Long = 0L,
    val lastHash: String? = null
)

// the full address to call (URL + token), or null if either one is missing
fun BackupSettings.requestUrl(): String? {
    val u = url?.trim().orEmpty()
    val t = token?.trim().orEmpty()
    if (u.isEmpty() || t.isEmpty()) return null
    val separator = if (u.contains('?')) '&' else '?'
    return u + separator + "token=" + URLEncoder.encode(t, "UTF-8")
}

private val Context.backupDataStore by preferencesDataStore("backup")

class BackupStore(private val context: Context) {
    private val onKey = booleanPreferencesKey("on")
    private val urlKey = stringPreferencesKey("url")
    private val tokenKey = stringPreferencesKey("token")
    private val pendingKey = booleanPreferencesKey("pending")
    private val syncKey = longPreferencesKey("last_sync")
    private val hashKey = stringPreferencesKey("last_hash")

    val backup: Flow<BackupSettings> = context.backupDataStore.data.map { p ->
        BackupSettings(
            on = p[onKey] ?: false,
            url = p[urlKey],
            token = p[tokenKey],
            pending = p[pendingKey] ?: false,
            lastSync = p[syncKey] ?: 0L,
            lastHash = p[hashKey]
        )
    }

    // turning it off forgets the URL, the token and everything else, like the folder option
    suspend fun setOn(on: Boolean) {
        context.backupDataStore.edit { p ->
            p[onKey] = on
            if (!on) {
                p.remove(urlKey)
                p.remove(tokenKey)
                p.remove(pendingKey)
                p.remove(syncKey)
                p.remove(hashKey)
            }
        }
    }

    suspend fun setCredentials(url: String, token: String) {
        context.backupDataStore.edit { p ->
            p[urlKey] = url
            p[tokenKey] = token
            p[pendingKey] = true
            p.remove(hashKey)
        }
    }

    suspend fun setPending(value: Boolean) {
        context.backupDataStore.edit { it[pendingKey] = value }
    }

    suspend fun markSynced(hash: String) {
        context.backupDataStore.edit { p ->
            p[hashKey] = hash
            p[syncKey] = System.currentTimeMillis()
            p[pendingKey] = false
        }
    }
}

// ---------------------------------------------------------------------------
// The names that get backed up
// ---------------------------------------------------------------------------

object BackupNames {
    fun names(songs: List<Song>): List<String> =
        songs.map { it.title.trim() }
            .filter { it.isNotEmpty() }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)

    fun fingerprint(names: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        names.forEach {
            md.update(it.toByteArray(Charsets.UTF_8))
            md.update(0.toByte())
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

// ---------------------------------------------------------------------------
// Google Apps Script web app (only GET and POST exist for web apps)
// ---------------------------------------------------------------------------

object SheetClient {
    private fun open(url: String, method: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.instanceFollowRedirects = true
        return c
    }

    // song names currently stored in the sheet, or null if the request failed
    fun fetch(url: String): List<String>? = try {
        val c = open(url, "GET")
        try {
            if (c.responseCode != 200) {
                null
            } else {
                val text = c.inputStream.bufferedReader().use { it.readText() }
                if (!text.trimStart().startsWith("[")) {
                    null
                } else {
                    val arr = JSONArray(text)
                    List(arr.length()) { arr.get(it).toString() }
                }
            }
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    // replaces everything in the sheet with the given names
    fun upload(url: String, names: List<String>): Boolean = try {
        val c = open(url, "POST")
        try {
            val body = JSONObject()
                .put("songs", JSONArray(names))
                .toString()
                .toByteArray(Charsets.UTF_8)
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body) }
            if (c.responseCode != 200) {
                false
            } else {
                val text = c.inputStream.bufferedReader().use { it.readText() }
                JSONObject(text).optBoolean("success", false)
            }
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        false
    }
}

// ---------------------------------------------------------------------------
// Scheduling: only runs on Wi-Fi, and only when something has changed
// ---------------------------------------------------------------------------

object BackupScheduler {
    private const val NOW = "gita_backup_now"
    private const val WATCH = "gita_backup_watch"
    private const val WEEKLY = "gita_backup_weekly"

    private fun wifiOnly(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()

    // sync as soon as Wi-Fi is available (remembered across restarts)
    fun syncSoon(context: Context) {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(wifiOnly())
            .setInputData(workDataOf(BackupWorker.KEY_MODE to BackupWorker.MODE_CHANGE))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
    }

    // watch the music library for changes + a weekly check; safe to call again and again
    fun start(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniqueWork(WATCH, ExistingWorkPolicy.KEEP, watchRequest())

        val weekly = PeriodicWorkRequestBuilder<BackupWorker>(7, TimeUnit.DAYS)
            .setConstraints(wifiOnly())
            .setInputData(workDataOf(BackupWorker.KEY_MODE to BackupWorker.MODE_VERIFY))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(WEEKLY, ExistingPeriodicWorkPolicy.KEEP, weekly)
    }

    // the system wakes this up only when the music library changes (no always-running service)
    private fun watchRequest(): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .addContentUriTrigger(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true)
                    .setTriggerContentUpdateDelay(1, TimeUnit.MINUTES)
                    .setTriggerContentMaxDelay(10, TimeUnit.MINUTES)
                    .build()
            )
            .setInputData(workDataOf(BackupWorker.KEY_MODE to BackupWorker.MODE_WATCH))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

    fun rearmWatch(context: Context) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WATCH, ExistingWorkPolicy.APPEND_OR_REPLACE, watchRequest())
    }

    fun stop(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(NOW)
        wm.cancelUniqueWork(WATCH)
        wm.cancelUniqueWork(WEEKLY)
    }
}

private fun isOnWifi(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

// the song names of the All tab, worked out fresh each time (so the latest state is always uploaded)
private suspend fun currentNames(context: Context): List<String>? {
    val permission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    if (ContextCompat.checkSelfPermission(context, permission) !=
        PackageManager.PERMISSION_GRANTED
    ) return null

    val store = SettingsStore(context)
    val settings = store.settings.first()
    val removed = store.removed.first()

    val all = try {
        loadSongs(context)
    } catch (e: Exception) {
        return null
    }
    val folder = if (settings.folderOnly) settings.folderId else null
    val inFolder = if (folder != null) {
        val ids = folderSongIds(context, folder)
        all.filter { it.id in ids }
    } else {
        all
    }
    return BackupNames.names(inFolder.filter { it.id !in removed })
}

private fun sameNames(remote: List<String>, local: List<String>): Boolean =
    remote.map { it.trim() }.sortedWith(String.CASE_INSENSITIVE_ORDER) ==
            local.map { it.trim() }.sortedWith(String.CASE_INSENSITIVE_ORDER)

class BackupWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val store = BackupStore(ctx)
        val b = store.backup.first()
        val target = b.requestUrl()

        // switched off (or URL / token missing): do nothing and let the watcher chain end
        if (!b.on || target == null) return Result.success()

        val mode = inputData.getString(KEY_MODE) ?: MODE_CHANGE

        // Wi-Fi only, never mobile data
        if (!isOnWifi(ctx)) return Result.retry()

        val names = withContext(Dispatchers.IO) { currentNames(ctx) }
        if (names.isNullOrEmpty()) {
            // could not read the songs: leave the saved backup untouched
            if (mode == MODE_WATCH) BackupScheduler.rearmWatch(ctx)
            return Result.success()
        }

        val hash = BackupNames.fingerprint(names)
        var touched = false
        val ok = withContext(Dispatchers.IO) {
            if (mode == MODE_VERIFY) {
                // weekly check: compare the sheet with the phone and fix it if different
                val remote = SheetClient.fetch(target)
                if (remote != null && sameNames(remote, names)) {
                    touched = true
                    true
                } else {
                    val uploaded = SheetClient.upload(target, names)
                    touched = uploaded
                    uploaded
                }
            } else if (hash == b.lastHash) {
                true // nothing changed since the last backup
            } else {
                store.setPending(true)
                val uploaded = SheetClient.upload(target, names)
                touched = uploaded
                uploaded
            }
        }

        if (!ok) return Result.retry()
        if (touched) store.markSynced(hash)
        if (mode == MODE_WATCH) BackupScheduler.rearmWatch(ctx)
        return Result.success()
    }

    companion object {
        const val KEY_MODE = "mode"
        const val MODE_CHANGE = "change"
        const val MODE_WATCH = "watch"
        const val MODE_VERIFY = "verify"
    }
}

// ---------------------------------------------------------------------------
// Settings panel
// ---------------------------------------------------------------------------

private fun statusText(b: BackupSettings): String = when {
    !b.on -> "Off"
    b.url.isNullOrBlank() || b.token.isNullOrBlank() ->
        "Enter the script URL and token, then tap Save"
    b.pending -> "Waiting for Wi-Fi to sync"
    b.lastSync > 0L -> "Last sync: " +
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(b.lastSync))
    else -> "Not synced yet"
}

// one input box; when there is an error and the box is empty, the error is shown inside it
@Composable
private fun BackupField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    error: String?,
    enabled: Boolean,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    keyboardActions: KeyboardActions
) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        text = label,
        color = SilverDim,
        fontSize = 11.sp,
        letterSpacing = 2.sp
    )
    Box(
        modifier = Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0xFF0C0C0C))
            .border(1.dp, if (error != null) Silver else SilverDark, shape)
            .padding(12.dp)
    ) {
        if (value.isEmpty()) {
            Text(
                text = error ?: placeholder,
                color = if (error != null) Silver else SilverDark,
                fontSize = 13.sp,
                maxLines = 1
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(color = Silver, fontSize = 13.sp),
            cursorBrush = SolidColor(Silver),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = keyboardActions,
            modifier = Modifier.fillMaxWidth()
        )
    }
    if (error != null && value.isNotEmpty()) {
        Text(
            text = error,
            color = Silver,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
fun BackupPanel(
    backup: BackupSettings,
    onToggle: (Boolean) -> Unit,
    onSave: (String, String) -> Unit
) {
    val on = backup.on
    var urlText by remember { mutableStateOf(backup.url ?: "") }
    var tokenText by remember { mutableStateOf(backup.token ?: "") }
    var urlError by remember { mutableStateOf<String?>(null) }
    var tokenError by remember { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val shape = RoundedCornerShape(10.dp)

    fun save() {
        val u = urlText.trim()
        val t = tokenText.trim()
        urlError = null
        tokenError = null

        // nothing given: the option simply turns off
        if (u.isEmpty() && t.isEmpty()) {
            onSave("", "")
            return
        }

        if (u.isEmpty()) {
            urlError = "Enter the script URL"
        } else if (!u.startsWith("https://script.google.com/")) {
            urlError = "Use the web app URL from your Apps Script deployment"
        }
        if (t.isEmpty()) tokenError = "Enter the token"

        when {
            // one of them is missing: the option turns off and the error stays visible
            u.isEmpty() || t.isEmpty() -> onSave("", "")
            urlError == null -> {
                onSave(u, t)
                focus.clearFocus()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Panel)
            .border(1.dp, EdgeBrush, shape)
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Back up song names",
                    color = Silver,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Text(
                    text = "Keeps a copy of your song names in Google Sheets. Syncs on Wi-Fi only.",
                    color = SilverDim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp, end = 12.dp)
                )
            }
            MetalToggle(
                checked = on,
                onCheckedChange = { turnedOn ->
                    urlError = null
                    tokenError = null
                    if (!turnedOn) {
                        urlText = ""
                        tokenText = ""
                    }
                    onToggle(turnedOn)
                }
            )
        }

        HorizontalDivider(
            color = Silver.copy(alpha = 0.15f),
            modifier = Modifier.padding(vertical = 16.dp)
        )

        Column(Modifier.alpha(if (on) 1f else 0.35f)) {
            BackupField(
                label = "SCRIPT URL",
                value = urlText,
                onValueChange = {
                    urlText = it
                    urlError = null
                },
                placeholder = "https://script.google.com/macros/s/…/exec",
                error = urlError,
                enabled = on,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
                keyboardActions = KeyboardActions(
                    onNext = { focus.moveFocus(FocusDirection.Down) }
                )
            )

            Column(Modifier.padding(top = 14.dp)) {
                BackupField(
                    label = "TOKEN",
                    value = tokenText,
                    onValueChange = {
                        tokenText = it
                        tokenError = null
                    },
                    placeholder = "Token set in your script",
                    error = tokenError,
                    enabled = on,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                    keyboardActions = KeyboardActions(onDone = { save() })
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
            ) {
                Text(
                    text = statusText(backup),
                    color = SilverDim,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f).padding(end = 12.dp)
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(width = 80.dp, height = 36.dp)
                        .metal(RoundedCornerShape(8.dp))
                        .clickable(enabled = on) { save() }
                ) {
                    Text(
                        text = "SAVE",
                        color = Color(0xFF1A1A1A),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        letterSpacing = 2.sp
                    )
                }
            }
        }
    }
}