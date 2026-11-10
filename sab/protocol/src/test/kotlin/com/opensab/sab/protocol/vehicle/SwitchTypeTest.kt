package com.opensab.sab.protocol.vehicle

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SwitchType has exactly `RESERVED(0)`, `TYPE_A(1)`, `TYPE_B(2)` and `UNKNOWN(-1)`. A CB650R
 * reports 0, so `RESERVED` is a real value, not a placeholder.
 */
class SwitchTypeTest {

    @Test
    fun mapsTheThreeCodesTheReferenceClientDeclares() {
        assertEquals(SwitchType.RESERVED, SwitchType.fromCode(0x00))
        assertEquals(SwitchType.TYPE_A, SwitchType.fromCode(0x01))
        assertEquals(SwitchType.TYPE_B, SwitchType.fromCode(0x02))
    }

    @Test
    fun declaresNoCodeBeyondTheReferenceCatalogue() {
        assertEquals(
            listOf(0x00, 0x01, 0x02, 0xFF),
            SwitchType.entries.map { it.code }
        )
    }

    /** The wire sentinel is the byte -1, which reaches the parser as an unsigned 0xFF. */
    @Test
    fun readsTheUnknownSentinelAsTheReferenceEncodesIt() {
        assertEquals(SwitchType.UNKNOWN, SwitchType.fromCode(0xFF))
    }

    @Test
    fun fallsBackToUnknownForACodeOutsideTheCatalogue() {
        assertEquals(SwitchType.UNKNOWN, SwitchType.fromCode(0x03))
        assertEquals(SwitchType.UNKNOWN, SwitchType.fromCode(99))
    }
}
