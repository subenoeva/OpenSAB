package com.opensab.cluster.nav

import com.opensab.cluster.PageProvider
import com.opensab.domain.nav.NavInstruction

internal fun navEntryPage(
    navInstruction: NavInstruction?,
    livePage: () -> PageProvider,
    menuPage: () -> PageProvider
): PageProvider = if (navInstruction != null) livePage() else menuPage()
