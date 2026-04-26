# Step 13: fusio-runtime implementation notes

Maintainer-facing rationale for the small choices that won't make sense
from reading the public KDoc alone. KDoc is consumer-focused; this file
captures the *why* of the implementation.

## Why `Channel` (not `MutableSharedFlow`) backs effects and event errors

Both streams need two simultaneous properties that no `SharedFlow`
configuration delivers cleanly:

1. **Buffer until a collector arrives** — Compose's lifecycle has small
   windows where `emitEffect` can fire before the consuming
   `LaunchedEffect` (in `forwardEffects` or the user's UI) is active.
   `Channel` queues those values; they're delivered as soon as the
   collector starts. `MutableSharedFlow(replay = 0)` would *drop* values
   emitted with no current subscriber.
2. **No replay on re-subscribe** — recomposition can cancel and restart
   a `LaunchedEffect` collector. We never want a past navigation /
   toast effect to re-fire when a new collector subscribes.
   `MutableSharedFlow(replay = N)` would re-emit the last `N` items to
   each new subscriber.

`Channel` satisfies both: items live in a FIFO buffer until consumed,
and once consumed they're gone — a fresh collector starts from "now".
Single-consumer-only is a side effect of this choice, not the goal;
callers who genuinely need fan-out wrap the exposed `Flow` with
`shareIn(scope, SharingStarted.Eagerly)`.

Capacity is bounded (file-private `BUFFER_CAPACITY = 64`) so an absent
or slow collector can't grow memory unboundedly. With the default
`BufferOverflow.SUSPEND`, a runaway emitter or stalled consumer suspends
the producer — the resulting test timeout / log signal is exactly the
failure mode we want to surface, vs. a silent leak. Realistic UI cadence
(single-digit effects per second) sits well within this headroom.

## Why `Presentation` is hand-written, not a `data class`

`data class` auto-generates `copy` / `componentN`, which pin the public
ABI to the exact field list — adding, removing, or reordering a field
becomes a binary-incompatible change for downstream consumers. Callers
of `Presentation` read `.state` / `.effectFlow` / `.eventErrorFlow` /
`.send` directly and don't need destructuring or `copy()`. The explicit
`equals` / `hashCode` / `toString` overrides preserve structural
equality (useful in tests) and a readable `toString` without the ABI
lock-in.

## Why `Presentation` is `@Stable`

Compose would otherwise treat `Presentation` as unstable because one of
its generic parameters appears as a `Flow<Effect>` — and Flows carry no
structural-equality guarantee. At the wrapper level the class IS stable:
the properties are `val`s set once at construction, the instance never
mutates observably, and `equals` / `hashCode` are consistent. Marking it
`@Stable` lets composables that take a `Presentation` parameter skip
recomposition when the same instance is passed twice.

## Why `fuse` is `inline` and not `@Composable`

Two coupled reasons:

- **`inline`** so the call site's `@Composable` context governs the
  block lambda — the `fuse` function itself doesn't have to be marked
  `@Composable` for the lambda body to be a valid Composable scope.
- **No `@Composable`** because the function never actually runs in
  production: the IR transformer (registered via `IrGenerationExtension`,
  which fires *before* inline lowering) replaces every call site with
  the real plumbing. The unrewritten stub body just throws.

The `contract { callsInPlace(block, AT_MOST_ONCE) }` is the strongest
honest claim the stub can make: statically the stub throws before
calling the block, so `EXACTLY_ONCE` would be a lie about un-rewritten
code. The IR transformer's replacement does invoke the block exactly
once, so users get the right inference for real builds.

## Why `forwardEffects` and `forwardEventErrors` are `public` in `fusio-runtime`

They're not part of the user-facing API — application code never calls
them directly. They're `public` only because the IR transformer inserts
calls to them into compiled consumer modules, and a generated call site
in user code can't reach an `internal` symbol from another module.

`forwardEventErrors` doesn't need a `mapper` parameter the way
`forwardEffects` does because errors don't need type remapping — a
`Throwable` flows through unchanged. The structural similarity between
the two functions is intentional: keep them parallel so future
maintenance touches both consistently.

## Why each `LaunchedEffect` in the forwarders is keyed on both scopes

`LaunchedEffect(childScope, parentScope) { … }` rather than
`LaunchedEffect(Unit) { … }`. The IR transformer wraps the child scope
in `remember { … }` so identity is stable across recompositions in the
common case. The keyed form pays nothing extra at steady state but
correctly restarts the collector if the IR transformer ever emits a
reparented scope (rare, but possible).

## Why `mapEvents` is `@PublishedApi internal`

It's invoked from compiler-plugin-emitted code at every `fuse { … }`
rewrite, where the lambda body is generated from `@MapTo` annotations.
`@PublishedApi internal` lets the generated call site (in a consumer
module) reach the function while keeping it out of IDE completion for
ordinary users. There's no reason for application code to call
`mapEvents` directly.
