package com.example.remind.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import androidx.room.TypeConverter

@Entity(tableName = "notes", indices = [Index(value = ["remoteId"], unique = true)])
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val body: String = "",
    val subject: String = "",
    val keywords: List<String> = emptyList(),
    val intervals: List<Int> = Scheduler.DEFAULT_INTERVALS,
    val stage: Int = 0,
    val nextDueDay: Long,
    val mastered: Boolean = false,
    val starred: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastReviewedAt: Long? = null,
    /** Inbox entry id when the note came in through sync; guards against importing it twice. */
    val remoteId: String? = null,
)

enum class AttachmentType { PHOTO, HANDWRITING, AUDIO, VIDEO }

@Entity(
    tableName = "attachments",
    foreignKeys = [ForeignKey(
        entity = Note::class,
        parentColumns = ["id"],
        childColumns = ["noteId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("noteId")],
)
data class Attachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    /** File name inside filesDir/attachments. */
    val fileName: String,
    val type: AttachmentType,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "review_logs",
    foreignKeys = [ForeignKey(
        entity = Note::class,
        parentColumns = ["id"],
        childColumns = ["noteId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("noteId"), Index("day")],
)
data class ReviewLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val reviewedAt: Long,
    val day: Long,
    val result: ReviewResult,
)

@Entity(tableName = "digests")
data class Digest(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean = true,
)

/** One reminder notification that was shown, and what was tapped on it. */
@Entity(tableName = "notification_log")
data class NotificationLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long?,
    val title: String,
    val detail: String,
    val postedAt: Long,
    /** ReviewResult name when a rating was tapped on the notification. */
    val response: String? = null,
)

/** Notes grouped by subject ("deck"). */
data class DeckSummary(
    val subject: String,
    val total: Int,
    val due: Int,
    val mastered: Int,
)

data class NoteWithAttachments(
    @Embedded val note: Note,
    @Relation(parentColumn = "id", entityColumn = "noteId")
    val attachments: List<Attachment>,
)

class Converters {
    @TypeConverter
    fun keywordsToString(list: List<String>): String = list.joinToString("\n")

    @TypeConverter
    fun stringToKeywords(value: String): List<String> =
        if (value.isEmpty()) emptyList() else value.split("\n")

    @TypeConverter
    fun intervalsToString(list: List<Int>): String = list.joinToString(",")

    @TypeConverter
    fun stringToIntervals(value: String): List<Int> =
        value.split(",").mapNotNull { it.trim().toIntOrNull() }.ifEmpty { Scheduler.DEFAULT_INTERVALS }

    @TypeConverter
    fun typeToString(type: AttachmentType): String = type.name

    @TypeConverter
    fun stringToType(value: String): AttachmentType = AttachmentType.valueOf(value)

    @TypeConverter
    fun resultToString(result: ReviewResult): String = result.name

    @TypeConverter
    fun stringToResult(value: String): ReviewResult = ReviewResult.valueOf(value)
}
