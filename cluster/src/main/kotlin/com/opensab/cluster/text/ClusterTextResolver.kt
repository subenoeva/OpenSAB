package com.opensab.cluster.text

import com.opensab.domain.weather.WmoCaption

/**
 * Turns a named cluster line into the words the rider reads. The engine sees neither Android nor
 * `R`, so a page says *which* line with a [ClusterToken] and the app's implementation says it in a
 * language.
 */
fun interface ClusterTextResolver {

    /** The line, in the rider's language. Never null and never empty: every token has a string. */
    fun resolve(token: ClusterToken): String
}

/**
 * The same seam for the one piece of cluster copy that is persisted.
 *
 * A second port rather than a marker interface both enums implement. A `sealed` marker cannot work —
 * Kotlin wants the subclasses in the same package, and [com.opensab.domain.weather.WmoCaption] lives
 * with the thing that persists it — and an unsealed one would force an `else` branch into the
 * resolver, a runtime hole where the compiler should refuse a token with no string.
 */
fun interface WeatherCaptionResolver {
    fun resolve(caption: WmoCaption): String
}
