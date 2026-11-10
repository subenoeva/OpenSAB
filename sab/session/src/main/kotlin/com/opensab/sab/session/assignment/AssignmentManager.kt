package com.opensab.sab.session.assignment

import com.opensab.logging.Log
import com.opensab.sab.protocol.assignment.AssignmentBuilder
import com.opensab.sab.protocol.assignment.AssignmentRecord
import com.opensab.sab.protocol.assignment.AssignmentReport
import com.opensab.sab.protocol.assignment.AssignmentResult
import com.opensab.sab.protocol.assignment.AssignmentResultParser
import com.opensab.sab.protocol.ble.SabGattUuids
import com.opensab.sab.session.ble.GattTransport
import com.opensab.sab.session.coroutines.isCancellationOfTheCaller
import com.opensab.sab.session.coroutines.runCatchingCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

interface AssignmentManager {
    suspend fun executeAssignment(
        transport: GattTransport,
        scope: CoroutineScope,
        colorRegistrationNeeded: Boolean = true
    ): Result<AssignmentReport>
}

/**
 * Default implementation: subscribes to AssignmentControl before writing anything, correlates each
 * record with the AssignmentResult carrying its requestId, and closes the batch with the
 * END_ASSIGNMENT sentinel as a write of its own.
 *
 * Sole owner of the AssignmentControl write path for the duration of one [executeAssignment] call.
 * The subscription outlives the call: it is launched in the session scope and ends with it, because
 * ending it earlier writes the CCCD back to 00 00 right around the REQUEST_SAB_MODE that follows.
 * Only a batch that fails cancels it here.
 *
 * @param resultTimeout per-record wait for the AssignmentResult that carries its requestId.
 *   300 ms rather than seconds: a local BLE round-trip is tens of ms, so this keeps a fully silent
 *   BTU batch under ~8 s and the GATT keepalive comes up before the BTU's ~39 s link-supervision
 *   drop when the ignition is off. It is only the timeout constant, not a consecutive-timeout bail.
 * @param subscriptionTimeout one-shot wait for the AssignmentControl CCCD to arm before the
 *   batch starts; a failure here downgrades to a blind run, it does not abort the connection.
 */
