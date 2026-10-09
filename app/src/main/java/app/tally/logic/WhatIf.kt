package app.tally.logic

import app.tally.data.AmountChange
import app.tally.data.AppState
import app.tally.data.Category
import app.tally.data.Frequency
import app.tally.data.Recurring
import app.tally.parser.WalletParser
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** A plan change the Ask tab can offer to apply with one tap. */
data class SuggestedChange(val planId: String?, val planName: String, val fromEpochDay: Long, val amount: Double)

/**
 * "My salary goes from 1600 gross (1411 net) to 3000*20/35 gross from mid November: how does that
 * change my headroom?" Works out the new take-home pay and reruns the runway with and without it.
 */
object WhatIf {

    /** Employee pension share for a working student (no health/unemployment contributions). */
    private const val PENSION = 0.093
    /** Rough marginal income-tax rate on extra pay at this income level (tax class I). */
    private const val MARGINAL_TAX = 0.20
    /** Fallback take-home ratio when we don't know your current gross/net pair. */
    private const val DEFAULT_NET_RATIO = 0.88

    private val trigger = Regex(
        """\b(shift|shifts|change|changes|changing|increase|increases|rise|rises|raise|raised|go up|goes up|becomes?|will be|switch(?:es)?|moves?|jumps?|drops?|decreases?|falls?|gets? cut)\b(\s+(to|by))?""",
    )
    private val salaryWords = Regex("""\b(salary|income|pay|paid|wage|wages|gehalt|lohn|earn|earning|stipend|paycheck)\b""")
    private val expr = Regex("""\d[\d.,]*(?:\s*[*/×x÷+\-]\s*\d[\d.,]*)*""")
    private val grossWords = Regex("""^\W{0,3}(?:€|eur|euros?)?\s*(?:per month\s*|a month\s*|/\s*month\s*|monthly\s*)?(pre[- ]?tax|gross|brutto|before tax)""")
    private val netWords = Regex("""^\W{0,3}(?:€|eur|euros?)?\s*(?:per month\s*|a month\s*|/\s*month\s*|monthly\s*)?(post[- ]?tax|net|netto|after tax|take[- ]?home|in hand)""")

