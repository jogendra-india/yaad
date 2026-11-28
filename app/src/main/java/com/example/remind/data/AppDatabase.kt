package com.example.remind.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Transaction
    @Query("SELECT * FROM notes WHERE mastered = 0 AND nextDueDay <= :today ORDER BY nextDueDay, createdAt")
    fun observeDue(today: Long): Flow<List<NoteWithAttachments>>

    @Transaction
    @Query("SELECT * FROM notes WHERE mastered = 0 AND nextDueDay > :today ORDER BY nextDueDay, createdAt LIMIT 30")
    fun observeUpcoming(today: Long): Flow<List<NoteWithAttachments>>

    @Transaction
    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<NoteWithAttachments>>

    @Transaction
    @Query("SELECT * FROM notes WHERE id = :id")
    fun observe(id: Long): Flow<NoteWithAttachments?>

    @Transaction
    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: Long): NoteWithAttachments?

    @Query("SELECT id FROM notes WHERE mastered = 0 AND nextDueDay <= :today ORDER BY nextDueDay, createdAt")
    suspend fun dueIds(today: Long): List<Long>

    @Query("SELECT title FROM notes WHERE mastered = 0 AND nextDueDay <= :today ORDER BY nextDueDay, createdAt")
    suspend fun dueTitles(today: Long): List<String>

    @Query("SELECT * FROM notes WHERE mastered = 0 AND nextDueDay <= :today ORDER BY nextDueDay, createdAt")
    suspend fun dueNotes(today: Long): List<Note>

    @Query("SELECT DISTINCT subject FROM notes WHERE subject != '' ORDER BY subject COLLATE NOCASE")
    fun observeSubjects(): Flow<List<String>>

    @Query(
        """SELECT subject, COUNT(*) AS total,
        SUM(CASE WHEN mastered = 0 AND nextDueDay <= :today THEN 1 ELSE 0 END) AS due,
        SUM(CASE WHEN mastered = 1 THEN 1 ELSE 0 END) AS mastered
        FROM notes GROUP BY subject ORDER BY subject = '', subject COLLATE NOCASE"""
    )
    fun observeDecks(today: Long): Flow<List<DeckSummary>>

    @Query("SELECT COUNT(*) FROM notes WHERE starred = 1")
    fun observeStarredCount(): Flow<Int>

    @Query("SELECT id FROM notes WHERE subject = :subject ORDER BY createdAt")
    suspend fun idsInDeck(subject: String): List<Long>

    @Query("SELECT id FROM notes WHERE starred = 1 ORDER BY createdAt")
    suspend fun starredIds(): List<Long>

    @Query("UPDATE notes SET starred = :starred WHERE id = :id")
    suspend fun setStarred(id: Long, starred: Boolean)

    @Query("SELECT * FROM notes")
    suspend fun allNotes(): List<Note>

    @Query("SELECT remoteId FROM notes WHERE remoteId IN (:remoteIds)")
    suspend fun existingRemoteIds(remoteIds: List<String>): List<String>

    @Insert
    suspend fun insert(note: Note): Long

    @Update
    suspend fun update(note: Note)

    @Delete
    suspend fun delete(note: Note)

    @Query("DELETE FROM notes")
    suspend fun deleteAll()
}

@Dao
interface AttachmentDao {
    @Insert
    suspend fun insert(attachment: Attachment): Long

    @Delete
    suspend fun delete(attachment: Attachment)

    @Query("SELECT * FROM attachments")
    suspend fun all(): List<Attachment>
}

@Dao
interface ReviewLogDao {
    @Insert
    suspend fun insert(log: ReviewLog): Long

    @Query("SELECT DISTINCT day FROM review_logs")
    fun observeDays(): Flow<List<Long>>

    @Query("SELECT * FROM review_logs WHERE noteId = :noteId ORDER BY reviewedAt DESC")
    fun observeForNote(noteId: Long): Flow<List<ReviewLog>>

    @Query("SELECT * FROM review_logs")
    suspend fun all(): List<ReviewLog>
}

@Dao
interface DigestDao {
    @Query("SELECT * FROM digests ORDER BY hour, minute")
    fun observeAll(): Flow<List<Digest>>

    @Query("SELECT * FROM digests")
    suspend fun all(): List<Digest>

    @Query("SELECT * FROM digests WHERE id = :id")
    suspend fun get(id: Long): Digest?

    @Insert
    suspend fun insert(digest: Digest): Long

    @Update
    suspend fun update(digest: Digest)

    @Delete
    suspend fun delete(digest: Digest)

    @Query("DELETE FROM digests")
    suspend fun deleteAll()
}

@Dao
interface NotificationLogDao {
    @Insert
    suspend fun insert(log: NotificationLog): Long

    @Query("SELECT * FROM notification_log ORDER BY postedAt DESC LIMIT 500")
    fun observeAll(): Flow<List<NotificationLog>>

    @Query("UPDATE notification_log SET response = :response WHERE id = (SELECT MAX(id) FROM notification_log WHERE noteId = :noteId)")
    suspend fun setResponse(noteId: Long, response: String)

    @Query("DELETE FROM notification_log WHERE postedAt < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM notification_log")
    suspend fun deleteAll()
}

/** v2 adds the notification history table. */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `notification_log` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `noteId` INTEGER, `title` TEXT NOT NULL, " +
                "`detail` TEXT NOT NULL, `postedAt` INTEGER NOT NULL, `response` TEXT)"
        )
    }
}

/** v3 records which inbox entry a synced note came from. */
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `notes` ADD COLUMN `remoteId` TEXT")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_notes_remoteId` ON `notes` (`remoteId`)")
    }
}

@Database(
    entities = [Note::class, Attachment::class, ReviewLog::class, Digest::class, NotificationLog::class],
    version = 3,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao
    abstract fun attachments(): AttachmentDao
    abstract fun reviewLogs(): ReviewLogDao
    abstract fun digests(): DigestDao
    abstract fun notificationLogs(): NotificationLogDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "remind.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
