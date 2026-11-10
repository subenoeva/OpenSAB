package com.opensab.logging.journal

/** The log lines that mark a session's lifecycle, and the strings the journal writes around them. */
internal object Anchors {
    val OPENED = Regex("""^SabSession #(\d+) opened for (\S+) \(origin=(\w+)\)""")
    val STARTED = Regex("""^SabSession #(\d+) started (\d+) participants:""")
    val CLOSING = Regex("""^SabSession #(\d+) closing \((\w+)\), (\d+) close actions""")
    val CLOSED_IN = Regex("""^SabSession #(\d+) closed in (\d+) ms""")
    const val JOURNAL_TAG = "Journal"
    const val HEADER_PREFIX = "Journal session file opened at "
    const val PROLOGUE_MARK = "---- prologue ----"
    const val SESSION_MARK = "---- session ----"
    const val MISSING_CLOSED_MARK = "Journal closed the session: no \"closed in\" 10 s after \"closing\""

    const val SAB_REQUEST = "Proactively requesting SAB_MODE"
    const val SAB_GRANTED = "DashboardMode changed to: SAB_MODE"
    const val SAB_NOT_GRANTED = "BTU has NOT granted SAB_MODE"
    const val FRAME_ACK = "BTU acknowledged frame successfully: commandId=0x01,"
    val REJECTED = Regex(
        """^BTU rejected projection frame: commandId=(0x[0-9A-Fa-f]{2}), requestId=(\d+), """ +
            """status=(0x[0-9A-Fa-f]{2}), errorPosition=(\S+)"""
    )

    const val CALL_FROM = "Call from "
    val CALL_GONE = Regex("""^Call \S+ is gone$""")
    const val MESSAGE_FROM = "Message from "
    const val NAV_MANEUVER = "Parsed navigation maneuver from "
    const val WEATHER_UPDATED = "Weather updated: "
    const val WEATHER_RESTORED = "Restored the last weather reading from cache"
    const val INDICATORS_CHANGED = "Active indicators changed to 0x"
    val HANDLEBAR_PRESS = Regex("""^Handlebar press (\w+)$""")
    const val MIRROR_PRESS = "Injecting mirror press "

    val LINK_CUE_FULL = Regex(
        """^Link cue (LINK_UP|LINK_DOWN) played on (.+?) on the (\w+) channel """ +
            """\(volume (\d+)/(\d+), muted=(true|false), interruptions=(\w+)\)$"""
    )
    val LINK_CUE_ROUTE_ONLY = Regex("""^Link cue (LINK_UP|LINK_DOWN) played on (.+)$""")
    val LINK_CUE_SKIPPED = Regex("""^Link cue (LINK_UP|LINK_DOWN) skipped: """)

    val DROPPED_ENTRIES = Regex("""^Journal dropped (\d+) entries between""")
    val EXCEPTION_TYPE = Regex("""^((?:[\w$]+\.)+[\w$]*(?:Exception|Error|Throwable))(?::|$)""")

    /**
     * How a notification subscription reported its end up to summarizer version 1, whatever ended
     * it. With a `CancellationException` it is the session stopping its own subscription, which the
     * app has since stopped logging as a warning; with anything else it is the link dying under it.
     */
    val SUBSCRIPTION_ENDED = setOf(
        "AssignmentControl notifications subscription terminated with error",
        "ControlPoint notifications subscription terminated with error",
        "FunctionNotification observation failed",
    )

    /** The known foreground-service-without-microphone failure ([KnownDefects.FGS_NO_MIC]). */
    const val FGS_FAILURE = "Failed to start foreground service with type 152"
}
