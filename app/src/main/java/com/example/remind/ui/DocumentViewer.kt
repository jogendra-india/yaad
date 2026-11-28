package com.example.remind.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.remind.data.AttachmentStore
import com.example.remind.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

private val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "mdown", "mkd")
private val TEXT_EXTENSIONS = setOf("txt", "text", "log")

private fun isMarkdown(file: File) = file.extension.lowercase() in MARKDOWN_EXTENSIONS
private fun isPlainText(file: File) = file.extension.lowercase() in TEXT_EXTENSIONS

/** Documents Yaad shows itself: PDF, Markdown and plain text. Everything else opens in another app. */
fun canViewInApp(file: File) = isPdf(file) || isMarkdown(file) || isPlainText(file)

/** Full-screen in-app viewer with buttons to open the file in another app or save it to the phone. */
@Composable
fun DocumentViewerDialog(file: File, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val saveToPhone = rememberSaveToPhone()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = tr("Band karo", "Close")) }
                    Text(
                        AttachmentStore.displayName(file.name),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { openExternally(context, file) }) {
                        Icon(Icons.Default.OpenInNew, contentDescription = tr("Dusre app me kholo", "Open in another app"))
                    }
                    IconButton(onClick = { saveToPhone(file) }) {
                        Icon(Icons.Default.Download, contentDescription = tr("Phone me save karo", "Save to phone"))
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (isPdf(file)) PdfPages(file) else MarkdownView(file)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Markdown / text

private fun cssColor(c: Color): String {
    val argb = c.toArgb()
    return "rgba(%d,%d,%d,%.3f)".format(
        java.util.Locale.US,
        AndroidColor.red(argb), AndroidColor.green(argb), AndroidColor.blue(argb), AndroidColor.alpha(argb) / 255f,
    )
}

private fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // No browser: ignore the tap.
    }
}

/**
 * Markdown rendered by the bundled marked.js inside a WebView, styled with the app's colours.
 * Offline and locked down: no network, no file access, raw HTML in the file is shown as text,
 * and tapped links open in the browser.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MarkdownView(file: File) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val page by produceState<String?>(null, file, colors) {
        value = withContext(Dispatchers.IO) {
            val text = runCatching { file.readText() }.getOrElse { "" }
            val marked = context.assets.open("viewer/marked.min.js").bufferedReader().use { it.readText() }
            buildPage(text, markdown = isMarkdown(file), marked = marked, css = viewerCss(colors))
        }
    }
    val html = page
    if (html == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(colors.background.toArgb())
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url.toString()
                        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("mailto:")) openLink(ctx, url)
                        return true
                    }
                }
            }
        },
        update = {
            if (it.tag != html) {
                it.tag = html
                it.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() },
    )
}

private fun viewerCss(c: androidx.compose.material3.ColorScheme): String = """
    :root { color-scheme: light dark; }
    html, body { margin: 0; background: ${cssColor(c.background)}; color: ${cssColor(c.onBackground)}; }
    body { font-family: system-ui, -apple-system, Roboto, sans-serif; font-size: 16px; line-height: 1.6;
           padding: 16px 18px 48px; word-wrap: break-word; overflow-wrap: anywhere; }
    h1, h2, h3, h4, h5, h6 { line-height: 1.3; margin: 1.4em 0 .5em; }
    h1 { font-size: 1.6em; } h2 { font-size: 1.35em; } h3 { font-size: 1.15em; }
    h1, h2 { padding-bottom: .25em; border-bottom: 1px solid ${cssColor(c.outlineVariant)}; }
    body > :first-child { margin-top: 0; }
    a { color: ${cssColor(c.primary)}; }
    p, ul, ol, blockquote, pre, table { margin: 0 0 1em; }
    ul, ol { padding-left: 1.4em; } li { margin: .2em 0; }
    li > input[type=checkbox] { margin-right: .4em; }
    blockquote { margin-left: 0; padding: .4em 1em; border-left: 4px solid ${cssColor(c.primary)};
                 background: ${cssColor(c.surfaceContainer)}; border-radius: 0 8px 8px 0; }
    code { font-family: ui-monospace, "Roboto Mono", monospace; font-size: .88em;
           background: ${cssColor(c.surfaceContainerHigh)}; padding: .12em .35em; border-radius: 5px; }
    pre { background: ${cssColor(c.surfaceContainerHigh)}; padding: 12px 14px; border-radius: 10px; overflow-x: auto; }
    pre code { background: none; padding: 0; font-size: .85em; white-space: pre; }
    pre.plain { white-space: pre-wrap; font-family: ui-monospace, "Roboto Mono", monospace; font-size: .9em; background: none; padding: 0; }
    table { border-collapse: collapse; display: block; overflow-x: auto; max-width: 100%; }
    th, td { border: 1px solid ${cssColor(c.outlineVariant)}; padding: 6px 10px; text-align: left; }
    th { background: ${cssColor(c.surfaceContainer)}; }
    hr { border: none; border-top: 1px solid ${cssColor(c.outlineVariant)}; margin: 1.5em 0; }
    img { max-width: 100%; }
""".trimIndent()

private fun buildPage(text: String, markdown: Boolean, marked: String, css: String): String {
    // JSONObject.quote escapes quotes, newlines and "</", so the text can't end the script early.
    val source = JSONObject.quote(text)
    val render = if (markdown) {
        """
        const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
        marked.use({ gfm: true, breaks: false, renderer: { html(t) { return esc(typeof t === 'string' ? t : t.text); } } });
        document.getElementById('doc').innerHTML = marked.parse(src);
        """
    } else {
        """
        const pre = document.createElement('pre'); pre.className = 'plain'; pre.textContent = src;
        document.getElementById('doc').appendChild(pre);
        """
    }
    return """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>$css</style></head>
        <body><div id="doc"></div>
        <script>$marked</script>
        <script>const src = $source;
        try { $render } catch (e) { document.getElementById('doc').textContent = src; }</script>
        </body></html>
    """.trimIndent()
}

// ---------------------------------------------------------------- PDF

/** An open PDF. PdfRenderer allows one page at a time, so every use goes through [lock]. */
private class PdfDoc(file: File) {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    private val lock = Mutex()
    private var closed = false

