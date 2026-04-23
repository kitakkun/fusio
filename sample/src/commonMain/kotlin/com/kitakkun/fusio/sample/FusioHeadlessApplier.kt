package com.kitakkun.fusio.sample

import androidx.compose.runtime.AbstractApplier

// Applier for a headless composition — we don't produce any UI tree, we just want
// presenters to execute and return state. A single virtual root is sufficient.
class FusioHeadlessApplier : AbstractApplier<Unit>(Unit) {
    override fun onClear() = Unit
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun remove(index: Int, count: Int) = Unit
}
