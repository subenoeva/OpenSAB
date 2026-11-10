package com.opensab.cluster
import com.opensab.cluster.music.volumeControlOn
import com.opensab.cluster.text.englishPresentation
import com.opensab.domain.call.CallNotificationBus
import com.opensab.domain.call.CallNotificationDriver
import com.opensab.domain.call.DefaultCallManager
import com.opensab.domain.call.MockCallDialer
import com.opensab.domain.call.RecordingTelecomAdapter
import com.opensab.domain.contacts.MockCallMenuAccess
import com.opensab.domain.contacts.MockContactDirectory
import com.opensab.domain.message.MessageInfo
import com.opensab.domain.message.MockMessageMenuAccess
import com.opensab.domain.message.MockMessageSource
import com.opensab.domain.message.MockSmsSender
import com.opensab.domain.music.RecordingMusicManager
import com.opensab.domain.music.RecordingVolumeSource
import com.opensab.domain.nav.FakeDeviceUnlockSource
import com.opensab.domain.nav.MockNavigationLauncher
import com.opensab.domain.nav.RecordingNavigationManager
import com.opensab.domain.settings.InboundCallMode
import com.opensab.domain.settings.InboundMessageMode
import com.opensab.domain.speech.MockSpeechRecognizer
import com.opensab.domain.weather.MockWeatherSource
import com.opensab.sab.protocol.assignment.FunctionId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PageProviderFactoryIdentityTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private val musicManager = RecordingMusicManager()
    private val navManager = RecordingNavigationManager()
    private val callBus = CallNotificationBus()
    private val callDriver = CallNotificationDriver(callBus)
    private val callManager = DefaultCallManager(callBus)
    private val weatherSource = MockWeatherSource()
    private val messageSource = MockMessageSource()
    private val telecomAdapter = RecordingTelecomAdapter()
    private val volumeSource = RecordingVolumeSource()
    private val speechRecognizer = MockSpeechRecognizer()
    private val contactDirectory = MockContactDirectory()
    private val callDialer = MockCallDialer()
    private val callMenuAccess = MockCallMenuAccess()
    private val smsSender = MockSmsSender()
    private val messageMenuAccess = MockMessageMenuAccess()
    private val navigationLauncher = MockNavigationLauncher()
    private val quickReplies = MutableStateFlow<List<String>>(emptyList())
    private val sentLinger = MutableStateFlow(2.seconds)
    private val inboundCallMode = MutableStateFlow(InboundCallMode.ALERT)
    private val inboundMessageMode = MutableStateFlow(InboundMessageMode.PROMPT)
    private val presentation = englishPresentation

    private fun createFactory(
        callMode: Flow<InboundCallMode> = inboundCallMode,
        messageMode: Flow<InboundMessageMode> = inboundMessageMode
    ): DefaultPageProviderFactory = DefaultPageProviderFactory(
        musicManager = musicManager,
        navManager = navManager,
        callManager = callManager,
        weatherSource = weatherSource,
        messageSource = messageSource,
        telecomAdapter = telecomAdapter,
        volumeSource = volumeSource,
        speechRecognizer = speechRecognizer,
        contactDirectory = contactDirectory,
        callDialer = callDialer,
        callMenuAccess = callMenuAccess,
        smsSender = smsSender,
        messageMenuAccess = messageMenuAccess,
        navigationLauncher = navigationLauncher,
        deviceUnlockSource = FakeDeviceUnlockSource(),
        quickReplies = quickReplies,
        sentLinger = sentLinger,
        rootMenuEntries = allRootMenuEntries(),
        handlebarVolumeControl = volumeControlOn,
        inboundCallMode = callMode,
        inboundMessageMode = messageMode,
        presentation = presentation
    )

    @Test
    fun callPageIsSingleSessionInstance() {
        val factory = createFactory()
        val first = factory.callPage()
        val second = factory.callPage()
        assertSame("callPage must be a single session instance (R-1)", first, second)
    }

    @Test
    fun callMenuPageIsRetainedSessionInstance() {
        val factory = createFactory()
        val first = factory.callMenuPage()
        val second = factory.callMenuPage()
        assertSame("callMenuPage must be a retained session instance (R-2)", first, second)
    }

    @Test
    fun weatherPageIsFreshOnEveryEntrance() {
        val factory = createFactory()
        val first = factory.createWeatherPage()
        val second = factory.createWeatherPage()
        assertNotSame("weatherPage must be fresh on every entrance to reset openingScriptOffered (R-3)", first, second)
    }

    @Test
    fun messagePageIsSingleSessionInstance() {
        val factory = createFactory()
        val first = factory.messagePage()
        val second = factory.messagePage()
        assertSame("messagePage must be a single session instance (R-4)", first, second)
    }

    @Test
    fun navigationPagesPreserveSessionIdentity() {
        val factory = createFactory()
        assertSame("liveNavPage must preserve session identity (R-8)", factory.liveNavPage(), factory.liveNavPage())
        assertSame("navMenuPage must preserve session identity (R-9)", factory.navMenuPage(), factory.navMenuPage())
    }

    @Test
    fun carouselAndInterruptShareCallPage() = testScope.runTest {
        callManager.start(backgroundScope)
        val factory = createFactory()

        callDriver.post("Ana", "+34600000000", callType = 1)
        runCurrent()

        val interruptSource = factory.createInterruptSource()
        val interruptPage = interruptSource.interrupting.first { it != null }

        val carousel = factory.createRootMenuPage()
        carousel.onInput(FunctionId.NEXT) // 1: NAVIGATION
        carousel.onInput(FunctionId.NEXT) // 2: CALL
        val nextAction = carousel.onInput(FunctionId.SELECT)
        assertTrue(nextAction is NavigationAction.Next)
        val carouselPage = (nextAction as NavigationAction.Next).page

        assertSame("Interrupt and carousel must share the identical CallPageProvider instance (R-1)", interruptPage, carouselPage)
        assertSame("Factory callPage() contract must match the shared instance (R-1)", factory.callPage(), carouselPage)
    }

    @Test
    fun carouselAndInterruptShareMessagePage() = testScope.runTest {
        val messageMode = MutableStateFlow(InboundMessageMode.READ_DIRECTLY)
        val factory = createFactory(messageMode = messageMode)

        val interruptSource = factory.createInterruptSource()
        val seen = mutableListOf<PageProvider?>()
        val job = backgroundScope.launch { interruptSource.interrupting.collect { seen += it } }
        runCurrent()

        messageSource.setMessage(MessageInfo(sender = "Bob", body = "Hello"))
        runCurrent()

        val interruptPage = seen.last()

        val carousel = factory.createRootMenuPage()
        carousel.onInput(FunctionId.NEXT) // 1: NAVIGATION
        carousel.onInput(FunctionId.NEXT) // 2: CALL
        carousel.onInput(FunctionId.NEXT) // 3: MESSAGE
        val nextAction = carousel.onInput(FunctionId.SELECT)
        assertTrue(nextAction is NavigationAction.Next)
        val carouselPage = (nextAction as NavigationAction.Next).page

        assertSame("Interrupt and carousel must share the identical MessagePageProvider instance (R-4)", interruptPage, carouselPage)
        assertSame("Factory messagePage() contract must match the shared instance (R-4)", factory.messagePage(), carouselPage)
        job.cancel()
    }

    @Test
    fun messageReplyPageIsFreshOnEveryAttempt() {
        val factory = createFactory()
        val first = factory.createMessageReplyPage()
        val second = factory.createMessageReplyPage()
        assertNotSame("createMessageReplyPage must be fresh on every attempt to ensure ReplyState.Listening (R-5)", first, second)
    }
}
