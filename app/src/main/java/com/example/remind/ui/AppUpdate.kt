package com.example.remind.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.remind.BuildConfig
import com.example.remind.tr
import com.example.remind.update.Release
import com.example.remind.update.UpdateState
import com.example.remind.update.Updater

/** Settings block: check GitHub for a newer build, download it and hand it to the installer. */
@Composable
fun AppUpdateSection() {
    val context = LocalContext.current
    val state = Updater.state
    val current = "v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"

    // No network call until the user taps "Check".
    // Back from the "install unknown apps" screen: carry on with the install if it was allowed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val ready = Updater.state as? UpdateState.Ready
        if (Updater.installAfterPermission && ready != null && Updater.canInstall(context)) {
            Updater.install(context, ready.apk)
        }
    }

    fun install(apk: java.io.File) {
        if (Updater.canInstall(context)) Updater.install(context, apk) else Updater.requestInstallPermission(context)
    }

    val transparent = ListItemDefaults.colors(containerColor = Color.Transparent)
    when (state) {
        UpdateState.Idle, UpdateState.UpToDate -> ListItem(
            headlineContent = { Text(if (state == UpdateState.UpToDate) tr("Latest version hai", "You're up to date") else "App update") },
            supportingContent = { Text(current) },
            leadingContent = {
                Icon(
                    if (state == UpdateState.UpToDate) Icons.Default.CheckCircle else Icons.Default.SystemUpdate,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            trailingContent = { TextButton(onClick = Updater::check) { Text(tr("Update check karo", "Check for update")) } },
            colors = transparent,
        )
        UpdateState.Checking -> ListItem(
            headlineContent = { Text(tr("Update check ho raha hai…", "Checking for updates…")) },
            supportingContent = { Text(current) },
            leadingContent = { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) },
            colors = transparent,
        )
        is UpdateState.Available -> UpdateCard(
            release = state.release,
            current = current,
            action = tr("Download karke install karo", "Download and install"),
            onAction = { Updater.download(context, state.release) },
        )
        is UpdateState.Downloading -> Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                tr("Download ho raha hai: ${(state.progress * 100).toInt()}%", "Downloading: ${(state.progress * 100).toInt()}%"),
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }
        is UpdateState.Ready -> UpdateCard(
            release = state.release,
            current = current,
            action = tr("Install karo", "Install"),
            onAction = { install(state.apk) },
        )
        is UpdateState.Failed -> ListItem(
            headlineContent = { Text(tr("Update nahi mila", "Couldn't check for updates")) },
            supportingContent = { Text(tr("Internet check karo. (${state.message})", "Check your internet. (${state.message})")) },
            leadingContent = { Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error) },
            trailingContent = { TextButton(onClick = Updater::check) { Text(tr("Phir se", "Retry")) } },
            colors = transparent,
        )
    }
}

@Composable
private fun UpdateCard(release: Release, current: String, action: String, onAction: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        ListItem(
            headlineContent = { Text(tr("Naya version: v${release.versionName}", "New version: v${release.versionName}")) },
            supportingContent = { Text(tr("Build ${release.versionCode} · abhi $current", "Build ${release.versionCode} · you have $current")) },
            leadingContent = { Icon(Icons.Default.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        Text(
            tr("Notes, photos, schedule sab safe rahenge.", "Your notes, photos and schedule stay as they are."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Button(
            onClick = onAction,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) { Text(action) }
    }
}
