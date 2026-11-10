package com.opensab.domain.notifications

/**
 * One `RemoteInput` of a reply action, reduced to what deciding whether it takes the dictated text
 * reads. [handle] is the platform `RemoteInput` in production, which the JVM cannot build.
 */
data class ReplyInput<out H : Any>(
    val handle: H,
    val resultKey: String,
    val allowFreeFormInput: Boolean,
    val choices: List<String>
)

/**
 * The inputs the dictated text goes into, in their order: only those that accept free-form input,
 * with or without choices. Empty means the action cannot carry a dictated reply.
 */
fun <H : Any> textInputsOf(inputs: List<ReplyInput<H>>): List<H> =
    inputs.filter { it.allowFreeFormInput }.map { it.handle }
