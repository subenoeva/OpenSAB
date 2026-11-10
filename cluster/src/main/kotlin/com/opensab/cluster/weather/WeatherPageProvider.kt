package com.opensab.cluster.weather

import com.opensab.cluster.LiveScreenPresets
import com.opensab.cluster.NavigationAction
import com.opensab.cluster.ResumeBehavior
import com.opensab.cluster.SpeakingPage
import com.opensab.cluster.mirror.MIRROR_REQUEST_ID
import com.opensab.cluster.mirror.MirrorAction
import com.opensab.cluster.mirror.MirrorActionToken
import com.opensab.cluster.mirror.MirrorPage
import com.opensab.cluster.mirror.MirrorSlot
import com.opensab.cluster.mirror.mirrorPageOf
import com.opensab.cluster.redrawOn
import com.opensab.cluster.text.ClusterTextResolver
import com.opensab.cluster.text.ClusterToken
import com.opensab.cluster.text.WeatherCaptionResolver
import com.opensab.domain.music.VolumeSource
import com.opensab.domain.speech.SpeechRequest
import com.opensab.domain.weather.WeatherInfo
import com.opensab.domain.weather.WeatherSource
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.ProjectionFrame
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * **This page exists to speak.** Looking is the carousel and hearing is the page: the entry the
 * rider came from already shows the icon, the temperature and the locality, so entering says what
 * does not fit in two lines — how the next few hours are going to be. The frame keeps painting the
 * reading as the accompaniment.
 *
 * It names the phrases and the engine says them, because only the engine knows when this page stops
 * being mounted (see [SpeakingPage]).
 */
class WeatherPageProvider(
    private val currentWeather: StateFlow<WeatherInfo?>,
    /** No default: one would be a page painting English because the graph forgot. */
    private val copy: ClusterTextResolver,
    /**
     * The forecast caption is the one piece of cluster copy that outlives the process — it goes to
     * disk as a [com.opensab.domain.weather.WmoCaption] and gets a language here. No default either,
     * for the same reason [copy] has none.
     */
    private val captions: WeatherCaptionResolver,
    private val weatherSource: WeatherSource? = null,
    private val volumeSource: VolumeSource? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis
) : SpeakingPage {

    override val name: String = "WeatherPage"

    /**
     * Conflated, because a second SELECT while one script is still queued replaces it instead of
     * stacking: there is one voice, and the newest thing the page has to say is the one worth
     * hearing.
     */
    private val scripts = Channel<List<SpeechRequest>>(Channel.CONFLATED)

    /**
     * Whether the opening script has been handed over, so that it is handed over **once per
     * instance** and not once per collection: the engine collects again every time an interruption
     * covers this page and goes away, and the forecast must not start over then.
     */
    private var openingScriptOffered = false

    /**
     * The script is built when somebody listens, and **not when the page is constructed**.
     *
     * Listening is the honest signal, and above all it is the right instant for the clock: the
     * hours a line announces are counted from when the rider hears it, not from whenever the page
     * happened to be built.
     *
     * Constructing is not that signal, and it is not allowed to become one: nothing observable may
     * happen in the constructor of any provider — `PageProviderConstructionContractTest` holds the
     * whole catalog to it.
     */
    override val speech: Flow<List<SpeechRequest>> = scripts.receiveAsFlow().onStart {
        if (!openingScriptOffered) {
            openingScriptOffered = true
            offerScript()
        }
    }

    private fun offerScript() {
        val script = forecastScript(currentWeather.value, nowMillis())
        if (script.isNotEmpty()) scripts.trySend(script)
    }

    override val onResumeBehavior: ResumeBehavior = ResumeBehavior.RETAIN

    override fun render(requestId: Int): ProjectionFrame {
        val weather = currentWeather.value ?: return LiveScreenPresets.weatherStandby(
            requestId = requestId,
            header = copy.resolve(ClusterToken.HEADER_WEATHER),
            empty = copy.resolve(ClusterToken.EMPTY_DATA)
        )
        val tempAndCond =
            "${weather.temperature}${weather.temperatureUnit} ${captions.resolve(weather.caption)}".trim()
        return LiveScreenPresets.weather(
            requestId = requestId,
            header = copy.resolve(ClusterToken.HEADER_WEATHER),
            temperatureAndCondition = tempAndCond,
            location = weather.locationName
        )
    }

    /**
     * The shared content-page block, with `SELECT` carrying this page's own action. The list is the
     * same on standby: asking for a fresh reading is exactly what a page with no forecast is for, so
     * nothing goes dark while the report is missing.
     *
     * There is no cancel action of its own, and that is not an omission: the cancel button of this
     * page **is** `BACK`. Leaving the page cuts the voice because leaving the page cancels the
     * engine's speech job, so a second token for it would paint a zone that already exists.
     *
     * `VOLUME_MUTE` takes `RIGHT` because `LEFT` already carries `BACK`, and the two arrows carry
     * the dedicated volume ids as aliases: `onInput` routes `PREVIOUS` and `VOLUME_UP` to the same
     * `raise()`, so declaring them apart would paint two identical zones.
     */
    override fun mirror(): MirrorPage = mirrorPageOf(
        name,
        render(MIRROR_REQUEST_ID),
        actions = listOf(
            MirrorAction(
                token = MirrorActionToken.REFRESH_WEATHER,
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
    )

    override val invalidations: Flow<Unit> = redrawOn(currentWeather)

    override fun onInput(functionId: FunctionId): NavigationAction = when (functionId) {
        FunctionId.BACK -> NavigationAction.Pop

        FunctionId.MENU -> NavigationAction.Reset

        // "Say it again", which is the only thing a rider wants from the single key of a page that
        // exists to speak. It asks for a fresh reading on the way, because a repeat should say the
        // freshest thing known and the request is debounced upstream, on the last attempt.
        FunctionId.SELECT -> {
            weatherSource?.refresh()
            offerScript()
            NavigationAction.None
        }

        FunctionId.PREVIOUS, FunctionId.VOLUME_UP -> {
            volumeSource?.raise()
            NavigationAction.None
        }

        FunctionId.NEXT, FunctionId.VOLUME_DOWN -> {
            volumeSource?.lower()
            NavigationAction.None
        }

        FunctionId.VOLUME_MUTE -> {
            volumeSource?.toggleMute()
            NavigationAction.None
        }

        else -> NavigationAction.None
    }
}
