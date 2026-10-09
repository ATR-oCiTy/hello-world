package app.tally.logic

import app.tally.data.Category
import app.tally.data.Expense
import app.tally.data.Recurring
import app.tally.data.Source
import app.tally.parser.Classifier
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.min

/** A monthly payment spotted in your history that isn't a plan yet. */
data class Suggestion(
    /** Merchant key + direction; identifies the series for dismissing and linking. */
    val id: String,
    val matchKey: String,
    val name: String,
    /** The most recent amount. */
    val amount: Double,
    val income: Boolean,
    val category: Category,
    val day: Int,
    val lastDate: LocalDate,
    val months: Int,
    /** True when recent amounts differ by more than a few percent (salary, phone bill). */
    val varies: Boolean,
    /** Every payee name seen in the series (rent can go to more than one). */
    val payees: List<String> = emptyList(),
)

/**
 * Finds monthly series: the same payee and direction, about one payment per calendar month for at
 * least three months running, around the same day, still happening recently.
 */
object RecurringDetector {

    private const val MIN_MONTHS = 3
    private const val MAX_DAY_DRIFT = 6
    private const val STALE_AFTER_DAYS = 45L

    fun detect(
        expenses: List<Expense>,
        plans: List<Recurring>,
        dismissed: Set<String>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Suggestion> {
        val planKeys = plans.flatMap { p -> p.matchKeys() + seriesKey(p.name) }.toSet()
        fun Expense.date() = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()

        val candidates = expenses
            .filter { it.source != Source.RECURRING && it.recurringId == null && it.amount >= 1.0 }
            .map { it to it.date() }

        // Pass 1: the same payee every month.
        val byPayee = candidates
            .groupBy { seriesKey(it.first.merchant) to it.first.income }
            .mapNotNull { (k, list) ->
                val (key, income) = k
                if (key.isEmpty() || key in planKeys) return@mapNotNull null
                series(listOf(key), income, list, today, maxSpread = 0.25)
            }
        val covered = byPayee.flatMap { it.matchKey.split('|') }.toSet() + planKeys

        // Pass 2: big payments of about the same amount, even when the payee's name changes
        // (rent paid at two terminals of the same landlord, for example).
        val byAmount = mutableListOf<Suggestion>()
        var pool = candidates.filter {
            !it.first.income && it.first.amount >= 100 && seriesKey(it.first.merchant) !in covered
        }
        while (pool.isNotEmpty()) {
            val best = pool.map { it.first.amount }.distinct()
                .map { a -> a to pool.filter { abs(it.first.amount - a) / a <= 0.04 } }
                .maxByOrNull { it.second.size } ?: break
            val members = best.second
            val keys = members.map { seriesKey(it.first.merchant) }.distinct()
            series(keys, false, members, today, maxSpread = 0.1)?.let { byAmount += it }
            pool = pool - members.toSet()
            if (members.size < MIN_MONTHS) break
        }

        return (byPayee + byAmount)
            .filter { it.id !in dismissed }
            // Already planned under another name (e.g. you typed "Rent" yourself).
            .filterNot { sug ->
                plans.any { p ->
                    p.income == sug.income && abs(p.amountOn(today) - sug.amount) / sug.amount <= 0.05 &&
                        dayDistance(p.day, sug.day) <= MAX_DAY_DRIFT
                }
            }
            .sortedByDescending { it.amount }
    }

    /** Merchant key that ignores spelling variants like "München" / "Muenchen" / "Munchen". */
    fun seriesKey(merchant: String): String =
        Classifier.key(
            merchant.lowercase()
                .replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "ss")
                .replace("ae", "a").replace("oe", "o").replace("ue", "u"),
        )

    private fun series(
        keys: List<String>,
        income: Boolean,
        items: List<Pair<Expense, LocalDate>>,
        today: LocalDate,
        maxSpread: Double,
    ): Suggestion? {
        val byMonth = items.groupBy { YearMonth.from(it.second) }
        if (byMonth.size < MIN_MONTHS) return null
        // A shop you visit weekly isn't a monthly bill.
        if (items.size > byMonth.size * 1.5) return null

        val median = items.map { it.first.amount }.sorted()[items.size / 2]
        // One payment per month: the one closest to the typical amount.
        val picks = byMonth.toSortedMap().values.map { month -> month.minBy { abs(it.first.amount - median) } }

        // The run of roughly-monthly payments (20–45 days apart) ending with the most recent one.
        val run = mutableListOf(picks.last())
        for (i in picks.size - 2 downTo 0) {
            val gap = ChronoUnit.DAYS.between(picks[i].second, run.first().second)
            if (gap in 20..45) run.add(0, picks[i]) else break
        }
        if (run.size < MIN_MONTHS) return null

        val last = run.last()
        if (ChronoUnit.DAYS.between(last.second, today) > STALE_AFTER_DAYS) return null

        // Around the same day each month (allowing for weekends and month ends).
        val recent = run.takeLast(4)
        val days = recent.map { it.second.dayOfMonth }
        val typicalDay = days.minBy { d -> days.sumOf { dayDistance(it, d) } }
        val dayOk = recent.count { dayDistance(it.second.dayOfMonth, typicalDay) <= MAX_DAY_DRIFT } >= recent.size - 1
        if (!dayOk) return null

        val amounts = recent.map { it.first.amount }
        val spread = (amounts.max() - amounts.min()) / amounts.sorted()[amounts.size / 2]
        // Bills keep a steady amount (allowing for raises and exchange rates); shopping doesn't.
        if (spread > maxSpread) return null

        val latest = last.first
        val matchKey = keys.sorted().joinToString("|")
        // A big same-amount payment that hops between payees is almost always rent.
        val looksLikeRent = !income && keys.size > 1 && latest.amount >= 300 && latest.category == Category.OTHER
        return Suggestion(
            id = "$matchKey|${if (income) "in" else "out"}",
            matchKey = matchKey,
            name = if (looksLikeRent) "Rent" else latest.merchant,
            amount = latest.amount,
            income = income,
            category = when {
                income -> Category.INCOME
                looksLikeRent -> Category.HOUSING
                else -> latest.category
            },
            day = typicalDay,
            lastDate = last.second,
            months = run.size,
            varies = spread > 0.02,
            payees = run.map { it.first.merchant }.distinct(),
        )
    }

    /** Distance between days of the month, treating the 31st and the 1st as neighbours. */
    private fun dayDistance(a: Int, b: Int): Int {
        val d = abs(a - b)
        return min(d, 31 - d)
    }
}
