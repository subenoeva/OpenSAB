package com.opensab.cluster
import com.opensab.cluster.call.CallDialPageProvider
import com.opensab.cluster.call.CallMenuPageProvider
import com.opensab.cluster.call.CallPageProvider
import com.opensab.cluster.call.DialSeed
import com.opensab.cluster.message.MessageComposePageProvider
import com.opensab.cluster.message.MessageContactSelectPageProvider
import com.opensab.cluster.message.MessageMenuPageProvider
import com.opensab.cluster.message.MessagePageProvider
import com.opensab.cluster.message.MessagePromptPageProvider
import com.opensab.cluster.message.MessageReplyPageProvider
import com.opensab.cluster.mirror.MirrorAction
import com.opensab.cluster.mirror.MirrorPage
import com.opensab.cluster.mirror.MirrorSlot
import com.opensab.cluster.music.MusicErrorPageProvider
import com.opensab.cluster.music.MusicLoadingPageProvider
import com.opensab.cluster.music.MusicPageProvider
import com.opensab.cluster.music.VolumePageProvider
import com.opensab.cluster.music.volumeControlOn
import com.opensab.cluster.nav.NavMenuPageProvider
import com.opensab.cluster.nav.NavVoiceSearchPageProvider
import com.opensab.cluster.nav.NavigationPageProvider
import com.opensab.cluster.rootmenu.RootMenuPageProvider
import com.opensab.cluster.text.englishClusterText
import com.opensab.cluster.text.englishWeatherCaptions
import com.opensab.cluster.weather.WeatherPageProvider
import com.opensab.domain.call.CallInfo
import com.opensab.domain.call.MockCallDialer
import com.opensab.domain.call.RecordingTelecomAdapter
import com.opensab.domain.contacts.CallTarget
import com.opensab.domain.contacts.MockCallMenuAccess
import com.opensab.domain.contacts.MockContactDirectory
import com.opensab.domain.contacts.PhoneKind
import com.opensab.domain.message.MessageInfo
import com.opensab.domain.message.MockMessageMenuAccess
import com.opensab.domain.message.MockMessageSource
import com.opensab.domain.message.MockSmsSender
import com.opensab.domain.music.MusicState
import com.opensab.domain.music.PlaybackStatus
import com.opensab.domain.music.RecordingMusicManager
import com.opensab.domain.music.RecordingVolumeSource
import com.opensab.domain.music.TrackMetadata
import com.opensab.domain.music.VolumeLevel
import com.opensab.domain.nav.FakeDeviceUnlockSource
import com.opensab.domain.nav.ManeuverType
import com.opensab.domain.nav.MockNavigationLauncher
import com.opensab.domain.nav.NavInstruction
import com.opensab.domain.nav.RecordingNavigationManager
import com.opensab.domain.speech.MockSpeechRecognizer
import com.opensab.domain.weather.MockWeatherSource
import com.opensab.domain.weather.WeatherConditionType
import com.opensab.domain.weather.WeatherInfo
import com.opensab.domain.weather.WmoCaption
import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.ProjectionFrame
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.fail
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * A provider instance together with the exact doubles it was wired with.
 *
 * The pairing is what makes a probe readable: `totalCalls` only answers "this press did something"
 * if the counters belong to the very instance that received the press.
 */
data class MirrorProbe(
    val provider: PageProvider,
    val doubles: RecordingDoubles
)

/**
 * One canonical state of one provider, as a factory rather than an instance.
 *
 * `onInput` mutates page state — `selectionIndex`, `togglePlayPause` — so every probe builds a
 * fresh provider: each [FunctionId] is pressed on an instance nobody has touched, and the
 * [MirrorPage] under test is captured from an equally fresh one.
 */
data class MirrorScenario(
    val label: String,
    val probe: () -> MirrorProbe
)

/**
 * The four invariants of `PageProvider.mirror()`, checked over the canonical scenarios of all seven
 * providers.
 *
 * There is no `Parameterized` runner in this project, so each invariant is a single test that walks
 * the scenario list, collects every breach and fails once at the end naming the scenario and the
 * action that broke it.
 */
