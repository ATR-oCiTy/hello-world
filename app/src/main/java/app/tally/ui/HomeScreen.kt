package app.tally.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tally.data.AppState
import app.tally.data.Expense
import app.tally.data.Source
import app.tally.ui.theme.Tally
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun HomeScreen(
    state: AppState,
    listenerEnabled: Boolean,
    onConnect: () -> Unit,
    onSettings: () -> Unit,
    onSetBalance: () -> Unit,
    onBudget: () -> Unit,
    onEdit: (Expense) -> Unit,
    onDelete: (Expense) -> Unit,
) {
    val today = LocalDate.now()
    val cur = state.currency
    val insights = rememberInsights(state)
    val monthTotal = insights.month.spent
    val byDay = state.expenses.groupBy { it.timestamp.toLocalDate() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 140.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { ScreenHeader(greeting(), "Your money", onSettings) }
        if (!listenerEnabled) item { ConnectCard(onConnect) }
        item {
            BalanceHero(
                balance = state.currentBalance,
                currency = cur,
                monthTotal = monthTotal,
                runway = runwayLabel(insights.runway),
                onClick = onSetBalance,
            )
        }
        if (state.monthlyBudget != null) item { BudgetStrip(insights, cur, onBudget) }
        item { WeekCard(state, today) }
        if (monthTotal > 0) item { CategoryCard(state, today, monthTotal) }

        if (state.expenses.isEmpty()) {
            item { EmptyState(listenerEnabled) }
        } else {
            byDay.forEach { (day, items) ->
                item(key = "h$day") { DayHeader(day, today, items.filter { !it.income }.sumOf { it.amount }, cur) }
                items(items, key = { it.id }) { e ->
                    SwipeRow(e, cur, onEdit = { onEdit(e) }, onDelete = { onDelete(e) })
                }
            }
        }
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Up late"
}

@Composable
private fun BudgetStrip(insights: Insights, cur: String, onClick: () -> Unit) {
    val m = insights.month
    val budget = m.budget ?: return
    val frac = (m.spent / budget).toFloat().coerceIn(0f, 1f)
    val committedFrac = ((m.spent + m.upcomingFixed) / budget).toFloat().coerceIn(0f, 1f)
    val color = when {
        m.spent > budget -> Tally.Red
        m.projectedSpend > budget -> Tally.Orange
        else -> Tally.Mint
    }
    GlassCard(Modifier.pressable(onClick), padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                money(m.safeToSpend ?: 0.0, cur, decimals = false),
                style = MaterialTheme.typography.titleLarge,
                color = if ((m.safeToSpend ?: 0.0) < 0) Tally.Red else Tally.Text,
            )
            Spacer(Modifier.width(8.dp))
            Text("safe to spend", style = MaterialTheme.typography.bodyMedium, color = Tally.Muted, modifier = Modifier.weight(1f))
            Text(
                "${money((m.dailyAllowance ?: 0.0).coerceAtLeast(0.0), cur, decimals = false)}/day",
                style = MaterialTheme.typography.labelLarge,
                color = color,
            )
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(Tally.SurfaceHi)) {
            Box(Modifier.fillMaxWidth(committedFrac).height(8.dp).clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.3f)))
            Box(Modifier.fillMaxWidth(frac).height(8.dp).clip(RoundedCornerShape(50)).background(color))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "${money(m.spent, cur, decimals = false)} of ${money(budget, cur, decimals = false)} · ${m.daysLeft} days left",
            style = MaterialTheme.typography.labelSmall,
            color = Tally.Muted,
        )
    }
}

@Composable
private fun ConnectCard(onConnect: () -> Unit) {
    GlassCard(Modifier.pressable(onConnect)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Tally.Brand),
                contentAlignment = Alignment.Center,
            ) { Text("⚡", fontSize = 20.sp) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Connect Google Wallet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Allow notification access so taps are logged automatically.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tally.Muted,
                )
            }
            Text("→", style = MaterialTheme.typography.titleLarge, color = Tally.Pink)
        }
    }
}

