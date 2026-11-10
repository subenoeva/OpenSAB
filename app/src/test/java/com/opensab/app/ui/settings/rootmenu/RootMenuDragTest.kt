package com.opensab.app.ui.settings.rootmenu

import org.junit.Assert.assertEquals
import org.junit.Test

class RootMenuDragTest {
    @Test fun `no movement stays put`() = assertEquals(2, dragTargetIndex(2, 0f, 100f, 6))
    @Test fun `under half a row stays put`() = assertEquals(2, dragTargetIndex(2, 49f, 100f, 6))
    @Test fun `past half a row moves one`() = assertEquals(3, dragTargetIndex(2, 51f, 100f, 6))
    @Test fun `upwards moves up`() = assertEquals(0, dragTargetIndex(2, -160f, 100f, 6))
    @Test fun `clamps at the top`() = assertEquals(0, dragTargetIndex(2, -900f, 100f, 6))
    @Test fun `clamps at the bottom`() = assertEquals(5, dragTargetIndex(2, 900f, 100f, 6))
    @Test fun `a row with no height yet stays put`() = assertEquals(2, dragTargetIndex(2, 300f, 0f, 6))
}
