package com.example.remind.ui

import com.example.remind.tr

import android.media.MediaPlayer
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.filled.Draw
import androidx.compose.runtime.key
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.remind.data.AttachmentType
import kotlinx.coroutines.delay
import java.io.File

/** Hinglish label for when a note is due, relative to today. */
fun dueLabel(nextDueDay: Long, today: Long): String {
    val diff = nextDueDay - today
    return when {
        diff < -1 -> "${-diff} din late"
        diff == -1L -> tr("Kal se due", "Due since yesterday")
        diff == 0L -> tr("Aaj", "Today")
        diff == 1L -> tr("Kal", "Tomorrow")
        else -> tr("$diff din baad", "In $diff days")
    }
}

fun formatDuration(ms: Int): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeywordChips(keywords: List<String>, modifier: Modifier = Modifier) {
    if (keywords.isEmpty()) return
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        keywords.forEach { SuggestionChip(onClick = {}, label = { Text(it) }) }
    }
}

/** Square thumbnail for any attachment type. */
@Composable
fun AttachmentThumb(
    file: File,
    type: AttachmentType,
    size: Dp,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        key(AttachmentEdits.revision) {
        when (type) {
            AttachmentType.PHOTO, AttachmentType.HANDWRITING, AttachmentType.VIDEO -> AsyncImage(
                model = file,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (type == AttachmentType.HANDWRITING) Modifier.background(Color.White) else Modifier),
            )
            AttachmentType.AUDIO -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Voice", style = MaterialTheme.typography.labelSmall)
            }
            AttachmentType.DOCUMENT -> DocumentThumbContent(file)
        }
        }
        if (type == AttachmentType.VIDEO) {
            Icon(
                Icons.Default.PlayCircle,
                contentDescription = "Video",
                tint = Color.White,
                modifier = Modifier.size(size / 3),
            )
        }
        if (onRemove != null) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(24.dp),
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.55f)),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Attachment types shown in the swipeable full-screen viewer. */
fun AttachmentType.isVisual() = this == AttachmentType.PHOTO || this == AttachmentType.HANDWRITING || this == AttachmentType.VIDEO

/**
 * Read-only view of a note's attachments: visual ones in a row (tap for full screen), voice notes
 * as players, documents as rows that open in another app. Every one can be saved to the phone.
 */
@Composable
fun AttachmentGallery(items: List<Pair<File, AttachmentType>>, modifier: Modifier = Modifier) {
    var viewing by remember { mutableStateOf<Pair<File, AttachmentType>?>(null) }
    val visual = items.filter { it.second.isVisual() }
    val audio = items.filter { it.second == AttachmentType.AUDIO }
    val documents = items.filter { it.second == AttachmentType.DOCUMENT }
    val saveToPhone = rememberSaveToPhone()

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (visual.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visual) { item ->
                    AttachmentThumb(item.first, item.second, size = 140.dp, onClick = { viewing = item })
                }
            }
        }
        audio.forEach { AudioPlayer(it.first, onSave = saveToPhone) }
        documents.forEach { DocumentRow(it.first, onSave = saveToPhone) }
    }

    viewing?.let { item ->
        MediaViewerDialog(visual, startIndex = visual.indexOf(item).coerceAtLeast(0)) { viewing = null }
    }
}

