package com.opensab.domain.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleMapsReaderTest {

    private fun maps(
        title: String?,
        arrow: String? = null,
        text: String? = null,
        subText: String? = "Llegada: 13:10",
        shortCriticalText: String? = ""
    ) = NavigationNotification(
        packageName = "com.google.android.apps.maps",
        arrow = arrow,
        title = title,
        text = text,
        subText = subText,
        progress = null,
        progressMax = null,
        shortCriticalText = shortCriticalText
    )

    /**
     * N1: the text of every distinct notification Maps posted on the 2026-09-26 ride, NBSP bytes
     * included (`maps-es-2026-09-26-s2.tsv`, generated from the trace).
     * The trace kept no arrows, so the maneuver is not part of this vector.
     */
    @Test
    fun everyDistinctNotificationOfTheRideReadsItsText() {
        val rows = javaClass.getResourceAsStream("maps-es-2026-09-26-s2.tsv")!!
            .bufferedReader(Charsets.UTF_8)
            .readLines()
            .filter { it.isNotEmpty() }
            .map { it.split('\t') }
        assertEquals(123, rows.size)

        val mismatches = rows.mapNotNull { row ->
            val (title, text, subText, distance, street) = row
            val eta = row[5]
            val read = GoogleMapsReader.read(maps(title, text = text.ifEmpty { null }, subText = subText.ifEmpty { null }))
            val expected = listOf(distance, street, eta.ifEmpty { null })
            val actual = listOf(read?.distanceFormatted, read?.streetName, read?.etaFormatted)
            if (expected == actual) null else "$title | expected $expected, read $actual"
        }
        assertTrue(mismatches.joinToString("\n"), mismatches.isEmpty())
    }

    /** N2: the dumped extras shape — no text, the ETA in subText, the maneuver from the arrow. */
    @Test
    fun mapsExtrasShapeReadsTheArrowTitleAndSubTextSeparately() {
        val read = GoogleMapsReader.read(maps("Dirígete hacia Rúa do Castro", arrow = "maneuver_depart"))!!

        assertEquals(ManeuverType.DEPART, read.maneuver)
        assertEquals("", read.distanceFormatted)
        assertEquals("Rúa do Castro", read.streetName)
        assertEquals("13:10", read.etaFormatted)
        assertEquals("Dirígete hacia Rúa do Castro", read.title)
    }

    /** N3. */
    @Test
    fun arrivalTakesTheDestinationFromTheText() {
        val read = GoogleMapsReader.read(
            maps("Has llegado", arrow = "maneuver_destination", text = "a Centro de Ocio Acuatico", subText = null)
        )!!

        assertEquals(ManeuverType.DESTINATION, read.maneuver)
        assertEquals("Centro de Ocio Acuatico", read.streetName)
        assertNull(read.etaFormatted)
    }

    @Test
    fun anUnknownOrMissingArrowIsStillGuidance() {
        val unknown = GoogleMapsReader.read(maps("250\u00A0m · Centro de Ocio Acuatico", arrow = "maneuver_new_in_2027"))!!
        val missing = GoogleMapsReader.read(maps("250\u00A0m · Centro de Ocio Acuatico", arrow = null))!!

        assertEquals(ManeuverType.UNKNOWN, unknown.maneuver)
        assertEquals(ManeuverType.UNKNOWN, missing.maneuver)
        assertEquals("250 m", missing.distanceFormatted)
        assertEquals("Centro de Ocio Acuatico", missing.streetName)
    }

    @Test
    fun anEmptyTitleIsNotGuidance() {
        assertNull(GoogleMapsReader.read(maps(null)))
        assertNull(GoogleMapsReader.read(maps("  ")))
    }

    @Test
    fun streetFollowsEnDireccionAsOneConnector() {
        val read = GoogleMapsReader.read(
            maps("850\u00A0m · En la rotonda, toma la primera salida en dirección Barrio as Fontiñas/EP-0000")
        )!!

        assertEquals("Barrio as Fontiñas/EP-0000", read.streetName)
    }

    @Test
    fun aConnectorInsideAWordDoesNotCutTheStreet() {
        assertEquals(
            "Avenida Montecastaños",
            GoogleMapsReader.read(maps("Gira a la derecha hacia Avenida Montecastaños"))!!.streetName
        )
    }

    @Test
    fun otherUnitsAndAPlainSpaceSplitToo() {
        assertEquals("1,2 km", GoogleMapsReader.read(maps("1,2\u00A0km · Gira a la derecha"))!!.distanceFormatted)
        assertEquals("1,2 km", GoogleMapsReader.read(maps("1,2 km · Gira a la derecha"))!!.distanceFormatted)
        val miles = GoogleMapsReader.read(maps("0.3 mi · Turn right onto Main St"))!!
        assertEquals("0.3 mi", miles.distanceFormatted)
        assertEquals("Turn right onto Main St", miles.title)
    }

    /** Distance is read from the status chip; Maps sometimes leaves the title bare. */
    @Test
    fun withoutAPrefixTheChipTextIsTheDistance() {
        val read = GoogleMapsReader.read(maps("Gira a la derecha hacia Rúa Mosteiro", shortCriticalText = "300 m"))!!

        assertEquals("300 m", read.distanceFormatted)
    }

    @Test
    fun twelveHourArrivalTimesKeepTheirSuffix() {
        assertEquals(
            "1:05 PM",
            GoogleMapsReader.read(maps("Turn right onto Main St", subText = "Arrival: 1:05 PM"))!!.etaFormatted
        )
    }
}
