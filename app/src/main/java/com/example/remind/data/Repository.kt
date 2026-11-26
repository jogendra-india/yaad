package com.example.remind.data

import android.content.Context
import androidx.room.withTransaction
import com.example.remind.notify.DigestScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

enum class ThemeMode { SYSTEM, LIGHT, DARK }

fun today(): Long = LocalDate.now().toEpochDay()

/** An attachment picked in the editor; its file already exists in the store. */
data class DraftAttachment(val fileName: String, val type: AttachmentType, val existingId: Long? = null)

class Repository(
    private val context: Context,
    private val db: AppDatabase,
    val store: AttachmentStore,
) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var defaultIntervals: List<Int>
        get() = prefs.getString(KEY_INTERVALS, null)?.let(Scheduler::parseIntervals) ?: Scheduler.DEFAULT_INTERVALS
        set(value) = prefs.edit().putString(KEY_INTERVALS, Scheduler.formatIntervals(value)).apply()

    private val _themeMode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "") }.getOrDefault(ThemeMode.SYSTEM)
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode

    private val _dynamicColor = MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC, false))
    val dynamicColor: StateFlow<Boolean> = _dynamicColor

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _themeMode.value = mode
    }

    fun setDynamicColor(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC, enabled).apply()
        _dynamicColor.value = enabled
    }

    private val _remindersEnabled = MutableStateFlow(prefs.getBoolean(KEY_REMINDERS, true))
    /** Master switch for all digest notifications. */
    val remindersEnabled: StateFlow<Boolean> = _remindersEnabled

    suspend fun setRemindersEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REMINDERS, enabled).apply()
        _remindersEnabled.value = enabled
        rescheduleDigests()
    }

    fun observeDue(today: Long) = db.notes().observeDue(today)
    fun observeUpcoming(today: Long) = db.notes().observeUpcoming(today)
    fun observeAll() = db.notes().observeAll()
    fun observe(id: Long) = db.notes().observe(id)
    fun observeSubjects() = db.notes().observeSubjects()
    fun observeReviewDays(): Flow<List<Long>> = db.reviewLogs().observeDays()
    fun observeLogs(noteId: Long) = db.reviewLogs().observeForNote(noteId)
    fun observeDecks(today: Long) = db.notes().observeDecks(today)
    fun observeStarredCount() = db.notes().observeStarredCount()

    suspend fun idsInDeck(subject: String) = db.notes().idsInDeck(subject)
    suspend fun starredIds() = db.notes().starredIds()
    suspend fun setStarred(id: Long, starred: Boolean) = db.notes().setStarred(id, starred)

    suspend fun get(id: Long) = db.notes().get(id)
    suspend fun dueIds() = db.notes().dueIds(today())
    suspend fun dueTitles() = db.notes().dueTitles(today())

    suspend fun saveNote(
        existing: Note?,
        title: String,
        body: String,
        subject: String,
        keywords: List<String>,
        intervals: List<Int>,
        attachments: List<DraftAttachment>,
        removed: List<Attachment>,
    ): Long {
        val noteId = db.withTransaction {
            val id = if (existing == null) {
                db.notes().insert(
                    Note(
                        title = title,
                        body = body,
                        subject = subject,
                        keywords = keywords,
                        intervals = intervals,
                        nextDueDay = Scheduler.firstDueDay(intervals, today()),
                    )
                )
            } else {
                db.notes().update(
                    existing.copy(
                        title = title,
                        body = body,
                        subject = subject,
                        keywords = keywords,
                        intervals = intervals,
                        stage = existing.stage.coerceAtMost(intervals.size),
                    )
                )
                existing.id
            }
            removed.forEach { db.attachments().delete(it) }
            attachments.filter { it.existingId == null }.forEach {
                db.attachments().insert(Attachment(noteId = id, fileName = it.fileName, type = it.type))
            }
            id
        }
        removed.forEach { store.delete(it.fileName) }
        return noteId
    }

    suspend fun review(noteId: Long, result: ReviewResult) {
        val note = db.notes().get(noteId)?.note ?: return
        val day = today()
        val outcome = Scheduler.review(note.stage, note.intervals, result, day)
        val now = System.currentTimeMillis()
        db.withTransaction {
            db.notes().update(
                note.copy(
                    stage = outcome.stage,
                    nextDueDay = outcome.nextDueDay,
                    mastered = outcome.mastered,
                    lastReviewedAt = now,
                )
            )
            db.reviewLogs().insert(ReviewLog(noteId = noteId, reviewedAt = now, day = day, result = result))
        }
    }

    suspend fun resetProgress(note: Note) {
        db.notes().update(
            note.copy(stage = 0, mastered = false, nextDueDay = Scheduler.firstDueDay(note.intervals, today()))
        )
    }

    suspend fun makeDueToday(note: Note) {
        db.notes().update(note.copy(nextDueDay = today(), mastered = false))
    }

    suspend fun delete(item: NoteWithAttachments) {
        db.notes().delete(item.note)
        item.attachments.forEach { store.delete(it.fileName) }
    }

    // Digests

    fun observeDigests() = db.digests().observeAll()

    suspend fun digest(id: Long) = db.digests().get(id)

    suspend fun addDigest(hour: Int, minute: Int) {
        val id = db.digests().insert(Digest(hour = hour, minute = minute))
        applyDigest(Digest(id, hour, minute, true))
    }

    suspend fun setDigestEnabled(digest: Digest, enabled: Boolean) {
        val updated = digest.copy(enabled = enabled)
        db.digests().update(updated)
        applyDigest(updated)
    }

    suspend fun removeDigest(digest: Digest) {
        db.digests().delete(digest)
        DigestScheduler.cancel(context, digest)
    }

    suspend fun rescheduleDigests() {
        db.digests().all().forEach { applyDigest(it) }
    }

    /** Schedules the digest, or cancels it when it or the master switch is off. */
    fun applyDigest(digest: Digest) {
        if (_remindersEnabled.value) DigestScheduler.schedule(context, digest) else DigestScheduler.cancel(context, digest)
    }

    /** First launch: one evening digest at 8 PM. */
    suspend fun seedIfNeeded() {
        if (prefs.getBoolean(KEY_SEEDED, false)) return
        if (db.digests().all().isEmpty()) db.digests().insert(Digest(hour = 20, minute = 0))
        prefs.edit().putBoolean(KEY_SEEDED, true).apply()
    }

    internal val database get() = db

    private companion object {
        const val KEY_INTERVALS = "default_intervals"
        const val KEY_SEEDED = "seeded"
        const val KEY_THEME = "theme_mode"
        const val KEY_DYNAMIC = "dynamic_color"
        const val KEY_REMINDERS = "reminders_enabled"
    }
}
