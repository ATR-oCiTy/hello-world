package app.tally.logic

import app.tally.data.Category
import app.tally.data.Expense
import app.tally.data.Recurring
import app.tally.data.Source
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class MonthStats(
    val spent: Double,
    val income: Double,
    /** Recurring costs already posted this month. */
    val fixedSpent: Double,
    /** Recurring costs still to come this month. */
    val upcomingFixed: Double,
    val upcomingIncome: Double,
    /** Spent + upcoming fixed + your usual daily spending for the rest of the month. */
    val projectedSpend: Double,
    val dayOfMonth: Int,
    val daysInMonth: Int,
    val budget: Double?,
    /** Budget minus what's spent and what's already committed. */
    val safeToSpend: Double?,
    val dailyAllowance: Double?,
    /** Where your spending "should" be today if you were exactly on budget. */
    val expectedByNow: Double?,
    /** Spending in the same days of last month, for comparison. */
    val lastMonthToDate: Double,
) {
    val daysLeft: Int get() = daysInMonth - dayOfMonth + 1
    /** Positive when you're ahead of a straight-line budget (spending too fast). */
    val overPace: Double? get() = expectedByNow?.let { spent - it }
}

data class Runway(
    /** First day the balance drops below zero, or null if it doesn't within the horizon. */
    val runOut: LocalDate?,
    val months: Double?,
    /** Average monthly change in balance: income minus fixed costs minus usual spending. */
    val monthlyNet: Double,
)

data class CategoryChange(val category: Category, val thisMonth: Double, val lastMonth: Double)

data class MonthTotals(val month: YearMonth, val spent: Double, val income: Double)

object Metrics {

    private const val DAYS_PER_MONTH = 30.44
    private const val HORIZON_DAYS = 5 * 366

    private fun Expense.date(zone: ZoneId): LocalDate = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()

    /**
     * Your usual day-to-day spending: everything except recurring items, averaged over the
     * last [window] days (or fewer if you haven't used Tally that long; at least a week).
     */
    fun dailyVariableSpend(
        expenses: List<Expense>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        window: Int = 60,
    ): Double {
        val from = today.minusDays(window - 1L)
        val variable = expenses.filter { !it.income && !it.isPlanned }
        val inWindow = variable.filter { it.date(zone) in from..today }
        if (inWindow.isEmpty()) return 0.0
        val first = variable.minOf { it.date(zone) }
        val days = (ChronoUnit.DAYS.between(maxOf(first, from), today) + 1).coerceIn(7, window.toLong())
        return inWindow.sumOf { it.amount } / days
    }

    fun month(
        expenses: List<Expense>,
        recurring: List<Recurring>,
        budget: Double?,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): MonthStats {
        val ym = YearMonth.from(today)
        val end = ym.atEndOfMonth()
        val thisMonth = expenses.filter { YearMonth.from(it.date(zone)) == ym }
        val spent = thisMonth.filter { !it.income }.sumOf { it.amount }
        val income = thisMonth.filter { it.income }.sumOf { it.amount }
        val fixedSpent = thisMonth.filter { !it.income && it.isPlanned }.sumOf { it.amount }

        fun upcoming(wantIncome: Boolean) = recurring
            .filter { it.active && it.income == wantIncome }
            .sumOf { r ->
                // A monthly bill counts once per month: rent due on the 30th but paid on the 1st has
                // already been paid this month, so its next due date belongs to next month's budget.
                val paidThisMonth = r.frequency == app.tally.data.Frequency.MONTHLY &&
                    thisMonth.any { it.recurringId == r.id }
                if (paidThisMonth) return@sumOf 0.0
                val from = maxOf(today, LocalDate.ofEpochDay(r.postedThroughEpochDay + 1))
                Recurrence.occurrences(r, from, end).sumOf { r.amountOn(it) }
            }
        val upcomingFixed = upcoming(false)
        val upcomingIncome = upcoming(true)

        val daily = dailyVariableSpend(expenses, today, zone)
        val restOfMonth = ym.lengthOfMonth() - today.dayOfMonth
        val projected = spent + upcomingFixed + daily * restOfMonth

        val lastYm = ym.minusMonths(1)
        val lastCutoff = lastYm.atDay(minOf(today.dayOfMonth, lastYm.lengthOfMonth()))
        val lastMonthToDate = expenses
            .filter { !it.income && it.date(zone).let { d -> YearMonth.from(d) == lastYm && d <= lastCutoff } }
            .sumOf { it.amount }

        val safe = budget?.let { it - spent - upcomingFixed }
        val expected = budget?.let { b ->
            val fixedTotal = fixedSpent + upcomingFixed
            fixedSpent + (b - fixedTotal).coerceAtLeast(0.0) * today.dayOfMonth / ym.lengthOfMonth()
        }

        return MonthStats(
            spent = spent,
            income = income,
            fixedSpent = fixedSpent,
            upcomingFixed = upcomingFixed,
            upcomingIncome = upcomingIncome,
            projectedSpend = projected,
            dayOfMonth = today.dayOfMonth,
            daysInMonth = ym.lengthOfMonth(),
            budget = budget,
            safeToSpend = safe,
            dailyAllowance = safe?.let { it / (restOfMonth + 1) },
            expectedByNow = expected,
            lastMonthToDate = lastMonthToDate,
        )
    }

