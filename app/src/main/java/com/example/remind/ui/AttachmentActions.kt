package com.example.remind.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.remind.data.AttachmentStore
import com.example.remind.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Mime type from the file extension; unknown types fall back to a generic binary. */
fun mimeFor(file: File): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"

fun isPdf(file: File) = file.extension.equals("pdf", ignoreCase = true)

/** Opens an attachment in whichever installed app handles its type (PDF viewer, Word, Slides…). */
fun openExternally(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeFor(file))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(
            Intent.createChooser(view, AttachmentStore.displayName(file.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, tr("Is file ko kholne ke liye koi app nahi", "No app on this phone can open this file"), Toast.LENGTH_LONG).show()
    }
}

/** "Save as" through the system file picker: works on every Android version, needs no permission. */
private class SaveAs : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>) =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.second)
            .putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data
}

/** Returns a function that asks where to save [File] on the phone and copies it there. */
@Composable
fun rememberSaveToPhone(): (File) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pending = remember { arrayOfNulls<File>(1) }
    val launcher = rememberLauncherForActivityResult(SaveAs()) { target ->
        val source = pending[0] ?: return@rememberLauncherForActivityResult
        pending[0] = null
        if (target == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(target)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                        ?: error("no stream")
                }.isSuccess
            }
            Toast.makeText(
                context,
                if (ok) tr("Phone me save ho gaya", "Saved to your phone") else tr("Save nahi hua", "Couldn't save the file"),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
    return { file ->
        pending[0] = file
        try {
            launcher.launch(AttachmentStore.displayName(file.name) to mimeFor(file))
        } catch (e: ActivityNotFoundException) {
            pending[0] = null
            Toast.makeText(context, tr("File save karne wala app nahi mila", "No app to save files with"), Toast.LENGTH_SHORT).show()
        }
    }
}

/** First page of a PDF, rendered once with the platform PdfRenderer. Null while loading or if unreadable. */
@Composable
fun rememberPdfPreview(file: File, widthPx: Int = 480): Bitmap? {
    val bitmap by produceState<Bitmap?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        renderer.openPage(0).use { page ->
                            val height = (widthPx.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                            Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888).also {
                                it.eraseColor(AndroidColor.WHITE)
                                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                }
            }.getOrNull()
        }
    }
    return bitmap
}

/** Thumbnail body for a document: PDF first page when possible, else an icon with the extension. */
@Composable
fun DocumentThumbContent(file: File) {
    val preview = if (isPdf(file)) rememberPdfPreview(file, widthPx = 300) else null
    if (preview != null) {
        Image(
            bitmap = preview.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White),
        )
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(6.dp)) {
            Icon(
                if (isPdf(file)) Icons.Default.PictureAsPdf else Icons.Default.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                file.extension.uppercase().ifEmpty { "FILE" },
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}

/** A document in a note: icon, name and size; tap to open it, or save a copy to the phone. */
@Composable
fun DocumentRow(file: File, onSave: (File) -> Unit) {
    val context = LocalContext.current
    Surface(
        onClick = { openExternally(context, file) },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                if (isPdf(file)) Icons.Default.PictureAsPdf else Icons.Default.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    AttachmentStore.displayName(file.name),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(formatSize(file.length()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { openExternally(context, file) }) {
                Icon(Icons.Default.OpenInNew, contentDescription = tr("Kholo", "Open"))
            }
            IconButton(onClick = { onSave(file) }) {
                Icon(Icons.Default.Download, contentDescription = tr("Phone me save karo", "Save to phone"))
            }
        }
    }
}

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
