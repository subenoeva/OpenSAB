package com.opensab.sab.session

/**
 * Test-only helper that marks [this] connected without going through [SabSessionController].
 *
 * [SabSession.connected]'s setter is `internal` because [SabSessionController] is its sole
 * production owner (see [SabSession]'s KDoc). Tests in other modules cannot see that setter across
 * the module boundary, so this fixture is the one sanctioned way for them to stand up a connected
 * session without widening the production API.
 */
fun SabSession.markConnectedForTest(state: SabSessionState.Connected) {
    connected = state
}

/**
 * Test-only helper that closes [this] without going through [SabSessionController].
 *
 * [SabSession.close] is `internal` for the same single-ownership reason as [markConnectedForTest].
 */
suspend fun SabSession.closeForTest(reason: CloseReason) {
    close(reason)
}
