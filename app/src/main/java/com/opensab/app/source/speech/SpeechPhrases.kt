package com.opensab.app.source.speech

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.opensab.app.R
import com.opensab.domain.speech.SpeechQuantity
import com.opensab.domain.speech.SpeechRequest
import com.opensab.domain.speech.SpeechToken
import com.opensab.domain.speech.SpeechWord

/**
 * The resources of one phrase, one per kind of cluster: the same situation is said differently on
 * a TFT screen than on a segment display.
 *
 * The dimension lives in this table and not in [SpeechRequest] because the cluster belongs to the
 * vehicle of the session, not to the request; `VehicleCapabilitiesHolder` owns that fact.
 *
 * The single-resource constructor is the "does not diverge" case, so no string is duplicated.
 */
data class SpeechPhrases(
    @StringRes val tft: Int,
    @StringRes val indicator: Int,
    @StringRes val help: Int? = null
) {

    constructor(@StringRes both: Int, @StringRes help: Int? = null) : this(both, both, help)

    /** Before a motorcycle is identified, `segmentDisplay` is false and the phrase reads as TFT. */
    fun forCluster(segmentDisplay: Boolean): Int =
        if (segmentDisplay) indicator else tft
}

/**
 * Exhaustive over [SpeechToken] and deliberately without an `else` branch: a token added to the
 * engine must not compile until somebody has given it a phrase, in `values/` and `values-es/`.
 */
