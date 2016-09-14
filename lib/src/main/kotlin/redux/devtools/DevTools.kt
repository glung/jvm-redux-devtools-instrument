/*
  * CREDITS
  *
  * Port from JavaScript to Kotlin of "redux-devtools-instrument"
  * https://github.com/zalmoxisus/redux-devtools-instrument
 */
package redux.devtools

import redux.api.Reducer
import redux.api.Store

data class DevToolsState<out S : Any>(
        val actionsById: Map<Int, DevToolsAction.PerformAction>,
        val nextActionId: Int,
        val stagedActionIds: List<Int>,
        val skippedActionIds: List<Int>,
        val committedState: S,
        val currentStateIndex: Int,
        val computedStates: List<S>) {
}

sealed class DevToolsAction(val timestamp: Long = System.currentTimeMillis()) {
    class PerformAction(val action: Any) : DevToolsAction()
    class Commit : DevToolsAction()
    class Rollback : DevToolsAction()
    class Reset : DevToolsAction()
    class ToggleAction(val index: Int) : DevToolsAction()
    class SetActionsActive(val start: Int, val end: Int, val active: Boolean = true) : DevToolsAction()
    class Sweep : DevToolsAction()
    class JumpToState(val index: Int) : DevToolsAction()
    class ImportState<out S : Any>(val state: DevToolsState<S>) : DevToolsAction()
    class ImportActions(val actions: List<Any>) : DevToolsAction()
}

class DevTools<T : Any> {
    lateinit var devStore: Store<DevToolsState<T>>

    fun instrument(maxAge: Int = INFINITY) = Store.Enhancer { leafCreator: Store.Creator ->

        if (maxAge < 2) {
            throw IllegalArgumentException("Max age should be over 2")
        }

        object : Store.Creator {
            // S must be the same as S
            override fun <S : Any> create(reducer: Reducer<S>, initialState: S, enhancer: Store.Enhancer?): Store<S> {
                fun liftReducer(reducer: Reducer<S>) =
                        liftReducerWith(reducer, initialState, maxAge)

                fun <S : Any> unliftState(devToolsState: DevToolsState<S>) =
                        devToolsState.computedStates[devToolsState.currentStateIndex]

                fun unliftStore(devToolsStore: Store<DevToolsState<S>>) = object : Store<S> {

                    override fun dispatch(action: Any): Any {
                        devToolsStore.dispatch(DevToolsAction.PerformAction(action))
                        return action
                    }

                    override fun replaceReducer(reducer: Reducer<S>) = devToolsStore.replaceReducer(liftReducer(reducer))

                    override fun subscribe(subscriber: Store.Subscriber): Store.Subscription = devToolsStore.subscribe(subscriber)

                    override fun getState(): S {
                        return unliftState(devToolsStore.getState())
                    }
                }

                val devToolsReducer: Reducer<DevToolsState<S>> = liftReducer(reducer)
                val liftedInitialState = liftState(initialState)
                val liftedStore = leafCreator.create(devToolsReducer, liftedInitialState, enhancer)
                val unliftedStore = unliftStore(liftedStore)

                // T must be the same type as S or it sends an exception here.
                devStore = liftedStore as Store<DevToolsState<T>>
                return unliftedStore
            }

        }
    }

