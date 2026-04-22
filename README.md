# aria

AriaはComposable関数ベースで構築するロジック実装をよりクリーンに実現することを目指したライブラリです。

Composable関数ベースのPresenterは、状態の再計算をスマートに解決することができますが、
画面の機能が増えれば増えるほど、Presenterの実装が肥大化していきます。

```kotlin
@Composable
fun myScreenPresenter(eventFlow: Flow<MyScreenEvent>): MyScreenEvent {
    var state1 by remember { mutableStateOf(...) }
    var state2 by remember { mutableStateOf(...) }
    var state3 by remember { mutableStateOf(...) }
    
    LaunchedEffect(Unit) {
        state1 = doSomething()
    }
    
    return MyUiState(
        
    )
}
```

Ariaは、Composable関数ベースのPresenterを複数のComposable関数に分割することで、ロジックの肥大化を防ぎます。
それと同時に、手動で実装すると発生してしまうボイラープレートコードをCompiler Pluginによって削減します。

```kotlin
@Composable
fun myScreenPresenter(eventFlow: Flow<MyScreenEvent>): MyScreenUiState {
    val state1 = aria { doSomething() }
    val state2 = aria { doSomethingElse() }
    val state3 = aria { doAnotherThing() }

    // stateを組み合わせて返すだけ
    return MyScreenUiState(...)
}
```

```kotlin
context(screenContext: MyScreenContext)
@Composable
fun myScreenPresenter(eventFlow: Flow<MyScreenEvent>): MyScreenUiState = buildPresenter(eventFlow) {
    val state1 = mappedScope<FavoriteEvent, FavoriteEffect> { favoriteArea() }

    // stateを組み合わせて返すだけ
    return MyScreenUiState(...)
}

context(ariaContext: FavoriteAreaContext)
@Composable
fun PresenterScope<FavoriteEvent, FavoriteEffect>.favoriteArea(eventFlow: Flow<FavoriteEvent>): Area<FavoriteState, FavoriteEffect> {
    return Area(state)
}

```