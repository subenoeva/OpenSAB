package com.opensab.cluster.text

import com.opensab.sab.protocol.projection.ProjectionBuffer

/**
 * What the cluster cuts a text field to, and it cuts in silence.
 *
 * The two numbers belong to the codec (`ProjectionBuffer`), so they are quoted here rather than
 * written again: a budget that drifted from the encoder would pass a test and truncate on the
 * motorcycle.
 */
enum class TextBudget(val maxBytes: Int) {
    LINE(ProjectionBuffer.MAX_TEXT_BYTES),
    POPUP(ProjectionBuffer.MAX_POPUP_TEXT_BYTES)
}

/**
 * Everything the instrument cluster says, named rather than written.
 *
 * The engine sees neither Android nor `R`, so a page names the sentence with one of these and
 * something below the seam says it in a language.
 *
 * **No arguments, and that is deliberate.** The voice needed `SpeechArg` because its phrases are
 * templates — "Message from {sender} on {app}". None of these is: a sender or a contact name
 * reaches the frame as *data*, in its own slot, never substituted inside a sentence. The day one of
 * them needs a template, `ClusterArg` is one file and a check at construction, exactly where it is
 * already written for the voice.
 */
enum class ClusterToken(val budget: TextBudget) {

    // ── Carousel headers ────────────────────────────────────────────────────
    HEADER_WEATHER(TextBudget.LINE),
    HEADER_NAVIGATION(TextBudget.LINE),
    HEADER_PHONE(TextBudget.LINE),
    HEADER_MESSAGES(TextBudget.LINE),
    HEADER_MUSIC(TextBudget.LINE),
    HEADER_VOLUME(TextBudget.LINE),

    /** The live navigation screen wears the short one; only its standby says the whole word. */
    HEADER_NAV_SHORT(TextBudget.LINE),

    // ── Standby lines ───────────────────────────────────────────────────────
    EMPTY_CALL(TextBudget.LINE),
    EMPTY_DATA(TextBudget.LINE),
    EMPTY_MESSAGES(TextBudget.LINE),
    EMPTY_NAVIGATION(TextBudget.LINE),

    // ── Call state ──────────────────────────────────────────────────────────
    CALL_RINGING(TextBudget.LINE),
    CALL_IN_CALL(TextBudget.LINE),
    CALL_ENDED(TextBudget.LINE),
    CALL_CALLING(TextBudget.LINE),
    CALL_FAILED(TextBudget.LINE),

    // ── Voice dialling ──────────────────────────────────────────────────────
    DIAL_SAY_A_NAME(TextBudget.LINE),
    DIAL_NOT_IN_CONTACTS(TextBudget.LINE),

    // ── Number kinds ────────────────────────────────────────────────────────
    PHONE_KIND_MOBILE(TextBudget.LINE),
    PHONE_KIND_HOME(TextBudget.LINE),
    PHONE_KIND_WORK(TextBudget.LINE),
    PHONE_KIND_MAIN(TextBudget.LINE),
    PHONE_KIND_OTHER(TextBudget.LINE),

    // ── Call menu ───────────────────────────────────────────────────────────
    CALL_MENU_BY_VOICE(TextBudget.LINE),
    CALL_MENU_FAVORITES(TextBudget.LINE),

    // ── Message menu ─────────────────────────────────────────────────────────
    MESSAGE_MENU_BY_VOICE(TextBudget.LINE),
    MESSAGE_MENU_FAVORITES(TextBudget.LINE),

    // ── Message reply ───────────────────────────────────────────────────────
    REPLY_SAY_YOUR_MESSAGE(TextBudget.LINE),
    REPLY_SENDING(TextBudget.LINE),
    REPLY_SENT(TextBudget.LINE),
    REPLY_NOT_SENT(TextBudget.LINE),

    // ── Volume ──────────────────────────────────────────────────────────────
    VOLUME_MUTE(TextBudget.LINE),

    // ── Navigation voice search ─────────────────────────────────────────────
    NAV_MENU_VOICE_SEARCH(TextBudget.LINE),
    NAV_VOICE_LISTENING(TextBudget.LINE),
    NAV_LAUNCHING(TextBudget.LINE),
    NAV_VOICE_NOTHING_UNDERSTOOD(TextBudget.LINE),
    NAV_VOICE_NO_APP(TextBudget.LINE),
    NAV_VOICE_AMBIGUOUS(TextBudget.LINE),
    NAV_VOICE_UNLOCK_PHONE(TextBudget.LINE),

    // ── Listening ───────────────────────────────────────────────────────────
    /**
     * Not a failure of the recogniser: it heard, and there was nothing there. It sits with the nine
     * failures because the rider does the same thing about it — press and speak again.
     */
    LISTEN_NOTHING_UNDERSTOOD(TextBudget.LINE),

    LISTEN_NO_PERMISSION(TextBudget.LINE),
    LISTEN_NO_RECOGNIZER(TextBudget.LINE),
    LISTEN_NETWORK(TextBudget.LINE),
    LISTEN_AUDIO(TextBudget.LINE),
    LISTEN_SERVER(TextBudget.LINE),
    LISTEN_CLIENT(TextBudget.LINE),
    LISTEN_BUSY(TextBudget.LINE),
    LISTEN_LANGUAGE(TextBudget.LINE),
    LISTEN_UNKNOWN(TextBudget.LINE),

    /**
     * The microphone exists and is granted, but Android will not lend it to a service that was
     * started with the app out of sight. Opening the app is what fixes it, so that is what it says.
     */
    LISTEN_OPEN_APP(TextBudget.LINE),

    // ── Music loading & error ───────────────────────────────────────────────
    LOADING(TextBudget.LINE),
    MUSIC_ERROR(TextBudget.LINE)
}

