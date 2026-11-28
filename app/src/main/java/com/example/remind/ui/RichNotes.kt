package com.example.remind.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import com.example.remind.tr
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichText

/**
 * Note text is stored as HTML written by the rich editor (bold, italic,
 * underline, bullet and numbered lists). Notes saved before formatting existed
 * are plain text; they are wrapped into paragraphs when loaded.
 */
object RichNotes {

    fun isHtml(body: String): Boolean = body.trimStart().startsWith("<")

    /** HTML for the editor / viewer, converting legacy plain text. */
    fun toHtml(body: String): String {
        if (body.isBlank() || isHtml(body)) return body
        return body.lines().joinToString("") { "<p>${escape(it)}</p>" }
    }

    /** Plain text without formatting (notifications, search, auto titles). */
    fun plain(body: String): String {
        if (!isHtml(body)) return body
        return HtmlCompat.fromHtml(body, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
    }

    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private val HREF = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val URL = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private const val TRAILING = ".,;:!?)]}>'\""

    /**
     * Web links in a note: link targets in its HTML plus bare URLs in its text (the source link
     * from the browser extension, pasted links, old notes), in order, without repeats.
     */
    fun links(body: String): List<String> {
        if (body.isBlank()) return emptyList()
        val found = HREF.findAll(body).map { HtmlCompat.fromHtml(it.groupValues[1], HtmlCompat.FROM_HTML_MODE_COMPACT).toString() } +
            URL.findAll(plain(body)).map { it.value }
        return found
            .map { it.trim().trimEnd { c -> c in TRAILING } }
            .filter { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
            .distinct()
            .take(10)
            .toList()
    }
}

/** Read-only formatted note text. */
@Composable
fun NotesText(body: String, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current) {
    val state = rememberRichTextState()
    LaunchedEffect(body) { state.setHtml(RichNotes.toHtml(body)) }
    RichText(state = state, modifier = modifier, style = style)
}

/** The note's web links as rows that open in the browser: site name on top, full address below. */
@Composable
fun NoteLinks(body: String, modifier: Modifier = Modifier) {
    val links = remember(body) { RichNotes.links(body) }
    if (links.isEmpty()) return
    val context = LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        links.forEach { url ->
            val host = remember(url) { runCatching { Uri.parse(url).host?.removePrefix("www.") }.getOrNull() ?: url }
            Surface(
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(context, tr("Link kholne ke liye browser nahi mila", "No browser to open the link"), Toast.LENGTH_SHORT).show()
                    }
                },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Default.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(host, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            url,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(Icons.Default.OpenInNew, contentDescription = tr("Browser me kholo", "Open in browser"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