fun SpeechToken.phrases(): SpeechPhrases = when (this) {
    SpeechToken.WEATHER_FORECAST_MESSAGE -> SpeechPhrases(
        both = R.string.speech_weather_forecast_message,
        help = R.string.speech_weather_forecast_message_help
    )
    SpeechToken.WEATHER_FORECAST_LINE -> SpeechPhrases(R.string.speech_weather_forecast_line)
    SpeechToken.WEATHER_CURRENT_LINE -> SpeechPhrases(R.string.speech_weather_current_line)
    SpeechToken.MESSAGE_READ -> SpeechPhrases(
        both = R.string.speech_message_read,
        help = R.string.speech_message_read_help
    )
    SpeechToken.MESSAGE_PROMPT -> SpeechPhrases(
        both = R.string.speech_message_prompt,
        help = R.string.speech_message_prompt_help
    )
    SpeechToken.MESSAGE_PROMPT_SHORT -> SpeechPhrases(
        both = R.string.speech_message_prompt_short,
        help = R.string.speech_message_prompt_help
    )
    SpeechToken.MESSAGE_CONFIRM -> SpeechPhrases(
        both = R.string.speech_message_confirm,
        help = R.string.speech_message_confirm_help
    )
    SpeechToken.CALL_CONFIRM -> SpeechPhrases(
        both = R.string.speech_call_confirm,
        help = R.string.speech_call_confirm_help
    )
    SpeechToken.CALL_RINGING -> SpeechPhrases(
        both = R.string.speech_call_ringing,
        help = R.string.speech_call_ringing_help
    )
    SpeechToken.CALL_RINGING_UNKNOWN -> SpeechPhrases(
        both = R.string.speech_call_ringing_unknown,
        help = R.string.speech_call_ringing_help
    )
    SpeechToken.NAV_CONFIRM -> SpeechPhrases(
        both = R.string.speech_nav_confirm,
        help = R.string.speech_nav_confirm_help
    )
    SpeechToken.NAV_MANEUVER -> SpeechPhrases(R.string.speech_nav_maneuver)

    // Carousel narration
    SpeechToken.ROOT_WEATHER -> SpeechPhrases(
        both = R.string.speech_root_weather,
        help = R.string.speech_root_weather_help
    )
    SpeechToken.ROOT_WEATHER_EMPTY -> SpeechPhrases(
        both = R.string.speech_root_weather_empty,
        help = R.string.speech_root_weather_empty_help
    )
    SpeechToken.ROOT_NAVIGATION -> SpeechPhrases(
        both = R.string.speech_root_navigation,
        help = R.string.speech_root_navigation_help
    )
    SpeechToken.ROOT_NAVIGATION_GUIDING -> SpeechPhrases(
        both = R.string.speech_root_navigation_guiding,
        help = R.string.speech_root_navigation_guiding_help
    )
    SpeechToken.ROOT_CALL -> SpeechPhrases(
        both = R.string.speech_root_call,
        help = R.string.speech_root_call_help
    )
    SpeechToken.ROOT_MESSAGE_UNREAD -> SpeechPhrases(
        both = R.string.speech_root_message_unread,
        help = R.string.speech_root_message_unread_help
    )
    SpeechToken.ROOT_MESSAGE -> SpeechPhrases(
        both = R.string.speech_root_message,
        help = R.string.speech_root_message_help
    )
    SpeechToken.ROOT_MUSIC_PLAYING -> SpeechPhrases(
        both = R.string.speech_root_music_playing,
        help = R.string.speech_root_music_playing_help
    )
    SpeechToken.ROOT_MUSIC -> SpeechPhrases(
        both = R.string.speech_root_music,
        help = R.string.speech_root_music_help
    )
    SpeechToken.ROOT_VOLUME -> SpeechPhrases(
        both = R.string.speech_root_volume,
        help = R.string.speech_root_volume_help
    )

    // Submenu narration
    SpeechToken.MENU_TITLE -> SpeechPhrases(R.string.speech_menu_title)
    SpeechToken.CALL_MENU_VOICE -> SpeechPhrases(
        both = R.string.speech_call_menu_voice,
        help = R.string.speech_call_menu_voice_help
    )
    SpeechToken.CALL_MENU_VOICE_DISABLED -> SpeechPhrases(R.string.speech_call_menu_voice_disabled)
    SpeechToken.CALL_MENU_FAVORITES -> SpeechPhrases(
        both = R.string.speech_call_menu_favorites,
        help = R.string.speech_call_menu_favorites_help
    )
    SpeechToken.CALL_MENU_FAVORITES_EMPTY -> SpeechPhrases(R.string.speech_call_menu_favorites_empty)
    SpeechToken.MESSAGE_MENU_VOICE -> SpeechPhrases(
        both = R.string.speech_message_menu_voice,
        help = R.string.speech_message_menu_voice_help
    )
    SpeechToken.MESSAGE_MENU_VOICE_DISABLED -> SpeechPhrases(R.string.speech_message_menu_voice_disabled)
    SpeechToken.MESSAGE_MENU_FAVORITES -> SpeechPhrases(
        both = R.string.speech_message_menu_favorites,
        help = R.string.speech_message_menu_favorites_help
    )
    SpeechToken.MESSAGE_MENU_FAVORITES_EMPTY -> SpeechPhrases(R.string.speech_message_menu_favorites_empty)
    SpeechToken.NAV_MENU_VOICE -> SpeechPhrases(
        both = R.string.speech_nav_menu_voice,
        help = R.string.speech_nav_menu_voice_help
    )

    // Music & volume
    SpeechToken.MUSIC_LIVE -> SpeechPhrases(
        both = R.string.speech_music_live,
        help = R.string.speech_music_live_help
    )
    SpeechToken.MUSIC_LOADING -> SpeechPhrases(R.string.speech_music_loading)
    SpeechToken.MUSIC_ERROR -> SpeechPhrases(
        both = R.string.speech_music_error,
        help = R.string.speech_music_error_help
    )
    SpeechToken.VOLUME_LEVEL -> SpeechPhrases(
        both = R.string.speech_volume_level,
        help = R.string.speech_volume_level_help
    )
    SpeechToken.VOLUME_MUTED -> SpeechPhrases(
        both = R.string.speech_volume_muted,
        help = R.string.speech_volume_muted_help
    )

    // Voice error/result pages
    SpeechToken.VOICE_NOTHING_UNDERSTOOD -> SpeechPhrases(
        both = R.string.speech_voice_nothing_understood,
        help = R.string.speech_voice_nothing_understood_help
    )
    SpeechToken.NAV_NOTHING_UNDERSTOOD -> SpeechPhrases(
        both = R.string.speech_nav_nothing_understood,
        help = R.string.speech_nav_nothing_understood_help
    )
    SpeechToken.VOICE_FAILED -> SpeechPhrases(
        both = R.string.speech_voice_failed,
        help = R.string.speech_voice_failed_help
    )
    SpeechToken.NAV_FAILED -> SpeechPhrases(
        both = R.string.speech_voice_failed,
        help = R.string.speech_nav_failed_help
    )
    SpeechToken.VOICE_MIC_NEEDS_APP -> SpeechPhrases(both = R.string.speech_voice_mic_needs_app)
    SpeechToken.VOICE_NO_MATCH -> SpeechPhrases(
        both = R.string.speech_voice_no_match,
        help = R.string.speech_voice_no_match_help
    )
    SpeechToken.CALL_NOT_PLACED -> SpeechPhrases(
        both = R.string.speech_call_not_placed,
        help = R.string.speech_call_not_placed_help
    )
    SpeechToken.MESSAGE_CHOOSING_REPLY -> SpeechPhrases(
        both = R.string.speech_message_choosing_reply,
        help = R.string.speech_message_choosing_reply_help
    )
    SpeechToken.MESSAGE_SENT -> SpeechPhrases(R.string.speech_message_sent)
    SpeechToken.MESSAGE_NOT_SENT -> SpeechPhrases(
        both = R.string.speech_message_not_sent,
        help = R.string.speech_message_not_sent_help
    )
    SpeechToken.REPLY_NOT_SENT -> SpeechPhrases(
        both = R.string.speech_message_not_sent,
        help = R.string.speech_reply_not_sent_help
    )
    SpeechToken.NAV_NO_APP_AVAILABLE -> SpeechPhrases(
        both = R.string.speech_nav_no_app_available,
        help = R.string.speech_nav_no_app_available_help
    )
    SpeechToken.NAV_AMBIGUOUS_QUERY -> SpeechPhrases(
        both = R.string.speech_nav_ambiguous_query,
        help = R.string.speech_nav_ambiguous_query_help
    )
    SpeechToken.NAV_UNLOCK_PHONE -> SpeechPhrases(
        both = R.string.speech_nav_unlock_phone,
        help = R.string.speech_nav_unlock_phone_help
    )
}

