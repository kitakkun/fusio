package com.kitakkun.fusio.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Top-level Compose UI for the demo.
 *
 * The Compose tree only ever pushes events onto `eventFlow` and reads
 * `fusio.state` — it doesn't know which sub-presenter handles what. All the
 * bookkeeping (counter clamp, insufficient-funds check, favorite toggle,
 * their effect emissions) is inside the respective sub-presenters; the
 * parent composes them with two sibling `mappedScope { ... }` calls.
 */
@Composable
fun App() {
    val eventFlow = remember { MutableSharedFlow<MyScreenEvent>(extraBufferCapacity = 64) }
    val effects = remember { mutableStateListOf<MyScreenEffect>() }

    val fusio = myScreenPresenter(eventFlow)
    val state = fusio.state

    LaunchedEffect(fusio.effectFlow) {
        fusio.effectFlow.collect { effects += it }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Fusio demo", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Two sibling sub-presenters (Favorite + Wallet). Each owns its own " +
                        "state and constraints; the parent only wires events and effects " +
                        "through @MapTo / @MapFrom.",
                    style = MaterialTheme.typography.bodySmall,
                )

                SearchField(
                    query = state.searchQuery,
                    onQueryChange = { eventFlow.tryEmit(MyScreenEvent.Search(it)) },
                )

                FavoriteCard(
                    favoriteState = state.favoriteState,
                    onToggle = { eventFlow.tryEmit(MyScreenEvent.ToggleFavorite("item-1")) },
                    onIncrement = { eventFlow.tryEmit(MyScreenEvent.IncrementCounter) },
                    onReset = { eventFlow.tryEmit(MyScreenEvent.ResetCounter) },
                )

                WalletCard(
                    walletState = state.walletState,
                    onDeposit = { eventFlow.tryEmit(MyScreenEvent.Deposit(amount = 10)) },
                    onWithdraw = { eventFlow.tryEmit(MyScreenEvent.Withdraw(amount = 5)) },
                    onWithdrawLarge = { eventFlow.tryEmit(MyScreenEvent.Withdraw(amount = 50)) },
                )

                EffectsLog(
                    effects = effects,
                    onClear = { effects.clear() },
                )
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text("Search query (parent-only, not sent to any sub-presenter)") },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun FavoriteCard(
    favoriteState: FavoriteState,
    onToggle: () -> Unit,
    onIncrement: () -> Unit,
    onReset: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Favorite sub-presenter", style = MaterialTheme.typography.titleMedium)
            Text(
                "Favorited: ${if (favoriteState.isFavorited) "yes" else "no"}  " +
                    "• Counter: ${favoriteState.counter.value}" +
                    if (favoriteState.counter.isAtMax) "  (MAX)" else "",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onToggle) { Text("Toggle favorite") }
                Button(onClick = onIncrement) { Text("+1") }
                OutlinedButton(onClick = onReset) { Text("Reset") }
            }
        }
    }
}

@Composable
private fun WalletCard(
    walletState: WalletState,
    onDeposit: () -> Unit,
    onWithdraw: () -> Unit,
    onWithdrawLarge: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Wallet sub-presenter", style = MaterialTheme.typography.titleMedium)
            Text(
                "Balance: ${walletState.balance}" +
                    (walletState.lastAction?.let { "  (last: $it)" }.orEmpty()),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDeposit) { Text("Deposit 10") }
                OutlinedButton(onClick = onWithdraw) { Text("Withdraw 5") }
                OutlinedButton(onClick = onWithdrawLarge) { Text("Withdraw 50") }
            }
            Text(
                "Try 'Withdraw 50' with a low balance to see the InsufficientFunds " +
                    "effect bubble up to the parent.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun EffectsLog(effects: List<MyScreenEffect>, onClear: () -> Unit) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Effects log (${effects.size})",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (effects.isNotEmpty()) {
                    OutlinedButton(onClick = onClear) { Text("Clear") }
                }
            }
            if (effects.isEmpty()) {
                Text(
                    "— (click buttons above to see effects bubble up through @MapFrom)",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                effects.forEach { Text(it.toString(), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
