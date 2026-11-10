package com.opensab.app.e2e

import com.opensab.domain.message.MessageNotification
import com.opensab.domain.message.MessageNotificationEvent
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.BodyType
import com.opensab.sab.protocol.projection.IconId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two things the rider reported off the carousel on 2026-09-10, both of them measured on the
 * wire in a field capture before anybody knew what they were.
 *
 * 1. **The message entry goes missing.** It was the one carousel item sending a content icon
 *    *and* a second line, and the BTU answers that pair with `status=0x0C` pointing at the
 *    `contentIconId` byte — eight times in the capture, `requestId` 9, 30, 55, 58, 60, 66, 101 and
 *    106, every one of them `01 xx 0B 08 "Messages" ... 04 00 04 "Mama" 00 08 "Probando" 10`. The
 *    mirror shows it because the mirror is published before the verdict.
 * 2. **The phone entry stays ringing after the call is rejected.** The call had exactly one way to
 *    end — its notification leaving the shade — and Google Dialer does not do that: it re-posts the
 *    same key without its `CallStyle`. In the capture the entry sat on `Abuelo Ejemplo / Ringing`
 *    from 12:37:52 to the end of the session, with ten `Rejecting the live call` behind it.
 *
 * Both tests read the bytes the simulator received, because both defects are about what reaches
 * the cluster and not about what the engine believes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CarouselEntryE2eTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private fun whatsApp() = MessageNotificationEvent.Posted(
        MessageNotification(
            packageName = "com.whatsapp",
            key = "0|com.whatsapp|1|null|10123",
            title = "Mama",
            text = "Probando",
            conversationTitle = null,
            isGroupConversation = false,
            isGroupSummary = false,
            isOngoing = false,
            postTimeMillis = 1_700_000_000_000L
        )
    )

    /** Content icon and the two content lines of a MENU frame, read off the bytes on the cable. */
    private data class MenuContent(val icon: Int, val line1: String, val line2: String)

    private fun decodeMenu(payload: ByteArray): MenuContent {
        var i = 2
        fun byte(): Int = payload[i++].toInt() and 0xFF
        fun text(): String {
            val length = byte()
            if (length == 0) return ""
            val value = String(payload, i, length, Charsets.UTF_8)
            i += length + 1
            return value
        }
        /** An icon carries its tint exactly when it is present. */
        fun icon(): Int = byte().also { if (it != 0) i++ }

        byte()
        text()
        assertEquals("this vector only reads MENU bodies", BodyType.MENU.value, byte())
        byte()
        repeat(4) { icon() }
        val contentIcon = icon()
        return MenuContent(contentIcon, text(), text())
    }

    @Test
    fun `the message entry reaches the cluster with a body behind it`() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)
        harness.connect().getOrThrow()
        harness.handOverScreen()
        runCurrent()

        harness.messageBus.post(whatsApp())
        runCurrent()
        // The prompt takes the screen; the rider gives it back and the message stays unread.
        harness.btu.press(FunctionId.BACK)
        runCurrent()
        repeat(3) {
            harness.btu.press(FunctionId.NEXT)
            runCurrent()
        }

        val painted = harness.btu.baseFrames().last().payload
        assertTrue("the rider has to be on the message entry", painted.containsUtf8("Messages"))

        val content = decodeMenu(painted)
        assertEquals("Mama", content.line1)
        assertEquals("Probando", content.line2)
        assertEquals(
            "an entry with a second line sends no content icon, or the BTU rejects the frame at " +
                "that very byte",
            IconId.IC_NONE.id,
            content.icon
        )
    }

    @Test
    fun `the phone entry lets go of a call the dialer stopped calling a call`() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)
        harness.connect().getOrThrow()
        harness.handOverScreen()
        runCurrent()

        harness.callDriver.post(callerName = "Abuelo Ejemplo", callerNumber = "600400400")
        runCurrent()

        // The rider rejects on the phone. The dialer answers by re-posting the very same key
        // without its CallStyle, which the listener announces by key and never as a removal.
        harness.callDriver.remove()
        runCurrent()

        repeat(2) {
            harness.btu.press(FunctionId.NEXT)
            runCurrent()
        }

        val painted = harness.btu.baseFrames().last().payload
        assertTrue("the rider has to be on the phone entry", painted.containsUtf8("Phone"))

        val content = decodeMenu(painted)
        assertEquals("the caller has to be gone", "", content.line1)
        assertEquals("and the status line with it", "", content.line2)
    }
}
