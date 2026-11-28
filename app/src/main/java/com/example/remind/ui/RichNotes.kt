package com.example.remind.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.core.text.HtmlCompat
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
}

/** Read-only formatted note text. */
@Composable
fun NotesText(body: String, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current) {
    val state = rememberRichTextState()
    LaunchedEffect(body) { state.setHtml(RichNotes.toHtml(body)) }
    RichText(state = state, modifier = modifier, style = style)
}
