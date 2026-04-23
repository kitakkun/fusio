package com.kitakkun.fusio.test.internal

import androidx.compose.runtime.AbstractApplier

/**
 * A Compose [androidx.compose.runtime.Applier] with no UI tree — Fusio
 * presenters don't emit UI nodes, so every applier method is a no-op.
 *
 * The `Unit` root is a stand-in: Compose needs *some* root object to hand
 * the applier, but none of the tree operations actually run against it.
 */
internal class HeadlessApplier : AbstractApplier<Unit>(Unit) {
    override fun onClear() = Unit
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun remove(index: Int, count: Int) = Unit
}
