package com.example.remind.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/** Stores attachment files in filesDir/attachments; the DB only keeps file names. */
class AttachmentStore(private val context: Context) {

    val dir: File = File(context.filesDir, "attachments").apply { mkdirs() }

    fun file(name: String) = File(dir, name)

    fun newFile(extension: String): File = File(dir, "${UUID.randomUUID()}.$extension")

    fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun delete(name: String) {
        file(name).delete()
    }

    /** Downscales a freshly captured camera photo in place and applies EXIF rotation. */
    fun compressPhoto(file: File): Boolean {
        val bitmap = decodeScaled { file.inputStream() } ?: return false
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        bitmap.recycle()
        return true
    }

    /** Copies a gallery image into the store as a compressed JPEG. Returns the new file name. */
    fun importImage(uri: Uri): String? {
        val resolver = context.contentResolver
        val bitmap = decodeScaled { resolver.openInputStream(uri) ?: error("cannot open $uri") } ?: return null
        val out = newFile("jpg")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        bitmap.recycle()
        return out.name
    }

    /** Copies a gallery video as is. Returns the new file name. */
    fun importVideo(uri: Uri): String? {
        val out = newFile("mp4")
        val input = context.contentResolver.openInputStream(uri) ?: return null
        input.use { src -> out.outputStream().use { src.copyTo(it) } }
        return out.name
    }

    /**
     * A new file that keeps a readable name: `<uuid>~Notes.pdf`. Documents need their original
     * name to be shown and saved back out; the prefix keeps every file name unique.
     */
    fun newNamedFile(originalName: String): File = File(dir, "${UUID.randomUUID()}$NAME_SEPARATOR${safeName(originalName)}")

    /** Copies a picked document into the store under its own name. Returns the new file name. */
    fun importDocument(uri: Uri): String? {
        val resolver = context.contentResolver
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "document"
        val out = newNamedFile(name)
        val input = resolver.openInputStream(uri) ?: return null
        input.use { src -> out.outputStream().use { src.copyTo(it) } }
        return out.name
    }

    fun saveBitmap(bitmap: Bitmap): String {
        val out = newFile("png")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return out.name
    }

    private fun decodeScaled(open: () -> InputStream): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = open().use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        val orientation = runCatching {
            open().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val scale = min(1f, MAX_SIDE.toFloat() / max(decoded.width, decoded.height))
        val matrix = Matrix().apply {
            postScale(scale, scale)
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            }
        }
        if (matrix.isIdentity) return decoded
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
            if (it !== decoded) decoded.recycle()
        }
    }.getOrNull()

    companion object {
        private const val MAX_SIDE = 1600
        private const val JPEG_QUALITY = 85
        private const val NAME_SEPARATOR = "~"
        private const val MAX_NAME_LENGTH = 120
        private val UNSAFE = Regex("[\\\\/:*?\"<>|\\x00-\\x1f]")

        /** The name to show and save as: the original name for documents, else the stored name. */
        fun displayName(fileName: String): String = fileName.substringAfter(NAME_SEPARATOR, fileName)

        /** Strips path and reserved characters, keeping the extension when the name is shortened. */
        fun safeName(raw: String): String {
            val cleaned = raw.substringAfterLast('/').substringAfterLast('\\').replace(UNSAFE, "_").trim().trim('.')
            if (cleaned.isEmpty()) return "document"
            if (cleaned.length <= MAX_NAME_LENGTH) return cleaned
            val ext = cleaned.substringAfterLast('.', "").take(10)
            val stem = cleaned.substringBeforeLast('.').take(MAX_NAME_LENGTH - ext.length - 1)
            return if (ext.isEmpty()) cleaned.take(MAX_NAME_LENGTH) else "$stem.$ext"
        }
    }
}
