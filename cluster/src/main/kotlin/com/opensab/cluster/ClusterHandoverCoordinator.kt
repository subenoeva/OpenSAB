package com.opensab.cluster

import com.opensab.cluster.mirror.ProjectionMirror
import com.opensab.logging.Log
import com.opensab.sab.protocol.projection.RequestIdGenerator
import com.opensab.sab.session.LiveSessionLinkHolder
import com.opensab.sab.session.coroutines.runCatchingCancellable
import com.opensab.sab.session.input.InputDispatcher
import com.opensab.sab.session.input.SabModeWatchdog
import com.opensab.sab.session.input.ScreenGrant
import kotlinx.coroutines.isActive

interface ClusterHandoverCoordinator {
    suspend fun requestClusterHandover(): Result<Unit>
}

class DefaultClusterHandoverCoordinator(
    private val liveLinkHolder: LiveSessionLinkHolder,
    private val inputDispatcher: InputDispatcher,
    private val requestIdGenerator: RequestIdGenerator,
    private val sabModeWatchdog: SabModeWatchdog,
    private val projectionMirror: ProjectionMirror
) : ClusterHandoverCoordinator {

    override suspend fun requestClusterHandover(): Result<Unit> {
        val link = liveLinkHolder.current
        if (link == null) {
            Log.w("Cannot request the cluster screen with no live session")
            return Result.failure(IllegalStateException("No active session to request the screen from"))
        }
        val session = link.session
        val transport = link.transport

        if (session.isClosed || session.connected == null) {
            Log.w("Cannot request handover on a closed or incomplete session")
            return Result.failure(IllegalStateException("Session is closed or not connected"))
        }

        return session.confined {
            projectionMirror.markHandoverRequested()
            session.onClose("handover-cleanup") { projectionMirror.publishHandoverDenied() }

            val requested = runCatchingCancellable {
                inputDispatcher.requestSabMode(transport, requestIdGenerator.nextId()).getOrThrow()
            }.onFailure { error ->
                Log.w(error, "REQUEST_SAB_MODE write did not succeed")
                projectionMirror.publishHandoverDenied()
            }

            if (requested.isSuccess && session.scope.isActive) {
                link.watchdogJob.getAndSet(null)?.cancel()
                val job = sabModeWatchdog.start(inputDispatcher.currentMode, session.scope) {
                    if (inputDispatcher.screenGrant.value == ScreenGrant.ASSUMED) {
                        // The engine is projecting and owns the mirror; a denial would put it on
                        // the tachometer under frames that keep being written.
                        Log.w("Cluster did not grant the screen, but the session projects without it: mirror left alone")
                    } else {
                        Log.w("Cluster did not grant the screen: keeping the mirror on the tachometer")
                        projectionMirror.publishGrantOverdue()
                    }
                }
                link.watchdogJob.set(job)
            }

            requested
        }
    }
}
