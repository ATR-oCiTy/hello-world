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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tally.data.AppState
import app.tally.data.Capture
import app.tally.ui.theme.Tally
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val currencies = listOf("€", "$", "£", "₹", "CHF", "zł", "kr")

@Composable
fun SettingsScreen(
    state: AppState,
    modelInstalled: Boolean,
    modelSizeMb: Long,
    modelProgress: Float?,
    onPickModel: () -> Unit,
    onRemoveModel: () -> Unit,
    listenerEnabled: Boolean,
    onBack: () -> Unit,
    onOpenAccess: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onSetBalance: () -> Unit,
    onSetBudget: () -> Unit,
    onImport: () -> Unit,
    onRemoveImport: (app.tally.data.ImportBatch?) -> Unit,
    onCurrency: (String) -> Unit,
    onForget: (String) -> Unit,
    onErase: () -> Unit,
) {
    var confirmErase by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<Pair<app.tally.data.ImportBatch?, String>?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
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
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Tally.Text)
                }
                Spacer(Modifier.width(14.dp))
                Text("Settings", style = MaterialTheme.typography.headlineMedium)
            }
        }

        item {
            GlassCard {
                SectionLabel("Google Wallet")
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(if (listenerEnabled) Tally.Mint else Tally.Red, 10.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (listenerEnabled) "Listening for tap payments" else "Not connected",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tally reads only Google Wallet's payment notifications to log taps. " +
                        "Nothing leaves your phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tally.Muted,
                )
                Spacer(Modifier.height(16.dp))
                GradientButton(if (listenerEnabled) "Notification access" else "Grant notification access", onClick = onOpenAccess)
                if (!listenerEnabled) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Toggle greyed out? Android blocks this for sideloaded apps. Open App info → ⋮ " +
                            "(top right) → Allow restricted settings, then come back and try again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tally.Muted,
                    )
                    TextButton(onClick = onOpenAppInfo) { Text("Open App info", color = Tally.Pink) }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Also make sure Wallet's own “Transactions” notifications are switched on " +
                        "(Wallet → profile → Notifications).",
                    style = MaterialTheme.typography.labelSmall,
                    color = Tally.Faint,
                )
            }
        }

        item {
            GlassCard(Modifier.pressable(onSetBalance)) {
                SectionLabel("Balance")
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.currentBalance?.let { money(it, state.currency) } ?: "Not set",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text("Update →", style = MaterialTheme.typography.labelLarge, color = Tally.Pink)
                }
                if (state.balance != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Set to ${money(state.balance, state.currency)} on ${fmt(state.balanceSetAt, "d MMM, HH:mm")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Tally.Muted,
                    )
                }
            }
        }

        item {
            GlassCard {
                SectionLabel("On-device AI (Ask tab)")
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(if (modelInstalled) Tally.Mint else Tally.Faint, 10.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when {
                            modelProgress != null -> "Installing… ${(modelProgress * 100).toInt()}%"
                            modelInstalled -> "Model installed · $modelSizeMb MB"
                            else -> "No model: Ask gives exact, instant answers"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "For free-form questions, add Gemma 3 1B. It runs fully on your phone, offline, and never sees " +
                        "anything but your own figures. Get \"gemma3-1b-it-int4.task\" (~550 MB) from " +
                        "huggingface.co/litert-community/Gemma3-1B-IT (free account, accept Google's licence), then pick it here. " +
                        "Other MediaPipe .task chat models work too.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tally.Muted,
                )
                Spacer(Modifier.height(14.dp))
                if (modelProgress == null) {
                    GradientButton(if (modelInstalled) "Replace model file" else "Choose model file", onClick = onPickModel)
                    if (modelInstalled) {
                        TextButton(onClick = onRemoveModel, modifier = Modifier.fillMaxWidth()) {
                            Text("Remove model", color = Tally.Red)
                        }
                    }
                }
            }
        }

        item {
            GlassCard(Modifier.pressable(onSetBudget)) {
                SectionLabel("Monthly budget")
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.monthlyBudget?.let { money(it, state.currency) } ?: "Not set",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(if (state.monthlyBudget == null) "Set →" else "Edit →", style = MaterialTheme.typography.labelLarge, color = Tally.Pink)
                }
            }
        }

        item {
            GlassCard(Modifier.pressable(onImport)) {
                SectionLabel("Import bank statement")
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Pick a PDF or CSV", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("Choose →", style = MaterialTheme.typography.labelLarge, color = Tally.Pink)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Download your statement from the Expatrio app and pick it here. You review every row before " +
                        "anything is added, and ones Tally already has are skipped.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tally.Muted,
                )
            }
        }

        item {
            GlassCard {
                SectionLabel("Currency symbol")
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    currencies.take(4).forEach { c -> Chip(c, selected = state.currency == c) { onCurrency(c) } }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    currencies.drop(4).forEach { c -> Chip(c, selected = state.currency == c) { onCurrency(c) } }
                    CustomCurrency(state.currency, onCurrency)
                }
            }
        }

        item {
            GlassCard {
                SectionLabel("Learned places")
                Spacer(Modifier.height(4.dp))
                Text(
                    if (state.learned.isEmpty()) "Change a payment's category and Tally will file that place the same way from then on."
                    else "Future taps here get these categories. Older untagged payments were re-filed too.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tally.Muted,
                )
                state.learned.entries.sortedBy { it.key }.forEach { (key, scores) ->
                    val cat = scores.maxByOrNull { it.value }?.key ?: return@forEach
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        EmojiBadge(cat, 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                key.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(cat.label, style = MaterialTheme.typography.labelSmall, color = cat.tint)
                        }
                        TextButton(onClick = { onForget(key) }) { Text("Forget", color = Tally.Muted) }
                    }
                }
            }
        }

        item {
            GlassCard(Modifier.pressable { showLog = !showLog }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SectionLabel("Captured notifications")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "The last ${state.captures.size} Wallet notifications and what Tally made of them. " +
                                "Handy if a tap didn't show up.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Tally.Muted,
                        )
                    }
                    Text(if (showLog) "−" else "+", style = MaterialTheme.typography.titleLarge, color = Tally.Pink)
                }
            }
        }
        if (showLog) {
            if (state.captures.isEmpty()) {
                item { Text("Nothing captured yet.", color = Tally.Faint, modifier = Modifier.padding(start = 8.dp)) }
            }
            items(state.captures) { CaptureRow(it) }
        }

        val untracked = state.expenses.count { it.source == app.tally.data.Source.IMPORT && it.importId == null }
        if (state.imports.isNotEmpty() || untracked > 0) {
            item {
                GlassCard {
                    SectionLabel("Imports")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Remove an import that went wrong. Only its transactions go; taps, manual entries and plans stay. " +
                            "If it set your balance, the previous balance comes back.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tally.Muted,
                    )
                    state.imports.sortedByDescending { it.importedAt }.forEach { b ->
                        val count = state.expenses.count { it.importId == b.id }
                        ImportBatchRow(
                            title = b.fileName,
                            detail = "$count transactions · " +
                                "${fmtDay(b.firstEpochDay)} – ${fmtDay(b.lastEpochDay)} · imported ${fmt(b.importedAt, "d MMM, HH:mm")}" +
                                if (b.setBalanceAt != null) " · set balance" else "",
                        ) { confirmRemove = b to b.fileName }
                    }
                    if (untracked > 0) {
                        ImportBatchRow(title = "Earlier imports", detail = "$untracked transactions") {
                            confirmRemove = null to "earlier imports"
                        }
                    }
                }
            }
        }

        item {
            GlassCard(Modifier.pressable { confirmErase = true }) {
                Text("Erase all data", style = MaterialTheme.typography.titleMedium, color = Tally.Red)
                Text(
                    "Deletes every transaction, plan, your balance and budget, learned places and the capture log.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tally.Muted,
                )
            }
        }
    }

    confirmRemove?.let { (batch, label) ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            containerColor = Tally.SurfaceHi,
            title = { Text("Remove $label?") },
            text = { Text("Its transactions are deleted from Tally. You can import the file again later.", color = Tally.Muted) },
            confirmButton = {
                TextButton(onClick = { confirmRemove = null; onRemoveImport(batch) }) { Text("Remove", color = Tally.Red) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text("Cancel", color = Tally.Muted) }
            },
        )
    }

    if (confirmErase) {
        AlertDialog(
            onDismissRequest = { confirmErase = false },
            containerColor = Tally.SurfaceHi,
            title = { Text("Erase everything?") },
            text = { Text("This can't be undone.", color = Tally.Muted) },
            confirmButton = {
                TextButton(onClick = { confirmErase = false; onErase() }) { Text("Erase", color = Tally.Red) }
            },
            dismissButton = {
                TextButton(onClick = { confirmErase = false }) { Text("Cancel", color = Tally.Muted) }
            },
        )
    }
}

