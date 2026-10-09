package app.tally.logic

import app.tally.data.Expense
import app.tally.data.Recurring
import app.tally.data.Source
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

enum class MatchKind {
    /** Already in Tally (an earlier import of an overlapping statement, a tap, a manual entry). */
    DUPLICATE,
    /** You unticked this row in an earlier import. */
    SKIPPED_BEFORE,
    /** A plan posted an estimate for this; the real bank row should replace it. */
    REPLACES_PLANNED,
}

data class ImportMatch(val kind: MatchKind, val existing: Expense?)

/**
 * Decides, for each row of a statement being imported, whether Tally already has it.
 * Statements overlap from month to month, so this has to be exact for re-uploads while still
 * catching the same payment logged another way (a Google Wallet tap, or a plan's estimate).
 */
object ImportMatcher {

    private const val TAP_DAYS = 3L
    private const val PLAN_DAYS = 10L

    /** Stable identity of a statement row: the bank's operation id when it has one. */
    fun fingerprint(l: StatementLine): String = l.externalId ?: listOf(
        l.date.toString(),
        "%.2f".format(Locale.US, l.amount),
        if (l.income) "in" else "out",
        RecurringDetector.seriesKey(l.merchant ?: l.description),
    ).joinToString("|")

    fun match(
        lines: List<StatementLine>,
        expenses: List<Expense>,
        plans: List<Recurring>,
        skipped: Set<String>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<ImportMatch?> {
        val used = mutableSetOf<String>()
        val dated = expenses.map { it to Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
        val byExternalId = expenses.filter { it.externalId != null }.associateBy { it.externalId!! }
        val plansById = plans.associateBy { it.id }

        fun closest(l: StatementLine, candidates: List<Pair<Expense, LocalDate>>): Expense? =
            candidates.minByOrNull { (_, d) -> abs(ChronoUnit.DAYS.between(d, l.date)) }?.first?.also { used += it.id }

        return lines.map { l ->
            l.externalId?.let { id -> byExternalId[id]?.takeIf { it.id !in used } }?.let {
                used += it.id
                return@map ImportMatch(MatchKind.DUPLICATE, it)
            }
            if (fingerprint(l) in skipped) return@map ImportMatch(MatchKind.SKIPPED_BEFORE, null)

            val key = RecurringDetector.seriesKey(l.merchant ?: l.description)
            val same = dated.filter { (e, d) ->
                val days = abs(ChronoUnit.DAYS.between(d, l.date))
                e.id !in used && e.source != Source.RECURRING && e.income == l.income &&
                    abs(e.amount - l.amount) < 0.01 &&
                    when {
                        // Two rows that both carry bank ids are different transactions if the ids differ.
                        e.externalId != null && l.externalId != null -> false
                        e.source == Source.IMPORT -> days == 0L
                        else -> days <= TAP_DAYS
                    }
            }
            closest(l, same)?.let { return@map ImportMatch(MatchKind.DUPLICATE, it) }

            val planned = dated.filter { (e, d) ->
                if (e.id in used || e.source != Source.RECURRING || e.income != l.income) return@filter false
                if (abs(ChronoUnit.DAYS.between(d, l.date)) > PLAN_DAYS) return@filter false
                val plan = e.recurringId?.let(plansById::get)
                val samePayee = plan != null && (key in plan.matchKeys() || key == RecurringDetector.seriesKey(plan.name))
                val exactAmount = abs(e.amount - l.amount) < 0.01
                (samePayee && abs(e.amount - l.amount) <= e.amount * 0.5) || exactAmount
            }
            closest(l, planned)?.let { return@map ImportMatch(MatchKind.REPLACES_PLANNED, it) }

            null
        }
    }
}
