package com.opensab.cluster

import com.opensab.cluster.call.CallDialPageProvider
import com.opensab.cluster.call.CallMenuPageProvider
import com.opensab.cluster.call.CallPageProvider
import com.opensab.cluster.call.DialSeed
import com.opensab.cluster.call.callEntryPage
import com.opensab.cluster.message.MessageComposePageProvider
import com.opensab.cluster.message.MessageContactSelectPageProvider
import com.opensab.cluster.message.MessageMenuPageProvider
import com.opensab.cluster.message.MessagePageProvider
import com.opensab.cluster.message.MessagePromptPageProvider
import com.opensab.cluster.message.MessageReplyPageProvider
import com.opensab.cluster.message.messageEntryPage
import com.opensab.cluster.music.MusicErrorPageProvider
import com.opensab.cluster.music.MusicLoadingPageProvider
import com.opensab.cluster.music.MusicPageProvider
import com.opensab.cluster.music.VolumePageProvider
import com.opensab.cluster.nav.NavMenuPageProvider
import com.opensab.cluster.nav.NavVoiceSearchPageProvider
import com.opensab.cluster.nav.NavigationPageProvider
import com.opensab.cluster.nav.navEntryPage
import com.opensab.cluster.rootmenu.RootMenuItem
import com.opensab.cluster.rootmenu.RootMenuPageProvider
import com.opensab.cluster.text.ClusterPresentation
import com.opensab.cluster.weather.WeatherPageProvider
import com.opensab.domain.call.CallDialer
import com.opensab.domain.call.CallManager
import com.opensab.domain.call.TelecomAdapter
import com.opensab.domain.contacts.CallMenuAccess
import com.opensab.domain.contacts.CallTarget
import com.opensab.domain.contacts.ContactDirectory
import com.opensab.domain.message.MessageMenuAccess
import com.opensab.domain.message.MessageSource
import com.opensab.domain.message.SmsSender
import com.opensab.domain.music.MusicManager
import com.opensab.domain.music.VolumeSource
import com.opensab.domain.nav.DeviceUnlockSource
import com.opensab.domain.nav.NavigationLauncher
import com.opensab.domain.nav.NavigationManager
import com.opensab.domain.settings.InboundCallMode
import com.opensab.domain.settings.InboundMessageMode
import com.opensab.domain.settings.RootMenuEntry
import com.opensab.domain.speech.SpeechRecognizer
import com.opensab.domain.weather.WeatherSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * Pages that must keep one identity for the session are retained here; subpages are created fresh
 * on every request. The calls that are not obvious:
 * - one [CallPageProvider] shared by the carousel, the interrupt and the dialler's return, because
 *   `RINGING -> ACTIVE` hangs on reference equality; the same for [MessagePageProvider];
 * - [CallMenuPageProvider] retained so its cursor survives leaving and coming back;
 * - [WeatherPageProvider] fresh on every entrance, so the opening script is offered again and the
 *   cache is re-read;
 * - the reply page fresh on every dictation, so it always starts listening.
 */