class PageProviderMirrorContractTest {

    // ---------------------------------------------------------------------------------------
    // Canonical scenarios
    // ---------------------------------------------------------------------------------------

    private val scenarios: List<MirrorScenario> = listOf(

        MirrorScenario("RootMenuPageProvider / entry with a page") {
            MirrorProbe(
                provider = RootMenuPageProvider(entries = allRootMenuEntries(), pageFactory = { stubPage() }, copy = englishClusterText),
                doubles = RecordingDoubles()
            )
        },

        MirrorScenario("RootMenuPageProvider / entry that cannot be entered") {
            MirrorProbe(
                provider = RootMenuPageProvider(
                    entries = allRootMenuEntries(),
                    currentWeather = MutableStateFlow(null),
                    pageFactory = { stubPage() },
                    copy = englishClusterText
                ),
                doubles = RecordingDoubles()
            )
        },

        MirrorScenario("MusicPageProvider / PLAYING") {
            val music = RecordingMusicManager()
            music.state.value = MusicState(
                metadata = TrackMetadata(title = "Bohemian Rhapsody", artist = "Queen"),
                status = PlaybackStatus.PLAYING
            )
            MirrorProbe(
                provider = MusicPageProvider(music.currentState, englishClusterText, volumeControlOn, music),
                doubles = RecordingDoubles(music = music)
            )
        },

        MirrorScenario("MusicPageProvider / PAUSED") {
            val music = RecordingMusicManager()
            music.state.value = MusicState(
                metadata = TrackMetadata(title = "Bohemian Rhapsody", artist = "Queen"),
                status = PlaybackStatus.PAUSED
            )
            MirrorProbe(
                provider = MusicPageProvider(music.currentState, englishClusterText, volumeControlOn, music),
                doubles = RecordingDoubles(music = music)
            )
        },

        MirrorScenario("VolumePageProvider / normal level") {
            val volume = RecordingVolumeSource()
            volume.state.value = VolumeLevel(percent = 55, isMuted = false)
            MirrorProbe(
                provider = VolumePageProvider(volume.level, englishClusterText, volumeControlOn, volume),
                doubles = RecordingDoubles(volume = volume)
            )
        },

        MirrorScenario("VolumePageProvider / muted") {
            val volume = RecordingVolumeSource()
            volume.state.value = VolumeLevel(percent = 55, isMuted = true)
            MirrorProbe(
                provider = VolumePageProvider(volume.level, englishClusterText, volumeControlOn, volume),
                doubles = RecordingDoubles(volume = volume)
            )
        },

        MirrorScenario("NavigationPageProvider / with instruction") {
            val navigation = RecordingNavigationManager()
            val volume = RecordingVolumeSource()
            navigation.state.value = NavInstruction(
                title = "Turn right",
                streetName = "Main Street",
                distanceFormatted = "300 m",
                maneuver = ManeuverType.TURN_RIGHT
            )
            MirrorProbe(
                provider = NavigationPageProvider(
                    navigation.currentInstruction,
                    englishClusterText,
                    navigation,
                    volume
                ),
                doubles = RecordingDoubles(volume = volume, navigation = navigation)
            )
        },

        MirrorScenario("NavigationPageProvider / standby") {
            val navigation = RecordingNavigationManager()
            val volume = RecordingVolumeSource()
            MirrorProbe(
                provider = NavigationPageProvider(
                    navigation.currentInstruction,
                    englishClusterText,
                    navigation,
                    volume
                ),
                doubles = RecordingDoubles(volume = volume, navigation = navigation)
            )
        },

        MirrorScenario("WeatherPageProvider / with forecast") {
            val weather = MockWeatherSource()
            val volume = RecordingVolumeSource()
            weather.updateWeather(
                WeatherInfo(
                    locationName = "Santiago",
                    temperature = 14,
                    caption = WmoCaption.RAIN,
                    type = WeatherConditionType.RAIN
                )
            )
            MirrorProbe(
                provider = WeatherPageProvider(weather.currentWeather, englishClusterText, englishWeatherCaptions, weather, volume),
                doubles = RecordingDoubles(volume = volume, weather = weather)
            )
        },

        MirrorScenario("WeatherPageProvider / standby") {
            val weather = MockWeatherSource()
            val volume = RecordingVolumeSource()
            MirrorProbe(
                provider = WeatherPageProvider(weather.currentWeather, englishClusterText, englishWeatherCaptions, weather, volume),
                doubles = RecordingDoubles(volume = volume, weather = weather)
            )
        },

        MirrorScenario("MessagePageProvider / with message") {
            val message = MockMessageSource()
            val volume = RecordingVolumeSource()
            message.setMessage(MessageInfo(sender = "Ana", body = "On my way"))
            MirrorProbe(
                provider = MessagePageProvider(message.currentMessage, englishClusterText, message, volumeSource = volume),
                doubles = RecordingDoubles(volume = volume, message = message)
            )
        },

        MirrorScenario("MessagePageProvider / with a message that can be replied to") {
            val message = MockMessageSource()
            val volume = RecordingVolumeSource()
            message.setMessage(MessageInfo(sender = "Ana", body = "On my way"))
            message.setCanReply(true)
            MirrorProbe(
                provider = MessagePageProvider(
                    currentMessage = message.currentMessage,
                    copy = englishClusterText,
                    messageSource = message,
                    canReply = message.canReply,
                    volumeSource = volume,
                    replyPageFactory = { stubPage() }
                ),
                doubles = RecordingDoubles(volume = volume, message = message)
            )
        },

        MirrorScenario("MessagePageProvider / standby") {
            val message = MockMessageSource()
            val volume = RecordingVolumeSource()
            MirrorProbe(
                provider = MessagePageProvider(message.currentMessage, englishClusterText, message, volumeSource = volume),
                doubles = RecordingDoubles(volume = volume, message = message)
            )
        },

        /**
         * Only the face the page mounts in, because a probe is built and never driven: the other
         * six are reached through `work()`, and `MessageReplyPageProviderTest` sweeps all seven.
         */
        MirrorScenario("MessageReplyPageProvider / listening") {
            val message = MockMessageSource()
            val volume = RecordingVolumeSource()
            MirrorProbe(
                provider = MessageReplyPageProvider(
                    recognizer = MockSpeechRecognizer(),
                    messageSource = message,
                    sentLinger = MutableStateFlow(2.seconds),
                    copy = englishClusterText,
                    volumeSource = volume
                ),
                doubles = RecordingDoubles(volume = volume, message = message)
            )
        },

        MirrorScenario("MessagePromptPageProvider / unread message") {
            val message = MockMessageSource()
            val volume = RecordingVolumeSource()
            message.setMessage(
                MessageInfo(sender = "Carlos", body = "Llego en 10", appName = "WhatsApp")
            )
            MirrorProbe(
                provider = MessagePromptPageProvider(
                    message.currentMessage,
                    englishClusterText,
                    message,
                    volume
                ) { stubPage() },
                doubles = RecordingDoubles(volume = volume, message = message)
            )
        },

        MirrorScenario("MessagePromptPageProvider / standby") {
            val message = MockMessageSource()
            val volume = RecordingVolumeSource()
            MirrorProbe(
                provider = MessagePromptPageProvider(
                    message.currentMessage,
                    englishClusterText,
                    message,
                    volume
                ) { stubPage() },
                doubles = RecordingDoubles(volume = volume, message = message)
            )
        },

        MirrorScenario("CallPageProvider / RINGING") {
            val telecom = RecordingTelecomAdapter()
            val volume = RecordingVolumeSource()
            telecom.setIncomingCall(incomingCall())
            MirrorProbe(
                provider = CallPageProvider(telecom.currentCall, englishClusterText, telecom, volume),
                doubles = RecordingDoubles(volume = volume, telecom = telecom)
            )
        },

        MirrorScenario("CallPageProvider / ACTIVE") {
            val telecom = RecordingTelecomAdapter()
            val volume = RecordingVolumeSource()
            telecom.setIncomingCall(incomingCall())
            // The mock only reaches ACTIVE through acceptCall; the setup press must not be
            // mistaken for one the page made.
            telecom.acceptCall()
            telecom.resetCounts()
            MirrorProbe(
                provider = CallPageProvider(telecom.currentCall, englishClusterText, telecom, volume),
                doubles = RecordingDoubles(volume = volume, telecom = telecom)
            )
        },

        MirrorScenario("CallPageProvider / standby") {
            val telecom = RecordingTelecomAdapter()
            val volume = RecordingVolumeSource()
            MirrorProbe(
                provider = CallPageProvider(telecom.currentCall, englishClusterText, telecom, volume),
                doubles = RecordingDoubles(volume = volume, telecom = telecom)
            )
        },

        /**
         * Only the face the page mounts in with a voice seed, because a probe is built and never
         * driven: the other faces are reached through `work()`, and `CallDialPageProviderTest`
         * sweeps all seven.
         */
        MirrorScenario("CallDialPageProvider / Listening") {
            val volume = RecordingVolumeSource()
            val directory = MockContactDirectory()
            val dialer = MockCallDialer()
            MirrorProbe(
                provider = CallDialPageProvider(
                    seed = DialSeed.Voice,
                    recognizer = MockSpeechRecognizer(),
                    directory = directory,
                    dialer = dialer,
                    livePageFactory = { stubPage() },
                    copy = englishClusterText,
                    volumeSource = volume,
                    activeCall = MutableStateFlow<CallInfo?>(null)
                ),
                doubles = RecordingDoubles(volume = volume, contacts = directory, dialer = dialer)
            )
        },

        MirrorScenario("CallMenuPageProvider / shut") {
            val access = MockCallMenuAccess()
            MirrorProbe(
                provider = CallMenuPageProvider(access, { stubPage() }, englishClusterText),
                doubles = RecordingDoubles(access = access)
            )
        },

        MirrorScenario("MessageMenuPageProvider / shut") {
            val access = MockMessageMenuAccess()
            MirrorProbe(
                provider = MessageMenuPageProvider(access, { stubPage() }, englishClusterText),
                doubles = RecordingDoubles(messageAccess = access)
            )
        },

        MirrorScenario("MessageContactSelectPageProvider / Listening") {
            val volume = RecordingVolumeSource()
            val directory = MockContactDirectory()
            MirrorProbe(
                provider = MessageContactSelectPageProvider(
                    seed = DialSeed.Voice,
                    recognizer = MockSpeechRecognizer(),
                    directory = directory,
                    composePageFactory = { stubPage() },
                    copy = englishClusterText,
                    volumeSource = volume
                ),
                doubles = RecordingDoubles(volume = volume, contacts = directory)
            )
        },

        MirrorScenario("MessageComposePageProvider / Listening") {
            val volume = RecordingVolumeSource()
            val sender = MockSmsSender()
            val target = CallTarget("Bob", "+123456789", PhoneKind.MOBILE)
            MirrorProbe(
                provider = MessageComposePageProvider(
                    target = target,
                    recognizer = MockSpeechRecognizer(),
                    smsSender = sender,
                    sentLinger = MutableStateFlow(2.seconds),
                    copy = englishClusterText,
                    volumeSource = volume
                ),
                doubles = RecordingDoubles(volume = volume, smsSender = sender)
            )
        },

        MirrorScenario("NavMenuPageProvider / voice search") {
            MirrorProbe(
                provider = NavMenuPageProvider({ stubPage() }, englishClusterText),
                doubles = RecordingDoubles()
            )
        },

        MirrorScenario("NavVoiceSearchPageProvider / listening") {
            val volume = RecordingVolumeSource()
            val launcher = MockNavigationLauncher()
            MirrorProbe(
                provider = NavVoiceSearchPageProvider(
                    recognizer = MockSpeechRecognizer(),
                    launcher = launcher,
                    unlockSource = FakeDeviceUnlockSource(),
                    navInstructionFlow = MutableStateFlow(null),
                    livePageFactory = { stubPage() },
                    copy = englishClusterText,
                    volumeSource = volume
                ),
                doubles = RecordingDoubles(volume = volume, launcher = launcher)
            )
        },

        MirrorScenario("MusicLoadingPageProvider") {
            val music = RecordingMusicManager()
            MirrorProbe(
                provider = MusicLoadingPageProvider(
                    musicState = music.currentState,
                    copy = englishClusterText,
                    volumeControl = volumeControlOn,
                    livePageFactory = { stubPage() },
                    errorPageFactory = { stubPage() },
                    musicManager = music
                ),
                doubles = RecordingDoubles(music = music)
            )
        },

        MirrorScenario("MusicErrorPageProvider") {
            val volume = RecordingVolumeSource()
            val music = RecordingMusicManager()
            MirrorProbe(
                provider = MusicErrorPageProvider(
                    copy = englishClusterText,
                    volumeControl = volumeControlOn,
                    volumeSource = volume,
                    musicManager = music
                ),
                doubles = RecordingDoubles(volume = volume, music = music)
            )
        }
    )

