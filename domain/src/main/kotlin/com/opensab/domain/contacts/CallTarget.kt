package com.opensab.domain.contacts

/**
 * One row of the cursor: a contact flattened by number.
 *
 * This is the piece that makes the rest cheap. A contact with three numbers flattens into three
 * targets and three contacts that tie flatten into three targets, so "which Ana" and "which number
 * of Ana" are **the same list** — one mechanism instead of two, and the disambiguation face the
 * design turned down never has to exist.
 */
data class CallTarget(
    val displayName: String,
    val number: String,
    val kind: PhoneKind
)
