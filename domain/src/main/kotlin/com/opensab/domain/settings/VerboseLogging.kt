package com.opensab.domain.settings

/**
 * What "Verbose Protocol Logging" means when the rider never touched it: on in a debuggable build,
 * where a garage run wants the hex frames without a step to forget, off in a release, which logs
 * nothing anyway. Same shape as [resolveTemperatureUnit].
 */
fun resolveVerboseLogging(chosen: Boolean?, debuggableBuild: Boolean): Boolean = chosen ?: debuggableBuild
