package com.opensab.app.service

import com.opensab.sab.session.SessionCommand
import com.opensab.sab.session.SessionOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure decoding of what [SabConnectionService.onStartCommand] receives: no
 * `Intent`, no `Context`, so every branch is a JVM test instead of `assembleDebug` reading a diff.
 */
class SessionIntentsTest {

    @Test
    fun `a start with a mac decodes to Start with its origin and start id`() {
        val command = SessionIntents.decode(
            action = SabConnectionService.ACTION_START_CONNECT,
            macAddress = "02:00:00:00:00:AB",
            origin = "SCANNER",
            startId = 7
        )
        assertEquals(SessionCommand.Start("02:00:00:00:00:AB", SessionOrigin.SCANNER, 7), command)
    }

    @Test
    fun `a start without a mac decodes to nothing`() {
        val command = SessionIntents.decode(
            action = SabConnectionService.ACTION_START_CONNECT,
            macAddress = null,
            origin = "RECEIVER",
            startId = 1
        )
        assertNull(command)
    }

    @Test
    fun `a null action decodes to nothing`() {
        val command = SessionIntents.decode(
            action = null,
            macAddress = "02:00:00:00:00:AB",
            origin = "RECEIVER",
            startId = 1
        )
        assertNull(command)
    }

    @Test
    fun `an unknown action decodes to nothing`() {
        val command = SessionIntents.decode(
            action = "com.opensab.service.SOMETHING_ELSE",
            macAddress = "02:00:00:00:00:AB",
            origin = "RECEIVER",
            startId = 1
        )
        assertNull(command)
    }

    @Test
    fun `a start with no origin reads as RECEIVER`() {
        val command = SessionIntents.decode(
            action = SabConnectionService.ACTION_START_CONNECT,
            macAddress = "02:00:00:00:00:AB",
            origin = null,
            startId = 3
        )
        assertEquals(SessionCommand.Start("02:00:00:00:00:AB", SessionOrigin.RECEIVER, 3), command)
    }

    @Test
    fun `a disconnect decodes whatever extras it carries`() {
        val withOrigin = SessionIntents.decode(
            action = SabConnectionService.ACTION_DISCONNECT,
            macAddress = null,
            origin = "DASHBOARD",
            startId = 9
        )
        assertEquals(SessionCommand.Disconnect(SessionOrigin.DASHBOARD, 9), withOrigin)

        val withoutOrigin = SessionIntents.decode(
            action = SabConnectionService.ACTION_DISCONNECT,
            macAddress = null,
            origin = null,
            startId = 10
        )
        assertEquals(SessionCommand.Disconnect(SessionOrigin.RECEIVER, 10), withoutOrigin)
    }
}
