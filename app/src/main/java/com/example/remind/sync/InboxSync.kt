package com.example.remind.sync

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.remind.RemindApp
import com.example.remind.data.AttachmentType
import com.example.remind.data.DraftAttachment
import com.example.remind.tr
import com.example.remind.ui.RichNotes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime

/** Where the inbox lives and the key that opens it. Both are typed in by the user in Settings. */
data class SyncConfig(val url: String, val key: String) {
    val isComplete get() = url.isNotBlank() && key.isNotBlank()
}

data class SyncResult(
    /** Notes created on this phone. */
    val added: Int,
    /** Entries that were already here (an earlier ack never reached the server). */
    val alreadyHad: Int,
    /** Entries that could not be saved; they stay on the server for the next sync. */
    val failed: Int,
    /** Notes were saved but the server was not told; the next sync clears them. */
    val ackFailed: Boolean,
)

sealed interface SyncState {
    data object Idle : SyncState
    data class Running(val done: Int, val total: Int) : SyncState
    data class Done(val result: SyncResult) : SyncState
    data class Failed(val message: String) : SyncState
}

sealed interface TestState {
    data object Idle : TestState
    data object Testing : TestState
    data class Ok(val vaultName: String, val pending: Int) : TestState
    data class Failed(val message: String) : TestState
}

/**
 * Pulls notes that were added to the inbox from elsewhere (the browser extension) into this phone.
 *
 * Runs only when the user taps Sync. Order of operations, so nothing is ever lost:
 * 1. list the waiting entries;
 * 2. for each, download its files and save note + attachments in one transaction;
 * 3. only then tell the server (ack), which deletes them there.
 * An entry that fails stays on the server for the next tap. An entry already saved here
 * (same remoteId) is not saved twice, just acked again.
 */
object InboxSync {
    private const val PREFS = "sync"
    private const val KEY_URL = "inbox_url"
    private const val KEY_KEY = "inbox_key"
    private const val KEY_LAST_SYNC = "last_sync_at"

    private const val HEADER_KEY = "X-Yaad-Key"
    private const val PAGE_SIZE = 100
    private const val ACK_CHUNK = 200
    private val EXTENSION = Regex("^[a-z0-9]{1,10}$")

    var state by mutableStateOf<SyncState>(SyncState.Idle)
        private set
    var testState by mutableStateOf<TestState>(TestState.Idle)
        private set
    var lastSyncAt by mutableStateOf(0L)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var testJob: Job? = null

    val isRunning get() = state is SyncState.Running

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun config(context: Context): SyncConfig {
        val p = prefs(context)
        if (lastSyncAt == 0L) lastSyncAt = p.getLong(KEY_LAST_SYNC, 0L)
        return SyncConfig(p.getString(KEY_URL, "").orEmpty(), p.getString(KEY_KEY, "").orEmpty())
    }

    fun saveConfig(context: Context, url: String, key: String) {
        prefs(context).edit()
            .putString(KEY_URL, normalizeUrl(url))
            .putString(KEY_KEY, key.trim())
            .apply()
        testState = TestState.Idle
    }

