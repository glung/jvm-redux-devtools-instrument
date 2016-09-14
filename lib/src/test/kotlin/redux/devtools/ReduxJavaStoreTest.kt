package redux.devtools

import com.redux.ReduxJavaStoreAdapter
import redux.api.Reducer
import redux.api.Store


class ReduxJavaStoreTest : org.jetbrains.spek.api.Spek(makeTests(storeCreator()))

private fun storeCreator() = { reducer: Reducer<Int>, initialState: Int, enhancer: Store.Enhancer? ->
    ReduxJavaStoreAdapter.create(reducer, initialState, enhancer)
}
