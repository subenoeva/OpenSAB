package com.opensab.domain.call

/** Why the cluster is, or is not, going to see an incoming call. */
enum class CallAccessState {

    READY,

    /**
     * The rider has not granted notification access. It is the same system screen the app already
     * sends them to for media, so a rider who plays music has nothing left to grant.
     */
    NEEDS_NOTIFICATION_ACCESS,

    /**
     * Below API 31 there is no `Notification$CallStyle`, so there is no route at all. Without
     * saying so the parser would turn every notification down, nothing would error, and the rider
     * would never learn why the cluster never rings.
     */
    UNSUPPORTED;

    /**
     * Only [NEEDS_NOTIFICATION_ACCESS] has somewhere to send the rider. A row that stays clickable
     * in the other two is a control that says "press me" over a press that does nothing.
     */
    val isActionable: Boolean get() = this == NEEDS_NOTIFICATION_ACCESS
}

/** Kept pure so the suite covers it and the Compose row only paints what it is told. */
object CallAccessPolicy {

    /**
     * The telecom standby (`ANSWER_PHONE_CALLS`) takes no part: it is not on the main path, and no
     * dialer has been observed needing it.
     */
    fun resolve(sdkInt: Int, listenerEnabled: Boolean): CallAccessState = when {
        sdkInt < ANDROID_12 -> CallAccessState.UNSUPPORTED
        !listenerEnabled -> CallAccessState.NEEDS_NOTIFICATION_ACCESS
        else -> CallAccessState.READY
    }

    /** `Build.VERSION_CODES.S`, spelled out so this domain stays free of Android entirely. */
    private const val ANDROID_12 = 31
}
