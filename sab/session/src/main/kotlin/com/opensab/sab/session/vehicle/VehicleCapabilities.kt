package com.opensab.sab.session.vehicle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.opensab.logging.Log

/**
 * Derived from the rider-declared [ClusterDisplay] together with the raw Display Capability byte
 * array read over GATT during the identity step.
 */
data class VehicleCapabilities(
    val segmentDisplay: Boolean = false,
    val declaredCapabilities: Set<Int> = emptySet()
) {

    /** A segment cluster has no palette to address. */
    val supportsColor: Boolean get() = !segmentDisplay

    /**
     * Both sources have to agree: a modal needs a layer to draw on and a link willing to carry it.
     *
     * The panel describes what it can draw, the BTU bitmask describes what the link accepts, and
     * the stricter of the two wins. Deriving this from the panel alone would push a modal at a
     * link that never declared POP_UP_DISPLAY.
     */
    val supportsModals: Boolean
        get() = supportsColor && declaredCapabilities.contains(CAP_POP_UP_DISPLAY)

    companion object {
        const val CAP_INDICATOR = 0x01       // Bit 0
        const val CAP_POP_UP_DISPLAY = 0x02  // Bit 1
        const val CAP_MYSTERY_BOX = 0x04     // Bit 2

        /**
         * Permissive baseline used before any motorcycle connects. Full colour and every layer, so
         * nothing is degraded until a cluster has actually been identified.
         */
        val Permissive = VehicleCapabilities(
            segmentDisplay = false,
            declaredCapabilities = setOf(CAP_INDICATOR, CAP_POP_UP_DISPLAY, CAP_MYSTERY_BOX)
        )

        /** Decodes the `DisplayCapability` characteristic bitmask. */
        fun declaredFrom(displayCapability: ByteArray): Set<Int> {
            if (displayCapability.isEmpty()) return emptySet()
            val mask = displayCapability[0].toInt() and 0xFF
            val capabilities = mutableSetOf<Int>()
            if ((mask and CAP_INDICATOR) != 0) capabilities.add(CAP_INDICATOR)
            if ((mask and CAP_POP_UP_DISPLAY) != 0) capabilities.add(CAP_POP_UP_DISPLAY)
            if ((mask and CAP_MYSTERY_BOX) != 0) capabilities.add(CAP_MYSTERY_BOX)
            return capabilities
        }

        fun from(vehicle: KnownVehicle) = VehicleCapabilities(
            segmentDisplay = vehicle.clusterDisplay == ClusterDisplay.SEGMENT,
            declaredCapabilities = declaredFrom(vehicle.vehicleInfo.displayCapability)
        )
    }
}

/**
 * Updated by the connection orchestrator right after reading vehicle identity and reset to
 * [VehicleCapabilities.Permissive] upon disconnect.
 */
class VehicleCapabilitiesHolder {
    private val _state = MutableStateFlow(VehicleCapabilities.Permissive)
    val state: StateFlow<VehicleCapabilities> = _state.asStateFlow()
    val capabilities: StateFlow<VehicleCapabilities> get() = state

    fun adopt(vehicle: KnownVehicle) {
        val adopted = VehicleCapabilities.from(vehicle)
        _state.value = adopted
        // Logged at info because it is the only evidence, in a garage with one shot at a session,
        // that degradation was in force before the first frame went out. It has to appear above
        // "Starting projection engine loop" in the trace; below it means the frame beat the policy.
        Log.i(
            "Cluster capabilities for %s: display=%s declared=%s color=%s modals=%s",
            vehicle.displayName,
            vehicle.clusterDisplay,
            adopted.declaredCapabilities.joinToString(",") { "0x%02X".format(it) },
            adopted.supportsColor,
            adopted.supportsModals
        )
    }

    fun reset() {
        _state.value = VehicleCapabilities.Permissive
    }
}
