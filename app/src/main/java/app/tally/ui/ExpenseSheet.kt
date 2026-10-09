package app.tally.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tally.data.AppState
import app.tally.data.Category
import app.tally.data.Expense
import app.tally.data.Frequency
import app.tally.data.Recurring
import app.tally.logic.Recurrence
import app.tally.data.Source
import app.tally.parser.Classifier
import app.tally.parser.Learned
import app.tally.ui.theme.Tally
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

sealed interface Sheet {
    data object NewExpense : Sheet
    data class EditExpense(val expense: Expense) : Sheet
    data object Balance : Sheet
    data object Budget : Sheet
    data object NewRecurring : Sheet
    data class EditRecurring(val recurring: Recurring) : Sheet
}

class SheetActions(
    val onSave: (expense: Expense, taught: Boolean) -> Unit,
    val onDelete: (Expense) -> Unit,
    val onSetBalance: (Double) -> Unit,
    val onSetBudget: (Double?) -> Unit,
    val onSaveRecurring: (Recurring) -> Unit,
    val onDeleteRecurring: (Recurring) -> Unit,
)

private val amountPattern = Regex("""^\d{0,9}([.,]\d{0,2})?$""")
private fun String.toAmount(): Double? = replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
private fun Double.toInput(): String = if (this % 1.0 == 0.0) toLong().toString() else "%.2f".format(java.util.Locale.US, this)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TallySheet(
    sheet: Sheet,
    state: AppState,
    onDismiss: () -> Unit,
    actions: SheetActions,
) {
    val currency = state.currency
    val learned = state.learned
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Tally.Surface,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 12.dp, bottom = 4.dp)
                    .width(40.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Tally.Faint),
            )
        },
    ) {
        when (sheet) {
            Sheet.Balance -> AmountForm(
                title = "Set balance",
                description = "Enter what's in your account right now. Everything after this moment adjusts it.",
                currency = currency,
                initial = state.currentBalance,
                button = "Save balance",
                onSave = actions.onSetBalance,
            )
            Sheet.Budget -> AmountForm(
                title = "Monthly budget",
                description = "Everything you allow yourself in a month, rent, insurance and subscriptions included.",
                currency = currency,
                initial = state.monthlyBudget,
                button = "Save budget",
                onSave = actions.onSetBudget,
                onRemove = if (state.monthlyBudget != null) ({ actions.onSetBudget(null) }) else null,
            )
            Sheet.NewExpense -> ExpenseForm(null, currency, learned, actions.onSave, actions.onDelete)
            is Sheet.EditExpense -> ExpenseForm(sheet.expense, currency, learned, actions.onSave, actions.onDelete)
            Sheet.NewRecurring -> RecurringForm(null, currency, learned, actions.onSaveRecurring, actions.onDeleteRecurring)
            is Sheet.EditRecurring -> RecurringForm(sheet.recurring, currency, learned, actions.onSaveRecurring, actions.onDeleteRecurring)
        }
    }
}

@Composable
private fun AmountField(value: String, currency: String, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BasicTextField(
        value = value,
        onValueChange = { if (amountPattern.matches(it)) onChange(it) },
        singleLine = true,
        textStyle = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.Black, color = Tally.Text, letterSpacing = (-1.5).sp),
        cursorBrush = SolidColor(Tally.Pink),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        decorationBox = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    currency,
                    style = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.Black, brush = Tally.Brand),
                )
                Spacer(Modifier.width(6.dp))
                Box {
                    if (value.isEmpty()) {
                        Text("0", style = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.Black, color = Tally.Faint))
                    }
                    inner()
                }
            }
        },
    )
}

@Composable
private fun InputField(
    value: String,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Tally.Text),
        cursorBrush = SolidColor(Tally.Pink),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(Tally.SurfaceHi)
                    .border(1.dp, Tally.Stroke, shape)
                    .padding(horizontal = 18.dp, vertical = 16.dp),
            ) {
                if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = Tally.Faint)
                inner()
            }
        },
    )
}

@Composable
private fun SheetColumn(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 8.dp, bottom = 16.dp)
            .navigationBarsPadding()
            .imePadding(),
    ) { content() }
}

