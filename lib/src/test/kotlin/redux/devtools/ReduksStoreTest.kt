package redux.devtools

import redux.api.Reducer
import redux.api.Store
import redux.api.adapters.ReduksStoreAdapter


class ReduksStoreTest : org.jetbrains.spek.api.Spek(makeTests(storeCreator()))

private fun storeCreator() = { reducer: Reducer<Int>, initialState: Int, enhancer: Store.Enhancer? ->
    ReduksStoreAdapter.create(reducer, initialState, enhancer)
}