@Composable
private fun BalanceHero(
    balance: Double?,
    currency: String,
    monthTotal: Double,
    runway: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(32.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .shadow(32.dp, shape, ambientColor = Tally.Pink, spotColor = Tally.Pink)
            .clip(shape)
            .background(Brush.linearGradient(Tally.HeroColors))
            .drawBehind {
                // Soft light blobs give the gradient some depth.
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.28f), Color.Transparent),
                        center = Offset(size.width * 0.9f, 0f),
                        radius = size.width * 0.7f,
                    ),
                    radius = size.width * 0.7f,
                    center = Offset(size.width * 0.9f, 0f),
                )
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color(0x66000000), Color.Transparent),
                        center = Offset(0f, size.height),
                        radius = size.width * 0.6f,
                    ),
                    radius = size.width * 0.6f,
                    center = Offset(0f, size.height),
                )
            }
            .pressable(onClick)
            .padding(24.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "BALANCE",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.weight(1f),
                )
                Text("✎ edit", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
            }
            Spacer(Modifier.height(10.dp))
            if (balance != null) {
                Text(
                    money(balance, currency),
                    style = MaterialTheme.typography.displayLarge,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text("Set balance", style = MaterialTheme.typography.displayLarge.copy(fontSize = 38.sp), color = Color.White)
                Text(
                    "Tap here and enter what's in your Expatrio account.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeroPill("This month", money(monthTotal, currency), Modifier.weight(1f))
                HeroPill("Money lasts", runway, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HeroPill(label: String, value: String, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.14f))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.75f))
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WeekCard(state: AppState, today: LocalDate) {
    val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val totals = days.map { d -> state.spending.filter { it.timestamp.toLocalDate() == d }.sumOf { it.amount } }
    val max = totals.maxOrNull()?.takeIf { it > 0 } ?: 1.0

    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val progress by animateFloatAsState(
        if (started) 1f else 0f,
        tween(900, easing = FastOutSlowInEasing),
        label = "bars",
    )

    GlassCard {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                SectionLabel("Last 7 days")
                Spacer(Modifier.height(4.dp))
                Text(money(totals.sum(), state.currency), style = MaterialTheme.typography.titleLarge)
            }
            Text(
                "avg ${money(totals.average(), state.currency, decimals = false)}/day",
                style = MaterialTheme.typography.labelSmall,
                color = Tally.Muted,
            )
        }
        Spacer(Modifier.height(20.dp))
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val n = days.size
            val gap = 12.dp.toPx()
            val barW = (size.width - gap * (n - 1)) / n
            val minH = 6.dp.toPx()
            totals.forEachIndexed { i, v ->
                val h = ((v / max).toFloat() * size.height * progress).coerceAtLeast(minH)
                val x = i * (barW + gap)
                val isToday = i == n - 1
                val brush = when {
                    isToday -> Brush.verticalGradient(listOf(Tally.Orange, Tally.Pink, Tally.Violet), startY = size.height - h, endY = size.height)
                    v > 0 -> SolidColor(Color(0xFF2E2E40))
                    else -> SolidColor(Color(0xFF1C1C27))
                }
                drawRoundRect(
                    brush = brush,
                    topLeft = Offset(x, size.height - h),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(10.dp.toPx()),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            days.forEachIndexed { i, d ->
                Text(
                    d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (i == days.lastIndex) Tally.Text else Tally.Faint,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun CategoryCard(state: AppState, today: LocalDate, monthTotal: Double) {
    val split = state.spending
        .filter { val d = it.timestamp.toLocalDate(); d.year == today.year && d.month == today.month }
        .groupBy { it.category }
        .mapValues { (_, v) -> v.sumOf { it.amount } }
        .entries.sortedByDescending { it.value }

    GlassCard {
        SectionLabel("Where it went · ${today.month.getDisplayName(TextStyle.FULL, Locale.getDefault())}")
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(50)),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            split.forEach { (cat, v) ->
                Box(
                    Modifier
                        .weight((v / monthTotal).toFloat().coerceAtLeast(0.02f))
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(cat.tint),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        split.take(4).forEach { (cat, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(cat.tint, 10.dp)
                Spacer(Modifier.width(10.dp))
                Text("${cat.emoji}  ${cat.label}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    "${(v / monthTotal * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = Tally.Muted,
                    modifier = Modifier.padding(end = 12.dp),
                )
                Text(money(v, state.currency), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun DayHeader(day: LocalDate, today: LocalDate, total: Double, currency: String) {
    val label = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(DateTimeFormatter.ofPattern("EEE, d MMM"))
    }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        SectionLabel(label, Modifier.weight(1f))
        Text(money(total, currency), style = MaterialTheme.typography.labelSmall, color = Tally.Muted)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(e: Expense, currency: String, onEdit: () -> Unit, onDelete: () -> Unit) {
    val dismiss = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            if (it == SwipeToDismissBoxValue.EndToStart) {
                onDelete(); true
            } else false
        },
    )
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, Tally.Red.copy(alpha = 0.85f))))
                    .padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Color.White)
            }
        },
    ) {
        ExpenseRow(e, currency, onEdit)
    }
}

@Composable
private fun ExpenseRow(e: Expense, currency: String, onClick: () -> Unit) {
    val time = e.timestamp.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
    val via = when (e.source) {
        Source.WALLET -> "Tap & Pay" + (e.card?.let { " ••$it" } ?: "")
        Source.MANUAL -> "Manual"
        Source.RECURRING -> "🔁 Plan"
        Source.IMPORT -> "Imported"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Tally.Surface)
            .border(1.dp, Tally.Stroke, RoundedCornerShape(24.dp))
            .pressable(onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EmojiBadge(e.category)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(e.merchant, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (e.source == Source.WALLET) {
                    Dot(Tally.Mint, 6.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    "$time · $via" + if (e.note.isNotBlank()) " · ${e.note}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = Tally.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            if (e.income) "+" + money(e.amount, currency) else money(-e.amount, currency),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = if (e.income) Tally.Mint else Tally.Text,
        )
    }
}

@Composable
private fun EmptyState(listenerEnabled: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("💸", fontSize = 48.sp)
        Spacer(Modifier.height(12.dp))
        Text("Nothing spent yet", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            if (listenerEnabled) "Tap your phone at a till and it'll show up here." else "Connect Wallet above, or add one with +.",
            style = MaterialTheme.typography.bodyMedium,
            color = Tally.Muted,
        )
    }
}

private fun Long.toLocalTime(): LocalTime =
    java.time.Instant.ofEpochMilli(this).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
