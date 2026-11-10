package com.opensab.app.ui.common

import com.opensab.sab.session.vehicle.BodyStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class BodyStyleLabelTest {

    @Test
    fun everyBodyStyleHasItsOwnLabel() {
        val labels = BodyStyle.entries.map { it.label() }
        assertEquals(BodyStyle.entries.size, labels.toSet().size)
    }
}
