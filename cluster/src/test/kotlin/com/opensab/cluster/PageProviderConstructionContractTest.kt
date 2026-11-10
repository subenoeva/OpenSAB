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
import com.opensab.cluster.music.MusicErrorPageProvider
import com.opensab.cluster.music.MusicLoadingPageProvider
import com.opensab.cluster.music.MusicPageProvider
import com.opensab.cluster.music.VolumePageProvider
import com.opensab.cluster.music.volumeControlOn
import com.opensab.cluster.nav.NavMenuPageProvider
import com.opensab.cluster.nav.NavVoiceSearchPageProvider
import com.opensab.cluster.nav.NavigationPageProvider
import com.opensab.cluster.rootmenu.RootMenuItem
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
import com.opensab.domain.music.RecordingMusicManager
import com.opensab.domain.music.RecordingVolumeSource
import com.opensab.domain.music.VolumeLevel
import com.opensab.domain.nav.FakeDeviceUnlockSource
import com.opensab.domain.nav.MockNavigationLauncher
import com.opensab.domain.nav.NavInstruction
import com.opensab.domain.nav.RecordingNavigationManager
import com.opensab.domain.speech.MockSpeechRecognizer
import com.opensab.domain.weather.MockWeatherSource
import com.opensab.domain.weather.WeatherInfo
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * One provider of the catalog, its recording doubles and its clock, as a factory: the point of this
 * harness is to build the same provider many times against the *same* witnesses.
 *
 * [sources] holds the very [MutableStateFlow] instances the provider was handed, because
 * `subscriptionCount` is what catches a constructor that starts collecting, and only the mutable
 * flow exposes it.
 */
data class ConstructionScenario(
    val label: String,
    val sources: List<MutableStateFlow<*>>,
    val doubles: RecordingDoubles,
    val clockReads: () -> Int,
    val build: () -> PageProvider
)

/**
 * **Constructing a page observes nothing.**
 *
 * `PageProvider.mirror()` runs on every projected frame and `render()` runs on every write, so a
 * provider whose constructor reads a clock, asks a source for something or subscribes to a flow
 * puts that work on the hot path of the engine — an `init { offerScript() }` once read the clock and
 * built forecast scripts only to throw them away. This harness keeps the next page from doing it
 * in silence.
 *
 * **What it forbids is observing, not allocating.** `WeatherPageProvider` builds a conflated
 * `Channel` in its constructor and all six carousel pages build their `redrawOn` flow there, and
 * that is what constructing anything costs. The three witnesses are all about what leaves the
 * object: [RecordingDoubles.totalCalls] for the one action each page can ask of its source,
 * `subscriptionCount` for a constructor that starts collecting, and an injected clock for the
 * provider that takes one.
 *
 * The clock binds where a clock can be injected, which today is `WeatherPageProvider` — the page
 * that had the defect and the only one whose output depends on the instant. The other two witnesses
 * cover all seven.
 *
 * Instead of a `Parameterized` runner (which would fail with N distinct red tests), each test
 * walks the scenario list manually to collect all breaches and report every failing provider in
 * a single assertion failure.
 */
class PageProviderConstructionContractTest {

