package app.tally.data

import android.content.Context
import app.tally.parser.ParsedPayment
import app.tally.parser.WalletParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Currency
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Single source of truth, shared by the UI and the notification listener (same process).
 * Everything lives in one JSON file in app-private storage; it never leaves the phone.
 */
object ExpenseStore {

    private const val MAX_CAPTURES = 40
    private const val MAX_SEEN = 300
    private const val DUPLICATE_WINDOW_MS = 5 * 60 * 1000L

    private val writer = Executors.newSingleThreadExecutor()
    private lateinit var file: File
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    /** Signatures of notifications already handled, so re-posts/updates aren't double counted. */
    private val seen = ArrayDeque<String>()

    @Synchronized
    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "tally.json")
        _state.value = if (file.exists()) {
            runCatching { decode(JSONObject(file.readText())) }.getOrElse { AppState() }
        } else {
            AppState(currency = defaultCurrency())
        }
    }

    fun upsert(expense: Expense) = mutate { s ->
        val others = s.expenses.filterNot { it.id == expense.id }
        s.copy(expenses = (others + expense).sortedByDescending { it.timestamp })
    }

    fun delete(id: String) = mutate { s -> s.copy(expenses = s.expenses.filterNot { it.id == id }) }

    fun setBalance(amount: Double) = mutate { s ->
        s.copy(balance = amount, balanceSetAt = System.currentTimeMillis())
    }

    fun setCurrency(symbol: String) = mutate { s -> s.copy(currency = symbol) }

    fun eraseAll() = mutate { s -> AppState(currency = s.currency) }

    fun newId(): String = UUID.randomUUID().toString()

    /** Called by the listener for every notification from a Wallet package. */
    @Synchronized
    fun onWalletNotification(
        pkg: String,
        key: String,
        postTime: Long,
        title: String,
        text: String,
        parsed: ParsedPayment?,
    ) {
        val signature = "$key|$title|$text"
        if (signature in seen) return
        seen.addLast(signature)
        while (seen.size > MAX_SEEN) seen.removeFirst()

        mutate { s ->
            val duplicate = parsed != null && s.expenses.any {
                it.source == Source.WALLET &&
                    abs(it.amount - parsed.amount) < 0.005 &&
                    it.merchant == parsed.merchant &&
                    abs(it.timestamp - postTime) < DUPLICATE_WINDOW_MS
            }
            val result = when {
                parsed == null -> "Not a payment"
                duplicate -> "Duplicate, skipped"
                else -> "Added ${parsed.currency ?: ""}${parsed.amount} · ${parsed.merchant}"
            }
            val capture = Capture(postTime, pkg, title, text, result, parsed != null && !duplicate)
            var next = s.copy(captures = (listOf(capture) + s.captures).take(MAX_CAPTURES))
            if (parsed != null && !duplicate) {
                val expense = Expense(
                    id = newId(),
                    amount = parsed.amount,
                    merchant = parsed.merchant,
                    category = WalletParser.guessCategory(parsed.merchant),
                    timestamp = postTime,
                    source = Source.WALLET,
                    card = parsed.card,
                )
                next = next.copy(expenses = (next.expenses + expense).sortedByDescending { it.timestamp })
            }
            next
        }
    }

    @Synchronized
    private fun mutate(block: (AppState) -> AppState) {
        _state.update(block)
        val snapshot = _state.value
        writer.execute { persist(snapshot) }
    }

    private fun persist(s: AppState) {
        val tmp = File(file.parentFile, "tally.json.tmp")
        tmp.writeText(encode(s).toString())
        tmp.renameTo(file)
    }

    private fun defaultCurrency(): String =
        runCatching { Currency.getInstance(Locale.getDefault()).getSymbol(Locale.getDefault()) }
            .getOrNull()
            ?.takeIf { it.length <= 3 }
            ?: "$"

    private fun encode(s: AppState) = JSONObject().apply {
        put("version", 1)
        s.balance?.let { put("balance", it) }
        put("balanceSetAt", s.balanceSetAt)
        put("currency", s.currency)
        put("expenses", JSONArray().apply {
            s.expenses.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id)
                    put("amount", e.amount)
                    put("merchant", e.merchant)
                    put("category", e.category.name)
                    put("timestamp", e.timestamp)
                    put("source", e.source.name)
                    put("note", e.note)
                    e.card?.let { put("card", it) }
                })
            }
        })
        put("captures", JSONArray().apply {
            s.captures.forEach { c ->
                put(JSONObject().apply {
                    put("time", c.time)
                    put("pkg", c.pkg)
                    put("title", c.title)
                    put("text", c.text)
                    put("result", c.result)
                    put("parsed", c.parsed)
                })
            }
        })
    }

    private fun decode(o: JSONObject): AppState {
        val expenses = o.optJSONArray("expenses").objects().map { e ->
            Expense(
                id = e.getString("id"),
                amount = e.getDouble("amount"),
                merchant = e.getString("merchant"),
                category = runCatching { Category.valueOf(e.getString("category")) }.getOrDefault(Category.OTHER),
                timestamp = e.getLong("timestamp"),
                source = runCatching { Source.valueOf(e.getString("source")) }.getOrDefault(Source.MANUAL),
                note = e.optString("note"),
                card = if (e.has("card")) e.getString("card") else null,
            )
        }
        val captures = o.optJSONArray("captures").objects().map { c ->
            Capture(
                time = c.getLong("time"),
                pkg = c.getString("pkg"),
                title = c.optString("title"),
                text = c.optString("text"),
                result = c.optString("result"),
                parsed = c.optBoolean("parsed"),
            )
        }
        return AppState(
            expenses = expenses.sortedByDescending { it.timestamp },
            balance = if (o.has("balance")) o.getDouble("balance") else null,
            balanceSetAt = o.optLong("balanceSetAt"),
            currency = o.optString("currency", "$"),
            captures = captures,
        )
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
}
