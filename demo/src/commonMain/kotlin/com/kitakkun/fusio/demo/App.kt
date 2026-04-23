package com.kitakkun.fusio.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Top-level Compose UI for the demo. Drives `myScreenPresenter` from a
 * MutableSharedFlow and renders the resulting UiState + collected effects.
 *
 * Every button click just pushes an event — the presenter owns the state
 * transitions. This is the exact shape a real Compose-Presenter app takes,
 * minus a navigation stack.
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
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Fusio demo", style = MaterialTheme.typography.headlineMedium)

                SearchField(
                    query = state.searchQuery,
                    onQueryChange = { eventFlow.tryEmit(MyScreenEvent.Search(it)) },
                )

                FavoriteRow(
                    favoriteState = state.favoriteState,
                    onToggle = { eventFlow.tryEmit(MyScreenEvent.ToggleFavorite("item-1")) },
                    onIncrement = { eventFlow.tryEmit(MyScreenEvent.IncrementCounter) },
                )

                EffectsLog(effects)
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text("Search query") },
    )
}

@Composable
private fun FavoriteRow(
    favoriteState: FavoriteState,
    onToggle: () -> Unit,
    onIncrement: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Favorited: ${if (favoriteState.isFavorited) "yes" else "no"}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Counter: ${favoriteState.counter.value}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onToggle) { Text("Toggle favorite") }
                Button(onClick = onIncrement) { Text("+1 counter") }
            }
        }
    }
}

@Composable
private fun EffectsLog(effects: List<MyScreenEffect>) {
    Card {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Effects log (${effects.size})", style = MaterialTheme.typography.titleSmall)
            if (effects.isEmpty()) {
                Text("— (interact with the buttons above)")
            } else {
                effects.forEach { Text(it.toString()) }
            }
        }
    }
}
