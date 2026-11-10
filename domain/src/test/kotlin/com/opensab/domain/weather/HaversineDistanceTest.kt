package com.opensab.domain.weather

import org.junit.Assert.assertEquals
import org.junit.Test

class HaversineDistanceTest {

    @Test
    fun `same coordinates return zero distance`() {
        val distance = haversineDistanceKm(40.4168, -3.7038, 40.4168, -3.7038)
        assertEquals(0.0, distance, 0.001)
    }

    @Test
    fun `calculates accurate distance between Madrid and Toledo`() {
        // Madrid Puerta del Sol: 40.4168, -3.7038
        // Toledo Zocodover: 39.8581, -4.0226
        val distance = haversineDistanceKm(40.4168, -3.7038, 39.8581, -4.0226)
        // Known geodesic distance is approx 67.5 km
        assertEquals(67.5, distance, 1.0)
    }

    @Test
    fun `detects displacement around five kilometer threshold`() {
        // Point A: 40.4168, -3.7038
        // Point B: ~5.5 km north (0.05 degrees latitude)
        val distance = haversineDistanceKm(40.4168, -3.7038, 40.4668, -3.7038)
        assertEquals(5.56, distance, 0.2)
    }
}
