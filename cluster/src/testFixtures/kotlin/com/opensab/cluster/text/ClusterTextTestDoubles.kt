package com.opensab.cluster.text

import com.opensab.domain.weather.WmoCaption

/**
 * Stable English stand-ins for tests that assert on real words — one of them as **bytes**, because
 * it pins a field regression by offset (`RootMenuPageProviderTest.encodesTheMusicItemWithATrackTitle…`).
 *
 * It is **not** an assertion about the translation, and nothing here is compared against
 * `values/strings.xml`. That a page takes its words from the resolver at all is what the `[TOKEN]`
 * lambdas of the copy tests prove, and only they prove it.
 *
 * Exhaustive and deliberately without an `else`, like the table it stands in for: a token added to
 * the engine must not compile here either.
 */
val englishClusterText = ClusterTextResolver { it.inEnglish() }

/** The caption half of the same stand-in, for the one page that paints a forecast. */
val englishWeatherCaptions = WeatherCaptionResolver { it.inEnglish() }

/** Bundles the two stand-ins into a single presentation double for JVM tests. */
val englishPresentation = ClusterPresentation(englishClusterText, englishWeatherCaptions)

private fun ClusterToken.inEnglish(): String = when (this) {
    ClusterToken.HEADER_WEATHER -> "Weather"
    ClusterToken.HEADER_NAVIGATION -> "Navigation"
    ClusterToken.HEADER_PHONE -> "Phone"
    ClusterToken.HEADER_MESSAGES -> "Messages"
    ClusterToken.HEADER_MUSIC -> "Music"
    ClusterToken.HEADER_VOLUME -> "Volume"
    ClusterToken.HEADER_NAV_SHORT -> "Nav"
    ClusterToken.EMPTY_CALL -> "No Call"
    ClusterToken.EMPTY_DATA -> "No Data"
    ClusterToken.EMPTY_MESSAGES -> "No Messages"
    ClusterToken.EMPTY_NAVIGATION -> "No Navigation"
    ClusterToken.CALL_RINGING -> "Ringing"
    ClusterToken.CALL_IN_CALL -> "In Call"
    ClusterToken.CALL_ENDED -> "Ended"
    ClusterToken.CALL_CALLING -> "Calling..."
    ClusterToken.CALL_FAILED -> "Call failed"
    ClusterToken.DIAL_SAY_A_NAME -> "Say a name"
    ClusterToken.DIAL_NOT_IN_CONTACTS -> "Not in contacts"
    ClusterToken.PHONE_KIND_MOBILE -> "Mobile"
    ClusterToken.PHONE_KIND_HOME -> "Home"
    ClusterToken.PHONE_KIND_WORK -> "Work"
    ClusterToken.PHONE_KIND_MAIN -> "Main"
    ClusterToken.PHONE_KIND_OTHER -> "Other"
    ClusterToken.CALL_MENU_BY_VOICE -> "By voice"
    ClusterToken.CALL_MENU_FAVORITES -> "Favorites"
    ClusterToken.MESSAGE_MENU_BY_VOICE -> "By voice"
    ClusterToken.MESSAGE_MENU_FAVORITES -> "Favorites"
    ClusterToken.REPLY_SAY_YOUR_MESSAGE -> "Say your message"
    ClusterToken.REPLY_SENDING -> "Sending..."
    ClusterToken.REPLY_SENT -> "Sent"
    ClusterToken.REPLY_NOT_SENT -> "Not sent"
    ClusterToken.VOLUME_MUTE -> "Mute"
    ClusterToken.NAV_MENU_VOICE_SEARCH -> "Voice Search"
    ClusterToken.NAV_VOICE_LISTENING -> "Listening..."
    ClusterToken.NAV_LAUNCHING -> "Starting route..."
    ClusterToken.NAV_VOICE_NOTHING_UNDERSTOOD -> "Nothing understood"
    ClusterToken.NAV_VOICE_NO_APP -> "No navigation app"
    ClusterToken.NAV_VOICE_AMBIGUOUS -> "Select on phone"
    ClusterToken.NAV_VOICE_UNLOCK_PHONE -> "Unlock your phone"
    ClusterToken.LISTEN_NOTHING_UNDERSTOOD -> "I didn't catch that"
    ClusterToken.LISTEN_NO_PERMISSION -> "Microphone denied"
    ClusterToken.LISTEN_NO_RECOGNIZER -> "No voice input"
    ClusterToken.LISTEN_NETWORK -> "No network"
    ClusterToken.LISTEN_AUDIO -> "Microphone error"
    ClusterToken.LISTEN_SERVER -> "Voice service down"
    ClusterToken.LISTEN_CLIENT -> "Voice input error"
    ClusterToken.LISTEN_BUSY -> "Microphone busy"
    ClusterToken.LISTEN_LANGUAGE -> "Language unsupported"
    ClusterToken.LISTEN_UNKNOWN -> "Could not listen"
    ClusterToken.LISTEN_OPEN_APP -> "Open OpenSAB for mic"
    ClusterToken.LOADING -> "Loading"
    ClusterToken.MUSIC_ERROR -> "App Error"
}


private fun WmoCaption.inEnglish(): String = when (this) {
    WmoCaption.CLEAR -> "Clear"
    WmoCaption.MAINLY_CLEAR -> "Mainly clear"
    WmoCaption.PARTLY_CLOUDY -> "Partly cloudy"
    WmoCaption.OVERCAST -> "Overcast"
    WmoCaption.FOG -> "Fog"
    WmoCaption.RIME_FOG -> "Rime fog"
    WmoCaption.DRIZZLE -> "Drizzle"
    WmoCaption.FREEZING_DRIZZLE -> "Freezing drizzle"
    WmoCaption.RAIN -> "Rain"
    WmoCaption.FREEZING_RAIN -> "Freezing rain"
    WmoCaption.SNOW -> "Snow"
    WmoCaption.SNOW_GRAINS -> "Snow grains"
    WmoCaption.RAIN_SHOWERS -> "Rain showers"
    WmoCaption.SNOW_SHOWERS -> "Snow showers"
    WmoCaption.THUNDERSTORM -> "Thunderstorm"
    WmoCaption.THUNDERSTORM_WITH_HAIL -> "Thunderstorm with hail"
    WmoCaption.UNKNOWN -> "Unknown"
}
