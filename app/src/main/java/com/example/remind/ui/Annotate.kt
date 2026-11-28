package com.example.remind.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.remind.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.hypot

/**
 * One pen stroke on a page or photo. Points are fractions of the target's width and height
 * (0..1) and [width] is a fraction of its width, so a stroke lands in the same place at any
 * render size: the small on-screen page, the zoomed editor and the exported file.
 */
data class InkLine(val points: List<Offset>, val color: Color, val width: Float, val highlighter: Boolean = false)

private const val HIGHLIGHT_ALPHA = 0.35f

object InkJson {
    fun encode(lines: List<InkLine>): JSONArray = JSONArray().apply {
        lines.forEach { line ->
            put(JSONObject().apply {
                put("c", line.color.toArgb())
                put("w", line.width.toDouble())
                put("h", line.highlighter)
                put("p", JSONArray().apply { line.points.forEach { put(it.x.toDouble()); put(it.y.toDouble()) } })
            })
        }
    }

    fun decode(arr: JSONArray): List<InkLine> = (0 until arr.length()).mapNotNull { i ->
        runCatching {
            val o = arr.getJSONObject(i)
            val p = o.getJSONArray("p")
            val points = (0 until p.length() / 2).map { Offset(p.getDouble(2 * it).toFloat(), p.getDouble(2 * it + 1).toFloat()) }
            InkLine(points, Color(o.getInt("c")), o.getDouble("w").toFloat(), o.optBoolean("h", false))
        }.getOrNull()
    }
}

/**
 * Annotations on a PDF, kept beside it as `<file name>.ink.json` (page index -> strokes).
 * The PDF itself stays untouched, so pages stay sharp and strokes can be undone; exports
 * draw the strokes into a copy (see [flattenPdf]).
 */
object PdfInk {
    fun sidecar(file: File) = File(file.parentFile, file.name + com.example.remind.data.AttachmentStore.INK_SUFFIX)

    fun load(file: File): Map<Int, List<InkLine>> = runCatching {
        val o = JSONObject(sidecar(file).readText())
        o.keys().asSequence().associate { it.toInt() to InkJson.decode(o.getJSONArray(it)) }.filterValues { it.isNotEmpty() }
    }.getOrDefault(emptyMap())

    fun save(file: File, ink: Map<Int, List<InkLine>>) {
        val kept = ink.filterValues { it.isNotEmpty() }
        if (kept.isEmpty()) {
            sidecar(file).delete()
            return
        }
        val o = JSONObject()
        kept.forEach { (page, lines) -> o.put(page.toString(), InkJson.encode(lines)) }
        sidecar(file).writeText(o.toString())
    }
}

/** Draws strokes on a platform canvas of [w] x [h] pixels (used when writing files). */
fun drawInk(canvas: android.graphics.Canvas, lines: List<InkLine>, w: Float, h: Float) {
    val paint = Paint().apply {
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    lines.forEach { line ->
        val color = if (line.highlighter) line.color.copy(alpha = HIGHLIGHT_ALPHA) else line.color
        paint.color = color.toArgb()
        paint.strokeWidth = line.width * w
        if (line.points.size == 1) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(line.points[0].x * w, line.points[0].y * h, line.width * w / 2, paint)
        } else {
            paint.style = Paint.Style.STROKE
            val path = android.graphics.Path()
            line.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x * w, p.y * h) else path.lineTo(p.x * w, p.y * h) }
            canvas.drawPath(path, paint)
        }
    }
}

