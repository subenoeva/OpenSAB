package com.opensab.domain.speech

import java.util.Locale

/**
 * What to say, before it has a language. The engine builds these; the platform turns them into a
 * string.
 *
 * [arity] is how many arguments the phrase takes, and it is checked on construction: a phrase with
 * three placeholders spoken with two arguments is a defect that only shows up on the bike.
 */
enum class SpeechToken(val arity: Int) {
    /** {locality} — "The weather in Vigo" */
    WEATHER_FORECAST_MESSAGE(1),

    /** {hour, description, temperature} — "In 3 hours, partly cloudy, 21 degrees" */
    WEATHER_FORECAST_LINE(3),

    /**
     * {description, temperature} — "Now, partly cloudy, 21 degrees".
     *
     * Its own token because the current reading through [WEATHER_FORECAST_LINE], whose first
     * argument is a plural of hours, would say "in 0 hours", a thing nobody says.
     */
    WEATHER_CURRENT_LINE(2),

    /** {body} — the message read out loud */
    MESSAGE_READ(1),

    /** {sender, appName} — "Message from Carlos on WhatsApp. Shall I read it?" */
    MESSAGE_PROMPT(2),

    /**
     * {sender} — the same question with the app left out.
     *
     * Not a nicety: `MessageInfo.appName` is nullable and `MessagingPackages.labelOf` returns null
     * outside the thirteen apps of the table. [SpeechRequest] checks arity on construction, so a
     * single two-argument token would make a label-less package throw on the bike, and an empty
     * string in its place would say "Message from Carlos on . Shall I read it?".
     */
    MESSAGE_PROMPT_SHORT(1),

    /**
     * {transcript} — what the rider dictated, read back before it goes.
     *
     * A pass-through template like [MESSAGE_READ]: it resolves to `%1$s` and adds no words of its
     * own. No forced locale, and that is not an omission — what is being read back is what the
     * rider just said, so the system voice is by construction the right one and `voiceLocaleFor`
     * would only ever guess at the alphabet of his own sentence.
     */
    MESSAGE_CONFIRM(1),

    /**
     * {name} — the candidate under the cursor, read out as it moves.
     *
     * A pass-through template like [MESSAGE_CONFIRM], and built on the state rather than on the
     * mount for the reason that makes the list usable with a helmet on: **moving the cursor reads
     * the new name**, and a redraw reads nothing.
     *
     * No forced locale. A person's name has no language, and picking one could only make the
     * pronunciation worse than the rider's own voice setting.
     */
    CALL_CONFIRM(1),

    /** {caller} — the caller's name or number. */
    CALL_RINGING(1),

    /** An incoming call whose caller is unknown or blank. */
    CALL_RINGING_UNKNOWN(0),

    /** {destination} — recognized by voice, read back before launching navigation. */
    NAV_CONFIRM(1),

    /**
     * {part} — one piece of the maneuver being repeated: the distance, the maneuver, the street.
     * A pass-through like [MESSAGE_READ]; each piece is its own phrase so a missing one is skipped
     * instead of leaving a dangling comma in the sentence.
     */
    NAV_MANEUVER(1),

    // Carousel narration
    ROOT_WEATHER(2),
    ROOT_WEATHER_EMPTY(0),
    ROOT_NAVIGATION(0),
    ROOT_NAVIGATION_GUIDING(0),
    ROOT_CALL(0),
    ROOT_MESSAGE_UNREAD(1),
    ROOT_MESSAGE(0),
    ROOT_MUSIC_PLAYING(1),
    ROOT_MUSIC(0),
    ROOT_VOLUME(0),

    // Submenu narration
    MENU_TITLE(1),
    CALL_MENU_VOICE(0),
    CALL_MENU_VOICE_DISABLED(0),
    CALL_MENU_FAVORITES(0),
    CALL_MENU_FAVORITES_EMPTY(0),
    MESSAGE_MENU_VOICE(0),
    MESSAGE_MENU_VOICE_DISABLED(0),
    MESSAGE_MENU_FAVORITES(0),
    MESSAGE_MENU_FAVORITES_EMPTY(0),
    NAV_MENU_VOICE(0),

    // Music & volume
    MUSIC_LIVE(0),
    MUSIC_LOADING(0),
    MUSIC_ERROR(0),
    VOLUME_LEVEL(1),
    VOLUME_MUTED(0),

    // Voice error/result pages
    VOICE_NOTHING_UNDERSTOOD(0),
    NAV_NOTHING_UNDERSTOOD(0),
    /** Help says BACK retries: true on the call, contact, reply and compose pages. */
    VOICE_FAILED(0),

    /** The destination search retries on SELECT and leaves on BACK, unlike the other four. */
    NAV_FAILED(0),

    /** No help: the only way out is on the phone, not on any key of this page. */
    VOICE_MIC_NEEDS_APP(0),
    VOICE_NO_MATCH(1),
    CALL_NOT_PLACED(1),
    MESSAGE_CHOOSING_REPLY(1),
    MESSAGE_SENT(0),
    /** Compose page: SELECT resends. */
    MESSAGE_NOT_SENT(0),

    /** Reply page: BACK resends, and SELECT opens the quick replies instead. */
    REPLY_NOT_SENT(0),
    NAV_NO_APP_AVAILABLE(0),
    NAV_AMBIGUOUS_QUERY(0),
    NAV_UNLOCK_PHONE(0)
}

/**
 * A phrase named rather than written: the engine cannot see `R`, so the only thing it can get wrong
 * on its own is how many arguments it hands over.
 *
 * [locale] forces the voice this one phrase is said in, and `null` — every phrase but one — is the
 * rider's own. It rides on the request and not on the synthesizer because the language belongs to
 * the *phrase*: the forecast is said in the rider's language and an incoming message in the one it
 * was written in, and both go through the same `PageVoice` in the same session.
 *
 * **It only makes sense on a phrase whose template adds no words of its own.** `MESSAGE_READ`
 * resolves to `%1$s`, so forcing Chinese on it says the message and nothing else; forcing Chinese
 * on `WEATHER_FORECAST_LINE` would say Spanish words with a Chinese voice. Today that is one token,
 * and the day it is two this comment is where the rule lives.
 *
 * That count is checked here, on construction, and not later in the resolver, because a phrase with
 * three placeholders spoken with two arguments is a defect that shows up on the bike and nowhere
 * else. A mismatched request refuses to exist instead of travelling down to the platform half,
 * which carries no test.
 */
data class SpeechRequest(
    val token: SpeechToken,
    val args: List<SpeechArg> = emptyList(),
    val locale: Locale? = null
) {
    init {
        require(args.size == token.arity) {
            "SpeechToken.$token takes ${token.arity} arguments, got ${args.size}"
        }
    }

    /** Sugar for the call sites in the engine: it delegates, so there is one arity check, not two. */
    constructor(token: SpeechToken, vararg args: SpeechArg) : this(token, args.toList())
}
