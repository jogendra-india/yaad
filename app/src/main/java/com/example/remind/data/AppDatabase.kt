package com.example.remind.data

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

@Database(
    entities = [Note::class, Attachment::class, ReviewLog::class, Digest::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao
    abstract fun attachments(): AttachmentDao
    abstract fun reviewLogs(): ReviewLogDao
    abstract fun digests(): DigestDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "remind.db").build()
    }
}