class DefaultPageProviderFactory(
    private val musicManager: MusicManager,
    private val navManager: NavigationManager,
    private val callManager: CallManager,
    private val weatherSource: WeatherSource,
    private val messageSource: MessageSource,
    private val telecomAdapter: TelecomAdapter,
    private val volumeSource: VolumeSource,
    private val speechRecognizer: SpeechRecognizer,
    private val contactDirectory: ContactDirectory,
    private val callDialer: CallDialer,
    private val callMenuAccess: CallMenuAccess,
    private val smsSender: SmsSender,
    private val messageMenuAccess: MessageMenuAccess,
    private val navigationLauncher: NavigationLauncher,
    private val deviceUnlockSource: DeviceUnlockSource,
    private val quickReplies: StateFlow<List<String>>,
    private val sentLinger: StateFlow<Duration>,
    /** The visible root menu entries in the rider's order; the graph owns it. */
    private val rootMenuEntries: StateFlow<List<RootMenuEntry>>,
    /** "Handlebar Volume Control"; the graph owns it. */
    private val handlebarVolumeControl: StateFlow<Boolean>,
    private val inboundCallMode: Flow<InboundCallMode>,
    private val inboundMessageMode: Flow<InboundMessageMode>,
    override val presentation: ClusterPresentation
) : PageProviderFactory {

    // --- Retained session instances ---

    private val callPageInstance: CallPageProvider by lazy {
        CallPageProvider(
            activeCall = callManager.activeCall,
            copy = presentation.text,
            telecomAdapter = telecomAdapter,
            volumeSource = volumeSource
        )
    }

    private val callMenuPageInstance: CallMenuPageProvider by lazy {
        CallMenuPageProvider(
            access = callMenuAccess,
            dialPageFactory = { seed -> createCallDialPage(seed) },
            copy = presentation.text
        )
    }

    private val messagePageInstance: MessagePageProvider by lazy {
        MessagePageProvider(
            currentMessage = messageSource.currentMessage,
            copy = presentation.text,
            messageSource = messageSource,
            canReply = messageSource.canReply,
            volumeSource = volumeSource,
            replyPageFactory = { createMessageReplyPage() }
        )
    }

    private val messagePromptPageInstance: MessagePromptPageProvider by lazy {
        MessagePromptPageProvider(
            currentMessage = messageSource.currentMessage,
            copy = presentation.text,
            messageSource = messageSource,
            volumeSource = volumeSource,
            readPageFactory = { messagePage() }
        )
    }

    private val messageMenuPageInstance: MessageMenuPageProvider by lazy {
        MessageMenuPageProvider(
            access = messageMenuAccess,
            selectContactPageFactory = { seed -> createMessageContactSelectPage(seed) },
            copy = presentation.text
        )
    }

    private val liveNavPageInstance: NavigationPageProvider by lazy {
        NavigationPageProvider(
            navInstruction = navManager.currentInstruction,
            copy = presentation.text,
            navigationManager = navManager,
            volumeSource = volumeSource
        )
    }

    private val navMenuPageInstance: NavMenuPageProvider by lazy {
        NavMenuPageProvider(
            voicePageFactory = { createNavVoiceSearchPage() },
            copy = presentation.text
        )
    }

    // --- PageProviderFactory public contract implementation ---

    override fun callPage(): CallPageProvider = callPageInstance

    override fun callMenuPage(): CallMenuPageProvider = callMenuPageInstance

    override fun createWeatherPage(): WeatherPageProvider =
        WeatherPageProvider(
            currentWeather = weatherSource.currentWeather,
            copy = presentation.text,
            captions = presentation.captions,
            weatherSource = weatherSource,
            volumeSource = volumeSource
        )

    override fun messagePage(): MessagePageProvider = messagePageInstance

    override fun liveNavPage(): NavigationPageProvider = liveNavPageInstance

    override fun navMenuPage(): NavMenuPageProvider = navMenuPageInstance

    override fun createRootMenuPage(): RootMenuPageProvider =
        RootMenuPageProvider(
            entries = rootMenuEntries,
            musicState = musicManager.currentState,
            navInstruction = navManager.currentInstruction,
            activeCall = callManager.activeCall,
            currentWeather = weatherSource.currentWeather,
            currentMessage = messageSource.currentMessage,
            copy = presentation.text,
            musicLoadingPageFactory = { createMusicLoadingPage() },
            messageMenuPageFactory = { messageMenuPageInstance },
            pageFactory = { item ->
                when (item) {
                    RootMenuItem.MUSIC -> createMusicPage()
                    RootMenuItem.VOLUME -> createVolumePage()
                    RootMenuItem.NAVIGATION -> navEntryPage(
                        navInstruction = navManager.currentInstruction.value,
                        livePage = { liveNavPage() },
                        menuPage = { navMenuPage() }
                    )
                    RootMenuItem.CALL -> callEntryPage(
                        activeCall = callManager.activeCall.value,
                        livePage = { callPage() },
                        menuPage = { callMenuPage() }
                    )
                    RootMenuItem.WEATHER -> createWeatherPage()
                    RootMenuItem.MESSAGE -> messageEntryPage(
                        currentMessage = messageSource.currentMessage.value,
                        readPage = { messagePage() },
                        menuPage = { messageMenuPageInstance }
                    )
                }
            }
        )

    override fun createInterruptSource(): InterruptSource =
        PriorityInterruptSource(
            CallInterruptSource(
                activeCall = callManager.activeCall,
                mode = inboundCallMode,
                callPageFactory = { callPage() }
            ),
            MessageInterruptSource(
                claimingScreen = messageSource.claimingScreen,
                mode = inboundMessageMode,
                promptPageFactory = { messagePromptPageInstance },
                readPageFactory = { messagePage() }
            )
        )

    // --- Ephemeral and subpage creation methods ---

    internal fun createCallDialPage(seed: DialSeed): CallDialPageProvider =
        CallDialPageProvider(
            seed = seed,
            recognizer = speechRecognizer,
            directory = contactDirectory,
            dialer = callDialer,
            livePageFactory = { callPage() },
            activeCall = callManager.activeCall,
            copy = presentation.text,
            volumeSource = volumeSource
        )

    internal fun createMessageContactSelectPage(seed: DialSeed): MessageContactSelectPageProvider =
        MessageContactSelectPageProvider(
            seed = seed,
            recognizer = speechRecognizer,
            directory = contactDirectory,
            composePageFactory = { target -> createMessageComposePage(target) },
            copy = presentation.text,
            volumeSource = volumeSource
        )

    internal fun createMessageComposePage(target: CallTarget): MessageComposePageProvider =
        MessageComposePageProvider(
            target = target,
            recognizer = speechRecognizer,
            smsSender = smsSender,
            sentLinger = sentLinger,
            copy = presentation.text,
            quickReplies = quickReplies,
            volumeSource = volumeSource
        )

    internal fun createMessageReplyPage(): MessageReplyPageProvider =
        MessageReplyPageProvider(
            recognizer = speechRecognizer,
            messageSource = messageSource,
            sentLinger = sentLinger,
            copy = presentation.text,
            quickReplies = quickReplies,
            volumeSource = volumeSource
        )

    internal fun createNavVoiceSearchPage(): NavVoiceSearchPageProvider =
        NavVoiceSearchPageProvider(
            recognizer = speechRecognizer,
            launcher = navigationLauncher,
            unlockSource = deviceUnlockSource,
            navInstructionFlow = navManager.currentInstruction,
            livePageFactory = { liveNavPage() },
            copy = presentation.text,
            volumeSource = volumeSource
        )

    internal fun createMusicPage(): MusicPageProvider =
        MusicPageProvider(
            musicState = musicManager.currentState,
            copy = presentation.text,
            volumeControl = handlebarVolumeControl,
            musicManager = musicManager
        )

    internal fun createMusicLoadingPage(): MusicLoadingPageProvider =
        MusicLoadingPageProvider(
            musicState = musicManager.currentState,
            copy = presentation.text,
            volumeControl = handlebarVolumeControl,
            livePageFactory = { createMusicPage() },
            errorPageFactory = { createMusicErrorPage() },
            musicManager = musicManager
        )

    internal fun createMusicErrorPage(): MusicErrorPageProvider =
        MusicErrorPageProvider(
            copy = presentation.text,
            volumeControl = handlebarVolumeControl,
            volumeSource = volumeSource,
            musicManager = musicManager
        )

    internal fun createVolumePage(): VolumePageProvider =
        VolumePageProvider(
            volumeLevel = volumeSource.level,
            copy = presentation.text,
            volumeControl = handlebarVolumeControl,
            volumeSource = volumeSource
        )
}
