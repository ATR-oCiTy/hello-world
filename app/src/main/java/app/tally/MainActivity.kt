package app.tally

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import app.tally.data.ExpenseStore
import app.tally.service.WalletListenerService
import app.tally.ui.HomeScreen
import app.tally.ui.SettingsScreen
import app.tally.ui.Sheet
import app.tally.ui.TallySheet
import app.tally.ui.pressable
import app.tally.ui.theme.Tally
import app.tally.ui.theme.TallyTheme
import kotlinx.coroutines.launch

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

private enum class Screen { Home, Settings }

@Composable
private fun TallyRoot() {
    val context = LocalContext.current
    val state by ExpenseStore.state.collectAsStateWithLifecycle()
    var listenerEnabled by remember { mutableStateOf(WalletListenerService.isEnabled(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        listenerEnabled = WalletListenerService.isEnabled(context)
    }

    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    BackHandler(enabled = screen == Screen.Settings) { screen = Screen.Home }

    fun deleteWithUndo(e: app.tally.data.Expense) {
        ExpenseStore.delete(e.id)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar("Deleted ${e.merchant}", actionLabel = "Undo", withDismissAction = false)
            if (result == SnackbarResult.ActionPerformed) ExpenseStore.upsert(e)
        }
    }

    Box(Modifier.fillMaxSize().background(Tally.Bg)) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                val dir = if (targetState == Screen.Settings) 1 else -1
                (slideInHorizontally(tween(320)) { it / 4 * dir } + fadeIn(tween(320)))
                    .togetherWith(slideOutHorizontally(tween(320)) { -it / 4 * dir } + fadeOut(tween(200)))
            },
            label = "screen",
        ) { s ->
            when (s) {
                Screen.Home -> HomeScreen(
                    state = state,
                    listenerEnabled = listenerEnabled,
                    onConnect = { WalletListenerService.openSettings(context) },
                    onSettings = { screen = Screen.Settings },
                    onSetBalance = { sheet = Sheet.Balance },
                    onEdit = { sheet = Sheet.EditExpense(it) },
                    onDelete = { deleteWithUndo(it) },
                )
                Screen.Settings -> SettingsScreen(
                    state = state,
                    listenerEnabled = listenerEnabled,
                    onBack = { screen = Screen.Home },
                    onOpenAccess = { WalletListenerService.openSettings(context) },
                    onOpenAppInfo = { WalletListenerService.openAppInfo(context) },
                    onSetBalance = { sheet = Sheet.Balance },
                    onCurrency = { ExpenseStore.setCurrency(it) },
                    onForget = { ExpenseStore.forget(it) },
                    onErase = { ExpenseStore.eraseAll() },
                )
            }
        }

        if (screen == Screen.Home) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(24.dp)
                    .shadow(24.dp, CircleShape, ambientColor = Tally.Pink, spotColor = Tally.Pink)
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(Tally.Brand)
                    .pressable { sheet = Sheet.NewExpense },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add expense", tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }

        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 100.dp, start = 16.dp, end = 16.dp),
        ) { data ->
            Snackbar(
                data,
                containerColor = Tally.SurfaceHi,
                contentColor = Tally.Text,
                actionColor = Tally.Pink,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            )
        }
    }

    sheet?.let { current ->
        TallySheet(
            sheet = current,
            currency = state.currency,
            currentBalance = state.currentBalance,
            learned = state.learned,
            onDismiss = { sheet = null },
            onSave = { e, taught -> ExpenseStore.save(e, taught); sheet = null },
            onDelete = { sheet = null; deleteWithUndo(it) },
            onSetBalance = { ExpenseStore.setBalance(it); sheet = null },
        )
    }
}
