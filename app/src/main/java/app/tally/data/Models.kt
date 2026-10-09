package app.tally.data

import app.tally.parser.Learned
import java.time.LocalDate

enum class Source { WALLET, MANUAL, RECURRING, IMPORT }

enum class Category(val label: String, val emoji: String, val color: Long) {
    FOOD("Food & Drink", "🍔", 0xFFFF8A3D),
    GROCERIES("Groceries", "🛒", 0xFF34E5A6),
    TRANSPORT("Transport", "🚇", 0xFF4DA3FF),
    SHOPPING("Shopping", "🛍️", 0xFFFF3D9A),
    FUN("Fun", "🎟️", 0xFF8B5CF6),
    SUBSCRIPTIONS("Subscriptions", "🔁", 0xFFA78BFA),
    HOUSING("Rent & Home", "🏠", 0xFFF472B6),
    BILLS("Bills & Insurance", "⚡", 0xFFFFD23D),
    HEALTH("Health", "💊", 0xFF5EEAD4),
    INCOME("Income", "💶", 0xFF34E5A6),
    OTHER("Other", "✨", 0xFF9CA3AF),
}

/** One money movement. Despite the name, [income] ones add to your balance. */
data class Expense(
    val id: String,
    val amount: Double,
    val merchant: String,
    val category: Category,
    val timestamp: Long,
    val source: Source,
    val note: String = "",
    val card: String? = null,
    /** You picked this category yourself, so learning never overrides it. */
    val userTagged: Boolean = false,
    val income: Boolean = false,
    /** Set when this was posted automatically by a [Recurring] item. */
    val recurringId: String? = null,
    /** The [ImportBatch] this came from, so a bad import can be removed on its own. */
    val importId: String? = null,
) {
    /** Posted by a plan, or a past bank transaction that a plan stands for. */
    val isPlanned: Boolean get() = source == Source.RECURRING || recurringId != null

    /** Effect on your balance: negative for spending, positive for income. */
    val signed: Double get() = if (income) amount else -amount
}

enum class Frequency(val label: String) { MONTHLY("Monthly"), YEARLY("Yearly") }

/** A direct debit, subscription or salary that Tally posts by itself on its due day. */
data class Recurring(
    val id: String,
    val name: String,
    val amount: Double,
    val income: Boolean,
    val category: Category,
    val frequency: Frequency,
    /** Day of the month, 1–31; clamped to the month's last day (31 → 30 Apr, 28/29 Feb). */
    val day: Int,
    /** Month of the year for [Frequency.YEARLY], 1–12. */
    val month: Int = 1,
    /** Occurrences before this epoch day are never posted. */
    val startEpochDay: Long,
    /** Everything up to and including this epoch day has been posted. */
    val postedThroughEpochDay: Long,
    val active: Boolean = true,
    /** Scheduled amount changes, e.g. a raise: from that day on, [AmountChange.amount] applies. */
    val changes: List<AmountChange> = emptyList(),
    /** Last day it can occur (inclusive), e.g. when a blocked-account payout runs out. */
    val endEpochDay: Long? = null,
    /** Merchant key of the bank transactions this plan stands for (set when auto-detected). */
    val matchKey: String? = null,
) {
    fun matchKeys(): List<String> = matchKey?.split('|')?.filter { it.isNotEmpty() }.orEmpty()

    fun amountOn(date: LocalDate): Double =
        changes.filter { it.fromEpochDay <= date.toEpochDay() }.maxByOrNull { it.fromEpochDay }?.amount ?: amount

    /** Active and not past its end date. */
    fun liveOn(date: LocalDate): Boolean = active && (endEpochDay == null || endEpochDay >= date.toEpochDay())

    /** What it costs (or brings in) per month on average, at the amount in force on [date]. */
    fun monthlyAmountOn(date: LocalDate): Double =
        if (!liveOn(date)) 0.0 else amountOn(date).let { if (frequency == Frequency.YEARLY) it / 12 else it }

    /** The next scheduled change after [date], if any. */
    fun nextChangeAfter(date: LocalDate): AmountChange? =
        changes.filter { it.fromEpochDay > date.toEpochDay() }.minByOrNull { it.fromEpochDay }
}

data class AmountChange(val fromEpochDay: Long, val amount: Double)

/** One statement import, kept so it can be undone as a unit. */
data class ImportBatch(
    val id: String,
    val importedAt: Long,
    val fileName: String,
    val count: Int,
    val firstEpochDay: Long,
    val lastEpochDay: Long,
    /** Set when this import also set the balance; lets removal put the old balance back. */
    val setBalanceAt: Long? = null,
    val previousBalance: Double? = null,
    val previousBalanceSetAt: Long = 0L,
)

/** A raw notification seen from a Wallet package, kept so parsing can be checked on-device. */
data class Capture(
    val time: Long,
    val pkg: String,
    val title: String,
    val text: String,
    val result: String,
    val parsed: Boolean,
)

data class AppState(
    val expenses: List<Expense> = emptyList(),
    /** Balance the user typed in, or null if never set. */
    val balance: Double? = null,
    /** When [balance] was set; transactions after this moment adjust it. */
    val balanceSetAt: Long = 0L,
    val currency: String = "€",
    val captures: List<Capture> = emptyList(),
    /** Merchant → category scores learned from your tagging. */
    val learned: Learned = emptyMap(),
    val recurring: List<Recurring> = emptyList(),
    /** Everything you allow yourself to spend in a month, fixed costs included. */
    val monthlyBudget: Double? = null,
    val imports: List<ImportBatch> = emptyList(),
    /** Detected-plan suggestions you dismissed (merchant keys), so they don't come back. */
    val dismissedSuggestions: Set<String> = emptySet(),
) {
    val currentBalance: Double?
        get() = balance?.let { b -> b + expenses.filter { it.timestamp > balanceSetAt }.sumOf { it.signed } }

    val spending: List<Expense> get() = expenses.filter { !it.income }
}
