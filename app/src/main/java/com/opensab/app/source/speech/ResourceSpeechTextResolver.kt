package com.opensab.app.source.speech

import android.content.Context
import com.opensab.app.R
import com.opensab.domain.settings.VoiceVerbosity
import com.opensab.domain.speech.SpeechArg
import com.opensab.domain.speech.SpeechRequest
import com.opensab.domain.speech.SpeechTextResolver
import com.opensab.domain.speech.assembleSpeechText
import com.opensab.sab.session.vehicle.VehicleCapabilities
import kotlinx.coroutines.flow.StateFlow

/**
 * [SpeechTextResolver] over `Context.getString`, with the cluster read from the live capabilities.
 *
 * The capabilities are read at the moment of speaking rather than captured once: the motorcycle is
 * identified well after the graph is built, so a snapshot would keep saying every phrase the way
 * the not-yet-known vehicle would say it.
 */
class ResourceSpeechTextResolver(
    context: Context,
    private val capabilities: StateFlow<VehicleCapabilities>
) : SpeechTextResolver {

    private val appContext = context.applicationContext

    override fun resolve(request: SpeechRequest): String =
        resolve(request, VoiceVerbosity.STANDARD)

    override fun resolve(request: SpeechRequest, verbosity: VoiceVerbosity): String {
        val phrases = request.token.phrases()
        val essentialText = appContext.getString(
            phrases.forCluster(capabilities.value.segmentDisplay),
            *request.args.map { it.say() }.toTypedArray()
        )

        return when (verbosity) {
            VoiceVerbosity.NONE -> ""
            VoiceVerbosity.STANDARD -> essentialText
            VoiceVerbosity.ADVANCED -> {
                val helpRes = phrases.help
                if (helpRes != null) {
                    val helpText = appContext.getString(helpRes)
                    val pause = appContext.getString(R.string.speech_pause)
                    assembleSpeechText(essentialText, pause, helpText)
                } else {
                    essentialText
                }
            }
        }
    }

    /** A plural passes the count twice: once to pick the form and once to fill its `%d`. */
    private fun SpeechArg.say(): String = when (this) {
        is SpeechArg.Text -> value
        is SpeechArg.Digits -> formatDigits(value)
        is SpeechArg.Quantity -> appContext.resources.getQuantityString(token.plural(), count, count)
        is SpeechArg.Word -> appContext.getString(token.string())
    }

    companion object {
        fun formatDigits(value: String): String {
            val trimmed = value.trim()
            val hasPlus = trimmed.startsWith("+")
            val digits = trimmed.filter { it.isDigit() }
            return when {
                digits.isEmpty() -> ""
                hasPlus -> "+ " + digits.asIterable().joinToString(" ")
                else -> digits.asIterable().joinToString(" ")
            }
        }
    }
}
