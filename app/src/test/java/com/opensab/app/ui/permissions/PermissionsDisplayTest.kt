package com.opensab.app.ui.permissions

import com.opensab.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionsDisplayTest {

    private fun row(id: PermissionId, status: PermissionStatus, essential: Boolean = false) =
        PermissionRow(PermissionEntry(id, PermissionKind.RUNTIME, essential), status)

    @Test
    fun leavesUnsupportedRowsOutOfBothHalvesOfTheCount() {
        val progress = listOf(
            row(PermissionId.BLUETOOTH, PermissionStatus.GRANTED),
            row(PermissionId.CONTACTS, PermissionStatus.DENIED),
            row(PermissionId.ANSWER_CALLS, PermissionStatus.UNSUPPORTED)
        ).progress()

        assertEquals(1, progress.granted)
        assertEquals(2, progress.grantable)
        assertEquals(0.5f, progress.fraction)
    }

    @Test
    fun blocksWhileAnEssentialRowIsDenied() = assertEquals(
        PermissionsTone.BLOCKING,
        listOf(
            row(PermissionId.BLUETOOTH, PermissionStatus.DENIED, essential = true),
            row(PermissionId.CONTACTS, PermissionStatus.GRANTED)
        ).progress().tone
    )

    @Test
    fun isCompleteOnceEveryGrantableRowIsGranted() = assertEquals(
        PermissionsTone.COMPLETE,
        listOf(
            row(PermissionId.BLUETOOTH, PermissionStatus.GRANTED, essential = true),
            row(PermissionId.ANSWER_CALLS, PermissionStatus.UNSUPPORTED)
        ).progress().tone
    )

    @Test
    fun isPartialWithOnlyOptionalRowsDenied() = assertEquals(
        PermissionsTone.PARTIAL,
        listOf(
            row(PermissionId.BLUETOOTH, PermissionStatus.GRANTED, essential = true),
            row(PermissionId.CONTACTS, PermissionStatus.DENIED)
        ).progress().tone
    )

    @Test
    fun isNeverCompleteWithNothingGrantable() {
        val progress = listOf(row(PermissionId.ANSWER_CALLS, PermissionStatus.UNSUPPORTED)).progress()

        assertEquals(PermissionsTone.PARTIAL, progress.tone)
        assertEquals(0f, progress.fraction)
    }

    @Test
    fun paintsEachRowByWhatItBlocks() {
        assertEquals(PermissionRowTone.BLOCKING, row(PermissionId.BLUETOOTH, PermissionStatus.DENIED, essential = true).tone)
        assertEquals(PermissionRowTone.PENDING, row(PermissionId.CONTACTS, PermissionStatus.DENIED).tone)
        assertEquals(PermissionRowTone.GRANTED, row(PermissionId.CONTACTS, PermissionStatus.GRANTED).tone)
        assertEquals(PermissionRowTone.UNSUPPORTED, row(PermissionId.ANSWER_CALLS, PermissionStatus.UNSUPPORTED).tone)
    }

    @Test
    fun onlyADeniedRowIsActionable() {
        assertTrue(row(PermissionId.CONTACTS, PermissionStatus.DENIED).isActionable)
        assertFalse(row(PermissionId.CONTACTS, PermissionStatus.GRANTED).isActionable)
        assertFalse(row(PermissionId.ANSWER_CALLS, PermissionStatus.UNSUPPORTED).isActionable)
    }

    @Test
    fun explainsTheAndroid12CutOnAnUnsupportedAnswerCallsRow() {
        assertEquals(
            R.string.permission_answer_calls_unsupported_desc,
            row(PermissionId.ANSWER_CALLS, PermissionStatus.UNSUPPORTED).descriptionRes()
        )
        assertEquals(
            R.string.permission_answer_calls_desc,
            row(PermissionId.ANSWER_CALLS, PermissionStatus.DENIED).descriptionRes()
        )
    }

    @Test
    fun givesEveryPermissionItsOwnTitleAndDescription() {
        val ids = PermissionId.entries
        assertEquals(ids.size, ids.map { it.titleRes() }.toSet().size)
        assertEquals(ids.size, ids.map { it.descriptionRes() }.toSet().size)
    }

    @Test
    fun tellsASystemDialogFromAWalkIntoSettings() =
        assertEquals(PermissionKind.entries.size, PermissionKind.entries.map { it.actionRes() }.toSet().size)

    @Test
    fun labelsEachStatusDifferently() =
        assertEquals(PermissionStatus.entries.size, PermissionStatus.entries.map { it.labelRes() }.toSet().size)
}
