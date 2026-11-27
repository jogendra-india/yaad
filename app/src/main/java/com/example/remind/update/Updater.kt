package com.example.remind.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.example.remind.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A published GitHub release with an APK attached. */
data class Release(
    val tag: String,
    val versionName: String,
    /** CI build number, taken from the tag (`v1.8-10` → 10); matches the APK's versionCode. */
    val versionCode: Int,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    data class Ready(val release: Release, val apk: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Self-update from the app's GitHub releases. Releases are signed with the same key,
 * so Android installs the downloaded APK over the current one and keeps all data.
 *
 * State lives here (not in a screen) so a download keeps going if the user leaves Settings.
 */
object Updater {
    private const val LATEST_URL = "https://api.github.com/repos/jogendra-india/yaad/releases/latest"

    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    /** Set while the user is away granting "install unknown apps", so we install on return. */
    var installAfterPermission = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun check() {
        if (job?.isActive == true) return
        state = UpdateState.Checking
        job = scope.launch {
            state = runCatching { fetchLatest() }.fold(
                onSuccess = { release ->
                    if (release != null && release.versionCode > BuildConfig.VERSION_CODE) UpdateState.Available(release)
                    else UpdateState.UpToDate
                },
                onFailure = { UpdateState.Failed(it.message ?: it.javaClass.simpleName) },
            )
        }
    }

    fun download(context: Context, release: Release) {
        if (job?.isActive == true) return
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        state = UpdateState.Downloading(release, 0f)
        job = scope.launch {
            state = runCatching {
                dir.listFiles()?.forEach { it.delete() }
                val target = File(dir, "yaad-${release.versionName}-${release.versionCode}.apk")
                fetchApk(release, target)
                UpdateState.Ready(release, target)
            }.getOrElse { UpdateState.Failed(it.message ?: it.javaClass.simpleName) }
        }
    }

    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    /** Opens the system "install unknown apps" toggle for Yaad. */
    fun requestInstallPermission(context: Context) {
        installAfterPermission = true
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Hands the APK to the system installer, which asks the user to confirm. */
    fun install(context: Context, apk: File) {
        installAfterPermission = false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun fetchLatest(): Release? {
        val conn = open(LATEST_URL).apply { setRequestProperty("Accept", "application/vnd.github+json") }
        try {
            if (conn.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("GitHub: HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.getString("tag_name")
            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") } ?: return null
            return Release(
                tag = tag,
                versionName = tag.removePrefix("v").substringBeforeLast('-'),
                versionCode = tag.substringAfterLast('-').toIntOrNull() ?: return null,
                notes = json.optString("body"),
                apkUrl = apk.getString("browser_download_url"),
                apkSize = apk.optLong("size", -1L),
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchApk(release: Release, target: File) {
        val conn = open(release.apkUrl)
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Download: HTTP ${conn.responseCode}")
            val total = release.apkSize.takeIf { it > 0 } ?: conn.contentLengthLong
            var done = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) state = UpdateState.Downloading(release, (done.toFloat() / total).coerceAtMost(1f))
                    }
                }
            }
            if (total > 0 && done != total) {
                target.delete()
                throw IOException("Download incomplete")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Yaad-Android")
    }
}
