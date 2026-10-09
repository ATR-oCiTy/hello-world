package app.tally.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tally.data.AppState
import app.tally.data.Frequency
import app.tally.data.Recurring
import app.tally.logic.Recurrence
import app.tally.ui.theme.Tally
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun PlansScreen(
    state: AppState,
    onSettings: () -> Unit,
    onEdit: (Recurring) -> Unit,
) {
    val today = LocalDate.now()
    val cur = state.currency
    val incomes = state.recurring.filter { it.income }.sortedBy { Recurrence.nextDue(it, today) ?: LocalDate.MAX }
    val costs = state.recurring.filter { !it.income }.sortedBy { Recurrence.nextDue(it, today) ?: LocalDate.MAX }
    val fixedOut = costs.filter { it.active }.sumOf { it.monthlyAmount }
    val fixedIn = incomes.filter { it.active }.sumOf { it.monthlyAmount }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 140.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenHeader("Salary, debits & subscriptions", "Plans", onSettings) }
        item {
            GlassCard {
                SectionLabel("Every month")
                Spacer(Modifier.height(12.dp))
                Row {
                    Column(Modifier.weight(1f)) {
                        Text("Comes in", style = MaterialTheme.typography.labelSmall, color = Tally.Muted)
                        Text(money(fixedIn, cur), style = MaterialTheme.typography.titleLarge, color = Tally.Mint)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Goes out", style = MaterialTheme.typography.labelSmall, color = Tally.Muted)
                        Text(money(fixedOut, cur), style = MaterialTheme.typography.titleLarge)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Left over", style = MaterialTheme.typography.labelSmall, color = Tally.Muted)
                        Text(
                            money(fixedIn - fixedOut, cur),
                            style = MaterialTheme.typography.titleLarge,
                            color = if (fixedIn - fixedOut >= 0) Tally.Text else Tally.Red,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Tally posts these by itself on their due day and counts them in your balance, budget and runway.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tally.Muted,
                )
            }
        }

        if (state.recurring.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🔁", fontSize = 44.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("No plans yet", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Add your salary, Deutschlandticket, insurance, subscriptions… with +",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tally.Muted,
                    )
                }
            }
        }
        if (incomes.isNotEmpty()) {
            item { SectionLabel("Income", Modifier.padding(start = 4.dp, top = 8.dp)) }
            items(incomes, key = { it.id }) { PlanRow(it, cur, today) { onEdit(it) } }
        }
        if (costs.isNotEmpty()) {
            item { SectionLabel("Debits & subscriptions", Modifier.padding(start = 4.dp, top = 8.dp)) }
            items(costs, key = { it.id }) { PlanRow(it, cur, today) { onEdit(it) } }
        }
    }
}

@Composable
private fun PlanRow(r: Recurring, cur: String, today: LocalDate, onClick: () -> Unit) {
    val next = Recurrence.nextDue(r, today)
    val whenText = when {
        !r.active -> "Paused"
        next == null -> "—"
        next == today -> "Today"
        next == today.plusDays(1) -> "Tomorrow"
        else -> "Next " + next.format(DateTimeFormatter.ofPattern("d MMM"))
    }
    val freq = if (r.frequency == Frequency.YEARLY) "yearly" else "monthly"
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (r.active) 1f else 0.5f)
            .clip(RoundedCornerShape(24.dp))
            .background(Tally.Surface)
            .border(1.dp, Tally.Stroke, RoundedCornerShape(24.dp))
            .pressable(onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EmojiBadge(r.category)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$whenText · $freq", style = MaterialTheme.typography.labelSmall, color = Tally.Muted)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            money(if (r.income) r.amount else -r.amount, cur).let { if (r.income) "+$it" else it },
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = if (r.income) Tally.Mint else Tally.Text,
        )
    }
}
