package com.example.remind.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration

/**
 * Light formatting for note text, stored as plain text with markers:
 * **bold**, *italic*, ++underline++, and list lines starting with "• " or "1. ".
 * A marker only counts when it is closed on the same line, so a stray "*" in a
 * formula stays literal.
 */
object RichNotes {

    const val BULLET = "• "
    private val numbered = Regex("^(\\d+)\\. ")

    private val styles = listOf(
        SpanStyle(fontWeight = FontWeight.Bold),
        SpanStyle(fontStyle = FontStyle.Italic),
        SpanStyle(textDecoration = TextDecoration.Underline),
    )

    /** Styled text for display, markers removed. */
    fun display(source: String): AnnotatedString = render(source, keepMarkers = false, markerColor = Color.Unspecified)

    /** Plain text with markers removed (notifications, search, titles). */
    fun plain(source: String): String = display(source).text

    /**
     * Applies the styles. With [keepMarkers] the markers stay in place (dimmed), so
     * the output has exactly the source's length and editor offsets still match.
     */
    fun render(source: String, keepMarkers: Boolean, markerColor: Color): AnnotatedString {
        val out = AnnotatedString.Builder()
        val open = BooleanArray(3)
        val start = IntArray(3)

        fun close(kind: Int) {
            if (!open[kind]) return
            if (out.length > start[kind]) out.addStyle(styles[kind], start[kind], out.length)
            open[kind] = false
        }

        var i = 0
        while (i < source.length) {
            val c = source[i]
            if (c == '\n') {
                for (k in 0..2) close(k)
                out.append(c)
                i++
                continue
            }
            val tok = tokenAt(source, i)
            if (tok != null) {
                val kind = kindOf(tok)
                val lineEnd = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                val opening = !open[kind]
                if (!opening || closingIndex(source, tok, i + tok.length, lineEnd) > i + tok.length) {
                    if (!opening) close(kind)
                    if (keepMarkers) {
                        val s = out.length
                        out.append(tok)
                        out.addStyle(SpanStyle(color = markerColor), s, out.length)
                    }
                    if (opening) {
                        open[kind] = true
                        start[kind] = out.length
                    }
                    i += tok.length
                    continue
                }
            }
            out.append(c)
            i++
        }
        for (k in 0..2) close(k)
        return out.toAnnotatedString()
    }

    private fun tokenAt(s: String, i: Int): String? = when {
        s.startsWith("**", i) -> "**"
        s.startsWith("++", i) -> "++"
        s[i] == '*' -> "*"
        else -> null
    }

    private fun kindOf(tok: String) = when (tok) {
        "**" -> 0
        "*" -> 1
        else -> 2
    }

    /** Index of the next [tok] in [from, end), or -1. */
    private fun closingIndex(s: String, tok: String, from: Int, end: Int): Int {
        var j = from
        while (j < end) {
            val t = tokenAt(s, j)
            if (t == tok) return j
            j += t?.length ?: 1
        }
        return -1
    }

    // ---- Editing helpers ----

    /** Wraps the selection in [tok], or inserts an empty pair at the cursor. */
    fun wrap(value: TextFieldValue, tok: String): TextFieldValue {
        val s = value.selection.min
        val e = value.selection.max
        val text = value.text
        val newText = text.substring(0, s) + tok + text.substring(s, e) + tok + text.substring(e)
        return if (s == e) {
            TextFieldValue(newText, TextRange(s + tok.length))
        } else {
            TextFieldValue(newText, TextRange(s + tok.length, e + tok.length))
        }
    }

    fun listPrefix(line: String): String? =
        if (line.startsWith(BULLET)) BULLET else numbered.find(line)?.value

    /** Turns the cursor's line into a bullet / numbered item, or back to plain text. */
    fun toggleList(value: TextFieldValue, numberedList: Boolean): TextFieldValue {
        val text = value.text
        val lineStart = text.lastIndexOf('\n', value.selection.min - 1) + 1
        val line = text.substring(lineStart).substringBefore('\n')
        val existing = listPrefix(line)
        val newPrefix = when {
            numberedList && existing != null && existing != BULLET -> ""
            !numberedList && existing == BULLET -> ""
            numberedList -> "${nextNumber(text, lineStart)}. "
            else -> BULLET
        }
        val removeLen = existing?.length ?: 0
        val newText = text.substring(0, lineStart) + newPrefix + text.substring(lineStart + removeLen)
        val delta = newPrefix.length - removeLen
        return TextFieldValue(
            newText,
            TextRange(
                (value.selection.start + delta).coerceIn(lineStart, newText.length),
                (value.selection.end + delta).coerceIn(lineStart, newText.length),
            ),
        )
    }

    private fun nextNumber(text: String, lineStart: Int): Int {
        if (lineStart == 0) return 1
        val prevStart = text.lastIndexOf('\n', lineStart - 2) + 1
        val prev = text.substring(prevStart, lineStart - 1)
        return numbered.find(prev)?.groupValues?.get(1)?.toIntOrNull()?.plus(1) ?: 1
    }

    /**
     * Continues a list when Enter is pressed on a list line; Enter on an empty
     * item ends the list instead.
     */
    fun onEdit(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
        if (new.text.length != old.text.length + 1 || !new.selection.collapsed) return new
        val p = new.selection.start
        if (p == 0 || new.text[p - 1] != '\n') return new
        if (new.text.removeRange(p - 1, p).toString() != old.text) return new
        val prevStart = new.text.lastIndexOf('\n', p - 2) + 1
        val prev = new.text.substring(prevStart, p - 1)
        val prefix = listPrefix(prev) ?: return new
        if (prev.length == prefix.length) {
            val newText = new.text.substring(0, prevStart) + new.text.substring(p)
            return TextFieldValue(newText, TextRange(prevStart))
        }
        val next = if (prefix == BULLET) BULLET else "${prefix.trimEnd().dropLast(1).toInt() + 1}. "
        val newText = new.text.substring(0, p) + next + new.text.substring(p)
        return TextFieldValue(newText, TextRange(p + next.length))
    }
}

/** Shows the formatting live in the editor; markers stay visible but dimmed. */
class RichNotesTransformation(private val markerColor: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(RichNotes.render(text.text, keepMarkers = true, markerColor = markerColor), OffsetMapping.Identity)
}
