package com.example.remind.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.remind.data.AttachmentStore
import com.example.remind.tr
import com.example.remind.data.AttachmentType
import com.example.remind.data.NoteWithAttachments

const val NO_DECK_LABEL = "Bina deck"

fun deckName(subject: String) = subject.ifBlank { NO_DECK_LABEL }

@Composable
fun dueColor(nextDueDay: Long, today: Long, mastered: Boolean): Color = when {
    mastered -> MaterialTheme.colorScheme.tertiary
    nextDueDay < today -> MaterialTheme.colorScheme.error
    nextDueDay == today -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun NoteCard(
    item: NoteWithAttachments,
    today: Long,
    store: AttachmentStore,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showCreated: Boolean = false,
) {
    val note = item.note
    val visual = item.attachments.firstOrNull { it.type.isVisual() }
    val hasAudio = item.attachments.any { it.type == AttachmentType.AUDIO }
    val hasVideo = item.attachments.any { it.type == AttachmentType.VIDEO }
    val hasDocument = item.attachments.any { it.type == AttachmentType.DOCUMENT }

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (visual != null) {
                    AsyncImage(
                        model = store.file(visual.fileName),
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            .then(if (visual.type == AttachmentType.HANDWRITING) Modifier.background(Color.White) else Modifier),
                    )
                } else {
                    Text(
                        note.title.take(1).uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        note.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (note.starred) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.Star, contentDescription = "Starred", tint = StarColor, modifier = Modifier.size(18.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (note.mastered) "Mastered" else dueLabel(note.nextDueDay, today),
                        style = MaterialTheme.typography.labelMedium,
                        color = dueColor(note.nextDueDay, today, note.mastered),
                    )
                    Text("·", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        deckName(note.subject),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (showCreated) {
                    Text(
                        createdLabel(note.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (note.keywords.isNotEmpty()) {
                    Text(
                        note.keywords.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (note.mastered) SmallIcon(Icons.Default.CheckCircle, MaterialTheme.colorScheme.tertiary)
                if (hasAudio) SmallIcon(Icons.Default.Mic)
                if (hasVideo) SmallIcon(Icons.Default.Videocam)
                if (hasDocument) SmallIcon(Icons.Default.Description)
                if (item.attachments.isEmpty() && note.body.isNotBlank()) SmallIcon(Icons.AutoMirrored.Filled.Notes)
            }
        }
    }
}

private val createdFormat = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a")

/** "Added 28 Nov 2026, 3:45 PM" in the phone's time zone. */
private fun createdLabel(millis: Long): String {
    val time = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
    return tr("Banaya ", "Added ") + createdFormat.format(time)
}

@Composable
private fun SmallIcon(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}
