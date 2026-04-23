package com.kitakkun.fusio.demo

import com.kitakkun.fusio.MapFrom
import com.kitakkun.fusio.MapTo

// ---------- Counter (leaf) ----------
//
// Bounded counter 0..MAX. `Reset` returns to 0. When `Increment` would cross
// MAX, the counter stays pinned and emits `MaxReached` so the parent can react
// without the leaf needing to know about any "screen-level" behaviour.

sealed interface CounterEvent {
    data object Increment : CounterEvent
    data object Reset : CounterEvent
}

sealed interface CounterEffect {
    data object MaxReached : CounterEffect
}

data class CounterState(val value: Int, val isAtMax: Boolean)

// ---------- Favorite (mid — hosts Counter) ----------
//
// Toggles favorite status and forwards its `ShowMessage` up. Also relays
// counter actions down via @MapTo and chains the counter's MaxReached up
// via @MapFrom.

sealed interface FavoriteEvent {
    data class Toggle(val id: String) : FavoriteEvent

    @MapTo(CounterEvent.Increment::class)
    data object IncrementCounter : FavoriteEvent

    @MapTo(CounterEvent.Reset::class)
    data object ResetCounter : FavoriteEvent
}

sealed interface FavoriteEffect {
    data class ShowMessage(val message: String) : FavoriteEffect

    @MapFrom(CounterEffect.MaxReached::class)
    data object CounterMaxReached : FavoriteEffect
}

data class FavoriteState(
    val isFavorited: Boolean,
    val counter: CounterState,
)

// ---------- Wallet (leaf, sibling of Favorite) ----------
//
// Balance with deposit / withdraw. A withdrawal that exceeds the balance
// is rejected inside the presenter; the UI learns via the InsufficientFunds
// effect instead of duplicating the rule. BalanceUpdated is fired on every
// successful change so the parent can log / snack it.

sealed interface WalletEvent {
    data class Deposit(val amount: Int) : WalletEvent
    data class Withdraw(val amount: Int) : WalletEvent
}

sealed interface WalletEffect {
    data class InsufficientFunds(val requested: Int, val available: Int) : WalletEffect
    data class BalanceUpdated(val newBalance: Int) : WalletEffect
}

data class WalletState(
    val balance: Int,
    val lastAction: String?,
)

// ---------- MyScreen (root) ----------
//
// Binds Favorite + Wallet as siblings via two sibling `mappedScope { ... }`
// calls. `Search` is a parent-only concern (no child maps it), everything
// else is routed down to the child that owns the rule.

sealed interface MyScreenEvent {
    data class Search(val query: String) : MyScreenEvent

    @MapTo(FavoriteEvent.Toggle::class)
    data class ToggleFavorite(val id: String) : MyScreenEvent

    @MapTo(FavoriteEvent.IncrementCounter::class)
    data object IncrementCounter : MyScreenEvent

    @MapTo(FavoriteEvent.ResetCounter::class)
    data object ResetCounter : MyScreenEvent

    @MapTo(WalletEvent.Deposit::class)
    data class Deposit(val amount: Int) : MyScreenEvent

    @MapTo(WalletEvent.Withdraw::class)
    data class Withdraw(val amount: Int) : MyScreenEvent
}

sealed interface MyScreenEffect {
    @MapFrom(FavoriteEffect.ShowMessage::class)
    data class ShowSnackbar(val message: String) : MyScreenEffect

    @MapFrom(FavoriteEffect.CounterMaxReached::class)
    data object CounterHitMax : MyScreenEffect

    @MapFrom(WalletEffect.InsufficientFunds::class)
    data class WalletWarning(val requested: Int, val available: Int) : MyScreenEffect

    @MapFrom(WalletEffect.BalanceUpdated::class)
    data class WalletBalanceChanged(val newBalance: Int) : MyScreenEffect
}

data class MyScreenUiState(
    val searchQuery: String,
    val favoriteState: FavoriteState,
    val walletState: WalletState,
)
