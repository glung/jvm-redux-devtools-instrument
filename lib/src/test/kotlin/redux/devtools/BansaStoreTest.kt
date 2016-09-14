package redux.devtools

import redux.api.Reducer
import redux.api.Store
import redux.api.adapters.BansaStoreAdapter


class BansaStoreTest : org.jetbrains.spek.api.Spek(makeTests(storeCreator()))

private fun storeCreator() = { reducer: Reducer<Int>, initialState: Int, enhancer: Store.Enhancer? ->
    BansaStoreAdapter.create(reducer, initialState, enhancer)
}
