package com.opensab.app.e2e

import com.opensab.cluster.text.ClusterTextResolver
import com.opensab.cluster.text.ClusterToken
import com.opensab.domain.message.MessageNotification
import com.opensab.domain.message.MessageNotificationEvent
import com.opensab.domain.message.MessageReplyCommands
import com.opensab.domain.speech.RecognitionFailure
import com.opensab.sab.protocol.assignment.FunctionId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cluster copy localization across engine, page, preset, codec and wire simulator. */
@OptIn(ExperimentalCoroutinesApi::class)
class ClusterI18nE2eTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private class StubReplyCommands : MessageReplyCommands {
        override suspend fun reply(text: String): Boolean = true
    }

    private fun whatsApp(reply: MessageReplyCommands = StubReplyCommands()) = MessageNotificationEvent.Posted(
        notification = MessageNotification(
            packageName = "com.whatsapp",
            key = "0|com.whatsapp|1|null|10123",
            title = "Elena",
            text = "Are you far",
            conversationTitle = null,
            isGroupConversation = false,
            isGroupSummary = false,
            isOngoing = false,
            postTimeMillis = 1_700_000_000_000L
        ),
        reply = reply
    )

    /**
     * The whole chain in one language: engine, page, preset, codec and wire. Nothing here is a
     * mock of the thing being measured — the headers are read back out of the bytes the simulator
     * received, which is the only place where "the cluster is in Spanish" is actually true.
     */
    @Test
    fun `should paint the whole carousel in the language it was given`() = testScope.runTest {
        val spanish = mapOf(
            ClusterToken.HEADER_WEATHER to "Meteorología",
            ClusterToken.HEADER_NAVIGATION to "Navegación",
            ClusterToken.HEADER_PHONE to "Teléfono",
            ClusterToken.HEADER_MESSAGES to "Mensajes",
            ClusterToken.HEADER_MUSIC to "Música",
            ClusterToken.HEADER_VOLUME to "Volumen"
        )
        val harness = SessionHarness(
            backgroundScope,
            clusterResolver = ClusterTextResolver { spanish[it] ?: it.name }
        )
        harness.connect().getOrThrow()
        harness.handOverScreen()

        val painted = buildList {
            add(harness.btu.baseFrames().last().payload)
            repeat(5) {
                harness.btu.press(FunctionId.NEXT)
                runCurrent()
                add(harness.btu.baseFrames().last().payload)
            }
        }

        assertEquals(
            emptyList<String>(),
            spanish.values.filterIndexed { index, word -> !painted[index].containsUtf8(word) }
        )
    }

    /**
     * A recognition failure in the reply page: the frame reaching the simulator carries the
     * localized error line in Spanish («Sin red»).
     */
    @Test
    fun `should paint a listen failure in the language it was given`() = testScope.runTest {
        val spanish = mapOf(
            ClusterToken.HEADER_MESSAGES to "Mensajes",
            ClusterToken.LISTEN_NETWORK to "Sin red"
        )
        val harness = SessionHarness(
            backgroundScope,
            clusterResolver = ClusterTextResolver { spanish[it] ?: it.name }
        )
        harness.connect().getOrThrow()
        harness.handOverScreen()
        runCurrent()

        harness.messageBus.post(whatsApp())
        runCurrent()

        // Select on prompt opens reading
        harness.btu.press(FunctionId.SELECT)
        runCurrent()

        harness.recognizer.willFail(RecognitionFailure.NETWORK)

        // Select on reading opens reply dictation
        harness.btu.press(FunctionId.SELECT)
        runCurrent()

        val lastFramePayload = harness.btu.baseFrames().last().payload
        assertTrue(
            "the cluster payload must contain 'Sin red'",
            lastFramePayload.containsUtf8("Sin red")
        )
    }
}
