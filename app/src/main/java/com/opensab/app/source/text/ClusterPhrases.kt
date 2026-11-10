package com.opensab.app.source.text

import androidx.annotation.StringRes
import com.opensab.app.R
import com.opensab.cluster.text.ClusterToken
import com.opensab.domain.weather.WmoCaption

/**
 * Exhaustive over [ClusterToken] and deliberately without an `else` branch: a token added to the
 * engine **must not compile** until somebody has given it a string. That the string exists in both
 * `values/` and `values-es/` is what `everyClusterTokenIsTranslated` checks.
 *
 * There is no cluster dimension here, unlike [com.opensab.app.source.speech.SpeechPhrases]: `Weather`
 * is `Weather` on both, and what a segment cluster needs (no accents) it already gets from
 * `AsciiFoldingTextSanitizer`, which `VehicleFramePolicy` runs over every frame it degrades.
 */
@StringRes
fun ClusterToken.string(): Int = when (this) {
    ClusterToken.HEADER_WEATHER -> R.string.cluster_header_weather
    ClusterToken.HEADER_NAVIGATION -> R.string.cluster_header_navigation
    ClusterToken.HEADER_PHONE -> R.string.cluster_header_phone
    ClusterToken.HEADER_MESSAGES -> R.string.cluster_header_messages
    ClusterToken.HEADER_MUSIC -> R.string.cluster_header_music
    ClusterToken.HEADER_VOLUME -> R.string.cluster_header_volume
    ClusterToken.HEADER_NAV_SHORT -> R.string.cluster_header_nav_short
    ClusterToken.EMPTY_CALL -> R.string.cluster_empty_call
    ClusterToken.EMPTY_DATA -> R.string.cluster_empty_data
    ClusterToken.EMPTY_MESSAGES -> R.string.cluster_empty_messages
    ClusterToken.EMPTY_NAVIGATION -> R.string.cluster_empty_navigation
    ClusterToken.CALL_RINGING -> R.string.cluster_call_ringing
    ClusterToken.CALL_IN_CALL -> R.string.cluster_call_in_call
    ClusterToken.CALL_ENDED -> R.string.cluster_call_ended
    ClusterToken.CALL_CALLING -> R.string.cluster_call_calling
    ClusterToken.CALL_FAILED -> R.string.cluster_call_failed
    ClusterToken.DIAL_SAY_A_NAME -> R.string.cluster_dial_say_a_name
    ClusterToken.DIAL_NOT_IN_CONTACTS -> R.string.cluster_dial_not_in_contacts
    ClusterToken.PHONE_KIND_MOBILE -> R.string.cluster_phone_kind_mobile
    ClusterToken.PHONE_KIND_HOME -> R.string.cluster_phone_kind_home
    ClusterToken.PHONE_KIND_WORK -> R.string.cluster_phone_kind_work
    ClusterToken.PHONE_KIND_MAIN -> R.string.cluster_phone_kind_main
    ClusterToken.PHONE_KIND_OTHER -> R.string.cluster_phone_kind_other
    ClusterToken.CALL_MENU_BY_VOICE -> R.string.cluster_call_menu_by_voice
    ClusterToken.CALL_MENU_FAVORITES -> R.string.cluster_call_menu_favorites
    ClusterToken.MESSAGE_MENU_BY_VOICE -> R.string.cluster_message_menu_by_voice
    ClusterToken.MESSAGE_MENU_FAVORITES -> R.string.cluster_message_menu_favorites
    ClusterToken.REPLY_SAY_YOUR_MESSAGE -> R.string.cluster_reply_say_your_message
    ClusterToken.REPLY_SENDING -> R.string.cluster_reply_sending
    ClusterToken.REPLY_SENT -> R.string.cluster_reply_sent
    ClusterToken.REPLY_NOT_SENT -> R.string.cluster_reply_not_sent
    ClusterToken.VOLUME_MUTE -> R.string.cluster_volume_mute
    ClusterToken.NAV_MENU_VOICE_SEARCH -> R.string.cluster_nav_menu_voice_search
    ClusterToken.NAV_VOICE_LISTENING -> R.string.cluster_nav_voice_listening
    ClusterToken.NAV_LAUNCHING -> R.string.cluster_nav_launching
    ClusterToken.NAV_VOICE_NOTHING_UNDERSTOOD -> R.string.cluster_nav_voice_nothing_understood
    ClusterToken.NAV_VOICE_NO_APP -> R.string.cluster_nav_voice_no_app
    ClusterToken.NAV_VOICE_AMBIGUOUS -> R.string.cluster_nav_voice_ambiguous
    ClusterToken.NAV_VOICE_UNLOCK_PHONE -> R.string.cluster_nav_voice_unlock_phone
    ClusterToken.LISTEN_NOTHING_UNDERSTOOD -> R.string.cluster_listen_nothing_understood
    ClusterToken.LISTEN_NO_PERMISSION -> R.string.cluster_listen_no_permission
    ClusterToken.LISTEN_NO_RECOGNIZER -> R.string.cluster_listen_no_recognizer
    ClusterToken.LISTEN_NETWORK -> R.string.cluster_listen_network
    ClusterToken.LISTEN_AUDIO -> R.string.cluster_listen_audio
    ClusterToken.LISTEN_SERVER -> R.string.cluster_listen_server
    ClusterToken.LISTEN_CLIENT -> R.string.cluster_listen_client
    ClusterToken.LISTEN_BUSY -> R.string.cluster_listen_busy
    ClusterToken.LISTEN_LANGUAGE -> R.string.cluster_listen_language
    ClusterToken.LISTEN_UNKNOWN -> R.string.cluster_listen_unknown
    ClusterToken.LISTEN_OPEN_APP -> R.string.cluster_listen_open_app
    ClusterToken.LOADING -> R.string.cluster_loading
    ClusterToken.MUSIC_ERROR -> R.string.cluster_music_error
}


