package com.opensab.cluster

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Redraw ticks built from the state flows a page reads.
 *
 * Every source drops its replayed value: a `StateFlow` hands the current one to each new collector
 * and the engine has already rendered with it at mount time. The signature takes `StateFlow` and not
 * `Flow` precisely because of that drop — on a hot flow it would swallow a real event.
 */
fun redrawOn(vararg sources: StateFlow<*>): Flow<Unit> =
    if (sources.isEmpty()) {
        emptyFlow()
    } else {
        merge(*sources.map { source -> source.drop(1).map { } }.toTypedArray())
    }