/** Draws strokes over a Compose canvas of [area] size. */
fun DrawScope.drawInkLines(lines: List<InkLine>, area: Size = size) {
    lines.forEach { line ->
        val color = if (line.highlighter) line.color.copy(alpha = HIGHLIGHT_ALPHA) else line.color
        val w = line.width * area.width
        if (line.points.size == 1) {
            drawCircle(color, radius = w / 2, center = Offset(line.points[0].x * area.width, line.points[0].y * area.height))
            return@forEach
        }
        val path = Path()
        line.points.forEachIndexed { i, p ->
            val x = p.x * area.width
            val y = p.y * area.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private enum class InkTool { PEN, BOLD, HIGHLIGHT, ERASER }

private val annotateColors = listOf(
    Color(0xFFE53935), // red
    Color(0xFF1E66F5), // blue
    Color(0xFF1B1B1F), // black
    Color(0xFF2E7D32), // green
    Color(0xFFFFC400), // yellow, best as a highlighter
)

/**
 * Full-screen editor: [background] with strokes drawn over it. One finger draws (or erases
 * whole strokes with the eraser), two fingers zoom and pan. Returns the full list on save.
 */
@Composable
fun AnnotateScreen(
    title: String,
    background: ImageBitmap,
    initial: List<InkLine>,
    onCancel: () -> Unit,
    onSave: (List<InkLine>) -> Unit,
    saving: Boolean = false,
) {
    val lines = remember { mutableStateListOf<InkLine>().apply { addAll(initial) } }
    val current = remember { mutableStateListOf<Offset>() }
    var color by remember { mutableStateOf(annotateColors[0]) }
    var tool by remember { mutableStateOf(InkTool.PEN) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var changes by remember { mutableIntStateOf(0) }
    // Once a stylus touches the page, only the stylus draws: fingers and palms move the page instead.
    var stylusMode by remember { mutableStateOf(false) }

    fun strokeWidth() = when (tool) {
        InkTool.BOLD -> 0.009f
        InkTool.HIGHLIGHT -> 0.03f
        else -> 0.004f
    }

    fun eraseAt(p: Offset) {
        val hit = lines.indexOfLast { line -> line.points.any { hypot(it.x - p.x, it.y - p.y) < 0.025f } }
        if (hit >= 0) {
            lines.removeAt(hit)
            changes++
        }
    }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = tr("Radd karo", "Cancel")) }
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = { lines.removeLastOrNull(); changes++ }, enabled = lines.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = { lines.clear(); changes++ }, enabled = lines.isNotEmpty()) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = tr("Sab mitao", "Clear all"))
                    }
                    if (saving) {
                        CircularProgressIndicator(Modifier.padding(horizontal = 16.dp).size(22.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = { onSave(lines.toList()) }) { Text(tr("Save karo", "Save")) }
                    }
                }

                BoxWithConstraints(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // Two fingers: zoom and pan. Consumed here, so the pen below never sees them.
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    // With a stylus in use, two touches may be a resting palm: only zoom
                                    // when no stylus is among them.
                                    val stylusDown = event.changes.any { it.pressed && (it.type == PointerType.Stylus || it.type == PointerType.Eraser) }
                                    if (!stylusDown && event.changes.count { it.pressed } >= 2) {
                                        zoom = (zoom * event.calculateZoom()).coerceIn(1f, 6f)
                                        pan = if (zoom == 1f) Offset.Zero else pan + event.calculatePan()
                                        event.changes.forEach { it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val density = LocalDensity.current
                    val boxW = with(density) { maxWidth.toPx() }
                    val boxH = with(density) { maxHeight.toPx() }
                    val imgAspect = background.width.toFloat() / background.height.coerceAtLeast(1)
                    val (fitW, fitH) = if (boxW / boxH > imgAspect) boxH * imgAspect to boxH else boxW to boxW / imgAspect
                    Box(
                        Modifier
                            .size(with(density) { fitW.toDp() }, with(density) { fitH.toDp() })
                            .graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = pan.x, translationY = pan.y)
                            // After graphicsLayer, so positions arrive in the page's own (unzoomed) pixels.
                            .pointerInput(tool, color) {
                                awaitEachGesture {
                                    val first = awaitFirstDown()
                                    val w = size.width.toFloat().coerceAtLeast(1f)
                                    val h = size.height.toFloat().coerceAtLeast(1f)
                                    fun norm(o: Offset) = Offset((o.x / w).coerceIn(0f, 1f), (o.y / h).coerceIn(0f, 1f))
                                    fun isPen(type: PointerType) = type == PointerType.Stylus || type == PointerType.Eraser
                                    var drawId: PointerId? = null
                                    var erasing = false
                                    var cancelled = false
                                    current.clear()
                                    fun start(c: PointerInputChange) {
                                        drawId = c.id
                                        // The eraser end of a stylus always erases.
                                        erasing = tool == InkTool.ERASER || c.type == PointerType.Eraser
                                        if (erasing) eraseAt(norm(c.position)) else current += norm(c.position)
                                    }
                                    if (isPen(first.type)) stylusMode = true
                                    // Palm rejection: in stylus mode a finger never draws.
                                    if (!stylusMode || isPen(first.type)) start(first)
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val id = drawId
                                        if (id == null) {
                                            // Only fingers so far: they pan a zoomed page; a stylus coming down starts drawing.
                                            val pen = event.changes.firstOrNull { it.pressed && isPen(it.type) }
                                            if (pen != null) {
                                                start(pen)
                                                pen.consume()
                                                continue
                                            }
                                            if (event.changes.none { it.pressed }) break
                                            val finger = event.changes.firstOrNull { it.pressed && !it.isConsumed }
                                            if (finger != null) {
                                                if (zoom > 1f) pan += finger.positionChange() * zoom
                                                finger.consume()
                                            }
                                            continue
                                        }
                                        val change = event.changes.firstOrNull { it.id == id }
                                        val fingerPinch = change != null && !isPen(change.type) && event.changes.count { it.pressed } >= 2
                                        if (change == null || change.isConsumed || fingerPinch) {
                                            cancelled = true
                                            break
                                        }
                                        if (!change.pressed) break
                                        if (erasing) eraseAt(norm(change.position)) else current += norm(change.position)
                                        change.consume()
                                    }
                                    if (drawId != null && !cancelled && !erasing && current.isNotEmpty()) {
                                        lines += InkLine(current.toList(), color, strokeWidth(), tool == InkTool.HIGHLIGHT)
                                        changes++
                                    }
                                    current.clear()
                                }
                            },
                    ) {
                        Image(background, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                        Canvas(Modifier.fillMaxSize()) {
                            drawInkLines(lines)
                            if (current.isNotEmpty()) {
                                drawInkLines(listOf(InkLine(current.toList(), color, strokeWidth(), tool == InkTool.HIGHLIGHT)))
                            }
                        }
                    }
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    annotateColors.forEach { c ->
                        Box(
                            Modifier
                                .size(30.dp)
                                .background(c, CircleShape)
                                .border(
                                    width = if (c == color && tool != InkTool.ERASER) 3.dp else 1.dp,
                                    color = if (c == color && tool != InkTool.ERASER) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    shape = CircleShape,
                                )
                                .clickable {
                                    color = c
                                    if (tool == InkTool.ERASER) tool = InkTool.PEN
                                },
                        )
                    }
                    FilterChip(selected = tool == InkTool.PEN, onClick = { tool = InkTool.PEN }, label = { Text("Pen") })
                    FilterChip(selected = tool == InkTool.BOLD, onClick = { tool = InkTool.BOLD }, label = { Text("Bold") })
                    FilterChip(selected = tool == InkTool.HIGHLIGHT, onClick = { tool = InkTool.HIGHLIGHT }, label = { Text("Highlighter") })
                    FilterChip(selected = tool == InkTool.ERASER, onClick = { tool = InkTool.ERASER }, label = { Text("Eraser") })
                }
            }
        }
    }
}

