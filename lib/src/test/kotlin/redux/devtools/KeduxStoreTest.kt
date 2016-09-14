package redux.devtools

import redux.api.Reducer
import redux.api.Store
import redux.api.adapters.KeduxStoreAdapter

class KeduxStoreTest : org.jetbrains.spek.api.Spek(makeTests(storeCreator()))

private fun storeCreator() = { reducer: Reducer<Int>, initialState: Int, enhancer: Store.Enhancer? ->
    KeduxStoreAdapter.create(reducer, initialState, enhancer)
}
