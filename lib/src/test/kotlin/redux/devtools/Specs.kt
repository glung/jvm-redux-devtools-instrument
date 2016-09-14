package redux.devtools

import org.jetbrains.spek.api.DescribeBody
import redux.api.Reducer
import redux.api.Store
import kotlin.test.assertTrue
import kotlin.test.expect

fun makeTests(createStore: (reducer: Reducer<Int>, initialState: Int, enhancer: Store.Enhancer?) -> Store<Int>): DescribeBody.() -> Unit {

    return {

        val counter = Reducer<Int> { state, action ->
            when (action) {
                "INCREMENT" -> state + 1
                "DECREMENT" -> state - 1
                else -> state
            }
        }

        val doubleCounter = Reducer<Int> { state, action ->
            when (action) {
                "INCREMENT" -> state + 2
                "DECREMENT" -> state - 2
                else -> state
            }
        }

        describe("instrument") {
            var devTools = DevTools<Int>()
            var store = createStore(counter, 0, devTools.instrument())
            var liftedStore = devTools.devStore

            beforeEach {
                devTools = DevTools()
                store = createStore(counter, 0, devTools.instrument())
                liftedStore = devTools.devStore
            }

            it("should perform actions") {
                expect(0) { store.getState() }

                store.dispatch("INCREMENT")
                expect(1) { store.getState() }

                store.dispatch("INCREMENT")
                expect(2) { store.getState() }
            }

            it("should rollback state to the last committed state") {
                store.dispatch("INCREMENT")
                store.dispatch("INCREMENT")
                expect(2) { store.getState() }

                liftedStore.dispatch(DevToolsAction.Commit())
                expect(2) { store.getState() }

                store.dispatch("INCREMENT")
                store.dispatch("INCREMENT")
                expect(4) { store.getState() }

                liftedStore.dispatch(DevToolsAction.Rollback())
                expect(2) { store.getState() }

                store.dispatch("DECREMENT")
                expect(1) { store.getState() }

                liftedStore.dispatch(DevToolsAction.Rollback())
                expect(2) { store.getState() }
            }

            it("should reset to initial state") {
                store.dispatch("INCREMENT")
                expect(1) { store.getState() }

                liftedStore.dispatch(DevToolsAction.Commit())
                expect(1) { store.getState() }

                store.dispatch("INCREMENT")
                expect(2) { store.getState() }

                liftedStore.dispatch(DevToolsAction.Rollback())
                expect(1) { store.getState() }

                store.dispatch("INCREMENT")
                expect(2) { store.getState() }

                liftedStore.dispatch(DevToolsAction.Reset())
                expect(0) { store.getState() }
            }

            it("should toggle an action") {
                // actionId 0 = @@INIT
                store.dispatch("INCREMENT")
                store.dispatch("DECREMENT")
                store.dispatch("INCREMENT")
                expect(1) { store.getState() }

                liftedStore.dispatch(DevToolsAction.ToggleAction(2))
                expect(2) { store.getState() }

                liftedStore.dispatch(DevToolsAction.ToggleAction(2))
                expect(1) { store.getState() }
            }

            it("should set multiple action skip") {
                // actionId 0 = @@INIT
                store.dispatch("INCREMENT")
                store.dispatch("INCREMENT")
                store.dispatch("INCREMENT")
                expect(3) { store.getState() }

                liftedStore.dispatch(DevToolsAction.SetActionsActive(1, 3, false))
                expect(1) { store.getState() }

                liftedStore.dispatch(DevToolsAction.SetActionsActive(0, 2, true))
                expect(2) { store.getState() }

                liftedStore.dispatch(DevToolsAction.SetActionsActive(0, 1, true))
                expect(2) { store.getState() }
            }

            it("should sweep disabled actions") {
                // actionId 0 = @@INIT
                store.dispatch("INCREMENT")
                store.dispatch("DECREMENT")
                store.dispatch("INCREMENT")
                store.dispatch("INCREMENT")

                expect(store.getState()) { 2 }
                expect(listOf(0, 1, 2, 3, 4)) { liftedStore.getState().stagedActionIds }
                expect(emptyList()) { liftedStore.getState().skippedActionIds }

                liftedStore.dispatch(DevToolsAction.ToggleAction(2))
                expect(store.getState()) { 3 }
                expect(listOf(0, 1, 2, 3, 4)) { liftedStore.getState().stagedActionIds }
                expect(listOf(2)) { liftedStore.getState().skippedActionIds }

                liftedStore.dispatch(DevToolsAction.Sweep())
                expect(store.getState()) { 3 }
                expect(listOf(0, 1, 3, 4)) { liftedStore.getState().stagedActionIds }
                expect(emptyList()) { liftedStore.getState().skippedActionIds }
            }

            it("should jump to state") {
                store.dispatch("INCREMENT")
                store.dispatch("DECREMENT")
                store.dispatch("INCREMENT")
                expect(1) { store.getState() }

                liftedStore.dispatch(DevToolsAction.JumpToState(0))
                expect(0) { store.getState() }

                liftedStore.dispatch(DevToolsAction.JumpToState(1))
                expect(1) { store.getState() }

                liftedStore.dispatch(DevToolsAction.JumpToState(2))
                expect(0) { store.getState() }

                store.dispatch("INCREMENT")
                expect(0) { store.getState() }

                liftedStore.dispatch(DevToolsAction.JumpToState(4))
                expect(2) { store.getState() }
            }

            it("should replace the reducer") {
                store.dispatch("INCREMENT")
                store.dispatch("DECREMENT")
                store.dispatch("INCREMENT")
                expect(1) { store.getState() }

                store.replaceReducer(doubleCounter)
                expect(2) { store.getState() }
            }

            it("should not recompute states on every action") {
                var reducerCalls = 0
                val monitor = Reducer<Int> { state, action ->
                    reducerCalls++
                    state
                }
                val monitoredStore = createStore(monitor, 0, devTools.instrument())
                expect(reducerCalls) { 1 }
                monitoredStore.dispatch("INCREMENT")
                monitoredStore.dispatch("INCREMENT")
                monitoredStore.dispatch("INCREMENT")
                expect(reducerCalls) { 4 }
            }

            it("should not recompute old states when toggling an action") {
                var reducerCalls = 0
                val monitor = Reducer<Int> { state, action ->
                    reducerCalls++
                    state
                }

                devTools = DevTools()
                val monitoredStore = createStore(monitor, 0, devTools.instrument())
                val monitoredLiftedStore = devTools.devStore

                expect(1) { reducerCalls }
                // actionId 0 = @@INIT
                monitoredStore.dispatch("INCREMENT")
                monitoredStore.dispatch("INCREMENT")
                monitoredStore.dispatch("INCREMENT")
                expect(4) { reducerCalls }

                monitoredLiftedStore.dispatch(DevToolsAction.ToggleAction(3))
                expect(4) { reducerCalls }

                monitoredLiftedStore.dispatch(DevToolsAction.ToggleAction(3))
                expect(5) { reducerCalls }

                monitoredLiftedStore.dispatch(DevToolsAction.ToggleAction(2))
                expect(6) { reducerCalls }

                monitoredLiftedStore.dispatch(DevToolsAction.ToggleAction(2))
                expect(8) { reducerCalls }

                monitoredLiftedStore.dispatch(DevToolsAction.ToggleAction(1))
                expect(10) { reducerCalls }

                monitoredLiftedStore.dispatch(DevToolsAction.ToggleAction(2))
                expect(11) { reducerCalls }

                monitoredLiftedStore.dispatch(DevToolsAction.ToggleAction(3))
                expect(11) { reducerCalls }

                monitoredLiftedStore.dispatch(DevToolsAction.ToggleAction(1))
                expect(12) { reducerCalls }

                monitoredLiftedStore.dispatch(DevToolsAction.ToggleAction(3))
                expect(13) { reducerCalls }

                monitoredLiftedStore.dispatch(DevToolsAction.ToggleAction(2))
                expect(15) { reducerCalls }
            }

            it("should not recompute states when jumping to state") {
                var reducerCalls = 0
                val monitor = Reducer<Int> { state, action ->
                    reducerCalls++
                    state
                }

                devTools = DevTools()
                val monitoredStore = createStore(monitor, 0, devTools.instrument())
                val monitoredLiftedStore = devTools.devStore

                expect(1) { reducerCalls }
                monitoredStore.dispatch("INCREMENT")
                monitoredStore.dispatch("INCREMENT")
                monitoredStore.dispatch("INCREMENT")
                expect(4) { reducerCalls }

                val savedComputedStates = monitoredLiftedStore.getState().computedStates

                monitoredLiftedStore.dispatch(DevToolsAction.JumpToState(0))
                expect(4) { reducerCalls }

                monitoredLiftedStore.dispatch(DevToolsAction.JumpToState(1))
                expect(4) { reducerCalls }

                monitoredLiftedStore.dispatch(DevToolsAction.JumpToState(3))
                expect(4) { reducerCalls }

                expect(savedComputedStates) { monitoredLiftedStore.getState().computedStates }
            }

            it("should not recompute states on monitor actions") {
                var reducerCalls = 0
                val monitor = Reducer<Int> { state, action ->
                    reducerCalls++
                    state
                }

                devTools = DevTools()
                val monitoredStore = createStore(monitor, 0, devTools.instrument())
                val monitoredLiftedStore = devTools.devStore

                expect(1) { reducerCalls }
                monitoredStore.dispatch("INCREMENT")
                monitoredStore.dispatch("INCREMENT")
                monitoredStore.dispatch("INCREMENT")
                expect(4) { reducerCalls }

                val savedComputedStates = monitoredLiftedStore.getState().computedStates

                monitoredLiftedStore.dispatch("lol")
                expect(4) { reducerCalls }

                monitoredLiftedStore.dispatch("wat")
                expect(4) { reducerCalls }

                expect(savedComputedStates) { monitoredLiftedStore.getState().computedStates }
            }
        }

        describe("maxAge option") {
            var devTools = DevTools<Int>()
            var configuredStore = createStore(counter, 0, devTools.instrument(maxAge = 3))
            var configuredLiftedStore = devTools.devStore

            beforeEach {
                devTools = DevTools()
                configuredStore = createStore(counter, 0, devTools.instrument(maxAge = 3))
                configuredLiftedStore = devTools.devStore
            }

            it("should auto-commit earliest non-@@INIT action when maxAge is reached") {
                configuredStore.dispatch("INCREMENT")
                configuredStore.dispatch("INCREMENT")
                var liftedStoreState = configuredLiftedStore.getState()

                expect(2) { configuredStore.getState() }
                expect(3) { liftedStoreState.actionsById.size }
                expect(0) { liftedStoreState.committedState }
                assertTrue { liftedStoreState.stagedActionIds.contains(1) }

                // Trigger auto-commit.
                configuredStore.dispatch("INCREMENT")
                liftedStoreState = configuredLiftedStore.getState()

                expect(3) { configuredStore.getState() }
                expect(3) { liftedStoreState.actionsById.size }
                assertTrue { liftedStoreState.stagedActionIds.contains(1).not() }
                expect(1) { liftedStoreState.computedStates[0] }
                expect(1) { liftedStoreState.committedState }
                expect(2) { liftedStoreState.currentStateIndex }
            }

            it("should remove skipped actions once committed") {
                configuredStore.dispatch("INCREMENT")
                configuredLiftedStore.dispatch(DevToolsAction.ToggleAction(1))

                configuredStore.dispatch("INCREMENT")
                assertTrue { configuredLiftedStore.getState().skippedActionIds.contains(1) }

                configuredStore.dispatch("INCREMENT")
                assertTrue { configuredLiftedStore.getState().skippedActionIds.contains(1).not() }
            }

            it("should update currentStateIndex when auto-committing") {
                configuredStore.dispatch("INCREMENT")
                configuredStore.dispatch("INCREMENT")

                var liftedStoreState = configuredLiftedStore.getState()
                expect(2) { liftedStoreState.currentStateIndex }

                // currentStateIndex should stay at 2 as actions are committed.
                configuredStore.dispatch("INCREMENT")
                liftedStoreState = configuredLiftedStore.getState()
                val currentComputedState = liftedStoreState.computedStates[liftedStoreState.currentStateIndex]
                expect(2) { liftedStoreState.currentStateIndex }
                expect(3) { currentComputedState }
            }
        }

        describe("Import State") {
            var exportedState: DevToolsState<Int> = emptyState()

            beforeEach {
                val devTools = DevTools<Int>()
                val monitoredStore = createStore(counter, 0, devTools.instrument())
                val monitoredLiftedStore = devTools.devStore

                // Set up state to export
                monitoredStore.dispatch("INCREMENT")
                monitoredStore.dispatch("INCREMENT")
                monitoredStore.dispatch("INCREMENT")

                exportedState = monitoredLiftedStore.getState()
            }

            it("should replay all the steps when a state is imported") {
                val devTools = DevTools<Int>()
                createStore(counter, 0, devTools.instrument())
                val importMonitoredLiftedStore = devTools.devStore

                importMonitoredLiftedStore.dispatch(DevToolsAction.ImportState(exportedState))
                expect(importMonitoredLiftedStore.getState()) { exportedState }
            }

            it("should replace the existing action log with the one imported") {
                var devTools = DevTools<Int>()
                val importMonitoredStore = createStore(counter, 0, devTools.instrument())
                val importMonitoredLiftedStore = devTools.devStore

                importMonitoredStore.dispatch("DECREMENT")
                importMonitoredStore.dispatch("DECREMENT")

                importMonitoredLiftedStore.dispatch(DevToolsAction.ImportState(exportedState))
                expect(importMonitoredLiftedStore.getState()) { exportedState }
            }
        }

        describe("Import Actions") {
            var exportedState = emptyState()
            val savedActions = listOf("INCREMENT", "INCREMENT", "INCREMENT")

            beforeEach {
                val devTools = DevTools<Int>()
                val monitoredStore = createStore(counter, 0, devTools.instrument())
                val monitoredLiftedStore = devTools.devStore
                // Pass actions through component
                savedActions.forEach { action -> monitoredStore.dispatch(action) }
                // get the final state
                exportedState = monitoredLiftedStore.getState()
            }

            it("should replay all the steps when a state is imported") {
                val devTools = DevTools<Int>()
                createStore(counter, 0, devTools.instrument())
                val importMonitoredLiftedStore = devTools.devStore

                importMonitoredLiftedStore.dispatch(DevToolsAction.ImportActions(savedActions))

                val actual = importMonitoredLiftedStore.getState()
                expect(exportedState.committedState) { actual.committedState }
                expect(exportedState.computedStates) { actual.computedStates }
                expect(exportedState.currentStateIndex) { actual.currentStateIndex }
                expect(exportedState.nextActionId) { actual.nextActionId }
                expect(exportedState.skippedActionIds) { actual.skippedActionIds }
                expect(exportedState.stagedActionIds) { actual.stagedActionIds }
                expect(actionsWithoutInit(exportedState)) { actionsWithoutInit(actual) }
            }

            it("should replace the existing action log with the one imported") {
                val devTools = DevTools<Int>()
                val importMonitoredStore = createStore(counter, 0, devTools.instrument())
                val importMonitoredLiftedStore = devTools.devStore

                importMonitoredStore.dispatch("DECREMENT")
                importMonitoredStore.dispatch("DECREMENT")

                importMonitoredLiftedStore.dispatch(DevToolsAction.ImportActions(savedActions))

                val actual = importMonitoredLiftedStore.getState()
                expect(exportedState.committedState) { actual.committedState }
                expect(exportedState.computedStates) { actual.computedStates }
                expect(exportedState.currentStateIndex) { actual.currentStateIndex }
                expect(exportedState.nextActionId) { actual.nextActionId }
                expect(exportedState.skippedActionIds) { actual.skippedActionIds }
                expect(exportedState.stagedActionIds) { actual.stagedActionIds }
                expect(actionsWithoutInit(exportedState)) { actionsWithoutInit(actual) }
            }
        }
    }
}


private fun actionsWithoutInit(exportedState: DevToolsState<Int>) =
        exportedState.actionsById.values.toList().slice(1..exportedState.actionsById.values.size - 1).map { it.action }

private fun emptyState(): DevToolsState<Int> {
    return DevToolsState(
            actionsById = emptyMap(),
            nextActionId = -1,
            stagedActionIds = emptyList(),
            skippedActionIds = emptyList(),
            committedState = -1,
            currentStateIndex = -1,
            computedStates = emptyList()
    )
}

