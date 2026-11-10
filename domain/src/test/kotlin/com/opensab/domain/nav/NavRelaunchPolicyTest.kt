package com.opensab.domain.nav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavRelaunchPolicyTest {

    private fun instruction(title: String) = NavInstruction(
        title = title,
        streetName = title,
        distanceFormatted = "",
        maneuver = ManeuverType.DEPART
    )

    @Test
    fun `a fresh launch may relaunch on unlock`() {
        assertTrue(NavRelaunchPolicy(baseline = null).mayRelaunch)
    }

    @Test
    fun `relaunches stop after two`() {
        val policy = NavRelaunchPolicy(baseline = null)

        assertTrue(policy.relaunched().mayRelaunch)
        assertFalse(policy.relaunched().relaunched().mayRelaunch)
    }

    @Test
    fun `no instruction is no guidance`() {
        assertFalse(NavRelaunchPolicy(baseline = null).startsGuidance(null))
    }

    @Test
    fun `the instruction held before the launch is not guidance`() {
        val stale = instruction("Old")

        assertFalse(NavRelaunchPolicy(baseline = stale).startsGuidance(stale))
    }

    @Test
    fun `an instruction posted after the launch is guidance even when it reads the same as the stale one`() {
        val stale = instruction("Same")

        assertTrue(NavRelaunchPolicy(baseline = stale).startsGuidance(stale.copy()))
    }
}