    private fun scenarios(): List<ConstructionScenario> {
        val rootMusic = MutableStateFlow(MusicState())
        val rootNav = MutableStateFlow<NavInstruction?>(null)
        val rootCall = MutableStateFlow<CallInfo?>(null)
        val rootWeather = MutableStateFlow<WeatherInfo?>(null)
        val rootMessage = MutableStateFlow<MessageInfo?>(null)

        val musicState = MutableStateFlow(MusicState())
        val musicManager = RecordingMusicManager()

        val volumeLevel = MutableStateFlow(VolumeLevel())
        val volumeSource = RecordingVolumeSource()

        val navInstruction = MutableStateFlow<NavInstruction?>(null)
        val navigationManager = RecordingNavigationManager()
        val navVolume = RecordingVolumeSource()
        val navLauncher = MockNavigationLauncher()

        val currentWeather = MutableStateFlow<WeatherInfo?>(null)
        val weatherSource = MockWeatherSource()
        val weatherVolume = RecordingVolumeSource()
        var weatherClockReads = 0

        val currentMessage = MutableStateFlow<MessageInfo?>(null)
        val messageSource = MockMessageSource()
        val messageVolume = RecordingVolumeSource()

        val promptMessage = MutableStateFlow<MessageInfo?>(null)
        val promptSource = MockMessageSource()
        val promptVolume = RecordingVolumeSource()

        val replySource = MockMessageSource()
        val replyVolume = RecordingVolumeSource()
        val replyLinger = MutableStateFlow(2.seconds)

        val activeCall = MutableStateFlow<CallInfo?>(null)
        val telecom = RecordingTelecomAdapter()
        val callVolume = RecordingVolumeSource()

        val dialDirectory = MockContactDirectory()
        val dialDialer = MockCallDialer()
        val dialVolume = RecordingVolumeSource()

        val menuAccess = MockCallMenuAccess()
        val messageMenuAccess = MockMessageMenuAccess()

        val selectDirectory = MockContactDirectory()
        val selectVolume = RecordingVolumeSource()

        val composeSender = MockSmsSender()
        val composeVolume = RecordingVolumeSource()
        val composeLinger = MutableStateFlow(2.seconds)
        val composeTarget = CallTarget("Bob", "+123456789", PhoneKind.MOBILE)

        return listOf(
            ConstructionScenario("RootMenuPageProvider",
                sources = listOf(rootMusic, rootNav, rootCall, rootWeather, rootMessage),
                doubles = RecordingDoubles(),
                clockReads = { 0 }
            ) {
                RootMenuPageProvider(
                    entries = allRootMenuEntries(),
                    musicState = rootMusic,
                    navInstruction = rootNav,
                    activeCall = rootCall,
                    currentWeather = rootWeather,
                    currentMessage = rootMessage,
                    pageFactory = { stubPage() },
                    copy = englishClusterText
                )
            },

            ConstructionScenario("MusicPageProvider",
                sources = listOf(musicState),
                doubles = RecordingDoubles(music = musicManager),
                clockReads = { 0 }
            ) {
                MusicPageProvider(musicState, englishClusterText, volumeControlOn, musicManager)
            },

            ConstructionScenario("VolumePageProvider",
                sources = listOf(volumeLevel),
                doubles = RecordingDoubles(volume = volumeSource),
                clockReads = { 0 }
            ) {
                VolumePageProvider(volumeLevel, englishClusterText, volumeControlOn, volumeSource)
            },

            ConstructionScenario("NavigationPageProvider",
                sources = listOf(navInstruction),
                doubles = RecordingDoubles(volume = navVolume, navigation = navigationManager),
                clockReads = { 0 }
            ) {
                NavigationPageProvider(navInstruction, englishClusterText, navigationManager, navVolume)
            },

            ConstructionScenario("WeatherPageProvider",
                sources = listOf(currentWeather),
                doubles = RecordingDoubles(volume = weatherVolume, weather = weatherSource),
                clockReads = { weatherClockReads }
            ) {
                WeatherPageProvider(
                    currentWeather = currentWeather,
                    copy = englishClusterText,
                    captions = englishWeatherCaptions,
                    weatherSource = weatherSource,
                    volumeSource = weatherVolume,
                    nowMillis = {
                        weatherClockReads++
                        0L
                    }
                )
            },

            ConstructionScenario("MessagePageProvider",
                sources = listOf(currentMessage),
                doubles = RecordingDoubles(volume = messageVolume, message = messageSource),
                clockReads = { 0 }
            ) {
                MessagePageProvider(
                    currentMessage = currentMessage,
                    copy = englishClusterText,
                    messageSource = messageSource,
                    canReply = messageSource.canReply,
                    volumeSource = messageVolume,
                    replyPageFactory = { stubPage() }
                )
            },

            ConstructionScenario("MessagePromptPageProvider",
                sources = listOf(promptMessage),
                doubles = RecordingDoubles(volume = promptVolume, message = promptSource),
                clockReads = { 0 }
            ) {
                MessagePromptPageProvider(
                    currentMessage = promptMessage,
                    copy = englishClusterText,
                    messageSource = promptSource,
                    volumeSource = promptVolume
                ) { stubPage() }
            },

            /**
             * The one page of the catalogue whose constructor could plausibly open a microphone,
             * which is exactly why it is here: [MessageReplyPageProvider] listens from `work()` and
             * never from `init`, so building it observes nothing and opens nothing.
             */
            ConstructionScenario("MessageReplyPageProvider",
                sources = listOf(replyLinger),
                doubles = RecordingDoubles(volume = replyVolume, message = replySource),
                clockReads = { 0 }
            ) {
                MessageReplyPageProvider(
                    recognizer = MockSpeechRecognizer(),
                    messageSource = replySource,
                    sentLinger = replyLinger,
                    copy = englishClusterText,
                    volumeSource = replyVolume
                )
            },

            ConstructionScenario("CallPageProvider",
                sources = listOf(activeCall),
                doubles = RecordingDoubles(volume = callVolume, telecom = telecom),
                clockReads = { 0 }
            ) {
                CallPageProvider(activeCall, englishClusterText, telecom, callVolume)
            },

            /**
             * The other page that could plausibly open a microphone from a constructor, and does
             * not: [CallDialPageProvider] listens from `work()`, and the seeded variant does not
             * listen at all.
             */
            ConstructionScenario("CallDialPageProvider",
                sources = emptyList(),
                doubles = RecordingDoubles(
                    volume = dialVolume,
                    contacts = dialDirectory,
                    dialer = dialDialer
                ),
                clockReads = { 0 }
            ) {
                CallDialPageProvider(
                    seed = DialSeed.Voice,
                    recognizer = MockSpeechRecognizer(),
                    directory = dialDirectory,
                    dialer = dialDialer,
                    livePageFactory = { stubPage() },
                    copy = englishClusterText,
                    volumeSource = dialVolume,
                    activeCall = MutableStateFlow<CallInfo?>(null)
                )
            },

            ConstructionScenario("CallMenuPageProvider",
                sources = emptyList(),
                doubles = RecordingDoubles(access = menuAccess),
                clockReads = { 0 }
            ) {
                CallMenuPageProvider(menuAccess, { stubPage() }, englishClusterText)
            },

            ConstructionScenario("MessageMenuPageProvider",
                sources = emptyList(),
                doubles = RecordingDoubles(messageAccess = messageMenuAccess),
                clockReads = { 0 }
            ) {
                MessageMenuPageProvider(messageMenuAccess, { stubPage() }, englishClusterText)
            },

            ConstructionScenario("MessageContactSelectPageProvider",
                sources = emptyList(),
                doubles = RecordingDoubles(volume = selectVolume, contacts = selectDirectory),
                clockReads = { 0 }
            ) {
                MessageContactSelectPageProvider(
                    seed = DialSeed.Voice,
                    recognizer = MockSpeechRecognizer(),
                    directory = selectDirectory,
                    composePageFactory = { stubPage() },
                    copy = englishClusterText,
                    volumeSource = selectVolume
                )
            },

            ConstructionScenario("MessageComposePageProvider",
                sources = listOf(composeLinger),
                doubles = RecordingDoubles(volume = composeVolume, smsSender = composeSender),
                clockReads = { 0 }
            ) {
                MessageComposePageProvider(
                    target = composeTarget,
                    recognizer = MockSpeechRecognizer(),
                    smsSender = composeSender,
                    sentLinger = composeLinger,
                    copy = englishClusterText,
                    volumeSource = composeVolume
                )
            },

            ConstructionScenario("NavMenuPageProvider",
                sources = emptyList(),
                doubles = RecordingDoubles(),
                clockReads = { 0 }
            ) {
                NavMenuPageProvider({ stubPage() }, englishClusterText)
            },

            ConstructionScenario("NavVoiceSearchPageProvider",
                sources = listOf(navInstruction),
                doubles = RecordingDoubles(volume = navVolume, launcher = navLauncher),
                clockReads = { 0 }
            ) {
                NavVoiceSearchPageProvider(
                    recognizer = MockSpeechRecognizer(),
                    launcher = navLauncher,
                    unlockSource = FakeDeviceUnlockSource(),
                    navInstructionFlow = navInstruction,
                    livePageFactory = { stubPage() },
                    copy = englishClusterText,
                    volumeSource = navVolume
                )
            },

            ConstructionScenario("MusicLoadingPageProvider",
                sources = listOf(musicState),
                doubles = RecordingDoubles(music = musicManager),
                clockReads = { 0 }
            ) {
                MusicLoadingPageProvider(
                    musicState = musicState,
                    copy = englishClusterText,
                    volumeControl = volumeControlOn,
                    livePageFactory = { stubPage() },
                    errorPageFactory = { stubPage() },
                    musicManager = musicManager
                )
            },

            ConstructionScenario("MusicErrorPageProvider",
                sources = emptyList(),
                doubles = RecordingDoubles(volume = volumeSource, music = musicManager),
                clockReads = { 0 }
            ) {
                MusicErrorPageProvider(
                    copy = englishClusterText,
                    volumeControl = volumeControlOn,
                    volumeSource = volumeSource,
                    musicManager = musicManager
                )
            }
        )
    }