@Composable
private fun AmountForm(
    title: String,
    description: String,
    currency: String,
    initial: Double?,
    button: String,
    onSave: (Double) -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    var text by remember { mutableStateOf(initial?.takeIf { it > 0 }?.toInput().orEmpty()) }
    SheetColumn {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(description, style = MaterialTheme.typography.bodyMedium, color = Tally.Muted)
        Spacer(Modifier.height(24.dp))
        AmountField(text, currency) { text = it }
        Spacer(Modifier.height(28.dp))
        val value = text.replace(',', '.').toDoubleOrNull()
        GradientButton(button, enabled = value != null) { value?.let(onSave) }
        if (onRemove != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) {
                Text("Remove", color = Tally.Red, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpenseForm(
    existing: Expense?,
    currency: String,
    learned: Learned,
    onSave: (expense: Expense, taught: Boolean) -> Unit,
    onDelete: (Expense) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    var amount by remember { mutableStateOf(existing?.amount?.toInput().orEmpty()) }
    var merchant by remember { mutableStateOf(existing?.merchant.orEmpty()) }
    var note by remember { mutableStateOf(existing?.note.orEmpty()) }
    var category by remember { mutableStateOf(existing?.category ?: Category.OTHER) }
    // True once you tap a category chip: that's a tag Tally learns from.
    var taught by remember { mutableStateOf(false) }
    var income by remember { mutableStateOf(existing?.income ?: false) }
    var date by remember { mutableStateOf(existing?.timestamp?.toLocalDate() ?: LocalDate.now()) }
    var picking by remember { mutableStateOf(false) }

    val today = LocalDate.now()

    SheetColumn {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    existing != null -> if (income) "Edit income" else "Edit expense"
                    income -> "New income"
                    else -> "New expense"
                },
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            if (existing?.source == Source.WALLET) {
                Text("via Tap & Pay", style = MaterialTheme.typography.labelSmall, color = Tally.Mint)
            }
        }
        Spacer(Modifier.height(16.dp))
        SegmentedToggle(listOf("Expense", "Income"), if (income) 1 else 0, accents = listOf(Tally.Pink, Tally.Mint)) {
            income = it == 1
            if (!taught) category = if (income) Category.INCOME else Classifier.classify(merchant, learned)
        }
        Spacer(Modifier.height(20.dp))
        AmountField(amount, currency) { amount = it }
        Spacer(Modifier.height(20.dp))

        InputField(merchant, if (income) "From? (e.g. ACME GmbH)" else "Where? (e.g. Starbucks)") {
            merchant = it
            if (!taught && existing == null && !income) category = Classifier.classify(it, learned)
        }
        Spacer(Modifier.height(20.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Category", Modifier.weight(1f))
            val hint = when {
                taught && merchant.isNotBlank() -> "✨ Tally will remember this"
                !taught && Classifier.learnedCategory(merchant, learned) == category -> "✨ Learned from your tags"
                else -> null
            }
            hint?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Tally.Violet) }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Category.entries.forEach { c ->
                Chip("${c.emoji} ${c.label}", selected = c == category, accent = c.tint) {
                    category = c
                    taught = true
                }
            }
        }
        Spacer(Modifier.height(20.dp))

        SectionLabel("When")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Today", selected = date == today) { date = today }
            Chip("Yesterday", selected = date == today.minusDays(1)) { date = today.minusDays(1) }
            val other = date != today && date != today.minusDays(1)
            Chip(if (other) date.format(DateTimeFormatter.ofPattern("d MMM")) else "Pick date…", selected = other) {
                picking = true
            }
        }
        Spacer(Modifier.height(20.dp))

        InputField(note, "Note (optional)") { note = it }
        Spacer(Modifier.height(28.dp))

        val value = amount.toAmount()
        GradientButton(if (existing != null) "Save changes" else if (income) "Add income" else "Add expense", enabled = value != null) {
            val ts = when {
                existing != null && existing.timestamp.toLocalDate() == date -> existing.timestamp
                date == today -> System.currentTimeMillis()
                else -> date.atTime(existing?.timestamp?.let {
                    Instant.ofEpochMilli(it).atZone(zone).toLocalTime()
                } ?: LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
            }
            onSave(
                Expense(
                    id = existing?.id ?: app.tally.data.ExpenseStore.newId(),
                    amount = value!!,
                    merchant = merchant.trim().ifEmpty { category.label },
                    category = category,
                    timestamp = ts,
                    source = existing?.source ?: Source.MANUAL,
                    note = note.trim(),
                    card = existing?.card,
                    userTagged = existing?.userTagged ?: false,
                    income = income,
                    recurringId = existing?.recurringId,
                ),
                taught && merchant.isNotBlank(),
            )
        }
        if (existing != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { onDelete(existing) }, modifier = Modifier.fillMaxWidth()) {
                Text("Delete", color = Tally.Red, style = MaterialTheme.typography.labelLarge)
            }
        }
    }

    if (picking) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    picking = false
                }) { Text("Done", color = Tally.Pink) }
            },
            dismissButton = {
                TextButton(onClick = { picking = false }) { Text("Cancel", color = Tally.Muted) }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun RecurringForm(
    existing: Recurring?,
    currency: String,
    learned: Learned,
    onSave: (Recurring) -> Unit,
    onDelete: (Recurring) -> Unit,
) {
    val today = LocalDate.now()
    var income by remember { mutableStateOf(existing?.income ?: false) }
    var amount by remember { mutableStateOf(existing?.amount?.toInput().orEmpty()) }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var category by remember { mutableStateOf(existing?.category ?: Category.SUBSCRIPTIONS) }
    var categoryTouched by remember { mutableStateOf(existing != null) }
    var frequency by remember { mutableStateOf(existing?.frequency ?: Frequency.MONTHLY) }
    var day by remember { mutableStateOf(existing?.day ?: today.dayOfMonth) }
    var month by remember { mutableStateOf(existing?.month ?: today.monthValue) }
    val active = existing?.active ?: true
    var skipToday by remember { mutableStateOf(false) }
    var changes by remember { mutableStateOf(existing?.changes.orEmpty()) }
    var endEpochDay by remember { mutableStateOf(existing?.endEpochDay) }
    var addingChange by remember { mutableStateOf(false) }
    var changeAmount by remember { mutableStateOf("") }
    var changeDate by remember { mutableStateOf(today.plusMonths(1).withDayOfMonth(1)) }
    var picking by remember { mutableStateOf<String?>(null) }

    val start = existing?.startEpochDay ?: (if (skipToday) today.plusDays(1) else today).toEpochDay()
    val draft = Recurring(
        id = existing?.id ?: app.tally.data.ExpenseStore.newId(),
        name = name.trim().ifEmpty { if (income) "Salary" else category.label },
        amount = amount.toAmount() ?: 0.0,
        income = income,
        category = category,
        frequency = frequency,
        day = day,
        month = month,
        startEpochDay = start,
        postedThroughEpochDay = existing?.postedThroughEpochDay ?: (today.toEpochDay() - 1),
        active = active,
        changes = changes.sortedBy { it.fromEpochDay },
        endEpochDay = endEpochDay,
        matchKey = existing?.matchKey,
    )
    val next = Recurrence.nextDue(draft, today)

    SheetColumn {
        Text(
            if (existing == null) "New plan" else "Edit plan",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Posted automatically on its due day: salary, Deutschlandticket, insurance, subscriptions.",
            style = MaterialTheme.typography.bodyMedium,
            color = Tally.Muted,
        )
        Spacer(Modifier.height(16.dp))
        SegmentedToggle(listOf("Goes out", "Comes in"), if (income) 1 else 0, accents = listOf(Tally.Pink, Tally.Mint)) {
            income = it == 1
            if (!categoryTouched) category = if (income) Category.INCOME else Classifier.classify(name, learned)
        }
        Spacer(Modifier.height(20.dp))
        AmountField(amount, currency) { amount = it }
        Spacer(Modifier.height(20.dp))
        InputField(name, if (income) "e.g. Working student salary" else "e.g. Deutschlandticket") {
            name = it
            if (!categoryTouched && !income) {
                category = Classifier.classify(it, learned).let { c -> if (c == Category.OTHER) Category.SUBSCRIPTIONS else c }
            }
        }
        Spacer(Modifier.height(20.dp))

        SectionLabel("Category")
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Category.entries.forEach { c ->
                Chip("${c.emoji} ${c.label}", selected = c == category, accent = c.tint) {
                    category = c
                    categoryTouched = true
                }
            }
        }
        Spacer(Modifier.height(20.dp))

        SectionLabel("Repeats")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Frequency.entries.forEach { f -> Chip(f.label, selected = f == frequency) { frequency = f } }
        }
        if (frequency == Frequency.YEARLY) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                java.time.Month.values().forEach { m ->
                    Chip(
                        m.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()),
                        selected = m.value == month,
                    ) { month = m.value }
                }
            }
        }
        Spacer(Modifier.height(20.dp))

        SectionLabel("On day")
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..31).forEach { d -> Chip(d.toString(), selected = d == day) { day = d } }
        }
        Spacer(Modifier.height(20.dp))

        SectionLabel(if (income) "Raises & changes" else "Price changes")
        Spacer(Modifier.height(6.dp))
        val fmt = DateTimeFormatter.ofPattern("d MMM yyyy")
        changes.sortedBy { it.fromEpochDay }.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "From ${LocalDate.ofEpochDay(c.fromEpochDay).format(fmt)}: ${money(c.amount, currency)}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { changes = changes - c }) { Text("Remove", color = Tally.Muted) }
            }
        }
        if (addingChange) {
            Spacer(Modifier.height(6.dp))
            InputField(changeAmount, "New amount", KeyboardType.Decimal) {
                if (amountPattern.matches(it)) changeAmount = it
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip("From ${changeDate.format(fmt)}", selected = false) { picking = "change" }
                Chip("Add", selected = changeAmount.toAmount() != null, accent = Tally.Violet) {
                    changeAmount.toAmount()?.let { a ->
                        changes = changes.filterNot { it.fromEpochDay == changeDate.toEpochDay() } +
                            app.tally.data.AmountChange(changeDate.toEpochDay(), a)
                        changeAmount = ""
                        addingChange = false
                    }
                }
            }
        } else {
            Chip(if (income) "+ Schedule a raise" else "+ Schedule a change", selected = false, accent = Tally.Violet) {
                addingChange = true
            }
        }
        Spacer(Modifier.height(20.dp))

        SectionLabel("Ends")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Never", selected = endEpochDay == null) { endEpochDay = null }
            Chip(
                endEpochDay?.let { "On " + LocalDate.ofEpochDay(it).format(fmt) } ?: "Pick end date…",
                selected = endEpochDay != null,
            ) { picking = "end" }
        }
        Spacer(Modifier.height(16.dp))

        Text(
            when {
                !active -> "Paused: nothing will be posted."
                next == null -> ""
                next == today -> "First one posts today."
                else -> "Next one posts on ${next.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy"))}."
            } + if (day > 28) " Shorter months use their last day." else "",
            style = MaterialTheme.typography.labelSmall,
            color = Tally.Violet,
        )
        if (existing == null && (next == today || skipToday)) {
            Spacer(Modifier.height(8.dp))
            Chip("Already paid today: skip it", selected = skipToday, accent = Tally.Violet) { skipToday = !skipToday }
        }
        Spacer(Modifier.height(24.dp))

        GradientButton(if (existing == null) "Add plan" else "Save changes", enabled = amount.toAmount() != null) {
            onSave(draft)
        }
        if (existing != null) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = { onSave(draft.copy(active = !active)) }, modifier = Modifier.weight(1f)) {
                    Text(if (active) "Pause" else "Resume", color = Tally.Muted, style = MaterialTheme.typography.labelLarge)
                }
                TextButton(onClick = { onDelete(existing) }, modifier = Modifier.weight(1f)) {
                    Text("Delete", color = Tally.Red, style = MaterialTheme.typography.labelLarge)
                }
            }
            Text(
                "Pause and Delete keep what's already been posted in your history.",
                style = MaterialTheme.typography.labelSmall,
                color = Tally.Faint,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    picking?.let { which ->
        val initial = if (which == "end") endEpochDay?.let(LocalDate::ofEpochDay) ?: today.plusMonths(6) else changeDate
        PickDate(initial, onDismiss = { picking = null }) { d ->
            if (which == "end") endEpochDay = d.toEpochDay() else changeDate = d
            picking = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickDate(initial: LocalDate, onDismiss: () -> Unit, onPicked: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPicked(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss()
            }) { Text("Done", color = Tally.Pink) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Tally.Muted) } },
    ) { DatePicker(state = state) }
}
