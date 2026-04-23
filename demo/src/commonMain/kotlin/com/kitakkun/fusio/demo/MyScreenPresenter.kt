package com.kitakkun.fusio.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kitakkun.fusio.Fusio
import com.kitakkun.fusio.PresenterScope
import com.kitakkun.fusio.buildPresenter
import com.kitakkun.fusio.mappedScope
import com.kitakkun.fusio.on
import kotlinx.coroutines.flow.Flow

/**
 * Root presenter for the demo screen. The state of Favorite (and its nested
 * Counter) and Wallet is assembled from two sibling `mappedScope { ... }`
 * calls — no parent-side glue beyond the annotations on each MyScreenEvent /
 * MyScreenEffect subtype. Remove the `mappedScope` lines and the parent
 * would have to re-implement both feature rulesets inline.
 */
@Composable
fun myScreenPresenter(
    eventFlow: Flow<MyScreenEvent>,
): Fusio<MyScreenUiState, MyScreenEffect> = buildPresenter(eventFlow) {
    var searchQuery by remember { mutableStateOf("") }

    on<MyScreenEvent.Search> { event ->
        searchQuery = event.query
    }

    val favoriteState = mappedScope { favorite() }
    val walletState = mappedScope { wallet() }

    MyScreenUiState(
        searchQuery = searchQuery,
        favoriteState = favoriteState,
        walletState = walletState,
    )
}
