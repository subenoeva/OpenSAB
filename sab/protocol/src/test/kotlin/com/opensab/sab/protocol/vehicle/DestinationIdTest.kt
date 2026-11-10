package com.opensab.sab.protocol.vehicle

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DestinationId is a 35-entry catalogue of markets carrying Honda's region code, plus
 * `UNKNOWN(-1)`. It is not four regions: a European bike reports 1 (`ED`) or 18 (`ED2`).
 */
class DestinationIdTest {

    @Test
    fun mapsTheMarketsAtTheEdgesOfTheReferenceCatalogue() {
        assertEquals(DestinationId.DEFAULT, DestinationId.fromCode(0))
        assertEquals(DestinationId.EU, DestinationId.fromCode(1))
        assertEquals(DestinationId.PERU, DestinationId.fromCode(34))
    }

    /** The two codes a European bike can report. */
    @Test
    fun mapsBothEuropeanMarketCodes() {
        assertEquals(DestinationId.EU, DestinationId.fromCode(1))
        assertEquals("ED", DestinationId.EU.regionCode)
        assertEquals(DestinationId.EU_TYPE2, DestinationId.fromCode(18))
        assertEquals("ED2", DestinationId.EU_TYPE2.regionCode)
    }

    @Test
    fun carriesTheHondaRegionCodeOfEachMarket() {
        assertEquals("G", DestinationId.GERMANY.regionCode)
        assertEquals("F", DestinationId.FRANCE.regionCode)
        assertEquals("A", DestinationId.AMERICA.regionCode)
        assertEquals("J", DestinationId.JAPAN.regionCode)
        assertEquals("3LS", DestinationId.LATIN_AMERICA.regionCode)
        assertEquals("FO", DestinationId.TAIWAN.regionCode)
    }

    @Test
    fun declaresTheThirtyFiveMarketsPlusTheUnknownSentinel() {
        val markets = DestinationId.entries.filter { it != DestinationId.UNKNOWN }
        assertEquals(35, markets.size)
        assertEquals((0..34).toList(), markets.map { it.code })
        assertEquals(36, DestinationId.entries.size)
    }

    /** The wire sentinel is the byte -1, which reaches the parser as an unsigned 0xFF. */
    @Test
    fun readsTheUnknownSentinelAsTheReferenceEncodesIt() {
        assertEquals(DestinationId.UNKNOWN, DestinationId.fromCode(0xFF))
    }

    @Test
    fun fallsBackToUnknownForACodeOutsideTheCatalogue() {
        assertEquals(DestinationId.UNKNOWN, DestinationId.fromCode(35))
        assertEquals(DestinationId.UNKNOWN, DestinationId.fromCode(99))
    }
}