/**
 * The only table whose keys are written to disk.
 *
 * Exhaustive over [WmoCaption] and deliberately without an `else` branch, like the table above.
 * The seventeen are the whole vocabulary `WmoConditionMapper` can produce.
 *
 * Each of them shares the cluster's first line with the temperature —
 * `"${temperature}${unit} ${caption}"` — so the budget they have to fit is 30 bytes
 * minus the longest prefix `-40°C ` takes, which is 7. `Tormenta con granizo` spends 29 of the 30.
 */
@StringRes
fun WmoCaption.string(): Int = when (this) {
    WmoCaption.CLEAR -> R.string.cluster_weather_clear
    WmoCaption.MAINLY_CLEAR -> R.string.cluster_weather_mainly_clear
    WmoCaption.PARTLY_CLOUDY -> R.string.cluster_weather_partly_cloudy
    WmoCaption.OVERCAST -> R.string.cluster_weather_overcast
    WmoCaption.FOG -> R.string.cluster_weather_fog
    WmoCaption.RIME_FOG -> R.string.cluster_weather_rime_fog
    WmoCaption.DRIZZLE -> R.string.cluster_weather_drizzle
    WmoCaption.FREEZING_DRIZZLE -> R.string.cluster_weather_freezing_drizzle
    WmoCaption.RAIN -> R.string.cluster_weather_rain
    WmoCaption.FREEZING_RAIN -> R.string.cluster_weather_freezing_rain
    WmoCaption.SNOW -> R.string.cluster_weather_snow
    WmoCaption.SNOW_GRAINS -> R.string.cluster_weather_snow_grains
    WmoCaption.RAIN_SHOWERS -> R.string.cluster_weather_rain_showers
    WmoCaption.SNOW_SHOWERS -> R.string.cluster_weather_snow_showers
    WmoCaption.THUNDERSTORM -> R.string.cluster_weather_thunderstorm
    WmoCaption.THUNDERSTORM_WITH_HAIL -> R.string.cluster_weather_thunderstorm_with_hail
    WmoCaption.UNKNOWN -> R.string.cluster_weather_unknown
}
