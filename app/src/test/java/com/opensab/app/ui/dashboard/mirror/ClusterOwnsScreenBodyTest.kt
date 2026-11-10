package com.opensab.app.ui.dashboard.mirror

import com.opensab.app.R
import com.opensab.cluster.mirror.MirrorState
import org.junit.Assert.assertEquals
import org.junit.Test

class ClusterOwnsScreenBodyTest {

    @Test
    fun explainsTheTachometerWhileNothingHasGoneWrong() {
        assertEquals(R.string.mirror_state_cluster_owns_body, MirrorState.ClusterOwnsScreen().bodyRes())
        assertEquals(
            R.string.mirror_state_cluster_owns_body,
            MirrorState.ClusterOwnsScreen(handoverPending = true).bodyRes()
        )
    }

    @Test
    fun suggestsTheDpadOnceAWaitForTheScreenHasRunOut() {
        assertEquals(
            R.string.mirror_state_grant_overdue_body,
            MirrorState.ClusterOwnsScreen(grantOverdue = true).bodyRes()
        )
    }
}
