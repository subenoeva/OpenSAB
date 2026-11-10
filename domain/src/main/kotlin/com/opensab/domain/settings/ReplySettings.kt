package com.opensab.domain.settings

/**
 * An empty [chosen] means "not touched", and resolves to [defaults] — which the caller reads from
 * `R.array.default_quick_replies`, because the domain cannot see `R`.
 *
 * Blanks are dropped: a menu entry that sends an empty message sends nothing to somebody who is
 * waiting. A short list is **not** padded back up to the defaults' length — that would put back
 * the words the rider deleted on purpose.
 */
fun resolveQuickReplies(chosen: List<String>, defaults: List<String>): List<String> {
    val kept = chosen.filter { it.isNotBlank() }
    return kept.ifEmpty { defaults.filter { it.isNotBlank() } }
}
