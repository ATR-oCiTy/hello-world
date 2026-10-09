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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tally.data.AppState
import app.tally.logic.Metrics
import app.tally.logic.MonthStats
import app.tally.logic.Runway
import app.tally.ui.theme.Tally
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/** Everything derived from the raw data that more than one screen shows. */
data class Insights(
    val month: MonthStats,
    val dailySpend: Double,
    /** At your actual recent pace, or null without a balance. */
    val runway: Runway?,
    /** If you spent exactly your budget, or null without a balance or budget. */
    val budgetRunway: Runway?,
)

@Composable
fun rememberInsights(state: AppState): Insights {
    val today = LocalDate.now()
    return remember(state, today) {
        val daily = Metrics.dailyVariableSpend(state.expenses, today)
        val balance = state.currentBalance
        Insights(
            month = Metrics.month(state.expenses, state.recurring, state.monthlyBudget, today),
            dailySpend = daily,
            runway = balance?.let { Metrics.runway(it, today, daily, state.recurring) },
            budgetRunway = if (balance != null && state.monthlyBudget != null) {
                Metrics.runway(balance, today, Metrics.budgetDailySpend(state.monthlyBudget, state.recurring), state.recurring)
            } else null,
        )
    }
}

/** "7.4 months" / "18 days" / "∞". */
fun runwayLabel(r: Runway?): String {
    val months = r?.months ?: return if (r == null) "—" else "∞"
    return if (months >= 1) "%.1f months".format(months)
    else "${(months * 30.44).toInt().coerceAtLeast(0)} days"
}

@Composable
fun InsightsScreen(
    state: AppState,
    onSettings: () -> Unit,
    onSetBudget: () -> Unit,
    onSetBalance: () -> Unit,
) {
    val insights = rememberInsights(state)
    val today = LocalDate.now()
    val cur = state.currency

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 140.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { ScreenHeader("Am I overspending?", "Insights", onSettings) }
        item { RunwayHero(insights, state, cur, onSetBalance) }
        item { BudgetCard(insights.month, cur, onSetBudget) }
        item { MonthTiles(insights.month, cur) }
        item { TrendCard(state, today) }
        item { PaceCard(insights, state, cur) }
        item { CategoryChangesCard(state, today) }
    }
}

