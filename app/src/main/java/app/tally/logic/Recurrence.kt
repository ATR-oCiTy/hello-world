package app.tally.logic

import app.tally.data.Frequency
import app.tally.data.Recurring
import java.time.LocalDate
import java.time.YearMonth

object Recurrence {

    /** Due dates of [r] between [from] and [to], both inclusive, never before its start. */
    fun occurrences(r: Recurring, from: LocalDate, to: LocalDate): List<LocalDate> {
        val start = maxOf(from, LocalDate.ofEpochDay(r.startEpochDay))
        val to = r.endEpochDay?.let { minOf(to, LocalDate.ofEpochDay(it)) } ?: to
        if (start > to) return emptyList()
        val out = mutableListOf<LocalDate>()
        var ym = YearMonth.from(start)
        val last = YearMonth.from(to)
        while (ym <= last) {
            if (r.frequency == Frequency.MONTHLY || ym.monthValue == r.month) {
                val date = ym.atDay(minOf(r.day, ym.lengthOfMonth()))
                if (date in start..to) out += date
            }
            ym = ym.plusMonths(1)
        }
        return out
    }

    fun occursOn(r: Recurring, date: LocalDate): Boolean =
        r.active && occurrences(r, date, date).isNotEmpty()

    /** The next due date on or after [from], or null if paused. */
    fun nextDue(r: Recurring, from: LocalDate): LocalDate? {
        if (!r.active) return null
        val after = maxOf(from, LocalDate.ofEpochDay(r.postedThroughEpochDay + 1))
        return occurrences(r, after, after.plusMonths(13)).firstOrNull()
    }

    /** Occurrences that are due by [today] and haven't been posted yet. */
    fun due(r: Recurring, today: LocalDate): List<LocalDate> =
        if (!r.active) emptyList()
        else occurrences(r, LocalDate.ofEpochDay(r.postedThroughEpochDay + 1), today)

}
