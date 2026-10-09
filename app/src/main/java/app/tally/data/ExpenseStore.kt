package app.tally.data

import android.content.Context
import app.tally.logic.Recurrence
import app.tally.logic.StatementLine
import app.tally.logic.StatementParser
import app.tally.parser.Classifier
import app.tally.parser.Learned
import app.tally.parser.ParsedPayment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
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
            AppState()
        }
        postDueRecurring()
    }

    // ---------- Recurring ----------

    /** Posts every recurring item that has fallen due since the app last looked. */
    fun postDueRecurring(today: LocalDate = LocalDate.now()) = mutate { s ->
        val zone = ZoneId.systemDefault()
        val posted = mutableListOf<Expense>()
        val updated = s.recurring.map { r ->
            Recurrence.due(r, today).forEach { date ->
                posted += Expense(
                    id = newId(),
                    amount = r.amount,
                    merchant = r.name,
                    category = r.category,
                    timestamp = date.atTime(9, 0).atZone(zone).toInstant().toEpochMilli(),
                    source = Source.RECURRING,
                    income = r.income,
                    recurringId = r.id,
                )
            }
            // Paused items move along too, so un-pausing never back-posts missed months.
            r.copy(postedThroughEpochDay = maxOf(r.postedThroughEpochDay, today.toEpochDay()))
        }
        if (posted.isEmpty() && updated == s.recurring) s
        else s.copy(recurring = updated, expenses = (s.expenses + posted).sortedByDescending { it.timestamp })
    }

    fun saveRecurring(r: Recurring) {
        mutate { s -> s.copy(recurring = s.recurring.filterNot { it.id == r.id } + r) }
        postDueRecurring()
    }

    /** Stops the item; transactions it already posted stay in your history. */
    fun deleteRecurring(id: String) = mutate { s -> s.copy(recurring = s.recurring.filterNot { it.id == id }) }

    fun setBudget(amount: Double?) = mutate { s -> s.copy(monthlyBudget = amount?.takeIf { it > 0 }) }

    // ---------- Statement import ----------

    /** Index-aligned with [lines]: the already-logged transaction each line matches, if any. */
    fun findDuplicates(lines: List<StatementLine>): List<Expense?> {
        val zone = ZoneId.systemDefault()
        val used = mutableSetOf<String>()
        val existing = _state.value.expenses
        return lines.map { l ->
            existing.firstOrNull { e ->
                e.id !in used &&
                    e.income == l.income &&
                    abs(e.amount - l.amount) < 0.01 &&
                    abs(ChronoUnit.DAYS.between(e.timestampDate(zone), l.date)) <= 3
            }?.also { used += it.id }
        }
    }

    fun import(lines: List<StatementLine>) = mutate { s ->
        val zone = ZoneId.systemDefault()
        val added = lines.map { l ->
            val name = StatementParser.merchantFrom(l.description)
            Expense(
                id = newId(),
                amount = l.amount,
                merchant = name,
                category = if (l.income) Category.INCOME else Classifier.classify(name, s.learned),
                timestamp = l.date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
                source = Source.IMPORT,
                note = l.description.takeIf { it != name }.orEmpty(),
                income = l.income,
            )
        }
        s.copy(expenses = (s.expenses + added).sortedByDescending { it.timestamp })
    }

    private fun Expense.timestampDate(zone: ZoneId): LocalDate =
        java.time.Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()

    // ---------- Transactions ----------

    fun upsert(expense: Expense) = mutate { s ->
        val others = s.expenses.filterNot { it.id == expense.id }
        s.copy(expenses = (others + expense).sortedByDescending { it.timestamp })
    }

    /**
     * Saves from the edit sheet. If you picked the category yourself ([taught]), the merchant is
     * learned and every other untagged expense from the same place is re-filed to match.
     */
    fun save(expense: Expense, taught: Boolean) = mutate { s ->
        val saved = if (taught) expense.copy(userTagged = true) else expense
        val learned = if (taught) Classifier.teach(s.learned, saved.merchant, saved.category) else s.learned
        val others = s.expenses.filterNot { it.id == saved.id }
        val refiled = if (taught) reclassify(others, learned, Classifier.key(saved.merchant)) else others
        s.copy(expenses = (refiled + saved).sortedByDescending { it.timestamp }, learned = learned)
    }

    /** Drops a learned merchant; its untagged expenses go back to the keyword rules. */
    fun forget(key: String) = mutate { s ->
        val learned = Classifier.forget(s.learned, key)
        s.copy(learned = learned, expenses = reclassify(s.expenses, learned, key))
    }

    private fun reclassify(expenses: List<Expense>, learned: Learned, key: String): List<Expense> {
        val brand = key.substringBefore(' ')
        val spreads = brand.length >= 4
        return expenses.map { e ->
            val k = Classifier.key(e.merchant)
            val related = k == key || (spreads && k.substringBefore(' ') == brand)
            if (e.userTagged || !related) e
            else e.copy(category = Classifier.classify(e.merchant, learned))
        }
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
                    category = Classifier.classify(parsed.merchant, s.learned),
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

    private fun encode(s: AppState) = JSONObject().apply {
        put("version", 1)
        s.balance?.let { put("balance", it) }
        put("balanceSetAt", s.balanceSetAt)
        put("currency", s.currency)
        s.monthlyBudget?.let { put("monthlyBudget", it) }
        put("recurring", JSONArray().apply {
            s.recurring.forEach { r ->
                put(JSONObject().apply {
                    put("id", r.id)
                    put("name", r.name)
                    put("amount", r.amount)
                    put("income", r.income)
                    put("category", r.category.name)
                    put("frequency", r.frequency.name)
                    put("day", r.day)
                    put("month", r.month)
                    put("start", r.startEpochDay)
                    put("postedThrough", r.postedThroughEpochDay)
                    put("active", r.active)
                })
            }
        })
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
                    put("userTagged", e.userTagged)
                    put("income", e.income)
                    e.recurringId?.let { put("recurringId", it) }
                })
            }
        })
        put("learned", JSONObject().apply {
            s.learned.forEach { (merchant, scores) ->
                put(merchant, JSONObject().apply { scores.forEach { (c, v) -> put(c.name, v) } })
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
                userTagged = e.optBoolean("userTagged"),
                income = e.optBoolean("income"),
                recurringId = if (e.has("recurringId")) e.getString("recurringId") else null,
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
        val learned = o.optJSONObject("learned")?.let { l ->
            l.keys().asSequence().associateWith { merchant ->
                val scores = l.getJSONObject(merchant)
                scores.keys().asSequence().mapNotNull { c ->
                    runCatching { Category.valueOf(c) to scores.getDouble(c) }.getOrNull()
                }.toMap()
            }
        } ?: emptyMap()
        val recurring = o.optJSONArray("recurring").objects().map { r ->
            Recurring(
                id = r.getString("id"),
                name = r.getString("name"),
                amount = r.getDouble("amount"),
                income = r.optBoolean("income"),
                category = runCatching { Category.valueOf(r.getString("category")) }.getOrDefault(Category.OTHER),
                frequency = runCatching { Frequency.valueOf(r.getString("frequency")) }.getOrDefault(Frequency.MONTHLY),
                day = r.getInt("day"),
                month = r.optInt("month", 1),
                startEpochDay = r.getLong("start"),
                postedThroughEpochDay = r.getLong("postedThrough"),
                active = r.optBoolean("active", true),
            )
        }
        return AppState(
            expenses = expenses.sortedByDescending { it.timestamp },
            balance = if (o.has("balance")) o.getDouble("balance") else null,
            balanceSetAt = o.optLong("balanceSetAt"),
            currency = o.optString("currency", "€"),
            recurring = recurring,
            monthlyBudget = if (o.has("monthlyBudget")) o.getDouble("monthlyBudget") else null,
            captures = captures,
            learned = learned,
        )
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
}
