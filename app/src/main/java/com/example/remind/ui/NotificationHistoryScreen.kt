package com.example.remind.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.remind.RemindApp
import com.example.remind.data.NotificationLog
import com.example.remind.tr
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val historyTime = DateTimeFormatter.ofPattern("d MMM, h:mm a")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationHistoryScreen(onBack: () -> Unit, onOpenNote: (Long) -> Unit) {
    val repo = (LocalContext.current.applicationContext as RemindApp).repository
    val logs by remember { repo.observeNotificationLog() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Purane notifications", "Notification history")) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (logs.isNotEmpty()) {
                        TextButton(onClick = { scope.launch { repo.clearNotificationLog() } }) { Text(tr("Saaf karo", "Clear")) }
                    }
                },
            )
        },
    ) { padding ->
        if (logs.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tr(
                        "Abhi tak koi notification nahi aaya. Pichhle 30 din ke notifications yahan dikhenge.",
                        "No notifications yet. Notifications from the last 30 days will show here.",
                    ),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(logs, key = { it.id }) { log -> HistoryRow(log, onOpenNote) }
            }
        }
    }
}

@Composable
private fun HistoryRow(log: NotificationLog, onOpenNote: (Long) -> Unit) {
    val noteId = log.noteId
    ListItem(
        overlineContent = {
            Text(historyTime.format(Instant.ofEpochMilli(log.postedAt).atZone(ZoneId.systemDefault())))
        },
        headlineContent = { Text(log.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(log.detail, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            log.response?.let { response ->
                val label = when (response) {
                    "GOOD" -> tr("Yaad tha", "Remembered")
                    "HARD" -> tr("Thoda", "Partly")
                    "FORGOT" -> tr("Bhool gaya", "Forgot")
                    else -> response
                }
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        },
        modifier = if (noteId != null) Modifier.clickable { onOpenNote(noteId) } else Modifier,
    )
}