/** Bumped after a file is rewritten in place, so images showing it load again. */
object AttachmentEdits {
    var revision by mutableIntStateOf(0)
}

/**
 * Draw on a photo or handwriting page. On save the strokes are drawn into the image file
 * itself (same name and format), so it shows edited everywhere and in backups.
 */
@Composable
fun ImageAnnotateDialog(file: File, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        }
    }
    val source = bitmap ?: return
    val image = remember(source) { source.asImageBitmap() }
    AnnotateScreen(
        title = tr("Photo pe likho", "Draw on photo"),
        background = image,
        initial = emptyList(),
        onCancel = onDismiss,
        saving = saving,
        onSave = { lines ->
            if (lines.isEmpty()) {
                onDismiss()
                return@AnnotateScreen
            }
            saving = true
            scope.launch {
                withContext(Dispatchers.IO) {
                    val out = source.copy(Bitmap.Config.ARGB_8888, true)
                    drawInk(android.graphics.Canvas(out), lines, out.width.toFloat(), out.height.toFloat())
                    val png = file.extension.equals("png", ignoreCase = true)
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.outputStream().use {
                        out.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (png) 100 else 92, it)
                    }
                    out.recycle()
                    if (!tmp.renameTo(file)) {
                        tmp.copyTo(file, overwrite = true)
                        tmp.delete()
                    }
                }
                AttachmentEdits.revision++
                saving = false
                onDismiss()
            }
        },
    )
}
