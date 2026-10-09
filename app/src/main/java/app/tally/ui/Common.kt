package app.tally.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import app.tally.data.Category
import app.tally.ui.theme.Tally
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

fun money(amount: Double, symbol: String, decimals: Boolean = true): String {
    val nf = NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = if (decimals) 2 else 0
        maximumFractionDigits = if (decimals) 2 else 0
    }
    return if (amount < 0) "−$symbol${nf.format(-amount)}" else "$symbol${nf.format(amount)}"
}

fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

val Category.tint: Color get() = Color(color)

/** Rounded card on the dark surface with a hairline border. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radius: Dp = 28.dp,
    padding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Tally.Surface)
            .border(1.dp, Tally.Stroke, shape)
            .padding(padding),
        content = content,
    )
}

/** Squishes slightly when pressed. */
@Composable
fun Modifier.pressable(onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(stiffness = 600f), label = "press")
    return this
        .scale(scale)
        .clickable(interactionSource = source, indication = null, onClick = onClick)
}

@Composable
fun GradientButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(shape)
            .background(if (enabled) Tally.Brand else SolidColor(Tally.SurfaceHi))
            .then(if (enabled) Modifier.pressable(onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) Color.White else Tally.Faint,
        )
    }
}

@Composable
fun Chip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    accent: Color = Tally.Pink,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.18f) else Tally.SurfaceHi)
            .border(1.dp, if (selected) accent.copy(alpha = 0.7f) else Tally.Stroke, shape)
            .pressable(onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) Color.White else Tally.Muted)
    }
}

@Composable
fun EmojiBadge(category: Category, size: Dp = 46.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.34f))
            .background(category.tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(category.emoji, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = Tally.Muted,
        modifier = modifier,
    )
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(50)).background(color))
}

@Composable
fun ScreenHeader(subtitle: String, title: String, onSettings: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Tally.Muted)
            Text(title, style = MaterialTheme.typography.headlineMedium)
        }
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Tally.Surface)
                .border(1.dp, Tally.Stroke, RoundedCornerShape(16.dp))
                .pressable(onSettings),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Tally.Text)
        }
    }
}

/** Two-option pill switch, e.g. Expense / Income. */
@Composable
fun SegmentedToggle(
    options: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    accents: List<Color> = options.map { Tally.Pink },
    onSelect: (Int) -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    androidx.compose.foundation.layout.Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Tally.SurfaceHi)
            .border(1.dp, Tally.Stroke, shape)
            .padding(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (on) accents[i].copy(alpha = 0.22f) else Color.Transparent)
                    .pressable { onSelect(i) }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) Color.White else Tally.Muted)
            }
        }
    }
}
