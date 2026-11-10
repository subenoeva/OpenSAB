package com.opensab.sab.session.vehicle

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class VehicleProfileTest {

    @Test
    fun clusterDisplayRoundTripsThroughItsCode() =
        ClusterDisplay.entries.forEach { assertEquals(it, ClusterDisplay.fromCode(it.code)) }

    @Test
    fun anUnknownOrMissingClusterDisplayCodeIsColor() {
        assertEquals(ClusterDisplay.COLOR, ClusterDisplay.fromCode(9))
        assertEquals(ClusterDisplay.COLOR, ClusterDisplay.fromCode(null))
    }

    @Test
    fun bodyStyleRoundTripsThroughItsCode() =
        BodyStyle.entries.forEach { assertEquals(it, BodyStyle.fromCode(it.code)) }

    @Test
    fun anUnknownOrMissingBodyStyleCodeIsGeneric() {
        assertEquals(BodyStyle.GENERIC, BodyStyle.fromCode(-1))
        assertEquals(BodyStyle.GENERIC, BodyStyle.fromCode(null))
    }

    @Test
    fun codesAreUnique() {
        assertEquals(ClusterDisplay.entries.size, ClusterDisplay.entries.map { it.code }.toSet().size)
        assertEquals(BodyStyle.entries.size, BodyStyle.entries.map { it.code }.toSet().size)
    }

    @Test
    fun aProfileRoundTrips() {
        val p = VehicleProfile("Mi CB", BodyStyle.NAKED, ClusterDisplay.SEGMENT)
        assertEquals(p, VehicleProfileCodec.decode(VehicleProfileCodec.encode(p)))
    }

    @Test
    fun theProfileIsStoredByCode() {
        val obj = Json.parseToJsonElement(
            VehicleProfileCodec.encode(VehicleProfile(null, BodyStyle.TOURING, ClusterDisplay.SEGMENT))
        ).jsonObject
        assertEquals(4, obj.getValue("bodyStyle").jsonPrimitive.int)
        assertEquals(1, obj.getValue("clusterDisplay").jsonPrimitive.int)
    }

    @Test
    fun anUnknownCodeInStorageDecodesToTheDefault() {
        // Literal JSON: the codec does not write defaults, so a replace() on encoded output would test nothing.
        val decoded = VehicleProfileCodec.decode("""{"name":"X","bodyStyle":42,"clusterDisplay":9}""")
        assertEquals(VehicleProfile("X", BodyStyle.GENERIC, ClusterDisplay.COLOR), decoded)
    }

    @Test
    fun anUnreadableProfileDecodesToTheDefaults() {
        assertEquals(VehicleProfile(), VehicleProfileCodec.decode("{not json"))
    }
}