@Composable
fun AudioPlayer(file: File, onSave: ((File) -> Unit)? = null) {
    val player = remember(file) { MediaPlayer() }
    var prepared by remember(file) { mutableStateOf(false) }
    var playing by remember(file) { mutableStateOf(false) }
    var duration by remember(file) { mutableIntStateOf(0) }
    var position by remember(file) { mutableFloatStateOf(0f) }

    DisposableEffect(file) {
        runCatching {
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener {
                playing = false
                position = 0f
                it.seekTo(0)
            }
            player.prepare()
            duration = player.duration
            prepared = true
        }
        onDispose { player.release() }
    }

    LaunchedEffect(playing) {
        while (playing) {
            position = player.currentPosition.toFloat()
            delay(200)
        }
    }

    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(enabled = prepared, onClick = {
                if (playing) player.pause() else player.start()
                playing = !playing
            }) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (playing) "Pause" else "Play")
            }
            Slider(
                value = position,
                valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                onValueChange = {
                    position = it
                    player.seekTo(it.toInt())
                },
                enabled = prepared,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${formatDuration(position.toInt())} / ${formatDuration(duration)}",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Visible,
            )
            if (onSave != null) {
                IconButton(onClick = { onSave(file) }) {
                    Icon(Icons.Default.Download, contentDescription = tr("Phone me save karo", "Save to phone"))
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

@Composable
fun ImageViewerDialog(file: File, whiteBackground: Boolean, onDismiss: () -> Unit) {
    FullScreenDialog(onDismiss) {
        ZoomableImage(file, whiteBackground)
    }
}

/**
 * Full-screen viewer for a note's photos, sketches and videos. Swipe left/right (or use the
 * arrows) to move between them without closing the viewer.
 */
@Composable
fun MediaViewerDialog(items: List<Pair<File, AttachmentType>>, startIndex: Int, onDismiss: () -> Unit) {
    if (items.isEmpty()) return
    val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, items.lastIndex)) { items.size }
    val scope = rememberCoroutineScope()
    var zoomed by remember { mutableStateOf(false) }
    var annotating by remember { mutableStateOf<File?>(null) }
    val saveToPhone = rememberSaveToPhone()

    annotating?.let { ImageAnnotateDialog(it) { annotating = null } }

    FullScreenDialog(onDismiss) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !zoomed,
            key = { items[it].first.absolutePath },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val (file, type) = items[page]
            val isCurrent = pagerState.settledPage == page
            // A drawn-on photo is rewritten in place; the new revision makes it load again.
            key(AttachmentEdits.revision) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    type != AttachmentType.VIDEO -> ZoomableImage(
                        file = file,
                        whiteBackground = type == AttachmentType.HANDWRITING,
                        active = isCurrent,
                        onZoomChange = { if (isCurrent) zoomed = it },
                    )
                    // Only the page in view plays; neighbours show a still frame.
                    isCurrent -> VideoSurface(file)
                    else -> {
                        AsyncImage(
                            model = file,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Icon(Icons.Default.PlayCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(72.dp))
                    }
                }
            }
            }
        }

        IconButton(
            onClick = { saveToPhone(items[pagerState.currentPage].first) },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape),
        ) {
            Icon(Icons.Default.Download, contentDescription = tr("Phone me save karo", "Save to phone"), tint = Color.White)
        }
        if (items[pagerState.currentPage].second != AttachmentType.VIDEO) {
            IconButton(
                onClick = { annotating = items[pagerState.currentPage].first },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 68.dp, top = 12.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape),
            ) {
                Icon(Icons.Default.Draw, contentDescription = tr("Photo pe likho", "Draw on photo"), tint = Color.White)
            }
        }

        if (items.size > 1) {
            val current = pagerState.currentPage
            Text(
                "${current + 1} / ${items.size}",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 20.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
            if (current > 0) {
                PagerArrow(Icons.AutoMirrored.Filled.KeyboardArrowLeft, tr("Pichhla", "Previous"), Modifier.align(Alignment.CenterStart)) {
                    scope.launch { pagerState.animateScrollToPage(current - 1) }
                }
            }
            if (current < items.lastIndex) {
                PagerArrow(Icons.AutoMirrored.Filled.KeyboardArrowRight, tr("Agla", "Next"), Modifier.align(Alignment.CenterEnd)) {
                    scope.launch { pagerState.animateScrollToPage(current + 1) }
                }
            }
        }
    }
}

@Composable
private fun PagerArrow(icon: ImageVector, description: String, modifier: Modifier, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .padding(8.dp)
            .background(Color.Black.copy(alpha = 0.5f), CircleShape),
    ) {
        Icon(icon, contentDescription = description, tint = Color.White)
    }
}

/**
 * Pinch-to-zoom image. Single-finger drags only pan while zoomed in, so at normal size they
 * fall through to a surrounding pager. Zoom resets when the image stops being [active].
 */
@OptIn(ExperimentalFoundationApi::class) // transformable(canPan = …)
@Composable
private fun ZoomableImage(
    file: File,
    whiteBackground: Boolean,
    active: Boolean = true,
    onZoomChange: (Boolean) -> Unit = {},
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        if (scale > 1f) {
            offsetX += pan.x
            offsetY += pan.y
        } else {
            offsetX = 0f
            offsetY = 0f
        }
        onZoomChange(scale > 1f)
    }
    LaunchedEffect(active) {
        if (!active) {
            scale = 1f
            offsetX = 0f
            offsetY = 0f
            onZoomChange(false)
        }
    }
    AsyncImage(
        model = file,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .then(if (whiteBackground) Modifier.background(Color.White) else Modifier)
            .transformable(transform, canPan = { scale > 1f })
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY),
    )
}

@Composable
fun VideoPlayerDialog(file: File, onDismiss: () -> Unit) {
    FullScreenDialog(onDismiss) {
        VideoSurface(file)
    }
}

@Composable
private fun VideoSurface(file: File) {
    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 56.dp),
        factory = { context ->
            VideoView(context).apply {
                val controller = MediaController(context)
                controller.setAnchorView(this)
                setMediaController(controller)
                setVideoPath(file.absolutePath)
                setOnPreparedListener { start() }
            }
        },
        onRelease = { it.stopPlayback() },
    )
}

@Composable
private fun FullScreenDialog(onDismiss: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            content()
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}
