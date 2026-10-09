package app.tally.logic

import app.tally.data.AppState
import app.tally.data.Category
import app.tally.data.Expense
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** What the question resolved to, kept so a language model can be handed the same facts. */
data class Answer(val text: String, val understood: Boolean)

/**
 * Answers questions about your money from the data itself, exactly and instantly.
 *
 * It picks out a time range ("last month", "in September", "this week", "last 30 days"), a
 * category or shop, and what you're asking (how much, top, how often, average, compare, runway,
 * budget, balance, subscriptions, income). A language model can phrase things more freely, but
 * small on-device models can't add up, so the numbers always come from here.
 */
object AskEngine {

    data class Range(val from: LocalDate, val to: LocalDate, val label: String) {
        val days: Long get() = ChronoUnit.DAYS.between(from, to) + 1
        fun previous(): Range {
            val len = days
            return Range(from.minusDays(len), from.minusDays(1), "the $len days before")
        }
    }

    private val months = mapOf(
        "january" to 1, "jan" to 1, "januar" to 1, "february" to 2, "feb" to 2, "februar" to 2,
        "march" to 3, "mar" to 3, "märz" to 3, "maerz" to 3, "april" to 4, "apr" to 4, "may" to 5, "mai" to 5,
        "june" to 6, "jun" to 6, "juni" to 6, "july" to 7, "jul" to 7, "juli" to 7, "august" to 8, "aug" to 8,
        "september" to 9, "sep" to 9, "sept" to 9, "october" to 10, "oct" to 10, "oktober" to 10, "okt" to 10,
        "november" to 11, "nov" to 11, "december" to 12, "dec" to 12, "dezember" to 12, "dez" to 12,
    )

    private val categoryWords: List<Pair<Category, List<String>>> = listOf(
        Category.GROCERIES to listOf("grocer", "supermarket", "rewe", "aldi", "lidl", "edeka", "kaufland"),
        Category.FOOD to listOf("food", "eat", "restaurant", "takeaway", "take-away", "delivery", "coffee", "cafe", "lunch", "dinner", "drink"),
        Category.TRANSPORT to listOf("transport", "travel", "train", "taxi", "commute", "ticket", "bus", "metro"),
        Category.SHOPPING to listOf("shopping", "clothes", "electronics"),
        Category.FUN to listOf("fun", "entertainment", "movie", "cinema", "going out"),
        Category.SUBSCRIPTIONS to listOf("subscription", "subs "),
        Category.HOUSING to listOf("rent", "housing", "home"),
        Category.BILLS to listOf("bill", "insurance", "phone", "utilities"),
        Category.HEALTH to listOf("health", "pharmacy", "doctor", "medicine", "gym"),
        Category.INCOME to listOf("income", "salary", "earn", "wage"),
    )

    private val nf: NumberFormat = NumberFormat.getNumberInstance(Locale.GERMANY).apply {
        minimumFractionDigits = 2; maximumFractionDigits = 2
    }

