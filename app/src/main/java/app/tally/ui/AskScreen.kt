package app.tally.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.tally.ui.theme.Tally

data class ChatMessage(
    val fromUser: Boolean,
    val text: String,
    /** Shown under an answer: "Exact" or "Gemma, from your data". */
    val source: String = "",
    /** The exact computed figures, shown under a model-written answer so you can check it. */
    val figures: String? = null,
    val thinking: Boolean = false,
    /** A plan change this answer suggests, offered as a button. */
    val change: app.tally.logic.SuggestedChange? = null,
    val applied: Boolean = false,
)

private val suggestions = listOf(
    "How much did I spend on food last month?",
    "When does my money run out?",
    "Top places this month",
    "Compare with last month",
    "What subscriptions do I pay?",
    "Biggest purchase last month",
    "How much at REWE in September?",
    "How much is left in my budget?",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskScreen(
    messages: List<ChatMessage>,
    modelInstalled: Boolean,
    useModel: Boolean,
    busy: Boolean,
    onToggleModel: () -> Unit,
    onSettings: () -> Unit,
    onSend: (String) -> Unit,
    onApply: (ChatMessage) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) list.animateScrollToItem(messages.size)
    }
    val keyboard = WindowInsets.isImeVisible

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = list,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ScreenHeader("Ask about your money", "Ask", onSettings)
                Spacer(Modifier.height(12.dp))
                ModeCard(modelInstalled, useModel, onToggleModel, onSettings)
            }
            if (messages.isEmpty()) {
                item {
                    Column(Modifier.padding(top = 12.dp)) {
                        Text("Try asking", style = MaterialTheme.typography.labelMedium, color = Tally.Muted)
                        Spacer(Modifier.height(10.dp))
                        suggestions.forEach { s ->
                            Box(
                                Modifier
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Tally.Surface)
                                    .border(1.dp, Tally.Stroke, RoundedCornerShape(16.dp))
                                    .pressable { onSend(s) }
                                    .padding(horizontal = 14.dp, vertical = 11.dp),
                            ) { Text(s, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }
            items(messages) { Bubble(it) { onApply(it) } }
        }

        if (messages.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                suggestions.take(5).forEach { s -> Chip(s, selected = false) { if (!busy) onSend(s) } }
            }
        }

        InputBar(
            value = input,
            enabled = !busy,
            onChange = { input = it },
            onSend = {
                val q = input.trim()
                if (q.isNotEmpty() && !busy) { onSend(q); input = "" }
            },
            // Leave room for the floating tab bar unless the keyboard is covering it.
            modifier = Modifier.padding(bottom = if (keyboard) 8.dp else 96.dp).let { if (keyboard) it else it.navigationBarsPadding() },
        )
    }
}

@Composable
private fun ModeCard(modelInstalled: Boolean, useModel: Boolean, onToggle: () -> Unit, onSettings: () -> Unit) {
    GlassCard(padding = 14.dp, radius = 22.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (modelInstalled && useModel) "🧠" else "⚡", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (modelInstalled && useModel) "Gemma on-device" else "Instant answers",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    when {
                        modelInstalled && useModel -> "Written by a model on your phone, figures checked by Tally. Offline."
                        modelInstalled -> "Exact figures straight from your data. Gemma is off."
                        else -> "Exact figures straight from your data. Add Gemma in Settings for free-form chat."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Tally.Muted,
                )
            }
            if (modelInstalled) {
                Chip(if (useModel) "On" else "Off", selected = useModel, accent = Tally.Violet, onClick = onToggle)
            } else {
                Chip("Set up", selected = false, accent = Tally.Violet, onClick = onSettings)
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage, onApply: () -> Unit) {
    val shape = RoundedCornerShape(
        topStart = 20.dp, topEnd = 20.dp,
        bottomStart = if (m.fromUser) 20.dp else 6.dp,
        bottomEnd = if (m.fromUser) 6.dp else 20.dp,
    )
    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(shape)
                .then(
                    if (m.fromUser) Modifier.background(Tally.Brand)
                    else Modifier.background(Tally.Surface).border(1.dp, Tally.Stroke, shape),
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (m.thinking) {
                ThinkingDots()
            } else {
                Text(m.text, style = MaterialTheme.typography.bodyLarge, color = if (m.fromUser) Color.White else Tally.Text)
                m.figures?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("Figures: $it", style = MaterialTheme.typography.labelSmall, color = Tally.Muted)
                }
                m.change?.let { c ->
                    Spacer(Modifier.height(10.dp))
                    Chip(
                        if (m.applied) "✓ Added to ${c.planName}" else "Apply to ${c.planName} plan",
                        selected = m.applied,
                        accent = Tally.Mint,
                    ) { if (!m.applied) onApply() }
                }
                if (m.source.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(m.source, style = MaterialTheme.typography.labelSmall, color = Tally.Violet)
                }
            }
        }
    }
}

@Composable
private fun ThinkingDots() {
    val t = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(
                initialValue = 0.25f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(500, delayMillis = i * 150), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(Modifier.size(8.dp).alpha(a).clip(CircleShape).background(Tally.Text))
        }
    }
}

@Composable
private fun InputBar(value: String, enabled: Boolean, onChange: (String) -> Unit, onSend: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(26.dp)
    Row(
        modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Tally.SurfaceHi)
            .border(1.dp, Tally.Stroke, shape)
            .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Tally.Text),
            cursorBrush = SolidColor(Tally.Pink),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text("Ask anything about your spending…", style = MaterialTheme.typography.bodyLarge, color = Tally.Faint)
                    inner()
                }
            },
        )
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (enabled && value.isNotBlank()) Tally.Brand else SolidColor(Tally.Surface))
                .pressable(onSend),
            contentAlignment = Alignment.Center,
        ) { Text("↑", style = MaterialTheme.typography.titleLarge, color = Color.White) }
    }
}