    /** `example.com/api/yaad/` -> `https://example.com/api/yaad`. */
    fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }

    /** Checks a URL + key pair without saving anything. */
    fun test(url: String, key: String) {
        if (testJob?.isActive == true) return
        testState = TestState.Testing
        testJob = scope.launch {
            testState = runCatching {
                val json = getJson("${normalizeUrl(url)}/ping/", key.trim())
                TestState.Ok(json.optString("name"), json.optInt("pending"))
            }.getOrElse { TestState.Failed(describe(it)) }
        }
    }

    fun start(app: RemindApp) {
        if (job?.isActive == true) return
        val config = config(app)
        if (!config.isComplete) {
            state = SyncState.Failed(tr("Pehle Settings me server URL aur key daalo", "Add the server URL and key in Settings first"))
            toast(app, (state as SyncState.Failed).message)
            return
        }
        state = SyncState.Running(0, 0)
        job = scope.launch {
            state = runCatching { sync(app, config) }.fold(
                onSuccess = { result ->
                    val now = System.currentTimeMillis()
                    prefs(app).edit().putLong(KEY_LAST_SYNC, now).apply()
                    lastSyncAt = now
                    SyncState.Done(result)
                },
                onFailure = { SyncState.Failed(describe(it)) },
            )
            toast(app, message(state))
        }
    }

    fun message(state: SyncState): String = when (state) {
        is SyncState.Done -> {
            val r = state.result
            buildString {
                append(
                    if (r.added == 0) tr("Koi naya note nahi", "No new notes")
                    else tr("${r.added} naye note aaye", "${r.added} new notes")
                )
                if (r.failed > 0) append(tr(" Â· ${r.failed} nahi aa paaye, phir se sync karo", " Â· ${r.failed} failed, sync again"))
                if (r.ackFailed) append(tr(" Â· server ko confirm nahi hua, agle sync me ho jayega", " Â· server not confirmed yet, the next sync will finish it"))
            }
        }
        is SyncState.Failed -> tr("Sync nahi hua: ${state.message}", "Sync failed: ${state.message}")
        is SyncState.Running -> tr("Sync ho raha haiâ€¦", "Syncingâ€¦")
        SyncState.Idle -> ""
    }

    private fun toast(context: Context, text: String) {
        if (text.isEmpty()) return
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }

    // --- the sync itself ------------------------------------------------------------------------

    private data class RemoteAttachment(val id: String, val kind: String, val extension: String, val name: String)

    private data class RemoteEntry(
        val id: String,
        val title: String,
        val body: String,
        val subject: String,
        val keywords: List<String>,
        val sourceUrl: String,
        val createdAt: Long,
        val attachments: List<RemoteAttachment>,
    )

    private suspend fun sync(app: RemindApp, config: SyncConfig): SyncResult {
        val repo = app.repository
        val entries = listAll(config)
        val existing = repo.existingRemoteIds(entries.map { it.id })
        val toAck = mutableListOf<String>()
        var added = 0
        var alreadyHad = 0
        var failed = 0

        entries.forEachIndexed { index, entry ->
            state = SyncState.Running(index, entries.size)
            if (entry.id in existing) {
                alreadyHad++
                toAck += entry.id
                return@forEachIndexed
            }
            val saved = mutableListOf<String>()
            try {
                val files = entry.attachments.map { attachment ->
                    downloadInto(app, config, attachment).also { saved += it.fileName }
                }
                val inserted = repo.insertSyncedNote(
                    remoteId = entry.id,
                    title = titleFor(entry),
                    body = bodyFor(entry),
                    subject = entry.subject,
                    keywords = entry.keywords,
                    createdAt = entry.createdAt,
                    attachments = files,
                )
                if (inserted) added++ else {
                    saved.forEach(repo.store::delete)
                    alreadyHad++
                }
                toAck += entry.id
            } catch (e: Exception) {
                if (e is AuthError) throw e
                saved.forEach(repo.store::delete)
                failed++
            }
        }
        state = SyncState.Running(entries.size, entries.size)

        val ackFailed = runCatching { toAck.chunked(ACK_CHUNK).forEach { ack(config, it) } }.isFailure
        return SyncResult(added, alreadyHad, failed, ackFailed)
    }

    private fun listAll(config: SyncConfig): List<RemoteEntry> {
        val all = mutableListOf<RemoteEntry>()
        var offset = 0
        while (true) {
            val page = getJson("${config.url}/entries/?limit=$PAGE_SIZE&offset=$offset", config.key)
            val results = page.getJSONArray("results")
            for (i in 0 until results.length()) all += parseEntry(results.getJSONObject(i))
            offset += results.length()
            if (results.length() == 0 || page.isNull("next") || offset >= page.optInt("count")) break
        }
        return all
    }

    private fun parseEntry(o: JSONObject): RemoteEntry {
        val attachments = o.optJSONArray("attachments") ?: JSONArray()
        val keywords = o.optJSONArray("keywords") ?: JSONArray()
        return RemoteEntry(
            id = o.getString("id"),
            title = o.optString("title").trim(),
            body = o.optString("body"),
            subject = o.optString("subject").trim(),
            keywords = (0 until keywords.length()).map { keywords.getString(it).trim() }.filter { it.isNotEmpty() },
            sourceUrl = o.optString("source_url").trim(),
            createdAt = runCatching { OffsetDateTime.parse(o.getString("created_at")).toInstant().toEpochMilli() }
                .getOrDefault(System.currentTimeMillis()),
            attachments = (0 until attachments.length()).map {
                val a = attachments.getJSONObject(it)
                RemoteAttachment(a.getString("id"), a.getString("kind"), a.optString("extension"), a.optString("name"))
            },
        )
    }

    private fun titleFor(entry: RemoteEntry): String = entry.title.ifEmpty {
        RichNotes.plain(entry.body).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(80)
            ?: entry.attachments.firstOrNull { it.kind == "document" && it.name.isNotBlank() }
                ?.name?.substringBeforeLast('.')?.take(80)
            ?: entry.subject.ifEmpty { tr("Web se note", "Web clip") }
    }

    /** The extension sends rich-text HTML; the source link goes on as its own paragraph. */
    private fun bodyFor(entry: RemoteEntry): String {
        val body = entry.body
        if (entry.sourceUrl.isEmpty() || body.contains(entry.sourceUrl)) return body
        if (RichNotes.isHtml(body)) return body + "<p>${escapeHtml(entry.sourceUrl)}</p>"
        return if (body.isBlank()) entry.sourceUrl else "${body.trimEnd()}\n\n${entry.sourceUrl}"
    }

    private fun escapeHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun typeFor(kind: String) = when (kind) {
        "handwriting" -> AttachmentType.HANDWRITING
        "audio" -> AttachmentType.AUDIO
        "video" -> AttachmentType.VIDEO
        "photo" -> AttachmentType.PHOTO
        // "document", or a kind a newer server adds: open it in another app.
        else -> AttachmentType.DOCUMENT
    }

    /** Downloads one attachment into the app's attachment store. Photos get the same compression as gallery picks. */
    private fun downloadInto(app: RemindApp, config: SyncConfig, attachment: RemoteAttachment): DraftAttachment {
        val store = app.repository.store
        val type = typeFor(attachment.kind)
        val ext = attachment.extension.lowercase().takeIf { EXTENSION.matches(it) } ?: "bin"
        val tmpDir = File(app.cacheDir, "inbox").apply { mkdirs() }
        val tmp = File(tmpDir, "${attachment.id}.$ext")
        try {
            download("${config.url}/attachments/${attachment.id}/", config.key, tmp)
            if (type == AttachmentType.PHOTO) {
                store.importImage(Uri.fromFile(tmp))?.let { return DraftAttachment(it, type) }
            }
            val target = if (type == AttachmentType.DOCUMENT) {
                val name = attachment.name.ifBlank { "document.$ext" }
                store.newNamedFile(if (name.contains('.')) name else "$name.$ext")
            } else {
                store.newFile(ext)
            }
            tmp.copyTo(target, overwrite = true)
            return DraftAttachment(target.name, type)
        } finally {
            tmp.delete()
        }
    }

    private fun ack(config: SyncConfig, ids: List<String>) {
        val conn = open("${config.url}/entries/ack/", config.key).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(JSONObject().put("ids", JSONArray(ids)).toString().toByteArray()) }
            check(conn)
        } finally {
            conn.disconnect()
        }
    }

    // --- http -------------------------------------------------------------------------------------

    private class AuthError : IOException("401")
    private class HttpError(val code: Int) : IOException("HTTP $code")

    private fun open(url: String, key: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 60_000
        setRequestProperty(HEADER_KEY, key)
        setRequestProperty("Accept", "application/json")
        setRequestProperty("User-Agent", "Yaad-Android")
    }

    private fun check(conn: HttpURLConnection) {
        val code = conn.responseCode
        if (code == HttpURLConnection.HTTP_UNAUTHORIZED) throw AuthError()
        if (code !in 200..299) throw HttpError(code)
    }

    private fun getJson(url: String, key: String): JSONObject {
        val conn = open(url, key)
        try {
            check(conn)
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private fun download(url: String, key: String, target: File) {
        val conn = open(url, key)
        try {
            check(conn)
            val expected = conn.contentLengthLong
            val written = conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it, 64 * 1024) } }
            if (expected > 0 && written != expected) throw IOException("Download incomplete")
        } finally {
            conn.disconnect()
        }
    }

    private fun describe(e: Throwable): String = when (e) {
        is AuthError -> tr("Key galat hai", "Wrong key")
        is HttpError -> if (e.code == 404) tr("URL galat hai (404)", "Wrong URL (404)") else "HTTP ${e.code}"
        is java.net.UnknownHostException -> tr("Server nahi mila, URL ya internet check karo", "Server not found, check the URL or internet")
        is java.net.MalformedURLException -> tr("URL sahi nahi hai", "Invalid URL")
        is java.io.IOException -> if (e.message?.contains("CLEARTEXT", ignoreCase = true) == true)
            tr("Sirf https URL chalega", "Only https URLs are allowed") else (e.message ?: e.javaClass.simpleName)
        else -> e.message ?: e.javaClass.simpleName
    }
}