class DefaultAssignmentManager(
    private val resultTimeout: Duration = 300.milliseconds,
    private val subscriptionTimeout: Duration = 2.seconds
) : AssignmentManager {

    override suspend fun executeAssignment(
        transport: GattTransport,
        scope: CoroutineScope,
        colorRegistrationNeeded: Boolean
    ): Result<AssignmentReport> = runCatchingCancellable {
        val sequence = AssignmentBuilder.buildStandardAssignmentSequence(colorRegistrationNeeded)
        Log.i("Starting dynamic assignment sequence (${sequence.size} records)")

        // A fresh flow per call: request ids restart at 1 on every session, so a result kept from
        // the previous connection would satisfy the wrong record.
        // replay must stay >= the batch size: an AssignmentResult that lands before the loop reaches
        // its record has to survive in the cache until then, hence maxOf(64, sequence.size).
        val results = MutableSharedFlow<AssignmentResult>(
            replay = maxOf(64, sequence.size),
            extraBufferCapacity = 64
        )
        // Observe, do not abort: if the notification channel never arms, log it and run the batch
        // blind — every record with a requestId reads as timed out, but the writes and the
        // END_ASSIGNMENT sentinel still go out and executeAssignment still returns success.
        val subscriptionJob = try {
            subscribeToResults(transport, scope, results)
        } catch (e: Throwable) {
            Log.w(e, "AssignmentControl subscription did not arm; running the assignment blind (results will all read as timed out)")
            null
        }

        val rejected = mutableListOf<AssignmentResult>()
        val timedOut = mutableListOf<Int>()
        var accepted = 0
        var written = 0

        try {
            for ((index, record) in sequence.withIndex()) {
                val payload = record.encode()
                val requestId = record.requestId?.toInt()
                Log.d("Writing assignment record #$index (${record.javaClass.simpleName}) [${payload.size} bytes]")

                transport.writeCharacteristic(
                    serviceUuid = SabGattUuids.SERVICE_ASSIGNMENT,
                    characteristicUuid = SabGattUuids.CHAR_ASSIGNMENT_CONTROL,
                    payload = payload,
                    withResponse = false
                ).getOrThrow()
                written++

                if (requestId == null) continue

                val result = if (subscriptionJob == null) {
                    null
                } else {
                    withTimeoutOrNull(resultTimeout) {
                        results.first { it.requestId == requestId }
                    }
                }

                when {
                    result == null -> {
                        timedOut += requestId
                        Log.w("BTU did not answer assignment record requestId=%d within %d ms", requestId, resultTimeout.inWholeMilliseconds)
                    }

                    result.status.isAccepted -> accepted++

                    else -> {
                        rejected += result
                        Log.w(
                            "BTU rejected assignment record: requestId=%d, status=%s (0x%02X), errorPosition=%s",
                            result.requestId,
                            result.status.name,
                            result.status.code,
                            result.errorPosition?.toString() ?: "none"
                        )
                    }
                }
            }

            // END_ASSIGNMENT is a control write of its own, sent after the batch and never
            // correlated: the BTU answers it with an event, not with an AssignmentResult.
            transport.writeCharacteristic(
                serviceUuid = SabGattUuids.SERVICE_ASSIGNMENT,
                characteristicUuid = SabGattUuids.CHAR_ASSIGNMENT_CONTROL,
                payload = AssignmentRecord.EndAssignment.encode(),
                withResponse = false
            ).getOrThrow()
        } catch (e: Throwable) {
            subscriptionJob?.cancel()
            throw e
        }

        val report = AssignmentReport(
            written = written,
            accepted = accepted,
            rejected = rejected.toList(),
            timedOut = timedOut.toList()
        )

        if (report.isClean) {
            Log.i("Dynamic assignment completed: %d/%d records accepted by the BTU", report.accepted, report.written)
        } else {
            Log.w(
                "Dynamic assignment INCOMPLETE: %d accepted, %d rejected, %d unanswered out of %d written",
                report.accepted,
                report.rejected.size,
                report.timedOut.size,
                report.written
            )
        }
        report
    }

    private suspend fun subscribeToResults(
        transport: GattTransport,
        scope: CoroutineScope,
        results: MutableSharedFlow<AssignmentResult>
    ): Job {
        val armed = CompletableDeferred<Unit>()
        val job = scope.launch {
            try {
                transport.observeNotifications(
                    serviceUuid = SabGattUuids.SERVICE_ASSIGNMENT,
                    characteristicUuid = SabGattUuids.CHAR_ASSIGNMENT_CONTROL,
                    onSubscriptionArmed = { armed.complete(Unit) }
                ).collect { raw ->
                    val parsed = runCatchingCancellable { AssignmentResultParser.parse(raw) }
                    // The BTU answers END_ASSIGNMENT (0x03) with an event, not an AssignmentResult, so
                    // one "Unparseable AssignmentControl notification" warning per batch is expected.
                    parsed.getOrNull()?.let { results.emit(it) }
                        ?: Log.w("Unparseable AssignmentControl notification: %s", raw.joinToString(" ") { "%02X".format(it) })
                }
            } catch (e: Throwable) {
                if (e.isCancellationOfTheCaller()) {
                    Log.d("AssignmentControl notifications subscription cancelled")
                    throw e
                }
                Log.w(e, "AssignmentControl notifications subscription terminated with error")
                if (!armed.isCompleted) armed.completeExceptionally(e)
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause != null && !armed.isCompleted) armed.completeExceptionally(cause)
        }
        try {
            withTimeout(subscriptionTimeout) { armed.await() }
        } catch (e: Throwable) {
            job.cancel()
            throw e
        }
        return job
    }
}
