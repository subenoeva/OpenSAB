package com.opensab.cluster.message

import com.opensab.cluster.PageProvider
import com.opensab.domain.message.MessageInfo

internal fun messageEntryPage(
    currentMessage: MessageInfo?,
    readPage: () -> PageProvider,
    menuPage: () -> PageProvider
): PageProvider = if (currentMessage != null) readPage() else menuPage()
