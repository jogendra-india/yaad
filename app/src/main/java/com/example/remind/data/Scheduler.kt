package com.example.remind.data

/**
 * Spaced-repetition rules. Days are epoch days (LocalDate.toEpochDay()).
 *
 * `stage` = how many successful reviews a note has had. The wait before the
 * next review is `intervals[stage]`; once stage reaches intervals.size the note
 * is mastered.
 */
object Scheduler {

    val DEFAULT_INTERVALS = listOf(1, 2, 4, 7, 15, 30, 60)

    data class Outcome(val stage: Int, val nextDueDay: Long, val mastered: Boolean)

    /** Parses "1, 2,4 7" into [1, 2, 4, 7]. Returns null if empty or any value is not a positive int. */
    fun parseIntervals(text: String): List<Int>? {
        val parts = text.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val values = parts.map { it.toIntOrNull() ?: return null }
        if (values.any { it <= 0 || it > 3650 }) return null
        return values
    }

    fun formatIntervals(intervals: List<Int>): String = intervals.joinToString(", ")

    fun firstDueDay(intervals: List<Int>, today: Long): Long = today + intervals.first()

    fun review(stage: Int, intervals: List<Int>, result: ReviewResult, today: Long): Outcome {
        require(intervals.isNotEmpty())
        val current = stage.coerceIn(0, intervals.lastIndex)
        return when (result) {
            ReviewResult.GOOD -> {
                val next = current + 1
                if (next >= intervals.size) {
                    Outcome(intervals.size, today, mastered = true)
                } else {
                    Outcome(next, today + intervals[next], mastered = false)
                }
            }
            ReviewResult.HARD -> Outcome(current, today + intervals[current], mastered = false)
            ReviewResult.FORGOT -> Outcome(0, today + intervals[0], mastered = false)
        }
    }

    /** Consecutive days with at least one review, ending today (or yesterday, if nothing yet today). */
    fun streak(reviewDays: Collection<Long>, today: Long): Int {
        val days = reviewDays.toHashSet()
        var day = if (today in days) today else today - 1
        var count = 0
        while (day in days) {
            count++
            day--
        }
        return count
    }
}

enum class ReviewResult { GOOD, HARD, FORGOT }
