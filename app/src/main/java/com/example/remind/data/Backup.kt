package com.example.remind.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.example.remind.notify.DigestScheduler
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup = zip with backup.json (all rows) + attachments/<file>.
 * Import replaces everything currently in the app.
 */
class Backup(private val context: Context, private val repo: Repository) {

    private val db get() = repo.database
    private val store get() = repo.store

    suspend fun export(target: Uri): Int {
        val notes = db.notes().allNotes()
        val attachments = db.attachments().all()
        val logs = db.reviewLogs().all()
        val digests = db.digests().all()

        val json = JSONObject().apply {
            put("version", 1)
            put("exportedAt", System.currentTimeMillis())
            put("defaultIntervals", JSONArray(repo.defaultIntervals))
            put("notes", JSONArray(notes.map { it.toJson() }))
            put("attachments", JSONArray(attachments.map { it.toJson() }))
            put("reviewLogs", JSONArray(logs.map { it.toJson() }))
            put("digests", JSONArray(digests.map { it.toJson() }))
        }

        val output = context.contentResolver.openOutputStream(target) ?: error("Cannot open backup file")
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(JSON_ENTRY))
            zip.write(json.toString().toByteArray())
            zip.closeEntry()
            attachments.forEach { attachment ->
                val file = store.file(attachment.fileName)
                if (!file.exists()) return@forEach
                zip.putNextEntry(ZipEntry(FILES_PREFIX + attachment.fileName))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                // PDF annotations ride along with their PDF.
                val ink = store.file(attachment.fileName + AttachmentStore.INK_SUFFIX)
                if (ink.exists()) {
                    zip.putNextEntry(ZipEntry(FILES_PREFIX + ink.name))
                    ink.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        return notes.size
    }

    suspend fun import(source: Uri): Int {
        val staging = File(context.cacheDir, "import").apply { deleteRecursively(); mkdirs() }
        var json: JSONObject? = null

        val input = context.contentResolver.openInputStream(source) ?: error("Cannot open backup file")
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                when {
                    entry.isDirectory -> Unit
                    name == JSON_ENTRY -> json = JSONObject(zip.readBytes().decodeToString())
                    name.startsWith(FILES_PREFIX) -> {
                        val fileName = name.removePrefix(FILES_PREFIX)
                        // Reject anything that could escape the staging directory.
                        if (fileName.isNotEmpty() && '/' !in fileName && '\\' !in fileName && fileName != "..") {
                            File(staging, fileName).outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        val data = json ?: error("Not a Yaad backup (backup.json missing)")

        val notes = data.getJSONArray("notes").objects().map(::noteFromJson)
        val attachments = data.getJSONArray("attachments").objects().map(::attachmentFromJson)
        val logs = data.getJSONArray("reviewLogs").objects().map(::logFromJson)
        val digests = data.getJSONArray("digests").objects().map(::digestFromJson)

        val oldAttachments = db.attachments().all()
        db.digests().all().forEach { DigestScheduler.cancel(context, it) }

        db.withTransaction {
            db.notes().deleteAll()
            db.digests().deleteAll()
            notes.forEach { db.notes().insert(it) }
            attachments.forEach { db.attachments().insert(it) }
            logs.forEach { db.reviewLogs().insert(it) }
            digests.forEach { db.digests().insert(it) }
        }

        val keep = attachments.map { it.fileName }.toSet()
        oldAttachments.filter { it.fileName !in keep }.forEach { store.delete(it.fileName) }
        staging.listFiles()?.forEach { file ->
            val owner = file.name.removeSuffix(AttachmentStore.INK_SUFFIX)
            if (file.name in keep || (owner != file.name && owner in keep)) file.copyTo(store.file(file.name), overwrite = true)
        }
        staging.deleteRecursively()

        data.optJSONArray("defaultIntervals")?.let { arr ->
            val values = (0 until arr.length()).map { arr.getInt(it) }
            if (values.isNotEmpty() && values.all { it > 0 }) repo.defaultIntervals = values
        }
        repo.rescheduleDigests()
        return notes.size
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun Note.toJson() = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("body", body)
        put("subject", subject)
        put("keywords", JSONArray(keywords))
        put("intervals", JSONArray(intervals))
        put("stage", stage)
        put("nextDueDay", nextDueDay)
        put("mastered", mastered)
        put("starred", starred)
        put("createdAt", createdAt)
        put("lastReviewedAt", lastReviewedAt ?: JSONObject.NULL)
        put("remoteId", remoteId ?: JSONObject.NULL)
    }

    private fun noteFromJson(o: JSONObject): Note {
        val keywords = o.getJSONArray("keywords")
        val intervals = o.getJSONArray("intervals")
        return Note(
            id = o.getLong("id"),
            title = o.getString("title"),
            body = o.optString("body"),
            subject = o.optString("subject"),
            keywords = (0 until keywords.length()).map { keywords.getString(it) },
            intervals = (0 until intervals.length()).map { intervals.getInt(it) }.ifEmpty { Scheduler.DEFAULT_INTERVALS },
            stage = o.getInt("stage"),
            nextDueDay = o.getLong("nextDueDay"),
            mastered = o.getBoolean("mastered"),
            starred = o.optBoolean("starred", false),
            createdAt = o.getLong("createdAt"),
            lastReviewedAt = if (o.isNull("lastReviewedAt")) null else o.getLong("lastReviewedAt"),
            remoteId = if (o.isNull("remoteId")) null else o.optString("remoteId").ifEmpty { null },
        )
    }

    private fun Attachment.toJson() = JSONObject().apply {
        put("id", id)
        put("noteId", noteId)
        put("fileName", fileName)
        put("type", type.name)
        put("createdAt", createdAt)
    }

    private fun attachmentFromJson(o: JSONObject) = Attachment(
        id = o.getLong("id"),
        noteId = o.getLong("noteId"),
        fileName = o.getString("fileName"),
        type = AttachmentType.valueOf(o.getString("type")),
        createdAt = o.getLong("createdAt"),
    )

    private fun ReviewLog.toJson() = JSONObject().apply {
        put("id", id)
        put("noteId", noteId)
        put("reviewedAt", reviewedAt)
        put("day", day)
        put("result", result.name)
    }

    private fun logFromJson(o: JSONObject) = ReviewLog(
        id = o.getLong("id"),
        noteId = o.getLong("noteId"),
        reviewedAt = o.getLong("reviewedAt"),
        day = o.getLong("day"),
        result = ReviewResult.valueOf(o.getString("result")),
    )

    private fun Digest.toJson() = JSONObject().apply {
        put("id", id)
        put("hour", hour)
        put("minute", minute)
        put("enabled", enabled)
    }

    private fun digestFromJson(o: JSONObject) = Digest(
        id = o.getLong("id"),
        hour = o.getInt("hour"),
        minute = o.getInt("minute"),
        enabled = o.getBoolean("enabled"),
    )

    private companion object {
        const val JSON_ENTRY = "backup.json"
        const val FILES_PREFIX = "attachments/"
    }
}
