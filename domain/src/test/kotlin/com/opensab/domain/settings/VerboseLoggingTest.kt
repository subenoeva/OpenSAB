package com.opensab.domain.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerboseLoggingTest {
    @Test fun unchosenIsOnInADebugBuild() = assertTrue(resolveVerboseLogging(null, debuggableBuild = true))
    @Test fun unchosenIsOffInARelease() = assertFalse(resolveVerboseLogging(null, debuggableBuild = false))
    @Test fun aChoiceWinsOverTheBuild() {
        assertFalse(resolveVerboseLogging(false, debuggableBuild = true))
        assertTrue(resolveVerboseLogging(true, debuggableBuild = false))
    }
}