@Composable
private fun CustomCurrency(current: String, onCurrency: (String) -> Unit) {
    var text by remember { mutableStateOf(if (current in currencies) "" else current) }
    val shape = RoundedCornerShape(50)
    BasicTextField(
        value = text,
        onValueChange = {
            text = it.take(4)
            if (text.isNotBlank()) onCurrency(text.trim())
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.labelLarge.copy(color = Tally.Text),
        cursorBrush = SolidColor(Tally.Pink),
        decorationBox = { inner ->
            Box(
                Modifier
                    .clip(shape)
                    .background(Tally.SurfaceHi)
                    .border(1.dp, if (text.isNotBlank() && text == current) Tally.Pink else Tally.Stroke, shape)
                    .padding(horizontal = 14.dp, vertical = 9.dp)
                    .width(64.dp),
            ) {
                if (text.isEmpty()) Text("Other", style = MaterialTheme.typography.labelLarge, color = Tally.Faint)
                inner()
            }
        },
    )
}

@Composable
private fun CaptureRow(c: Capture) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Tally.Surface)
            .border(1.dp, Tally.Stroke, RoundedCornerShape(18.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(if (c.parsed) Tally.Mint else Tally.Faint)
            Spacer(Modifier.width(8.dp))
            Text(c.result, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(fmt(c.time, "d MMM HH:mm"), style = MaterialTheme.typography.labelSmall, color = Tally.Faint)
        }
        Spacer(Modifier.height(6.dp))
        Text(c.title, style = MaterialTheme.typography.bodyMedium)
        Text(c.text, style = MaterialTheme.typography.bodyMedium, color = Tally.Muted)
        Text(c.pkg, style = MaterialTheme.typography.labelSmall, color = Tally.Faint)
    }
}

private fun fmt(millis: Long, pattern: String): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(pattern))

@Composable
private fun ImportBatchRow(title: String, detail: String, onRemove: () -> Unit) {
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("📄", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = Tally.Muted)
        }
        TextButton(onClick = onRemove) { Text("Remove", color = Tally.Red) }
    }
}

private fun fmtDay(epochDay: Long): String =
    java.time.LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern("d MMM yy"))