    // ---------------------------------------------------------------------------------------
    // Invariant 1 — no dead actions
    // ---------------------------------------------------------------------------------------

    /**
     * Every enabled [MirrorAction] must do something when its `functionId` goes through `onInput`.
     * Disabled actions are exempt on purpose: they exist to hold their slot while showing the rider
     * that right now they lead nowhere.
     */
    @Test
    fun `enabled action always has an effect`() {
        val failures = mutableListOf<String>()

        for (scenario in scenarios) {
            val page = scenario.probe().provider.mirror()
            for (action in page.actions.filter { it.enabled }) {
                val probe = scenario.probe()
                if (!hasEffect(probe, action.functionId)) {
                    failures += "${scenario.label}: enabled action ${describe(action)} is dead — " +
                        "onInput(${action.functionId}) returned None and no double recorded a call"
                }
            }
        }

        assertNoFailures("dead mirror action(s)", failures)
    }

    /**
     * Invariant 1 exempts disabled actions, and an exemption no scenario exercises is an exemption
     * nobody has tested. This is what stops the list from quietly losing its last disabled action
     * the day someone tidies scenarios up.
     */
    @Test
    fun `the scenario list still covers a disabled action`() {
        val disabled = scenarios.filter { scenario ->
            scenario.probe().provider.mirror().actions.any { !it.enabled }
        }

        if (disabled.isEmpty()) {
            fail(
                "no scenario hands over a disabled action, so the exemption of invariant 1 is " +
                    "never exercised"
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Invariant 2 — no hidden actions
    // ---------------------------------------------------------------------------------------

    /**
     * Every [FunctionId] that has an effect must be reachable from the mirror, as the `functionId`
     * of exactly one action or inside its `aliases`. `MODE_SAB` and `MODE_HU` never arrive as a
     * press and are exempt.
     */
    @Test
    fun `no reachable function is hidden`() {
        val failures = mutableListOf<String>()
        val pressable = FunctionId.entries.filter {
            it != FunctionId.MODE_SAB && it != FunctionId.MODE_HU
        }

        for (scenario in scenarios) {
            val page = scenario.probe().provider.mirror()
            for (functionId in pressable) {
                if (!hasEffect(scenario.probe(), functionId)) continue

                val owners = page.actions.filter {
                    functionId == it.functionId || functionId in it.aliases
                }
                if (owners.size == 1) continue

                failures += if (owners.isEmpty()) {
                    "${scenario.label}: $functionId has an effect in onInput but no mirror action " +
                        "declares it — reachable from the handlebar, unreachable from the phone"
                } else {
                    "${scenario.label}: $functionId is declared by ${owners.size} actions " +
                        "(${owners.joinToString { describe(it) }}), expected exactly one"
                }
            }
        }

        assertNoFailures("hidden or ambiguous function(s)", failures)
    }

    // ---------------------------------------------------------------------------------------
    // Invariant 3 — no collisions
    // ---------------------------------------------------------------------------------------

    /** No [FunctionId] and no [MirrorSlot] may be claimed twice within the same page. */
    @Test
    fun `no collisions`() {
        val failures = mutableListOf<String>()

        for (scenario in scenarios) {
            val page = scenario.probe().provider.mirror()

            val claimedBy = mutableMapOf<FunctionId, MirrorAction>()
            for (action in page.actions) {
                for (functionId in listOf(action.functionId) + action.aliases) {
                    val previous = claimedBy.put(functionId, action)
                    if (previous != null) {
                        failures += "${scenario.label}: $functionId is claimed by both " +
                            "${describe(previous)} and ${describe(action)}"
                    }
                }
            }

            val slots: List<MirrorSlot> = page.actions.map { it.slot }
            val repeated = slots.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            for (slot in repeated) {
                val sharing = page.actions.filter { it.slot == slot }
                failures += "${scenario.label}: slot $slot is shared by " +
                    sharing.joinToString { describe(it) }
            }
        }

        assertNoFailures("collision(s)", failures)
    }

    // ---------------------------------------------------------------------------------------
    // Invariant 4 — no text drift
    // ---------------------------------------------------------------------------------------

    /**
     * The mirror shows the very strings the cluster received. Captured from the same instance with
     * no press in between, so any difference is drift and not a state change.
     *
     * Slot mapping:
     * `title` = `headerText`, `lines` = the non-empty content lines, `accent` = `headerBgColorId`.
     */
    @Test
    fun `no text drift`() {
        val failures = mutableListOf<String>()

        for (scenario in scenarios) {
            val provider = scenario.probe().provider
            val page = provider.mirror()

            when (val frame = provider.render(0)) {
                is ProjectionFrame.LiveScreen -> compareText(
                    label = scenario.label,
                    page = page,
                    headerText = frame.headerText,
                    line1 = frame.contentTextLine1,
                    line2 = frame.contentTextLine2,
                    headerBgColorId = frame.headerBgColorId,
                    failures = failures
                )

                is ProjectionFrame.MenuScreen -> compareText(
                    label = scenario.label,
                    page = page,
                    headerText = frame.headerText,
                    line1 = frame.contentTextLine1,
                    line2 = frame.contentTextLine2,
                    headerBgColorId = frame.headerBgColorId,
                    failures = failures
                )

                else -> failures += "${scenario.label}: render(0) returned " +
                    "${frame::class.simpleName}, which carries no header or content slots for the " +
                    "mirror to twin"
            }
        }

        assertNoFailures("text drift(s)", failures)
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /**
     * The operative reading of "this press did something": the page moved the navigation stack, or
     * one of its sources was asked to act.
     */
    private fun hasEffect(probe: MirrorProbe, functionId: FunctionId): Boolean {
        val navigation = probe.provider.onInput(functionId)
        return navigation != NavigationAction.None || probe.doubles.totalCalls > 0
    }

    private fun compareText(
        label: String,
        page: MirrorPage,
        headerText: String?,
        line1: String?,
        line2: String?,
        headerBgColorId: ColorId,
        failures: MutableList<String>
    ) {
        if (page.title != headerText) {
            failures += "$label: title ${quote(page.title)} does not match the frame headerText " +
                quote(headerText)
        }

        val expectedLines = listOfNotNull(line1, line2).filter { it.isNotEmpty() }
        if (page.lines != expectedLines) {
            failures += "$label: lines ${page.lines} do not match the frame content lines " +
                "$expectedLines"
        }

        if (page.accent != headerBgColorId) {
            failures += "$label: accent ${page.accent} does not match the frame headerBgColorId " +
                "$headerBgColorId"
        }
    }

    private fun describe(action: MirrorAction): String =
        "${action.token}[functionId=${action.functionId}, slot=${action.slot}]"

    private fun quote(text: String?): String = if (text == null) "null" else "\"$text\""

    private fun assertNoFailures(what: String, failures: List<String>) {
        if (failures.isEmpty()) return
        fail(
            "${failures.size} $what:\n" + failures.joinToString("\n") { "  - $it" }
        )
    }

    private fun stubPage(): PageProvider = StubPageProvider(
        name = "StubPage",
        frame = LiveScreenPresets.music(
            requestId = 0,
            header = "Music",
            title = "Stub",
            artist = "Stub",
            playing = false
        )
    )

    private fun incomingCall(): CallInfo = CallInfo(
        callerName = "Ana",
        callerNumber = "+34600000000"
    )
}
