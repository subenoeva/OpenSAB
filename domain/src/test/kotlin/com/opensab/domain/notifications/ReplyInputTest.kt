package com.opensab.domain.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which `RemoteInput`s of the reply action take the dictated text: only those that accept free-form
 * input. A choice-only input (canned answers) would receive text it never offered.
 */
class ReplyInputTest {

    @Test
    fun onlyTheFreeFormInputsReceiveTheText() {
        val inputs = listOf(
            ReplyInput("quick", resultKey = "quick_reply", allowFreeFormInput = false, choices = listOf("👍", "Voy")),
            ReplyInput("reply", resultKey = "android.intent.extra.text", allowFreeFormInput = true, choices = emptyList())
        )

        assertEquals(listOf("reply"), textInputsOf(inputs))
    }

    /** Smart replies: the input offers choices and still takes free text. */
    @Test
    fun aFreeFormInputThatAlsoOffersChoicesReceivesTheText() {
        val inputs = listOf(
            ReplyInput("reply", resultKey = "android.intent.extra.text", allowFreeFormInput = true, choices = listOf("Ok", "Voy"))
        )

        assertEquals(listOf("reply"), textInputsOf(inputs))
    }

    @Test
    fun severalFreeFormInputsKeepTheirOrder() {
        val inputs = listOf(
            ReplyInput("first", resultKey = "a", allowFreeFormInput = true, choices = emptyList()),
            ReplyInput("choice", resultKey = "b", allowFreeFormInput = false, choices = listOf("Sí")),
            ReplyInput("second", resultKey = "c", allowFreeFormInput = true, choices = emptyList())
        )

        assertEquals(listOf("first", "second"), textInputsOf(inputs))
    }

    @Test
    fun anActionWithOnlyChoiceInputsHasNothingToFill() {
        val inputs = listOf(
            ReplyInput("quick", resultKey = "quick_reply", allowFreeFormInput = false, choices = listOf("Sí", "No"))
        )

        assertEquals(emptyList<String>(), textInputsOf(inputs))
    }
}
