package redux.devtools

import redux.api.Reducer
import redux.api.Store


class ReduxJavaTest : org.jetbrains.spek.api.Spek(makeTests(storeCreator()))

private fun storeCreator() = { reducer: Reducer<Int>, initialState: Int, enhancer: Store.Enhancer? ->
    com.glung.redux.Store.createStore(reducer, initialState, enhancer)
}