    /**
     * Walks forward day by day from tomorrow: subtract usual daily spending, apply each recurring
     * item on its due day, and report when the balance first goes negative.
     */
    fun runway(
        balance: Double,
        today: LocalDate,
        dailySpend: Double,
        recurring: List<Recurring>,
    ): Runway {
        val active = recurring.filter { it.active }
        // Describe the month ahead, so a raise or an ending payout already shows up.
        val ahead = today.plusMonths(1)
        val monthlyNet = active.sumOf { if (it.income) it.monthlyAmountOn(ahead) else -it.monthlyAmountOn(ahead) } -
            dailySpend * DAYS_PER_MONTH
        if (balance < 0) return Runway(today, 0.0, monthlyNet)

        val horizon = today.plusDays(HORIZON_DAYS.toLong())
        // Pre-compute every recurring movement in the horizon instead of checking each day.
        val movements = HashMap<LocalDate, Double>()
        active.forEach { r ->
            val from = maxOf(today.plusDays(1), LocalDate.ofEpochDay(r.postedThroughEpochDay + 1))
            Recurrence.occurrences(r, from, horizon).forEach { d ->
                val amount = r.amountOn(d)
                movements[d] = (movements[d] ?: 0.0) + if (r.income) amount else -amount
            }
        }

        var bal = balance
        var day = today
        while (day < horizon) {
            day = day.plusDays(1)
            bal += (movements[day] ?: 0.0) - dailySpend
            if (bal < 0) {
                val days = ChronoUnit.DAYS.between(today, day)
                return Runway(day, days / DAYS_PER_MONTH, monthlyNet)
            }
        }
        return Runway(null, null, monthlyNet)
    }

    /** Usual daily spending if you spend exactly your budget (minus fixed costs). */
    fun budgetDailySpend(budget: Double, recurring: List<Recurring>, today: LocalDate): Double {
        val fixed = recurring.filter { !it.income }.sumOf { it.monthlyAmountOn(today) }
        return ((budget - fixed) / DAYS_PER_MONTH).coerceAtLeast(0.0)
    }

    fun history(
        expenses: List<Expense>,
        today: LocalDate,
        months: Int = 6,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<MonthTotals> {
        val current = YearMonth.from(today)
        val byMonth = expenses.groupBy { YearMonth.from(it.date(zone)) }
        return (months - 1 downTo 0).map { back ->
            val ym = current.minusMonths(back.toLong())
            val list = byMonth[ym].orEmpty()
            MonthTotals(ym, list.filter { !it.income }.sumOf { it.amount }, list.filter { it.income }.sumOf { it.amount })
        }
    }

    /** Spending per category this month vs the same days last month, biggest first. */
    fun categoryChanges(
        expenses: List<Expense>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<CategoryChange> {
        val ym = YearMonth.from(today)
        val lastYm = ym.minusMonths(1)
        val lastCutoff = lastYm.atDay(minOf(today.dayOfMonth, lastYm.lengthOfMonth()))
        val spending = expenses.filter { !it.income }
        val now = spending.filter { YearMonth.from(it.date(zone)) == ym }.groupBy { it.category }
        val before = spending
            .filter { it.date(zone).let { d -> YearMonth.from(d) == lastYm && d <= lastCutoff } }
            .groupBy { it.category }
        return (now.keys + before.keys)
            .map { c -> CategoryChange(c, now[c].orEmpty().sumOf { it.amount }, before[c].orEmpty().sumOf { it.amount }) }
            .sortedByDescending { it.thisMonth }
    }
}
