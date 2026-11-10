package com.opensab.cluster.call

import com.opensab.cluster.PageProvider
import com.opensab.domain.call.CallInfo

/**
 * What the `CALL` entry of the carousel opens: the live call, or the call menu when there is none.
 *
 * A function rather than a `when` in the wiring, because a branch inside a Hilt module is one no test
 * can reach, and this one decides whether the rider lands on his live call or on the dialler.
 */
internal fun callEntryPage(
    activeCall: CallInfo?,
    livePage: () -> PageProvider,
    menuPage: () -> PageProvider
): PageProvider = if (activeCall != null) livePage() else menuPage()