    fun answer(question: String, state: AppState, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Answer =
        raw(question, state, today, zone).let { a ->
            a.copy(text = a.text.replace(Regex(" {2,}"), " ").replace(" .", ".").replace(" :", ":"))
        }

    private fun raw(question: String, state: AppState, today: LocalDate, zone: ZoneId): Answer {
        val q = " ${question.lowercase().replace(Regex("""[?!.,]"""), " ")} "
        val cur = state.currency
        fun m(x: Double) = "$cur${nf.format(x)}"
        fun has(vararg words: String) = words.any { q.contains(it) }

        val range = range(q, today)
        val merchant = merchantIn(q, state.expenses)
        val category = if (merchant == null) categoryIn(q) else null
        val dated = state.expenses.map { it to Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
        fun inRange(r: Range) = dated.filter { (_, d) -> d in r.from..r.to }.map { it.first }
            .filter { e -> merchant == null || RecurringDetector.seriesKey(e.merchant).contains(merchant) }
            .filter { e -> category == null || category == Category.INCOME || e.category == category }
        val what = merchant?.let { "at ${it.replaceFirstChar(Char::uppercase)}" } ?: category?.let { "on ${it.label}" } ?: ""

        // --- Forecast, budget, balance ---
        if (has("run out", "runway", "how long", "last until", "broke", "will my money")) {
            val bal = state.currentBalance ?: return Answer("Set your balance first (tap the balance card), then I can forecast.", true)
            val daily = Metrics.dailyVariableSpend(state.expenses, today, zone)
            val r = Metrics.runway(bal, today, daily, state.recurring)
            val base = "At your usual ${m(daily)}/day plus your plans (net ${m(r.monthlyNet)}/month)"
            return Answer(
                if (r.runOut == null) "$base, your ${m(bal)} doesn't run out: income covers your spending."
                else "$base, your ${m(bal)} lasts about ${"%.1f".format(r.months)} months, until around ${r.runOut.format(DateTimeFormatter.ofPattern("d MMMM yyyy"))}.",
                true,
            )
        }
        if (has("budget", "left to spend", "safe to spend", "can i spend", "afford", "how much is left", "how much left")) {
            val ms = Metrics.month(state.expenses, state.recurring, state.monthlyBudget, today, zone)
            val b = ms.budget ?: return Answer("You haven't set a monthly budget yet. Set one in Insights.", true)
            return Answer(
                "This month you've spent ${m(ms.spent)} of your ${m(b)} budget, with ${m(ms.upcomingFixed)} in plans still to come. " +
                    "Safe to spend: ${m(ms.safeToSpend ?: 0.0)}, about ${m((ms.dailyAllowance ?: 0.0).coerceAtLeast(0.0))}/day for ${ms.daysLeft} days. " +
                    "At your current pace you'll end the month around ${m(ms.projectedSpend)}.",
                true,
            )
        }
        if (has("balance", "how much do i have", "how much money do i have", "in my account")) {
            val bal = state.currentBalance ?: return Answer("You haven't set a balance yet.", true)
            return Answer("Your balance is ${m(bal)}.", true)
        }
        if (has("subscription", "recurring", "plans", "fixed cost", "direct debit", "standing")) {
            val live = state.recurring.filter { it.liveOn(today) }
            if (live.isEmpty()) return Answer("You don't have any plans yet. The Plans tab can spot them in your history.", true)
            val subsOnly = category == Category.SUBSCRIPTIONS
            val shown = live.filter { !subsOnly || it.category == Category.SUBSCRIPTIONS }
            val out = shown.filter { !it.income }.sumOf { it.monthlyAmountOn(today) }
            val inc = shown.filter { it.income }.sumOf { it.monthlyAmountOn(today) }
            val list = shown.sortedByDescending { it.amountOn(today) }.joinToString("; ") {
                "${it.name} ${if (it.income) "+" else ""}${m(it.amountOn(today))}"
            }
            return Answer(
                "${if (subsOnly) "Subscriptions" else "Your plans"}: $list. Monthly that's ${m(out)} out" +
                    (if (inc > 0) " and ${m(inc)} in." else "."),
                true,
            )
        }

        val txs = inRange(range)
        val spending = txs.filter { !it.income }
        val incomeAsked = category == Category.INCOME || has(" earn", "income", "salary", "got paid", "received", "came in")

        if (incomeAsked) {
            val income = dated.filter { (e, d) -> e.income && d in range.from..range.to }.map { it.first }
            if (income.isEmpty()) return Answer("No income recorded ${range.label}.", true)
            val top = income.groupBy { it.merchant }.mapValues { (_, v) -> v.sumOf { it.amount } }.entries.sortedByDescending { it.value }
            return Answer(
                "You received ${m(income.sumOf { it.amount })} ${range.label}, mostly from " +
                    top.take(3).joinToString(", ") { "${it.key} (${m(it.value)})" } + ".",
                true,
            )
        }

        if (has("compare", " vs ", "versus", "than last", "more than", "less than", "change", "difference")) {
            // "Compare with last month" means this month so far vs the same days last month.
            val againstLast = Regex("(with|to|than|vs|versus|against)\\s+(last|previous)\\s+(month|week)").find(q)
            val (curRange, prevRange) = when {
                againstLast?.groupValues?.get(3) == "week" -> {
                    val r = range(" this week ", today)
                    r to Range(r.from.minusWeeks(1), today.minusWeeks(1), "the same days last week")
                }
                againstLast != null || range.label.startsWith("this month") -> {
                    val r = monthRange(YearMonth.from(today), today)
                    r to monthRange(YearMonth.from(today).minusMonths(1), today, toDate = today.minusMonths(1))
                        .copy(label = "by the same day last month")
                }
                else -> range to range.previous()
            }
            val now = inRange(curRange).filter { !it.income }.sumOf { it.amount }
            val before = inRange(prevRange).filter { !it.income }.sumOf { it.amount }
            val diff = now - before
            val pct = if (before > 0) " (${if (diff >= 0) "+" else ""}${(diff / before * 100).toInt()}%)" else ""
            return Answer(
                "You spent ${m(now)} $what ${curRange.label} vs ${m(before)} ${prevRange.label}: " +
                    "${if (diff >= 0) "${m(diff)} more" else "${m(-diff)} less"}$pct.",
                true,
            )
        }

        if (has("biggest purchase", "largest purchase", "biggest payment", "largest payment", "most expensive", "biggest expense", "largest expense", "biggest transaction")) {
            val top = spending.sortedByDescending { it.amount }.take(3)
            if (top.isEmpty()) return Answer("No spending $what ${range.label}.".replace("  ", " "), true)
            return Answer(
                "Biggest ${range.label}: " + top.joinToString("; ") { "${it.merchant} ${m(it.amount)} on ${it.dateText(zone)}" } + ".",
                true,
            )
        }

        if (has("top", "most", "biggest", "largest", "where do i", "where did i", "where does", "which shop", "which store", "which categor")) {
            if (spending.isEmpty()) return Answer("No spending $what ${range.label}.".replace("  ", " "), true)
            val byCategory = has("categor") && category == null
            val groups = spending.groupBy { if (byCategory) it.category.label else it.merchant }
                .mapValues { (_, v) -> v.sumOf { it.amount } to v.size }
                .entries.sortedByDescending { it.value.first }.take(5)
            return Answer(
                "Top ${if (byCategory) "categories" else "places"} $what ${range.label}: ".replace("  ", " ") +
                    groups.joinToString("; ") { "${it.key} ${m(it.value.first)} (${it.value.second}×)" } +
                    ". Total ${m(spending.sumOf { it.amount })}.",
                true,
            )
        }

        if (has("how many times", "how often", "how many")) {
            return Answer(
                "${spending.size} payment${if (spending.size == 1) "" else "s"} $what ${range.label}, ${m(spending.sumOf { it.amount })} in total.".replace("  ", " "),
                true,
            )
        }

        if (has("average", "per day", "a day", "daily", "per week", "per month", "usually", "typical")) {
            val total = spending.sumOf { it.amount }
            val perDay = total / range.days
            return Answer(
                "On average ${m(perDay)}/day $what ${range.label} (${m(perDay * 7)}/week, ${m(perDay * 30.44)}/month), ${m(total)} in total.".replace("  ", " "),
                true,
            )
        }

        if (has("show", "list", "what did i buy", "what did i spend", "transactions", "payments")) {
            if (spending.isEmpty()) return Answer("Nothing $what ${range.label}.".replace("  ", " "), true)
            val shown = spending.sortedByDescending { it.timestamp }.take(8)
            return Answer(
                "${spending.size} payments $what ${range.label}".replace("  ", " ") +
                    (if (spending.size > shown.size) ", latest ${shown.size}" else "") + ": " +
                    shown.joinToString("; ") { "${it.dateText(zone)} ${it.merchant} ${m(it.amount)}" } + ".",
                true,
            )
        }

        if (has("how much", "spent", "spend", "spending", "cost", "pay for", "paid for", "pay at", "paid at")) {
            val total = spending.sumOf { it.amount }
            if (spending.isEmpty()) return Answer("You haven't spent anything $what ${range.label}.".replace("  ", " "), true)
            val biggest = spending.maxBy { it.amount }
            return Answer(
                "You spent ${m(total)} $what ${range.label} across ${spending.size} payment${if (spending.size == 1) "" else "s"}. ".replace("  ", " ") +
                    "Biggest: ${biggest.merchant} ${m(biggest.amount)} on ${biggest.dateText(zone)}.",
                true,
            )
        }

        return Answer(
            "I didn't catch that. Try: \"How much on food last month?\", \"Top places in September\", " +
                "\"When does my money run out?\", \"Compare with last month\" or \"What subscriptions do I pay?\"",
            false,
        )
    }

    /** A compact snapshot of your finances, for a language model to reason over. */
    fun summary(state: AppState, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String {
        val cur = state.currency
        fun m(x: Double) = "$cur${nf.format(x)}"
        val sb = StringBuilder()
        sb.appendLine("Today: $today. Currency: $cur.")
        state.currentBalance?.let { bal ->
            sb.appendLine("Balance: ${m(bal)}.")
            val daily = Metrics.dailyVariableSpend(state.expenses, today, zone)
            val r = Metrics.runway(bal, today, daily, state.recurring)
            sb.appendLine(
                "Usual day-to-day spending: ${m(daily)}/day. Net change per month incl. plans: ${m(r.monthlyNet)}. " +
                    (r.runOut?.let { "Money runs out around $it (${"%.1f".format(r.months)} months)." } ?: "Money does not run out."),
            )
        }
        val ms = Metrics.month(state.expenses, state.recurring, state.monthlyBudget, today, zone)
        sb.appendLine(
            "This month so far: spent ${m(ms.spent)}, income ${m(ms.income)}, plans still to come ${m(ms.upcomingFixed)}, projected month-end spend ${m(ms.projectedSpend)}." +
                (ms.budget?.let { " Budget ${m(it)}, safe to spend ${m(ms.safeToSpend ?: 0.0)}." } ?: ""),
        )
        val live = state.recurring.filter { it.liveOn(today) }
        if (live.isNotEmpty()) {
            sb.appendLine("Plans: " + live.joinToString("; ") { p ->
                "${p.name} ${if (p.income) "+" else "-"}${m(p.amountOn(today))} on day ${p.day}" +
                    (p.nextChangeAfter(today)?.let { " (becomes ${m(it.amount)} from ${LocalDate.ofEpochDay(it.fromEpochDay)})" } ?: "")
            })
        }
        sb.appendLine("Spending per month: " + Metrics.history(state.expenses, today, 6, zone).joinToString(", ") {
            "${it.month.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${m(it.spent)}"
        })
        val ym = YearMonth.from(today)
        listOf(ym to "This month", ym.minusMonths(1) to "Last month").forEach { (month, label) ->
            val list = state.expenses.filter {
                !it.income && YearMonth.from(Instant.ofEpochMilli(it.timestamp).atZone(zone)) == month
            }
            if (list.isEmpty()) return@forEach
            sb.appendLine("$label by category: " + list.groupBy { it.category.label }
                .mapValues { (_, v) -> v.sumOf { it.amount } }.entries.sortedByDescending { it.value }
                .joinToString(", ") { "${it.key} ${m(it.value)}" })
            sb.appendLine("$label top places: " + list.groupBy { it.merchant }
                .mapValues { (_, v) -> v.sumOf { it.amount } to v.size }.entries.sortedByDescending { it.value.first }.take(8)
                .joinToString(", ") { "${it.key} ${m(it.value.first)} (${it.value.second}x)" })
        }
        return sb.toString().trim()
    }

    // ---------- parsing ----------

    internal fun range(q: String, today: LocalDate): Range {
        val lastN = Regex("""(?:last|past)\s+(\d{1,3})\s+(day|week|month)s?""").find(q)
        if (lastN != null) {
            val n = lastN.groupValues[1].toLong()
            val from = when (lastN.groupValues[2]) {
                "day" -> today.minusDays(n - 1)
                "week" -> today.minusWeeks(n).plusDays(1)
                else -> today.minusMonths(n).plusDays(1)
            }
            return Range(from, today, "in the last $n ${lastN.groupValues[2]}${if (n == 1L) "" else "s"}")
        }
        return when {
            q.contains(" today ") -> Range(today, today, "today")
            q.contains("yesterday") -> today.minusDays(1).let { Range(it, it, "yesterday") }
            q.contains("this week") -> Range(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), today, "this week")
            q.contains("last week") -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1)
                .let { Range(it, it.plusDays(6), "last week") }
            q.contains("last month") || q.contains("previous month") ->
                monthRange(YearMonth.from(today).minusMonths(1), today)
            q.contains("this year") -> Range(today.withDayOfYear(1), today, "this year")
            q.contains("last year") -> today.minusYears(1).let { Range(it.withDayOfYear(1), it.withDayOfYear(it.lengthOfYear()), "last year") }
            q.contains(" ever ") || q.contains("all time") || q.contains("in total") || q.contains("overall") ->
                Range(LocalDate.of(2000, 1, 1), today, "overall")
            else -> namedMonth(q, today) ?: monthRange(YearMonth.from(today), today)
        }
    }

    private fun namedMonth(q: String, today: LocalDate): Range? {
        val words = q.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        for ((i, w) in words.withIndex()) {
            val month = months[w] ?: continue
            // "may" is also a verb: only count it after "in"/"for"/"during" or before a year.
            val next = words.getOrNull(i + 1)
            val year = next?.toIntOrNull()?.takeIf { it in 2000..2100 }
            if (w == "may" && year == null && words.getOrNull(i - 1) !in setOf("in", "for", "during", "since")) continue
            var ym = YearMonth.of(year ?: today.year, month)
            if (year == null && ym > YearMonth.from(today)) ym = ym.minusYears(1)
            return monthRange(ym, today)
        }
        return null
    }

    private fun monthRange(ym: YearMonth, today: LocalDate, toDate: LocalDate? = null): Range {
        val current = ym == YearMonth.from(today)
        val name = ym.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        val end = toDate ?: if (current) today else ym.atEndOfMonth()
        val label = when {
            current -> "this month"
            ym == YearMonth.from(today).minusMonths(1) -> "last month ($name)"
            ym.year == today.year -> "in $name"
            else -> "in $name ${ym.year}"
        }
        return Range(ym.atDay(1), minOf(end, ym.atEndOfMonth()), label)
    }

    private fun categoryIn(q: String): Category? =
        categoryWords.firstOrNull { (_, words) -> words.any { q.contains(it) } }?.first

    /** A shop named in the question, as a series key fragment ("rewe", "uber eats", "lebara"). */
    private fun merchantIn(q: String, expenses: List<Expense>): String? {
        val words = q.split(Regex("""\s+""")).filter { it.length >= 3 }.toSet()
        val stop = setOf("the", "and", "for", "how", "much", "did", "spend", "spent", "this", "last", "month", "week", "year",
            "what", "where", "when", "with", "from", "money", "food", "rent", "top", "most", "many", "times", "average", "day")
        return expenses.asSequence()
            .map { RecurringDetector.seriesKey(it.merchant) }
            .distinct()
            .flatMap { key -> key.split(' ').filter { it.length >= 4 && it !in stop }.map { it to key } }
            .firstOrNull { (token, _) -> token in words }
            ?.first
    }

    private fun Expense.dateText(zone: ZoneId): String =
        Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate().format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
}