    private val months = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "mär" to 3, "apr" to 4, "may" to 5, "mai" to 5, "jun" to 6, "jul" to 7,
        "aug" to 8, "sep" to 9, "oct" to 10, "okt" to 10, "nov" to 11, "dec" to 12, "dez" to 12,
    )
    private val datePhrase = Regex(
        """\b(?:(mid|middle|start|beginning|end)\s+(?:of\s+)?|(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?)?(jan|feb|mar|mär|apr|may|mai|jun|jul|aug|sep|oct|okt|nov|dec|dez)[a-zä]*\b(?:\s+(\d{4}))?""",
    )

    fun isWhatIf(question: String): Boolean {
        val q = question.lowercase()
        return salaryWords.containsMatchIn(q) && trigger.containsMatchIn(q) && expr.containsMatchIn(q) &&
            !Regex("""\bhow much (did|have) i (earn|make|get)""").containsMatchIn(q)
    }

    fun salary(question: String, state: AppState, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Answer {
        val cur = state.currency
        val nf = NumberFormat.getNumberInstance(Locale.GERMANY).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }
        fun m(x: Double) = "$cur${nf.format(x)}"

        var q = question.lowercase()
        // Pull out the start date first so its day number isn't mistaken for money.
        val date = datePhrase.find(q)?.let { d ->
            q = q.replaceRange(d.range, " ".repeat(d.value.length))
            dateFrom(d, today)
        } ?: today.plusMonths(1).withDayOfMonth(1)

        val t = trigger.find(q) ?: return Answer("Tell me the new amount, e.g. \"my salary goes up to 1700 gross from 15 November\".", true)
        val byMode = t.groupValues[3] == "by"
        data class Figure(val value: Double, val gross: Boolean?, val before: Boolean)
        val figures = expr.findAll(q).mapNotNull { mt ->
            val v = evaluate(mt.value) ?: return@mapNotNull null
            if (v < 50) return@mapNotNull null // hours, percentages, etc.
            val after = q.substring(mt.range.last + 1)
            val kind = when {
                grossWords.containsMatchIn(after) -> true
                netWords.containsMatchIn(after) -> false
                else -> null
            }
            Figure(v, kind, mt.range.first < t.range.first)
        }.toList()

        val current = figures.filter { it.before }
        val new = figures.firstOrNull { !it.before }
            ?: return Answer("What's the new amount? e.g. \"salary changes to 1700 gross from mid November\".", true)
        val curGross = current.firstOrNull { it.gross == true }?.value ?: current.firstOrNull { it.gross == null && current.size == 2 }?.value
        val curNet = current.firstOrNull { it.gross == false }?.value
            ?: current.lastOrNull { it.gross == null && it.value != curGross }?.value

        val plan = state.recurring.filter { it.income && it.liveOn(today) }.maxByOrNull { it.amountOn(today) }
        val baseNet = curNet ?: plan?.amountOn(date) ?: plan?.amount
        val mentionsGross = Regex("""pre[- ]?tax|gross|brutto|before tax""").containsMatchIn(q)
        val newIsGross = new.gross ?: (mentionsGross && !Regex("""post[- ]?tax|net\b|netto|after tax""").containsMatchIn(q.substring(t.range.last)))

        val rawNew = if (byMode) (if (newIsGross) (curGross ?: 0.0) else (baseNet ?: 0.0)) + new.value else new.value
        val (newNet, how) = when {
            !newIsGross -> rawNew to "as you said, take-home"
            curGross != null && baseNet != null -> {
                val keep = 1 - PENSION - MARGINAL_TAX
                (baseNet + (rawNew - curGross) * keep) to
                    "estimated: ${m(rawNew)} gross; of the extra ${m(rawNew - curGross)} you keep about ${(keep * 100).roundToInt()}% " +
                    "after pension (9.3%) and income tax (~20%)"
            }
            else -> (rawNew * DEFAULT_NET_RATIO) to "estimated at ${(DEFAULT_NET_RATIO * 100).roundToInt()}% of ${m(rawNew)} gross"
        }

        val bal = state.currentBalance
        val daily = Metrics.dailyVariableSpend(state.expenses, today, zone)
        val salaryPlan = plan ?: baseNet?.let {
            Recurring("whatif", "Salary", it, true, Category.INCOME, Frequency.MONTHLY, 28,
                startEpochDay = today.toEpochDay() + 1, postedThroughEpochDay = today.toEpochDay())
        }
        val others = state.recurring.filter { it.id != salaryPlan?.id }
        val before = bal?.let { Metrics.runway(it, today, daily, others + listOfNotNull(salaryPlan)) }
        val changed = salaryPlan?.copy(changes = salaryPlan.changes.filter { it.fromEpochDay < date.toEpochDay() } + AmountChange(date.toEpochDay(), newNet))
        val after = bal?.let { Metrics.runway(it, today, daily, others + listOfNotNull(changed)) }

        val df = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
        fun runwayText(r: Runway?) = when {
            r == null -> "unknown (set your balance)"
            r.runOut == null -> "doesn't run out"
            else -> "%.1f months (until ~%s)".format(Locale.ENGLISH, r.months, r.runOut.format(df))
        }
        val delta = if (baseNet != null) newNet - baseNet else null
        val sb = StringBuilder()
        sb.append("From ${date.format(df)} your take-home would be about ${m(newNet)}/month")
        if (delta != null) sb.append(" (${if (delta >= 0) "+" else "−"}${m(abs(delta))} vs ${m(baseNet!!)} now)")
        sb.append(". [$how]\n\n")
        sb.append("Runway now: ${runwayText(before)}.\nWith the change: ${runwayText(after)}.")
        if (before != null && after != null) {
            // Compare a typical month once the change applies (the runway's own figure looks one month ahead).
            val oldAmount = salaryPlan?.amountOn(date) ?: 0.0
            val netAfter = before.monthlyNet + (newNet - oldAmount)
            sb.append("\nMonthly net once it applies: ${m(before.monthlyNet)} → ${m(netAfter)}.")
            if (netAfter < 0) {
                sb.append(" You'd still be spending about ${m(-netAfter)}/month more than comes in.")
            } else {
                sb.append(" Your balance would grow by about ${m(netAfter)}/month.")
            }
        }
        if (plan == null) sb.append("\n\nTip: add your salary as a plan so the forecast always includes it.")

        return Answer(
            sb.toString(),
            understood = true,
            change = SuggestedChange(plan?.id, plan?.name ?: "Salary", date.toEpochDay(), (newNet * 100).roundToInt() / 100.0),
        )
    }

    private fun dateFrom(d: MatchResult, today: LocalDate): LocalDate {
        val month = months[d.groupValues[3].take(3)] ?: return today.plusMonths(1).withDayOfMonth(1)
        val explicitYear = d.groupValues[4].toIntOrNull()
        // A change you're asking about is in the future: a month already past means next year.
        var ym = YearMonth.of(explicitYear ?: today.year, month)
        if (explicitYear == null && ym < YearMonth.from(today)) ym = ym.plusYears(1)
        val day = when (d.groupValues[1]) {
            "mid", "middle" -> 15
            "end" -> ym.lengthOfMonth()
            "start", "beginning" -> 1
            else -> d.groupValues[2].toIntOrNull()?.coerceIn(1, ym.lengthOfMonth()) ?: 1
        }
        return ym.atDay(day)
    }

    /** Evaluates "3000*20/35", "1.600,50" or "1600 + 200" with the usual precedence. */
    internal fun evaluate(raw: String): Double? {
        val tokens = Regex("""\d[\d.,]*|[*/×x÷+\-]""").findAll(raw.replace(" ", "")).map { it.value }.toList()
        if (tokens.isEmpty()) return null
        val nums = mutableListOf<Double>()
        val ops = mutableListOf<Char>()
        tokens.forEachIndexed { i, tok ->
            if (i % 2 == 0) nums += WalletParser.toNumber(tok) ?: return null
            else ops += when (tok) { "×", "x" -> '*'; "÷" -> '/'; else -> tok[0] }
        }
        if (nums.size != ops.size + 1) return null
        // First * and /, then + and -.
        val n2 = mutableListOf(nums[0])
        val o2 = mutableListOf<Char>()
        ops.forEachIndexed { i, op ->
            when (op) {
                '*' -> n2[n2.lastIndex] = n2.last() * nums[i + 1]
                '/' -> n2[n2.lastIndex] = if (nums[i + 1] == 0.0) return null else n2.last() / nums[i + 1]
                else -> { o2 += op; n2 += nums[i + 1] }
            }
        }
        var result = n2[0]
        o2.forEachIndexed { i, op -> result = if (op == '+') result + n2[i + 1] else result - n2[i + 1] }
        return result
    }
}
