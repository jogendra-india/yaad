package com.example.remind.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.remind.RemindApp
import com.example.remind.sync.InboxSync
import com.example.remind.sync.SyncState
import com.example.remind.sync.TestState
import com.example.remind.tr
import java.text.DateFormat
import java.util.Date

/** Settings block: inbox server URL + key, a connection test, and the Sync button. */
@Composable
fun SyncSection() {
    val context = LocalContext.current
    val app = context.applicationContext as RemindApp
    val saved = remember { InboxSync.config(context) }
    var savedUrl by rememberSaveable { mutableStateOf(saved.url) }
    var savedKey by rememberSaveable { mutableStateOf(saved.key) }
    var url by rememberSaveable { mutableStateOf(saved.url) }
    var key by rememberSaveable { mutableStateOf(saved.key) }
    var showKey by rememberSaveable { mutableStateOf(false) }
    val changed = InboxSync.normalizeUrl(url) != savedUrl || key.trim() != savedKey
    val configured = savedUrl.isNotBlank() && savedKey.isNotBlank()
    val transparent = ListItemDefaults.colors(containerColor = Color.Transparent)

    Text(
        tr(
            "Browser extension se daale gaye notes yahan Sync dabane pe aate hain. Phone me save hone ke baad server se hat jaate hain.",
            "Notes added from the browser extension come in when you tap Sync. Once saved on this phone they are removed from the server.",
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Server URL") },
            placeholder = { Text("https://your-server.example/api/yaad") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("Key") },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "Show key")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { InboxSync.test(url, key) },
                enabled = url.isNotBlank() && key.isNotBlank() && InboxSync.testState != TestState.Testing,
            ) { Text(tr("Connection test", "Test connection")) }
            TextButton(
                onClick = {
                    InboxSync.saveConfig(context, url, key)
                    savedUrl = InboxSync.normalizeUrl(url)
                    savedKey = key.trim()
                    url = savedUrl
                },
                enabled = changed,
            ) { Text("Save") }
        }
        when (val test = InboxSync.testState) {
            TestState.Idle -> Unit
            TestState.Testing -> Text(tr("Check ho raha hai…", "Checking…"), style = MaterialTheme.typography.bodySmall)
            is TestState.Ok -> Text(
                tr("Connected (${test.vaultName}) · ${test.pending} note waiting", "Connected (${test.vaultName}) · ${test.pending} waiting"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            is TestState.Failed -> Text(test.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }

    val state = InboxSync.state
    val last = InboxSync.lastSyncAt
    val lastText = if (last > 0) {
        tr("Last sync: ", "Last sync: ") + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last))
    } else {
        tr("Abhi tak sync nahi hua", "Never synced")
    }
    when (state) {
        is SyncState.Running -> Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                if (state.total == 0) tr("Server se list aa rahi hai…", "Fetching the list…")
                else tr("Note ${state.done} / ${state.total}", "Note ${state.done} of ${state.total}"),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.total == 0) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            } else {
                LinearProgressIndicator(
                    progress = { state.done.toFloat() / state.total },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
        else -> ListItem(
            headlineContent = {
                Text(
                    when (state) {
                        is SyncState.Done, is SyncState.Failed -> InboxSync.message(state)
                        else -> "Sync"
                    }
                )
            },
            supportingContent = { Text(if (configured) lastText else tr("Pehle URL aur key save karo", "Save the URL and key first")) },
            leadingContent = {
                when (state) {
                    is SyncState.Done -> Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    is SyncState.Failed -> Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                    else -> Icon(Icons.Default.CloudSync, null, tint = MaterialTheme.colorScheme.primary)
                }
            },
            trailingContent = {
                TextButton(onClick = { InboxSync.start(app) }, enabled = configured && !changed) {
                    Text(tr("Sync karo", "Sync now"))
                }
            },
            colors = transparent,
        )
    }
}

/** Top-bar Sync action for Home. Shown only once the inbox is set up; spins while a sync runs. */
@Composable
fun SyncAction() {
    val context = LocalContext.current
    val app = context.applicationContext as RemindApp
    val configured = remember(InboxSync.lastSyncAt, InboxSync.state) { InboxSync.config(context).isComplete }
    if (!configured) return
    if (InboxSync.isRunning) {
        IconButton(onClick = {}, enabled = false) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
    } else {
        IconButton(onClick = { InboxSync.start(app) }) { Icon(Icons.Default.Sync, contentDescription = "Sync") }
    }
}