@Composable
private fun RunwayHero(insights: Insights, state: AppState, cur: String, onSetBalance: () -> Unit) {
    val r = insights.runway
    val shape = RoundedCornerShape(32.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF1E1B4B), Color(0xFF4C1D95), Color(0xFF0E7490))))
            .drawBehind {
                drawCircle(
                    Brush.radialGradient(
                        listOf(Tally.Mint.copy(alpha = 0.35f), Color.Transparent),
                        center = Offset(size.width, size.height),
                        radius = size.width * 0.8f,
                    ),
                    radius = size.width * 0.8f,
                    center = Offset(size.width, size.height),
                )
            }
            .then(if (r == null) Modifier.pressable(onSetBalance) else Modifier)
            .padding(24.dp),
    ) {
        Column {
            Text("YOUR MONEY LASTS", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.75f))
            Spacer(Modifier.height(10.dp))
            when {
                r == null -> {
                    Text("Set your balance", style = MaterialTheme.typography.displayLarge.copy(fontSize = 36.sp), color = Color.White)
                    Text("Tap to enter it, then Tally can forecast.", color = Color.White.copy(alpha = 0.8f))
                }
                r.runOut == null -> {
                    Text("Indefinitely", style = MaterialTheme.typography.displayLarge.copy(fontSize = 40.sp), color = Color.White)
                    Text(
                        "Income covers your spending: about +${money(r.monthlyNet, cur, decimals = false)} a month.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
                else -> {
                    Text(runwayLabel(r), style = MaterialTheme.typography.displayLarge, color = Color.White)
                    Text(
                        "Runs out around ${r.runOut.format(DateTimeFormatter.ofPattern("d MMMM yyyy"))}",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.9f),
                    )
                }
            }
            if (r != null) {
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassStat("Usual spend", "${money(insights.dailySpend, cur)}/day", Modifier.weight(1f))
                    GlassStat(
                        "On budget",
                        when {
                            state.monthlyBudget == null -> "No budget"
                            else -> runwayLabel(insights.budgetRunway)
                        },
                        Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Balance ${money(state.currentBalance ?: 0.0, cur)} · net ${money(r.monthlyNet, cur, decimals = false)}/month " +
                        "incl. ${state.recurring.count { it.active }} plans",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun GlassStat(label: String, value: String, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.75f))
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BudgetCard(m: MonthStats, cur: String, onSetBudget: () -> Unit) {
    val budget = m.budget
    GlassCard(Modifier.pressable(onSetBudget)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Monthly budget", Modifier.weight(1f))
            Text(if (budget == null) "Set →" else "Edit", style = MaterialTheme.typography.labelLarge, color = Tally.Pink)
        }
        Spacer(Modifier.height(14.dp))
        if (budget == null) {
            Text("Set a budget to see your headroom", style = MaterialTheme.typography.titleMedium)
            Text(
                "One number for everything you allow yourself per month, rent and subscriptions included.",
                style = MaterialTheme.typography.bodyMedium,
                color = Tally.Muted,
            )
            return@GlassCard
        }

        val committed = m.spent + m.upcomingFixed
        val (status, statusColor) = when {
            m.spent > budget -> "Over budget" to Tally.Red
            m.projectedSpend > budget -> "Heading over" to Tally.Orange
            (m.overPace ?: 0.0) > 0 -> "Spending fast" to Tally.Orange
            else -> "On track" to Tally.Mint
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            BudgetRing(spent = m.spent, committed = committed, budget = budget, color = statusColor)
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(statusColor.copy(alpha = 0.16f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) { Text(status, style = MaterialTheme.typography.labelSmall, color = statusColor) }
                Spacer(Modifier.height(8.dp))
                Text(
                    money(m.safeToSpend ?: 0.0, cur),
                    style = MaterialTheme.typography.headlineMedium,
                    color = if ((m.safeToSpend ?: 0.0) < 0) Tally.Red else Tally.Text,
                )
                Text("safe to spend this month", style = MaterialTheme.typography.labelSmall, color = Tally.Muted)
                Spacer(Modifier.height(6.dp))
                Text(
                    "${money((m.dailyAllowance ?: 0.0).coerceAtLeast(0.0), cur)}/day for ${m.daysLeft} days",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        StatRow("Spent so far", money(m.spent, cur))
        StatRow("Still to come (plans)", money(m.upcomingFixed, cur))
        StatRow("Projected month end", money(m.projectedSpend, cur), if (m.projectedSpend > budget) Tally.Orange else null)
        m.overPace?.let { p ->
            StatRow(
                if (p > 0) "Ahead of budget pace" else "Behind budget pace",
                money(abs(p), cur),
                if (p > 0) Tally.Orange else Tally.Mint,
            )
        }
    }
}

@Composable
private fun BudgetRing(spent: Double, committed: Double, budget: Double, color: Color) {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val p by animateFloatAsState(if (started) 1f else 0f, tween(900, easing = FastOutSlowInEasing), label = "ring")
    val spentFrac = (spent / budget).toFloat().coerceIn(0f, 1f)
    val committedFrac = (committed / budget).toFloat().coerceIn(0f, 1f)
    Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(Tally.SurfaceHi, -90f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            drawArc(color.copy(alpha = 0.3f), -90f, 360f * committedFrac * p, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(color, -90f, 360f * spentFrac * p, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${(spent / budget * 100).toInt()}%", style = MaterialTheme.typography.titleLarge)
            Text("used", style = MaterialTheme.typography.labelSmall, color = Tally.Muted)
        }
    }
}

@Composable
private fun StatRow(label: String, value: String, color: Color? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Tally.Muted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge, color = color ?: Tally.Text)
    }
}

@Composable
private fun MonthTiles(m: MonthStats, cur: String) {
    val net = m.income - m.spent
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile("In", money(m.income, cur, decimals = false), Tally.Mint, Modifier.weight(1f))
        Tile("Out", money(m.spent, cur, decimals = false), Tally.Pink, Modifier.weight(1f))
        Tile("Net", money(net, cur, decimals = false), if (net >= 0) Tally.Mint else Tally.Red, Modifier.weight(1f))
    }
}

@Composable
private fun Tile(label: String, value: String, accent: Color, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Tally.Surface)
            .border(1.dp, Tally.Stroke, RoundedCornerShape(22.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(accent, 7.dp)
            Spacer(Modifier.width(6.dp))
            Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = Tally.Muted)
        }
        Spacer(Modifier.height(6.dp))
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("this month", style = MaterialTheme.typography.labelSmall, color = Tally.Faint)
    }
}

@Composable
private fun TrendCard(state: AppState, today: LocalDate) {
    val months = Metrics.history(state.expenses, today)
    val max = months.maxOf { maxOf(it.spent, it.income) }.takeIf { it > 0 } ?: 1.0
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val p by animateFloatAsState(if (started) 1f else 0f, tween(900, easing = FastOutSlowInEasing), label = "trend")

    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Last 6 months", Modifier.weight(1f))
            Dot(Tally.Mint, 7.dp); Spacer(Modifier.width(4.dp))
            Text("in", style = MaterialTheme.typography.labelSmall, color = Tally.Muted)
            Spacer(Modifier.width(10.dp))
            Dot(Tally.Pink, 7.dp); Spacer(Modifier.width(4.dp))
            Text("out", style = MaterialTheme.typography.labelSmall, color = Tally.Muted)
        }
        Spacer(Modifier.height(18.dp))
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val groupGap = 14.dp.toPx()
            val groupW = (size.width - groupGap * (months.size - 1)) / months.size
            val barGap = 4.dp.toPx()
            val barW = (groupW - barGap) / 2
            val r = CornerRadius(8.dp.toPx())
            months.forEachIndexed { i, m ->
                val x = i * (groupW + groupGap)
                val inH = ((m.income / max).toFloat() * size.height * p).coerceAtLeast(4.dp.toPx())
                val outH = ((m.spent / max).toFloat() * size.height * p).coerceAtLeast(4.dp.toPx())
                drawRoundRect(Tally.Mint.copy(alpha = if (m.income > 0) 0.9f else 0.2f), Offset(x, size.height - inH), Size(barW, inH), r)
                drawRoundRect(
                    Brush.verticalGradient(listOf(Tally.Orange, Tally.Pink), startY = size.height - outH, endY = size.height),
                    Offset(x + barW + barGap, size.height - outH),
                    Size(barW, outH),
                    r,
                    alpha = if (m.spent > 0) 1f else 0.2f,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            months.forEach { m ->
                Text(
                    m.month.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (m.month.monthValue == today.monthValue) Tally.Text else Tally.Faint,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun PaceCard(insights: Insights, state: AppState, cur: String) {
    val m = insights.month
    val fixedOut = state.recurring.filter { it.active && !it.income }.sumOf { it.monthlyAmount }
    val fixedIn = state.recurring.filter { it.active && it.income }.sumOf { it.monthlyAmount }
    val vsLast = if (m.lastMonthToDate > 0) (m.spent - m.lastMonthToDate) / m.lastMonthToDate * 100 else null
    GlassCard {
        SectionLabel("The shape of your month")
        Spacer(Modifier.height(10.dp))
        StatRow("Fixed costs (plans)", "${money(fixedOut, cur)}/mo")
        StatRow("Regular income (plans)", "${money(fixedIn, cur)}/mo", Tally.Mint)
        StatRow("Usual day-to-day spending", "${money(insights.dailySpend * 30.44, cur, decimals = false)}/mo")
        if (fixedIn > 0) {
            StatRow("Fixed costs eat", "${(fixedOut / fixedIn * 100).toInt()}% of income", if (fixedOut > fixedIn) Tally.Red else null)
        }
        vsLast?.let {
            StatRow(
                "vs same point last month",
                "${if (it >= 0) "+" else ""}${it.toInt()}%",
                if (it > 10) Tally.Orange else if (it < -5) Tally.Mint else null,
            )
        }
    }
}

@Composable
private fun CategoryChangesCard(state: AppState, today: LocalDate) {
    val changes = Metrics.categoryChanges(state.expenses, today).filter { it.thisMonth > 0 }.take(6)
    if (changes.isEmpty()) return
    GlassCard {
        SectionLabel("Categories vs last month")
        Spacer(Modifier.height(6.dp))
        changes.forEach { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                EmojiBadge(c.category, 34.dp)
                Spacer(Modifier.width(12.dp))
                Text(c.category.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                val delta = c.thisMonth - c.lastMonth
                if (c.lastMonth > 0 || delta != 0.0) {
                    Text(
                        (if (delta > 0) "▲ " else if (delta < 0) "▼ " else "") + money(abs(delta), state.currency, decimals = false),
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            delta > 0 -> Tally.Orange
                            delta < 0 -> Tally.Mint
                            else -> Tally.Muted
                        },
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
                Text(money(c.thisMonth, state.currency, decimals = false), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