    companion object {

        private val INFINITY = Int.MAX_VALUE

        private fun <S : Any> liftState(initialState: S): DevToolsState<S> {
            return DevToolsState(
                    actionsById = emptyMap(),
                    nextActionId = 0,
                    stagedActionIds = emptyList(),
                    skippedActionIds = emptyList(),
                    currentStateIndex = -1,
                    committedState = initialState,
                    computedStates = emptyList()
            )
        }

        private fun <S : Any> liftReducerWith(reducer: Reducer<S>, initialCommittedState: S, maxAge: Int): Reducer<DevToolsState<S>> {
            return Reducer { state: DevToolsState<S>, action: Any ->
                var minInvalidatedStateIndex = 0

                val newState = when (action) {
                    is DevToolsAction.Reset -> reset(initialCommittedState)
                    is DevToolsAction.Commit -> commit(state)
                    is DevToolsAction.Rollback -> rollback(state)
                    is DevToolsAction.ToggleAction -> {
                        with(toggleAction(action, state)) {
                            // Optimization: we know history before this action hasn't changed
                            minInvalidatedStateIndex = stagedActionIds.indexOf(action.index)
                            this
                        }
                    }
                    is DevToolsAction.SetActionsActive -> {
                        // Optimization: we know history before this action hasn't changed
                        minInvalidatedStateIndex = state.stagedActionIds.indexOf(action.start)
                        setActionsActive(action, state)
                    }
                    is DevToolsAction.JumpToState -> {
                        // Optimization: we know the history has not changed.
                        minInvalidatedStateIndex = INFINITY
                        jumpToState(action, state)
                    }
                    is DevToolsAction.Sweep -> sweep(state)
                    is DevToolsAction.PerformAction -> {
                        with(performAction(action, state, maxAge)) {
                            // Optimization: we know that only the new action needs computing.
                            minInvalidatedStateIndex = stagedActionIds.size - 1;
                            this
                        }
                    }
                    is DevToolsAction.ImportState<*> -> {
                        // Completely replace everything.
                        minInvalidatedStateIndex = 0
                        action.state.copy() as DevToolsState<S>
                    }
                    is DevToolsAction.ImportActions -> {
                        // Completely replace everything.
                        minInvalidatedStateIndex = 0
                        importActions(action, initialCommittedState)
                    }
                    is Store.Companion.INIT -> {
                        // Always recompute states on hot reload and init.
                        minInvalidatedStateIndex = 0
                        // TODO : is it a reset ?
                        performAction(DevToolsAction.PerformAction(action), liftState(initialCommittedState), maxAge)
                    }
                    else -> {
                        // If the action is not recognized, it's a monitor action.
                        // Optimization: a monitor action can't change history.
                        minInvalidatedStateIndex = INFINITY
                        state
                    }
                }
                if (minInvalidatedStateIndex == INFINITY) {
                    newState
                } else {
                    newState.copy(computedStates = recomputeStates(newState, minInvalidatedStateIndex, reducer))
                }
            }
        }

        private fun <S : Any> importActions(action: DevToolsAction.ImportActions, initialCommittedState: S): DevToolsState<S> {
            val actions = listOf(Store.Companion.INIT) + action.actions
            val actionsById = actions.mapIndexed { i, action -> i to DevToolsAction.PerformAction(action) }.toMap()
            return reset(initialCommittedState).copy(
                    actionsById = actionsById,
                    nextActionId = actions.size,
                    stagedActionIds = (0..actions.size - 1).toList(),
                    currentStateIndex = actions.size - 1
            )
        }

        private fun <S : Any> commitExcessActions(state: DevToolsState<S>, n: Int): DevToolsState<S> {
            // Auto-commits n-number of excess actions.
            val excess = n;
            val idsToDelete = state.stagedActionIds.slice(1..excess + 1)
            val actionsById = state.actionsById.filter { idsToDelete.contains(it.key) }

            return state.copy(
                    actionsById = actionsById,
                    skippedActionIds = state.skippedActionIds.filter { idsToDelete.contains(it).not() },
                    stagedActionIds = listOf(0) + state.stagedActionIds.slice(excess + 1..state.stagedActionIds.size - 1),
                    committedState = state.computedStates[excess],
                    computedStates = state.computedStates.slice(excess..state.computedStates.size - 1),
                    currentStateIndex = if (state.currentStateIndex > excess) state.currentStateIndex - excess else 0
            )
        }

        private fun <S : Any> performAction(action: DevToolsAction.PerformAction, aState: DevToolsState<S>, maxAge: Int): DevToolsState<S> {
            // Auto-commit as new actions come in.
            val theState = if (aState.stagedActionIds.size === maxAge) {
                commitExcessActions(aState, 1)
            } else {
                aState
            }

            with(theState) {
                val isCurrentIndexTheLastOne = currentStateIndex === stagedActionIds.size - 1
                val nextStateIndex = if (isCurrentIndexTheLastOne) {
                    currentStateIndex + 1
                } else {
                    currentStateIndex
                }

                val actionId = nextActionId
                return copy(
                        currentStateIndex = nextStateIndex,
                        actionsById = actionsById + Pair(actionId, action),
                        nextActionId = actionId + 1,
                        stagedActionIds = stagedActionIds + actionId
                )
            }
        }

        // Forget any actions that are currently being skipped.
        private fun <S : Any> sweep(state: DevToolsState<S>): DevToolsState<S> {
            with(state) {
                val nesStagedActionIds = stagedActionIds - skippedActionIds
                return copy(
                        stagedActionIds = nesStagedActionIds,
                        skippedActionIds = emptyList(),
                        currentStateIndex = Math.min(currentStateIndex, nesStagedActionIds.size - 1)
                )
            }
        }

        // Without recomputing anything, move the pointer that tell us
        // which state is considered the current one. Useful for sliders.
        private fun <S : Any> jumpToState(action: DevToolsAction.JumpToState, state: DevToolsState<S>) =
                state.copy(currentStateIndex = action.index)

        // Toggle whether an action with given ID is skipped.
        // Being skipped means it is a no-op during the computation.
        private fun <S : Any> setActionsActive(action: DevToolsAction.SetActionsActive, state: DevToolsState<S>): DevToolsState<S> {
            with(state) {
                val actionIds = (action.start..action.end - 1)
                val skippedActionIds = if (action.active) {
                    skippedActionIds - actionIds
                } else {
                    skippedActionIds + actionIds
                }
                return copy(skippedActionIds = skippedActionIds)
            }
        }

        // Toggle whether an action with given ID is skipped.
        // Being skipped means it is a no-op during the computation.
        private fun <S : Any> toggleAction(action: DevToolsAction.ToggleAction, state: DevToolsState<S>): DevToolsState<S> {
            with(state) {
                val actionId = action.index
                val isActionSkipped = skippedActionIds.contains(actionId)
                val skippedActionIds = if (isActionSkipped) {
                    skippedActionIds - actionId
                } else {
                    listOf(actionId) + skippedActionIds
                }
                return copy(skippedActionIds = skippedActionIds)
            }
        }

        // Forget about any staged actions.
        // Start again from the last committed state.
        private fun <S : Any> rollback(state: DevToolsState<S>): DevToolsState<S> {
            return state.copy(
                    actionsById = mapOf(Pair(0, DevToolsAction.PerformAction(Store.Companion.INIT))),
                    nextActionId = 1,
                    stagedActionIds = listOf(0),
                    skippedActionIds = emptyList(),
                    currentStateIndex = 0,
                    computedStates = emptyList()
            )
        }

        // Consider the last committed state the new starting point.
        // Squash any staged actions into a single committed state.
        private fun <S : Any> commit(state: DevToolsState<S>): DevToolsState<S> {
            return state.copy(
                    actionsById = mapOf(Pair(0, DevToolsAction.PerformAction(Store.Companion.INIT))),
                    nextActionId = 1,
                    stagedActionIds = listOf(0),
                    skippedActionIds = emptyList(),
                    committedState = state.computedStates[state.currentStateIndex],
                    currentStateIndex = 0,
                    computedStates = emptyList()
            )
        }

        // Get back to the state the store was created with.
        private fun <S : Any> reset(initialCommittedState: S): DevToolsState<S> {
            return DevToolsState(
                    actionsById = mapOf(Pair(0, DevToolsAction.PerformAction(Store.Companion.INIT))),
                    committedState = initialCommittedState,
                    nextActionId = 0,
                    stagedActionIds = listOf(0),
                    skippedActionIds = emptyList(),
                    currentStateIndex = 0,
                    computedStates = emptyList()
            )
        }

        private fun <S : Any> recomputeStates(storeState: DevToolsState<S>, minInvalidatedStateIndex: Int, reducer: Reducer<S>): List<S> {
            with(storeState) {
                val previousStatesToKeep = if (minInvalidatedStateIndex > 0) {
                    computedStates.slice(0..minInvalidatedStateIndex - 1)
                } else {
                    emptyList<S>()
                }

                val nextComputedStates = previousStatesToKeep.toMutableList()
                for (i: Int in minInvalidatedStateIndex..stagedActionIds.size - 1) {
                    val actionId = stagedActionIds [i]
                    val previousState = getPreviousState(i, nextComputedStates)
                    val shouldSkip = skippedActionIds.indexOf(actionId) > -1

                    val element = if (shouldSkip) {
                        previousState
                    } else {
                        computeNextEntry(reducer, actionsById [actionId]!!, previousState)
                    }
                    nextComputedStates.add(element)
                }
                return nextComputedStates
            }
        }

        private fun <S : Any> DevToolsState<S>.getPreviousState(i: Int, nextComputedStates: MutableList<S>): S {
            val hasPreviewEntry = i > 0
            return if (hasPreviewEntry) {
                nextComputedStates [i - 1]
            } else {
                committedState
            }
        }

        private fun <S : Any> computeNextEntry(reducer: Reducer<S>, action: DevToolsAction.PerformAction, previousState: S) =
                reducer.reduce(previousState, action.action)
    }
}