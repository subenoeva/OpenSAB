package com.opensab.domain.weather

import kotlinx.serialization.Serializable

/**
 * The seventeen things the forecast can be called, named rather than written.
 *
 * **Its own enum, and not seventeen more `ClusterToken`s**, for one reason: this is the only thing
 * of the row that gets written to disk. Mixing a persisted enum with the screen tokens — which get
 * renamed and deleted with no consequence — is how a reordered entry breaks a cache one day.
 *
 * **And not `WeatherConditionType`**, which is already an enum and already in the segment: nine
 * values collapse seventeen captions. `Rain` and `Rain showers` are both `RAIN`, so localizing over
 * the type would change *what* the cluster says and not just the language it says it in, and
 * trimming the vocabulary is not a decision a translation gets to take.
 */
@Serializable
enum class WmoCaption {
    CLEAR,
    MAINLY_CLEAR,
    PARTLY_CLOUDY,
    OVERCAST,
    FOG,
    RIME_FOG,
    DRIZZLE,
    FREEZING_DRIZZLE,
    RAIN,
    FREEZING_RAIN,
    SNOW,
    SNOW_GRAINS,
    RAIN_SHOWERS,
    SNOW_SHOWERS,
    THUNDERSTORM,
    THUNDERSTORM_WITH_HAIL,

    /** Also what a reading stored before this enum existed comes back as. */
    UNKNOWN
}
