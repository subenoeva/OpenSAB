package com.opensab.cluster.nav
import com.opensab.cluster.NavigationAction
import com.opensab.cluster.ResumeBehavior
import com.opensab.cluster.mirror.MirrorAction
import com.opensab.cluster.mirror.MirrorActionToken
import com.opensab.cluster.mirror.MirrorSlot
import com.opensab.cluster.text.ClusterTextResolver
import com.opensab.cluster.text.englishClusterText
import com.opensab.domain.music.RecordingVolumeSource
import com.opensab.domain.nav.ManeuverType
import com.opensab.domain.nav.NavInstruction
import com.opensab.domain.nav.RecordingNavigationManager
import com.opensab.domain.speech.SpeechArg
import com.opensab.domain.speech.SpeechRequest
import com.opensab.domain.speech.SpeechToken
import com.opensab.domain.speech.SpeechWord
import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.IconId
import com.opensab.sab.protocol.projection.ProjectionFrame
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class NavigationPageProviderTest {

    /**
     * One entry per branch of `onInput` that does something. `VOLUME_MUTE` sits on `RIGHT` because `LEFT` already carries `BACK`.
     */
    private val expectedActions = listOf(
        MirrorAction(
            token = MirrorActionToken.REPEAT_MANEUVER,
            functionId = FunctionId.SELECT,
            slot = MirrorSlot.PRIMARY
        ),
        MirrorAction(
            token = MirrorActionToken.GO_BACK,
            functionId = FunctionId.BACK,
            slot = MirrorSlot.LEFT
        ),
        MirrorAction(
            token = MirrorActionToken.VOLUME_UP,
            functionId = FunctionId.PREVIOUS,
            slot = MirrorSlot.UP,
            aliases = setOf(FunctionId.VOLUME_UP)
        ),
        MirrorAction(
            token = MirrorActionToken.VOLUME_DOWN,
            functionId = FunctionId.NEXT,
            slot = MirrorSlot.DOWN,
            aliases = setOf(FunctionId.VOLUME_DOWN)
        ),
        MirrorAction(
            token = MirrorActionToken.VOLUME_MUTE,
            functionId = FunctionId.VOLUME_MUTE,
            slot = MirrorSlot.RIGHT
        ),
        MirrorAction(
            token = MirrorActionToken.OPEN_ROOT_MENU,
            functionId = FunctionId.MENU,
            slot = MirrorSlot.HEADER
        )
    )

    @Test
    fun `renders active navigation guidance with turn maneuver, distance, and street`() {
        val navManager = RecordingNavigationManager()
        val volumeSource = RecordingVolumeSource()
        val provider = NavigationPageProvider(navManager.currentInstruction, englishClusterText, navManager, volumeSource)

        navManager.state.value = NavInstruction(
            title = "Turn right",
            streetName = "Main Street",
            distanceFormatted = "300 m",
            maneuver = ManeuverType.TURN_RIGHT
        )

        val frame = provider.render(requestId = 101) as ProjectionFrame.LiveScreen
        assertEquals(101, frame.requestId)
        assertEquals("Nav", frame.headerText)
        assertEquals(ColorId.GREEN, frame.headerBgColorId)
        assertEquals(ColorId.GREEN, frame.bodyBgColorId)
        assertEquals(ColorId.GREEN_DARK, frame.contentBgColorId)
        assertEquals("300 m", frame.contentTextLine1)
        assertEquals("Main Street", frame.contentTextLine2)
        // The street line takes the place of the icon: a live screen carries one or the other.
        assertEquals(IconId.IC_NONE, frame.contentIconId)
        assertEquals(IconId.LIVE_CANCEL_ONE_BUTTON, frame.leftActionContentIconId)
        assertEquals(IconId.LIVE_RETRY, frame.rightActionContentIconId)
    }

    @Test
    fun `renders standby screen when no navigation instruction is active`() {
        val navManager = RecordingNavigationManager()
        val provider = NavigationPageProvider(navManager.currentInstruction, englishClusterText, navManager)

        val frame = provider.render(requestId = 102) as ProjectionFrame.LiveScreen
        assertEquals(102, frame.requestId)
        assertEquals("Navigation", frame.headerText)
        assertEquals(ColorId.GREEN, frame.headerBgColorId)
        assertEquals(IconId.NAVIGATION, frame.contentIconId)
        assertEquals("No Navigation", frame.contentTextLine1)
        assertEquals("", frame.contentTextLine2)
    }

    @Test
    fun `back button pops back to root menu`() {
        val navManager = RecordingNavigationManager()
        val provider = NavigationPageProvider(navManager.currentInstruction, englishClusterText, navManager)

        val action = provider.onInput(FunctionId.BACK)
        assertEquals(NavigationAction.Pop, action)
    }

    @Test
    fun `menu button resets back to root menu`() {
        val navManager = RecordingNavigationManager()
        val provider = NavigationPageProvider(navManager.currentInstruction, englishClusterText, navManager)

        val action = provider.onInput(FunctionId.MENU)
        assertEquals(NavigationAction.Reset, action)
    }

    @Test
    fun `select button triggers repeat maneuver on navigationManager and returns none`() {
        val navManager = RecordingNavigationManager()
        val provider = NavigationPageProvider(navManager.currentInstruction, englishClusterText, navManager)

        val action = provider.onInput(FunctionId.SELECT)
        assertEquals(NavigationAction.None, action)
        assertEquals(1, navManager.repeatManeuverCalls)
    }

    @Test
    fun `previous and volume up buttons raise volume`() {
        val navManager = RecordingNavigationManager()
        val volumeSource = RecordingVolumeSource()
        val provider = NavigationPageProvider(navManager.currentInstruction, englishClusterText, navManager, volumeSource)

        provider.onInput(FunctionId.PREVIOUS)
        assertEquals(1, volumeSource.raiseCalls)

        provider.onInput(FunctionId.VOLUME_UP)
        assertEquals(2, volumeSource.raiseCalls)
    }

    @Test
    fun `next and volume down buttons lower volume`() {
        val navManager = RecordingNavigationManager()
        val volumeSource = RecordingVolumeSource()
        val provider = NavigationPageProvider(navManager.currentInstruction, englishClusterText, navManager, volumeSource)

        provider.onInput(FunctionId.NEXT)
        assertEquals(1, volumeSource.lowerCalls)

        provider.onInput(FunctionId.VOLUME_DOWN)
        assertEquals(2, volumeSource.lowerCalls)
    }

    @Test
    fun `volume mute button toggles mute`() {
        val navManager = RecordingNavigationManager()
        val volumeSource = RecordingVolumeSource()
        val provider = NavigationPageProvider(navManager.currentInstruction, englishClusterText, navManager, volumeSource)

        provider.onInput(FunctionId.VOLUME_MUTE)
        assertEquals(1, volumeSource.muteCalls)
    }

    @Test
    fun `mirror declares one zone per input branch that acts, with the volume aliases`() {
        val navManager = RecordingNavigationManager()
        val volumeSource = RecordingVolumeSource()
        val provider = NavigationPageProvider(navManager.currentInstruction, englishClusterText, navManager, volumeSource)

        navManager.state.value = NavInstruction(
            title = "Turn right",
            streetName = "Main Street",
            distanceFormatted = "300 m",
            maneuver = ManeuverType.TURN_RIGHT
        )

        val mirror = provider.mirror()
        assertEquals("NavigationPage", mirror.pageName)
        assertEquals(expectedActions, mirror.actions)
    }

    @Test
    fun `mirror declares the same zones in standby, where every branch still acts`() {
        val navManager = RecordingNavigationManager()
        val volumeSource = RecordingVolumeSource()
        val provider = NavigationPageProvider(navManager.currentInstruction, englishClusterText, navManager, volumeSource)

        val mirror = provider.mirror()
        assertEquals("NavigationPage", mirror.pageName)
        assertEquals(expectedActions, mirror.actions)
    }

    @Test
    fun `retains state on resume`() {
        val navManager = RecordingNavigationManager()
        val provider = NavigationPageProvider(navManager.currentInstruction, englishClusterText, navManager)

        assertEquals(ResumeBehavior.RETAIN, provider.onResumeBehavior)
        assertEquals("NavigationPage", provider.name)
    }

    // -------------------------------------------------------------------------------------------
    // Copy
    // -------------------------------------------------------------------------------------------

    /**
     * The page names the line; somebody else says it in a language. What this checks is not the
     * translation — that lives in `values-es/` and is checked over the XML — but that the provider
     * does not invent the string, which is the half a Spanish phone would show in English.
     *
     * Guidance wears the short header and standby says the whole word, so the two are two tokens
     * and not one abbreviated at the last moment.
     */
    @Test
    fun `should take the guidance header from the resolver`() {
        val navManager = RecordingNavigationManager()
        val provider = NavigationPageProvider(
            navInstruction = navManager.currentInstruction,
            copy = { "[${it.name}]" },
            navigationManager = navManager
        )
        navManager.state.value = NavInstruction(
            title = "Turn right",
            streetName = "Main Street",
            distanceFormatted = "300 m",
            maneuver = ManeuverType.TURN_RIGHT
        )

        val frame = provider.render(requestId = 1) as ProjectionFrame.LiveScreen

        assertEquals("[HEADER_NAV_SHORT]", frame.headerText)
        assertEquals("300 m", frame.contentTextLine1)
    }

    @Test
    fun `should take the standby lines from the resolver`() {
        val navManager = RecordingNavigationManager()
        val provider = NavigationPageProvider(
            navInstruction = navManager.currentInstruction,
            copy = { "[${it.name}]" },
            navigationManager = navManager
        )

        val frame = provider.render(requestId = 2) as ProjectionFrame.LiveScreen

        assertEquals("[HEADER_NAVIGATION]", frame.headerText)
        assertEquals("[EMPTY_NAVIGATION]", frame.contentTextLine1)
    }

    private fun maneuverPart(arg: SpeechArg) = SpeechRequest(SpeechToken.NAV_MANEUVER, arg)

    /** SELECT on the navigation page repeats the current maneuver out loud. */
    @Test
    fun `select says the current maneuver distance first then the turn then the street`() = runTest {
        val navManager = RecordingNavigationManager()
        navManager.updateInstruction(
            NavInstruction(
                title = "250 m",
                streetName = "Calle Mayor",
                distanceFormatted = "250 m",
                maneuver = ManeuverType.TURN_LEFT
            )
        )
        val provider = NavigationPageProvider(navManager.currentInstruction, englishClusterText, navManager)

        provider.onInput(FunctionId.SELECT)

        assertEquals(
            listOf(
                maneuverPart(SpeechArg.Text("250 m")),
                maneuverPart(SpeechArg.Word(SpeechWord.NAV_TURN_LEFT)),
                maneuverPart(SpeechArg.Text("Calle Mayor"))
            ),
            provider.speech.first()
        )
    }

    /** A missing piece is left out rather than said as an empty pause. */
    @Test
    fun `select skips an unknown maneuver and a blank street`() = runTest {
        val navManager = RecordingNavigationManager()
        navManager.updateInstruction(
            NavInstruction(
                title = "1.2 km",
                streetName = "",
                distanceFormatted = "1.2 km",
                maneuver = ManeuverType.UNKNOWN
            )
        )
        val provider = NavigationPageProvider(navManager.currentInstruction, englishClusterText, navManager)

        provider.onInput(FunctionId.SELECT)

        assertEquals(listOf(maneuverPart(SpeechArg.Text("1.2 km"))), provider.speech.first())
    }

    /** The new maneuvers reuse the words the page already has. */
    @Test
    fun `select says the word of the family for the maneuvers the arrow brings`() = runTest {
        val expected = mapOf(
            ManeuverType.ROUNDABOUT_SLIGHT_LEFT to SpeechWord.NAV_ROUNDABOUT,
            ManeuverType.ROUNDABOUT_UTURN_CW to SpeechWord.NAV_ROUNDABOUT,
            ManeuverType.UTURN_RIGHT to SpeechWord.NAV_UTURN,
            ManeuverType.FORK_RIGHT to SpeechWord.NAV_TURN_SLIGHT_RIGHT,
            ManeuverType.RAMP_LEFT to SpeechWord.NAV_TURN_SLIGHT_LEFT,
            ManeuverType.DEPART to SpeechWord.NAV_STRAIGHT,
            ManeuverType.DESTINATION_RIGHT to SpeechWord.NAV_DESTINATION
        )
        expected.forEach { (maneuver, word) ->
            val navManager = RecordingNavigationManager()
            navManager.updateInstruction(
                NavInstruction(title = "", streetName = "", distanceFormatted = "100 m", maneuver = maneuver)
            )
            val provider = NavigationPageProvider(navManager.currentInstruction, englishClusterText, navManager)

            provider.onInput(FunctionId.SELECT)

            assertEquals(
                "$maneuver",
                listOf(maneuverPart(SpeechArg.Text("100 m")), maneuverPart(SpeechArg.Word(word))),
                provider.speech.first()
            )
        }
    }

    @Test
    fun `select without guidance says nothing`() = runTest {
        val navManager = RecordingNavigationManager()
        val provider = NavigationPageProvider(navManager.currentInstruction, englishClusterText, navManager)

        provider.onInput(FunctionId.SELECT)

        assertNull(withTimeoutOrNull(1.seconds) { provider.speech.first() })
    }

    /** Mounting the page is not a request to hear it: only SELECT speaks. */
    @Test
    fun `mounting the page says nothing`() = runTest {
        val navManager = RecordingNavigationManager()
        navManager.updateInstruction(
            NavInstruction("250 m", "Calle Mayor", "250 m", ManeuverType.TURN_RIGHT)
        )
        val provider = NavigationPageProvider(navManager.currentInstruction, englishClusterText, navManager)

        assertNull(withTimeoutOrNull(1.seconds) { provider.speech.first() })
    }
}
