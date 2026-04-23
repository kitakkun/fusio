package com.kitakkun.fusio.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kitakkun.fusio.PresenterScope
import com.kitakkun.fusio.on

@Composable
fun PresenterScope<WalletEvent, WalletEffect>.wallet(): WalletState {
    var balance by remember { mutableStateOf(0) }
    var lastAction by remember { mutableStateOf<String?>(null) }

    on<WalletEvent.Deposit> { event ->
        balance += event.amount
        lastAction = "+${event.amount} → $balance"
        emitEffect(WalletEffect.BalanceUpdated(balance))
    }

    on<WalletEvent.Withdraw> { event ->
        if (event.amount > balance) {
            emitEffect(
                WalletEffect.InsufficientFunds(
                    requested = event.amount,
                    available = balance,
                ),
            )
        } else {
            balance -= event.amount
            lastAction = "-${event.amount} → $balance"
            emitEffect(WalletEffect.BalanceUpdated(balance))
        }
    }

    return WalletState(balance = balance, lastAction = lastAction)
}