/**
 * Exhaustive over [SpeechQuantity] and deliberately without an `else` branch, like the phrase
 * table. No cluster dimension here: "three hours" is three hours on both displays.
 */
@PluralsRes
fun SpeechQuantity.plural(): Int = when (this) {
    SpeechQuantity.HOURS -> R.plurals.speech_quantity_hours
    SpeechQuantity.DEGREES -> R.plurals.speech_quantity_degrees
    SpeechQuantity.PERCENT -> R.plurals.speech_quantity_percent
}

/** Exhaustive over [SpeechWord] and deliberately without an `else` branch. */
@StringRes
fun SpeechWord.string(): Int = when (this) {
    SpeechWord.WEATHER_SUNNY -> R.string.speech_word_weather_sunny
    SpeechWord.WEATHER_CLEAR -> R.string.speech_word_weather_clear
    SpeechWord.WEATHER_CLOUDY -> R.string.speech_word_weather_cloudy
    SpeechWord.WEATHER_SUN_AND_CLOUD -> R.string.speech_word_weather_sun_and_cloud
    SpeechWord.WEATHER_RAIN -> R.string.speech_word_weather_rain
    SpeechWord.WEATHER_STORM -> R.string.speech_word_weather_storm
    SpeechWord.WEATHER_SNOW -> R.string.speech_word_weather_snow
    SpeechWord.WEATHER_FOG -> R.string.speech_word_weather_fog
    SpeechWord.WEATHER_UNKNOWN -> R.string.speech_word_weather_unknown
    SpeechWord.NAV_STRAIGHT -> R.string.speech_word_nav_straight
    SpeechWord.NAV_TURN_SLIGHT_LEFT -> R.string.speech_word_nav_turn_slight_left
    SpeechWord.NAV_TURN_LEFT -> R.string.speech_word_nav_turn_left
    SpeechWord.NAV_TURN_SHARP_LEFT -> R.string.speech_word_nav_turn_sharp_left
    SpeechWord.NAV_TURN_SLIGHT_RIGHT -> R.string.speech_word_nav_turn_slight_right
    SpeechWord.NAV_TURN_RIGHT -> R.string.speech_word_nav_turn_right
    SpeechWord.NAV_TURN_SHARP_RIGHT -> R.string.speech_word_nav_turn_sharp_right
    SpeechWord.NAV_UTURN -> R.string.speech_word_nav_uturn
    SpeechWord.NAV_ROUNDABOUT -> R.string.speech_word_nav_roundabout
    SpeechWord.NAV_DESTINATION -> R.string.speech_word_nav_destination
    SpeechWord.MENU_PHONE -> R.string.speech_word_menu_phone
    SpeechWord.MENU_MESSAGES -> R.string.speech_word_menu_messages
    SpeechWord.MENU_NAVIGATION -> R.string.speech_word_menu_navigation
}
