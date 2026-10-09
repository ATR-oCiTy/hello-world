package app.tally.ui

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tally.data.Expense
import app.tally.logic.StatementLine
import app.tally.ui.theme.Tally
import java.time.format.DateTimeFormatter

data class ImportRow(val line: StatementLine, val duplicate: Expense?, val selected: Boolean)

@Composable
fun ImportScreen(
    rows: List<ImportRow>,
    currency: String,
    onBack: () -> Unit,
    onChange: (index: Int, row: ImportRow) -> Unit,
    onImport: () -> Unit,
) {
    val selected = rows.count { it.selected }
    val dupes = rows.count { it.duplicate != null }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Tally.Surface)
                            .border(1.dp, Tally.Stroke, RoundedCornerShape(16.dp))
                            .pressable(onBack),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Tally.Text) }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Review import", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "${rows.size} found · $dupes already in Tally",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Tally.Muted,
                        )
                    }
                }
            }
            item {
                Text(
                    "Ticked rows get added. Ones that match something Tally already has (same amount, within 3 days) " +
                        "start unticked. Tap ± if a row has the wrong direction.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tally.Muted,
                )
            }
            if (rows.isEmpty()) {
                item {
                    GlassCard {
                        Text("No transactions recognised", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Tally couldn't find rows with a date and an amount in this file. " +
                                "If it's an Expatrio statement, send a redacted copy so the parser can be tuned.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Tally.Muted,
                        )
                    }
                }
            }
            itemsIndexed(rows) { i, row -> ImportRowView(row, currency, onToggle = { onChange(i, row.copy(selected = !row.selected)) }) {
                onChange(i, row.copy(line = row.line.copy(income = !row.line.income)))
            } }
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Tally.Bg.copy(alpha = 0.94f))
                .navigationBarsPadding()
                .padding(20.dp),
        ) {
            GradientButton(if (selected == 0) "Nothing selected" else "Import $selected", enabled = selected > 0, onClick = onImport)
        }
    }
}

@Composable
private fun ImportRowView(row: ImportRow, currency: String, onToggle: () -> Unit, onFlip: () -> Unit) {
    val l = row.line
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (row.selected) 1f else 0.55f)
            .clip(RoundedCornerShape(20.dp))
            .background(Tally.Surface)
            .border(1.dp, if (row.selected) Tally.Pink.copy(alpha = 0.4f) else Tally.Stroke, RoundedCornerShape(20.dp))
            .pressable(onToggle)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (row.selected) Tally.Pink else Tally.SurfaceHi),
            contentAlignment = Alignment.Center,
        ) {
            if (row.selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(l.description, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                l.date.format(DateTimeFormatter.ofPattern("d MMM yyyy")) +
                    (row.duplicate?.let { " · already logged as ${it.merchant}" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = if (row.duplicate != null) Tally.Orange else Tally.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                (if (l.income) "+" else "−") + money(l.amount, currency),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = if (l.income) Tally.Mint else Tally.Text,
            )
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Tally.SurfaceHi)
                    .pressable(onFlip)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) { Text("±", style = MaterialTheme.typography.labelLarge, color = Tally.Muted) }
        }
    }
}
