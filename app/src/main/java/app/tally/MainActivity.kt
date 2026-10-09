package app.tally

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tally.data.Expense
import app.tally.data.ExpenseStore
import app.tally.data.StatementReader
import app.tally.logic.StatementParser
import app.tally.service.WalletListenerService
import app.tally.ui.HomeScreen
import app.tally.ui.ImportRow
import app.tally.ui.ImportScreen
import app.tally.ui.InsightsScreen
import app.tally.ui.PlansScreen
import app.tally.ui.SettingsScreen
import app.tally.ui.Sheet
import app.tally.ui.SheetActions
import app.tally.ui.TallySheet
import app.tally.ui.pressable
import app.tally.ui.theme.Tally
import app.tally.ui.theme.TallyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        WalletListenerService.requestRebind(this)
        setContent { TallyTheme { TallyRoot() } }
    }
}

private enum class Screen(val label: String, val tab: Boolean) {
    Activity("Activity", true),
    Insights("Insights", true),
    Plans("Plans", true),
    Settings("Settings", false),
    Import("Import", false),
}

@Composable
private fun TallyRoot() {
    val context = LocalContext.current
    val state by ExpenseStore.state.collectAsStateWithLifecycle()
    var listenerEnabled by remember { mutableStateOf(WalletListenerService.isEnabled(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        listenerEnabled = WalletListenerService.isEnabled(context)
        // Catch up on salary / debits that fell due while the app was closed.
        ExpenseStore.postDueRecurring()
    }

    var screen by rememberSaveable { mutableStateOf(Screen.Activity) }
    var lastTab by rememberSaveable { mutableStateOf(Screen.Activity) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var importRows by remember { mutableStateOf<List<ImportRow>>(emptyList()) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun go(to: Screen) {
        if (to.tab) lastTab = to
        screen = to
    }
    BackHandler(enabled = screen != Screen.Activity) {
        go(if (screen.tab) Screen.Activity else lastTab)
    }

    fun toast(message: String) = scope.launch {
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(message)
    }

    fun deleteWithUndo(e: Expense) {
        ExpenseStore.delete(e.id)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar("Deleted ${e.merchant}", actionLabel = "Undo", withDismissAction = false)
            if (result == SnackbarResult.ActionPerformed) ExpenseStore.upsert(e)
        }
    }

    val pickStatement = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val lines = runCatching {
                withContext(Dispatchers.IO) { StatementParser.parse(StatementReader.readText(context, uri)) }
            }.getOrElse {
                toast("Couldn't read that file (${it.javaClass.simpleName})")
                return@launch
            }
            val dupes = ExpenseStore.findDuplicates(lines)
            importRows = lines.mapIndexed { i, l -> ImportRow(l, dupes[i], selected = dupes[i] == null) }
            go(Screen.Import)
        }
    }

    Box(Modifier.fillMaxSize().background(Tally.Bg)) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                (fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 24 })
                    .togetherWith(fadeOut(tween(160)))
            },
            label = "screen",
        ) { s ->
            when (s) {
                Screen.Activity -> HomeScreen(
                    state = state,
                    listenerEnabled = listenerEnabled,
                    onConnect = { WalletListenerService.openSettings(context) },
                    onSettings = { go(Screen.Settings) },
                    onSetBalance = { sheet = Sheet.Balance },
                    onBudget = { go(Screen.Insights) },
                    onEdit = { sheet = Sheet.EditExpense(it) },
                    onDelete = { deleteWithUndo(it) },
                )
                Screen.Insights -> InsightsScreen(
                    state = state,
                    onSettings = { go(Screen.Settings) },
                    onSetBudget = { sheet = Sheet.Budget },
                    onSetBalance = { sheet = Sheet.Balance },
                )
                Screen.Plans -> PlansScreen(
                    state = state,
                    onSettings = { go(Screen.Settings) },
                    onEdit = { sheet = Sheet.EditRecurring(it) },
                )
                Screen.Settings -> SettingsScreen(
                    state = state,
                    listenerEnabled = listenerEnabled,
                    onBack = { go(lastTab) },
                    onOpenAccess = { WalletListenerService.openSettings(context) },
                    onOpenAppInfo = { WalletListenerService.openAppInfo(context) },
                    onSetBalance = { sheet = Sheet.Balance },
                    onSetBudget = { sheet = Sheet.Budget },
                    onImport = { pickStatement.launch(arrayOf("application/pdf", "text/*", "application/vnd.ms-excel", "application/octet-stream")) },
                    onCurrency = { ExpenseStore.setCurrency(it) },
                    onForget = { ExpenseStore.forget(it) },
                    onErase = { ExpenseStore.eraseAll() },
                )
                Screen.Import -> ImportScreen(
                    rows = importRows,
                    currency = state.currency,
                    onBack = { go(Screen.Settings) },
                    onChange = { i, row -> importRows = importRows.toMutableList().also { it[i] = row } },
                    onImport = {
                        val chosen = importRows.filter { it.selected }.map { it.line }
                        ExpenseStore.import(chosen)
                        importRows = emptyList()
                        go(Screen.Activity)
                        toast("Imported ${chosen.size} transactions")
                    },
                )
            }
        }

        AnimatedVisibility(
            visible = screen.tab,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            BottomBar(
                current = screen,
                onSelect = { go(it) },
                onAdd = if (screen == Screen.Insights) null else ({
                    sheet = if (screen == Screen.Plans) Sheet.NewRecurring else Sheet.NewExpense
                }),
            )
        }

        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 104.dp, start = 16.dp, end = 16.dp),
        ) { data ->
            Snackbar(
                data,
                containerColor = Tally.SurfaceHi,
                contentColor = Tally.Text,
                actionColor = Tally.Pink,
                shape = RoundedCornerShape(18.dp),
            )
        }
    }

    sheet?.let { current ->
        TallySheet(
            sheet = current,
            state = state,
            onDismiss = { sheet = null },
            actions = SheetActions(
                onSave = { e, taught -> ExpenseStore.save(e, taught); sheet = null },
                onDelete = { sheet = null; deleteWithUndo(it) },
                onSetBalance = { ExpenseStore.setBalance(it); sheet = null },
                onSetBudget = { ExpenseStore.setBudget(it); sheet = null },
                onSaveRecurring = { ExpenseStore.saveRecurring(it); sheet = null },
                onDeleteRecurring = {
                    ExpenseStore.deleteRecurring(it.id)
                    sheet = null
                    toast("Removed ${it.name}")
                },
            ),
        )
    }
}

/** Floating glass pill with the three tabs, plus the gradient + button when it applies. */
@Composable
private fun BottomBar(current: Screen, onSelect: (Screen) -> Unit, onAdd: (() -> Unit)?) {
    Row(
        Modifier
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier
                .shadow(20.dp, RoundedCornerShape(50), ambientColor = Color.Black, spotColor = Color.Black)
                .clip(RoundedCornerShape(50))
                .background(Tally.SurfaceHi.copy(alpha = 0.96f))
                .border(1.dp, Tally.Stroke, RoundedCornerShape(50))
                .padding(6.dp),
        ) {
            Screen.entries.filter { it.tab }.forEach { s ->
                val on = s == current
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (on) Tally.Brand else androidx.compose.ui.graphics.SolidColor(Color.Transparent))
                        .pressable { onSelect(s) }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    Text(s.label, style = MaterialTheme.typography.labelLarge, color = if (on) Color.White else Tally.Muted)
                }
            }
        }
        if (onAdd != null) {
            Box(
                Modifier
                    .shadow(24.dp, CircleShape, ambientColor = Tally.Pink, spotColor = Tally.Pink)
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(Tally.Brand)
                    .pressable(onAdd),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add", tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }
    }
}
