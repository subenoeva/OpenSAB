package com.opensab.domain.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val autoConnect: Boolean = true,
    val autoDisconnect: Boolean = true,

    val keepScreenOnWhileRiding: Boolean = true,
    val showOnLockScreen: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,

    /**
     * The root carousel as the rider arranged it, stored as names.
     *
     * Empty means untouched: [resolveRootMenu] answers with the factory order, every entry visible.
     */
    val rootMenuOrder: List<String> = emptyList(),
    val rootMenuHidden: Set<String> = emptySet(),

    val favoriteMediaPackage: String? = null,
    val autoResumeMusicOnConnect: Boolean = false,
    val handlebarVolumeControl: Boolean = true,
    val muteAction: MuteAction = MuteAction.PAUSE_MUSIC,

    /**
     * What the favourite player is told to play on a headless cold start.
     *
     * [coldStartMediaPackage] is what makes this per-app without a map: a media ID means nothing
     * outside the app that issued it, so a pin whose package no longer matches the favourite is
     * ignored rather than dispatched. [coldStartMediaLabel] is kept so the settings row can name
     * the pinned content without connecting to the player just to draw a title.
     */
    val coldStartMediaId: String? = null,
    val coldStartMediaLabel: String? = null,
    val coldStartMediaPackage: String? = null,

    val interceptGpsNotifications: Boolean = true,

    /** null means the rider has not chosen: `resolveTemperatureUnit` falls back to the country. */
    val temperatureUnit: TemperatureUnit? = null,

    val inboundCallMode: InboundCallMode = InboundCallMode.ALERT,

    val inboundMessageMode: InboundMessageMode = InboundMessageMode.PROMPT,

    /**
     * The earcons' switch, and only theirs: silencing the voice does not silence the beeps and
     * vice versa. Read by nothing but `GatedCuePlayer`, the one place a cue is decided.
     */
    val soundCuesEnabled: Boolean = true,

    /** In [VoiceVerbosity.NONE] nothing is spoken, but earcons still follow [soundCuesEnabled]. */
    val voiceVerbosity: VoiceVerbosity = VoiceVerbosity.STANDARD,

    /**
     * The fixed replies the rider wrote for himself, in his order.
     *
     * Empty means he has not touched them, and [resolveQuickReplies] falls back to the ones the app
     * ships translated. A `List` and not a `Set`: the order is the rider's.
     */
    val quickReplies: List<String> = emptyList(),

    /** Stored as written; [Tunable.REPLY_SENT_LINGER] clamps it on read, never on write. */
    val replySentLingerSeconds: Int = Tunable.REPLY_SENT_LINGER.default,

    /** Stored as written; [Tunable.WEATHER_REFRESH] clamps it on read, never on write. */
    val weatherRefreshMinutes: Int = Tunable.WEATHER_REFRESH.default,

    /** Stored as written; [Tunable.SAB_MODE_WAIT] clamps it on read, never on write. */
    val sabModeWaitSeconds: Int = Tunable.SAB_MODE_WAIT.default,

    val ctsClockSync: Boolean = true,

    /**
     * Whether the permissions screen has ever shown itself.
     *
     * A boolean and not a catalogue version: a later release that adds an entry does **not** open
     * the screen on its own again. It is written when the rider presses "Continue" and never when
     * the screen is drawn, so a first run killed from the task switcher offers the screen again.
     */
    val permissionsIntroSeen: Boolean = false,

    /**
     * The rider's choice for the byte-by-byte frame dumps (level V), or null if never made:
     * [resolveVerboseLogging] then answers by build. Never filters level D: the session journal's
     * summary counts debug lines.
     *
     * Serialized under a new name on purpose. Every store written before carries an explicit
     * `"verboseLogging":false` (`encodeDefaults`), a value that never had any effect; read as a choice it
     * would keep the dumps off on the debug phone that needs them.
     */
    @SerialName("verboseLoggingChoice")
    val verboseLogging: Boolean? = null,

    /** Normalized on read by [SessionLogLimit], never on write. */
    val sessionLogLimitMb: Int = SessionLogLimit.DEFAULT_MB,

    /**
     * Developer switch, off unless the rider turns it on: while the cluster has not handed the
     * screen over in a session, every HU_MODE it notifies asks for it again. Read at session open.
     */
    val sabModeRerequestOnHuMode: Boolean = false,

    /**
     * Developer switch, off unless the rider turns it on: when the cluster has not confirmed the
     * handover by the end of the opening window, frames are written anyway. Read at session open.
     */
    val projectWithoutSabModeGrant: Boolean = false
) {
    constructor(interceptIncomingCalls: Boolean) : this(
        inboundCallMode = if (interceptIncomingCalls) InboundCallMode.ALERT else InboundCallMode.IGNORE
    )

    fun copy(interceptIncomingCalls: Boolean): AppSettings =
        copy(inboundCallMode = if (interceptIncomingCalls) InboundCallMode.ALERT else InboundCallMode.IGNORE)

    /** Derived, not serialized. */
    val interceptIncomingCalls: Boolean
        get() = inboundCallMode == InboundCallMode.ALERT
}

enum class ThemeMode(val label: String) {
    SYSTEM("System Default"),
    DARK("Dark Theme (Recommended)"),
    LIGHT("Light Theme")
}
