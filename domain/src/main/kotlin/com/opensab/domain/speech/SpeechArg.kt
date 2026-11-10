package com.opensab.domain.speech

/**
 * One argument of a phrase, before it has a language.
 *
 * Not a `String`, because a phrase can carry a plural: the hour of a forecast line needs a plural
 * form, and `com.opensab.cluster` cannot see Android's `R`, so it cannot produce "3 hours" and must
 * not try. It names the quantity and the platform half (in `SpeechPhrases`) says it.
 *
 * The other cheap answer — the source publishing the fragment already localized — is not dearer,
 * it is wrong, and the cache is what proves it: a `WeatherInfo` is serialized to DataStore with a
 * two-hour TTL, so a localized "in 3 hours" freezes on disk and lies the moment it is restored,
 * and it keeps speaking the old language after the rider changes the phone's.
 */
sealed interface SpeechArg {

    /** A proper noun or a body of text: said as it is, whatever the language. */
    data class Text(val value: String) : SpeechArg

    /** A phone number or digit string to be pronounced digit-by-digit. */
    data class Digits(val value: String) : SpeechArg

    /** A number said with its noun, which is a plural in every language and a table in Android. */
    data class Quantity(val token: SpeechQuantity, val count: Int) : SpeechArg

    /** A word this app owns and translates, like the sky the rider is riding under. */
    data class Word(val token: SpeechWord) : SpeechArg
}

/**
 * The nouns a number is said with. One entry per plural resource, and the table that gives each of
 * them one is `SpeechQuantity.plural` (`SpeechPhrases.kt`, `:app`) — the only file of this package that
 * sees `R`.
 *
 * The unit is deliberately not part of this: a temperature is spoken as "21 degrees" and never as
 * "21 degrees Celsius", because the rider chose the scale in Settings and has just read it on the
 * carousel entry he came from. Repeating it every three seconds under a helmet is noise.
 */
enum class SpeechQuantity { HOURS, DEGREES, PERCENT }

/**
 * A word the app translates itself, as opposed to one it merely repeats.
 *
 * The weather ones are keyed on [com.opensab.domain.weather.WeatherConditionType] — nine values —
 * and not on the sixteen captions of the WMO table, because the type is the collapse the icons
 * already use, it is what the cache persists, and it is stable. Localizing sixteen captions to say
 * "showers" instead of "rain" changes no decision a rider takes with a helmet on.
 */
enum class SpeechWord {
    WEATHER_SUNNY,
    WEATHER_CLEAR,
    WEATHER_CLOUDY,
    WEATHER_SUN_AND_CLOUD,
    WEATHER_RAIN,
    WEATHER_STORM,
    WEATHER_SNOW,
    WEATHER_FOG,
    WEATHER_UNKNOWN,
    NAV_STRAIGHT,
    NAV_TURN_SLIGHT_LEFT,
    NAV_TURN_LEFT,
    NAV_TURN_SHARP_LEFT,
    NAV_TURN_SLIGHT_RIGHT,
    NAV_TURN_RIGHT,
    NAV_TURN_SHARP_RIGHT,
    NAV_UTURN,
    NAV_ROUNDABOUT,
    NAV_DESTINATION,
    MENU_PHONE,
    MENU_MESSAGES,
    MENU_NAVIGATION
}
