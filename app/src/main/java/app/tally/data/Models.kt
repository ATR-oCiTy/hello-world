package app.tally.data

enum class Source { WALLET, MANUAL }

enum class Category(val label: String, val emoji: String, val color: Long) {
    FOOD("Food & Drink", "🍔", 0xFFFF8A3D),
    GROCERIES("Groceries", "🛒", 0xFF34E5A6),
    TRANSPORT("Transport", "🚕", 0xFF4DA3FF),
    SHOPPING("Shopping", "🛍️", 0xFFFF3D9A),
    FUN("Fun", "🎟️", 0xFF8B5CF6),
    BILLS("Bills", "⚡", 0xFFFFD23D),
    HEALTH("Health", "💊", 0xFF5EEAD4),
    OTHER("Other", "✨", 0xFF9CA3AF),
}

data class Expense(
    val id: String,
    val amount: Double,
    val merchant: String,
    val category: Category,
    val timestamp: Long,
    val source: Source,
    val note: String = "",
    val card: String? = null,
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
    /** When [balance] was set; expenses after this moment are subtracted from it. */
    val balanceSetAt: Long = 0L,
    val currency: String = "₹",
    val captures: List<Capture> = emptyList(),
) {
    val currentBalance: Double?
        get() = balance?.let { b -> b - expenses.filter { it.timestamp > balanceSetAt }.sumOf { it.amount } }
}
