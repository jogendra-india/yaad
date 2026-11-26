package com.example.remind.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Paint
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.remind.data.AttachmentStore
import kotlinx.coroutines.delay
import java.io.File

private class InkStroke(val points: List<Offset>, val color: Color, val width: Float)

private val inkColors = listOf(Color.Black, Color(0xFF1E4FD8), Color(0xFFD32F2F), Color(0xFF2E7D32))
private const val ERASER_WIDTH = 40f

/** Full-screen drawing pad. Saves a PNG with a white background and returns its file name. */
@Composable
fun HandwritingDialog(store: AttachmentStore, onDismiss: () -> Unit, onSaved: (String) -> Unit) {
    val strokes = remember { mutableStateListOf<InkStroke>() }
    val current = remember { mutableStateListOf<Offset>() }
    var color by remember { mutableStateOf(Color.Black) }
    var thick by remember { mutableStateOf(false) }
    var eraser by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val strokeColor = if (eraser) Color.White else color
    val strokeWidth = if (eraser) ERASER_WIDTH else if (thick) 12f else 5f

    fun commit() {
        if (current.isNotEmpty()) strokes += InkStroke(current.toList(), strokeColor, strokeWidth)
        current.clear()
    }

    fun save() {
        if (canvasSize.width == 0 || strokes.isEmpty()) {
            onDismiss()
            return
        }
        val bitmap = Bitmap.createBitmap(canvasSize.width, canvasSize.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        strokes.forEach { stroke ->
            paint.color = stroke.color.toArgb()
            paint.strokeWidth = stroke.width
            val path = android.graphics.Path()
            stroke.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
            if (stroke.points.size == 1) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(stroke.points[0].x, stroke.points[0].y, stroke.width / 2, paint)
                paint.style = Paint.Style.STROKE
            } else {
                canvas.drawPath(path, paint)
            }
        }
        val name = store.saveBitmap(bitmap)
        bitmap.recycle()
        onSaved(name)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .systemBarsPadding(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Cancel") }
                Text("Handwriting", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { strokes.removeLastOrNull() }, enabled = strokes.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                }
                IconButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty()) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Clear")
                }
                TextButton(onClick = ::save) { Text("Save") }
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(8.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    .background(Color.White)
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(strokeColor, strokeWidth) {
                        detectTapGestures { offset ->
                            strokes += InkStroke(listOf(offset), strokeColor, strokeWidth)
                        }
                    }
                    .pointerInput(strokeColor, strokeWidth) {
                        detectDragGestures(
                            onDragStart = { current.clear(); current += it },
                            onDrag = { change, _ -> current += change.position },
                            onDragEnd = { commit() },
                            onDragCancel = { commit() },
                        )
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    fun draw(points: List<Offset>, c: Color, w: Float) {
                        if (points.size == 1) {
                            drawCircle(c, radius = w / 2, center = points[0])
                            return
                        }
                        val path = Path()
                        points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
                        drawPath(path, c, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    strokes.forEach { draw(it.points, it.color, it.width) }
                    if (current.isNotEmpty()) draw(current, strokeColor, strokeWidth)
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                inkColors.forEach { c ->
                    Box(
                        Modifier
                            .size(32.dp)
                            .background(c, CircleShape)
                            .border(
                                width = if (c == color && !eraser) 3.dp else 0.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape,
                            )
                            .clickable { color = c; eraser = false },
                    )
                }
                Spacer(Modifier.weight(1f))
                FilterChip(selected = thick && !eraser, onClick = { thick = !thick; eraser = false }, label = { Text("Bold") })
                FilterChip(selected = eraser, onClick = { eraser = !eraser }, label = { Text("Eraser") })
            }
        }
    }
}

/** Records a voice note to an .m4a in the store and returns its file name. */
@Composable
fun VoiceRecorderDialog(store: AttachmentStore, onDismiss: () -> Unit, onSaved: (String) -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    var denied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        denied = !it
    }

    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var file by remember { mutableStateOf<File?>(null) }
    var elapsed by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    fun start() {
        val out = store.newFile("m4a")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(96_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = out
            elapsed = 0
        } catch (e: Exception) {
            r.release()
            out.delete()
            error = "Recording start nahi hui: ${e.message}"
        }
    }

    /** Returns true if a usable file was produced. */
    fun stop(): Boolean {
        val r = recorder ?: return false
        recorder = null
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        if (!ok) file?.delete()
        return ok
    }

    fun cancel() {
        stop()
        file?.delete()
        file = null
        onDismiss()
    }

    LaunchedEffect(recorder) {
        while (recorder != null) {
            delay(1000)
            elapsed++
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Dialog closed without saving (e.g. back press while recording).
            if (recorder != null) {
                stop()
                file?.delete()
            }
        }
    }

    AlertDialog(
        onDismissRequest = ::cancel,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text("Voice note") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                when {
                    !granted -> {
                        Text(if (denied) "Mic permission chahiye. Settings me allow karo." else "Voice record karne ke liye mic permission do.")
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { permission.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Allow mic") }
                    }
                    else -> {
                        Text(formatDuration(elapsed * 1000), style = MaterialTheme.typography.displaySmall)
                        Spacer(Modifier.height(16.dp))
                        FilledIconButton(
                            onClick = {
                                if (recorder == null) {
                                    start()
                                } else if (stop()) {
                                    file?.let { onSaved(it.name) }
                                } else {
                                    error = "Recording bahut chhoti thi, dobara try karo."
                                }
                            },
                            modifier = Modifier.size(72.dp),
                        ) {
                            Icon(
                                if (recorder == null) Icons.Default.Mic else Icons.Default.Stop,
                                contentDescription = if (recorder == null) "Record" else "Stop & save",
                                modifier = Modifier.size(36.dp),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (recorder == null) "Tap karke record shuru karo" else "Recording… tap karke save karo",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = ::cancel) { Text("Cancel") } },
    )
}