    @Test
    fun constructingAnyPageOfTheCatalogObservesNothing() {
        val failures = mutableListOf<String>()

        for (scenario in scenarios()) {
            repeat(BUILDS) { scenario.build() }

            if (scenario.doubles.totalCalls != 0) {
                failures += "${scenario.label}: $BUILDS constructions asked its sources for " +
                    "${scenario.doubles.totalCalls} thing(s)"
            }
            if (scenario.clockReads() != 0) {
                failures += "${scenario.label}: $BUILDS constructions read the clock " +
                    "${scenario.clockReads()} time(s)"
            }
        }

        assertNoFailures("provider(s) observing something while being constructed", failures)
    }

    @Test
    fun constructingAnyPageOfTheCatalogSubscribesToNoSource() {
        val failures = mutableListOf<String>()

        for (scenario in scenarios()) {
            repeat(BUILDS) { scenario.build() }

            scenario.sources.forEachIndexed { index, source ->
                if (source.subscriptionCount.value != 0) {
                    failures += "${scenario.label}: source #$index still has " +
                        "${source.subscriptionCount.value} subscriber(s) after $BUILDS constructions"
                }
            }
        }

        assertNoFailures("provider(s) subscribing while being constructed", failures)
    }

    /**
     * Pages the engine mounts outside the one-page-per-carousel-entry mapping, named one by one.
     *
     * Two of them no entry opens at all: the prompt and the reply reach the cluster through the
     * interrupt stack and through the reading page. The other two are `CALL`'s second branch —
     * that entry mounts the live call page when there is a call and the two-row menu when there is
     * none, so the count above can credit it with one page and this list
     * carries the other, plus the dialler the menu opens.
     */
    private val pagesOutsideTheCarousel = listOf(
        "MessagePromptPageProvider",
        "MessageReplyPageProvider",
        "CallMenuPageProvider",
        "CallDialPageProvider",
        "NavMenuPageProvider",
        "NavVoiceSearchPageProvider",
        "MusicLoadingPageProvider",
        "MusicErrorPageProvider",
        "MessageMenuPageProvider",
        "MessageContactSelectPageProvider",
        "MessageComposePageProvider"
    )

    /**
     * Tied to the enumeration rather than to a literal, so a new carousel entry arrives here instead
     * of slipping past: the engine can mount one page per entry, plus the carousel itself, plus
     * every page of [pagesOutsideTheCarousel].
     */
    @Test
    fun theCatalogCoversEveryProviderTheEngineCanMount() {
        val scenarios = scenarios()

        assertEquals(
            "the catalog must cover one page per carousel entry, the root menu, and every page " +
                "the engine mounts by another route",
            RootMenuItem.entries.size + 1 + pagesOutsideTheCarousel.size,
            scenarios.size
        )
        assertTrue(
            "every page outside the carousel must appear in the catalog under its own label",
            scenarios.map { it.label }.containsAll(pagesOutsideTheCarousel)
        )
        assertEquals(
            "two scenarios share a label, so one provider is covered twice and another not at all",
            scenarios.size,
            scenarios.map { it.label }.toSet().size
        )
    }

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

    private companion object {
        /**
         * Enough that a per-construction effect cannot hide behind a single call, and the same
         * count `WeatherPageProviderTest` already uses for its own version of this check.
         */
        const val BUILDS = 50
    }
}
