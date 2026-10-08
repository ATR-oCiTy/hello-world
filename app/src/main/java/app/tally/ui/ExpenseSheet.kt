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
import app.tally.data.Category
import app.tally.data.Expense
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
}

private val amountPattern = Regex("""^\d{0,9}([.,]\d{0,2})?$""")
private fun String.toAmount(): Double? = replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
private fun Double.toInput(): String = if (this % 1.0 == 0.0) toLong().toString() else "%.2f".format(java.util.Locale.US, this)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TallySheet(
    sheet: Sheet,
    currency: String,
    currentBalance: Double?,
    learned: Learned,
    onDismiss: () -> Unit,
    onSave: (expense: Expense, taught: Boolean) -> Unit,
    onDelete: (Expense) -> Unit,
    onSetBalance: (Double) -> Unit,
) {
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
            Sheet.Balance -> BalanceForm(currency, currentBalance, onSetBalance)
            Sheet.NewExpense -> ExpenseForm(null, currency, learned, onSave, onDelete)
            is Sheet.EditExpense -> ExpenseForm(sheet.expense, currency, learned, onSave, onDelete)
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
    onChange: (String) -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Tally.Text),
        cursorBrush = SolidColor(Tally.Pink),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
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
private fun BalanceForm(currency: String, current: Double?, onSet: (Double) -> Unit) {
    var text by remember { mutableStateOf(current?.takeIf { it > 0 }?.toInput().orEmpty()) }
    SheetColumn {
        Text("Set balance", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Enter what's in your account right now. Every payment after this gets subtracted.",
            style = MaterialTheme.typography.bodyMedium,
            color = Tally.Muted,
        )
        Spacer(Modifier.height(24.dp))
        AmountField(text, currency) { text = it }
        Spacer(Modifier.height(28.dp))
        val value = text.replace(',', '.').toDoubleOrNull()
        GradientButton("Save balance", enabled = value != null) { value?.let(onSet) }
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
    var date by remember { mutableStateOf(existing?.timestamp?.toLocalDate() ?: LocalDate.now()) }
    var picking by remember { mutableStateOf(false) }

    val today = LocalDate.now()

    SheetColumn {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (existing == null) "New expense" else "Edit expense",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            if (existing?.source == Source.WALLET) {
                Text("via Tap & Pay", style = MaterialTheme.typography.labelSmall, color = Tally.Mint)
            }
        }
        Spacer(Modifier.height(20.dp))
        AmountField(amount, currency) { amount = it }
        Spacer(Modifier.height(20.dp))

        InputField(merchant, "Where? (e.g. Starbucks)") {
            merchant = it
            if (!taught && existing == null) category = Classifier.classify(it, learned)
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
        GradientButton(if (existing == null) "Add expense" else "Save changes", enabled = value != null) {
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