    suspend fun pageAspects(): List<Float> = lock.withLock {
        (0 until renderer.pageCount).map { i ->
            renderer.openPage(i).use { it.width.toFloat() / it.height.coerceAtLeast(1) }
        }
    }

    suspend fun render(index: Int, widthPx: Int): Bitmap? = lock.withLock {
        if (closed) return@withLock null
        runCatching {
            renderer.openPage(index).use { page ->
                val height = (widthPx.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(AndroidColor.WHITE)
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }.getOrNull()
    }

    fun close() {
        CoroutineScope(Dispatchers.IO).launch {
            lock.withLock {
                closed = true
                runCatching { renderer.close() }
                runCatching { fd.close() }
            }
        }
    }
}

private const val MAX_ZOOM = 4f

/**
 * Every page of a PDF, top to bottom. Pinch to zoom (1x to 4x), double-tap to toggle 2.5x;
 * when zoomed in, scroll sideways as well as up and down. Pages are rendered sharper as you zoom.
 */
@Composable
private fun PdfPages(file: File) {
    var doc by remember(file) { mutableStateOf<PdfDoc?>(null) }
    var aspects by remember(file) { mutableStateOf<List<Float>?>(null) }
    var failed by remember(file) { mutableStateOf(false) }

    DisposableEffect(file) {
        val opened = runCatching { PdfDoc(file) }.getOrNull()
        if (opened == null) failed = true
        doc = opened
        onDispose { opened?.close() }
    }
    LaunchedEffect(doc) {
        val d = doc ?: return@LaunchedEffect
        aspects = withContext(Dispatchers.IO) { runCatching { d.pageAspects() }.getOrNull() }
        if (aspects == null) failed = true
    }

    if (failed) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(tr("Ye PDF khul nahi paya. Upar se dusre app me kholo.", "Couldn't open this PDF. Use the button above to open it in another app."))
        }
        return
    }
    val pages = aspects
    val pdf = doc
    if (pages == null || pdf == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    var zoom by remember(file) { mutableFloatStateOf(1f) }
    val listState = rememberLazyListState()
    val hScroll = rememberScrollState()
    val currentPage by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
        val density = LocalDensity.current
        val viewportPx = with(density) { maxWidth.toPx() }
        val viewportHeightPx = with(density) { maxHeight.toPx() }

        fun setZoom(target: Float) {
            val old = zoom
            val new = target.coerceIn(1f, MAX_ZOOM)
            if (new == old) return
            val ratio = new / old
            zoom = new
            // Keep the middle of the screen where it was.
            val newScroll = (hScroll.value + viewportPx / 2) * ratio - viewportPx / 2
            hScroll.dispatchRawDelta(newScroll - hScroll.value)
            listState.dispatchRawDelta((listState.firstVisibleItemScrollOffset + viewportHeightPx / 2) * (ratio - 1))
        }

        Box(
            Modifier
                .fillMaxSize()
                // Two fingers zoom; one finger is left to the scroll containers below.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                val factor = event.calculateZoom()
                                if (factor != 1f) setZoom(zoom * factor)
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { setZoom(if (zoom > 1.2f) 1f else 2.5f) })
                }
                .horizontalScroll(hScroll, enabled = zoom > 1f),
        ) {
            val pageWidth = maxWidth * zoom
            // Render at the zoomed size, in steps so a pinch doesn't re-render every frame; capped for memory.
            val step = when {
                zoom < 1.5f -> 1f
                zoom < 2.5f -> 2f
                else -> 3f
            }
            val renderPx = (viewportPx * step).toInt().coerceIn(1, 3000)
            LazyColumn(
                state = listState,
                modifier = Modifier.width(pageWidth).fillMaxSize(),
                contentPadding = PaddingValues(vertical = 12.dp, horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(pages.size) { index ->
                    PdfPage(pdf, index, pages[index], renderPx)
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
        ) {
            Text(
                "$currentPage / ${pages.size}",
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun PdfPage(pdf: PdfDoc, index: Int, aspect: Float, renderPx: Int) {
    // Keeps showing the last bitmap while a sharper one renders.
    var bitmap by remember(pdf, index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(pdf, index, renderPx) {
        pdf.render(index, renderPx)?.let { bitmap = it }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(aspect.coerceIn(0.1f, 10f))
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = tr("Page ${index + 1}", "Page ${index + 1}"),
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator(Modifier.padding(24.dp))
        }
    }
}
