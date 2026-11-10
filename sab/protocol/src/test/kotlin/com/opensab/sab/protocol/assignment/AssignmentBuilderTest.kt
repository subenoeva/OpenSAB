package com.opensab.sab.protocol.assignment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssignmentBuilderTest {

    @Test
    fun standardSequenceContainsTenFunctionsAndSeventeenColors() {
        val sequence = AssignmentBuilder.buildStandardAssignmentSequence(colorRegistrationNeeded = true)

        // 2 HID functions + 8 standard functions + 17 colors = 27 records
        assertEquals(27, sequence.size)

        val hidFunctions = sequence.filterIsInstance<AssignmentRecord.FunctionHid>()
        assertEquals(2, hidFunctions.size)
        assertEquals(listOf(FunctionId.VOLUME_UP, FunctionId.VOLUME_DOWN), hidFunctions.map { it.functionId })

        val standardFunctions = sequence.filterIsInstance<AssignmentRecord.Function>()
        assertEquals(8, standardFunctions.size)

        val allFunctions = hidFunctions.map { it.functionId } + standardFunctions.map { it.functionId }
        assertEquals(FunctionId.entries.toSet(), allFunctions.toSet())

        val colors = sequence.filterIsInstance<AssignmentRecord.Color>()
        assertEquals(17, colors.size)
    }

    @Test
    fun standardSequenceDoesNotRegisterPureWhiteOrPureBlack() {
        val colors = AssignmentBuilder.buildStandardAssignmentSequence()
            .filterIsInstance<AssignmentRecord.Color>()
            .map { it.colorId }

        assertFalse("PURE_WHITE is a fixed cluster colour, not a palette slot", colors.contains(ColorId.PURE_WHITE.id))
        assertFalse("PURE_BLACK is a fixed cluster colour, not a palette slot", colors.contains(ColorId.PURE_BLACK.id))
        assertEquals(ColorId.entries.drop(2).map { it.id }, colors)
    }

    @Test
    fun standardSequenceNeverContainsTheEndAssignmentSentinel() {
        val sequence = AssignmentBuilder.buildStandardAssignmentSequence()

        assertTrue(
            "END_ASSIGNMENT is a separate write sent after the batch is validated",
            sequence.none { it is AssignmentRecord.EndAssignment }
        )
    }

    @Test
    fun sequenceWithoutColorRegistrationContainsOnlyFunctions() {
        val sequence = AssignmentBuilder.buildStandardAssignmentSequence(colorRegistrationNeeded = false)

        assertEquals(10, sequence.size)
        assertTrue(sequence.filterIsInstance<AssignmentRecord.Color>().isEmpty())
    }

    @Test
    fun requestIdsAreSequentialStartingAtOne() {
        val sequence = AssignmentBuilder.buildStandardAssignmentSequence()

        assertEquals((1..27).toList(), sequence.mapNotNull { it.requestId?.toInt() })
    }
}
