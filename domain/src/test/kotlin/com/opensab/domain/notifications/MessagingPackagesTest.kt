package com.opensab.domain.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The allowlist the cluster is willing to interrupt for. It lives here rather than inside the
 * listener service so the suite actually covers it.
 */
class MessagingPackagesTest {

    @Test
    fun acceptsTheThirteenPackagesOfTheReferenceClient() {
        val expected = listOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "org.thoughtcrime.securesms",
            "com.facebook.orca",
            "jp.naver.line.android",
            "com.viber.voip",
            "com.tencent.mm",
            "com.kakao.talk",
            "com.icq.mobile.client",
            "com.google.android.apps.messaging",
            "com.google.android.talk",
            "com.samsung.android.messaging"
        )

        expected.forEach { pkg ->
            assertTrue("$pkg must be treated as a messaging app", MessagingPackages.isMessaging(pkg))
        }
        assertEquals(13, expected.size)
    }

    @Test
    fun rejectsEverythingElse() {
        listOf(
            "com.instagram.android",
            "com.twitter.android",
            "com.google.android.apps.maps",
            "com.opensab",
            ""
        ).forEach { pkg ->
            assertFalse("$pkg must not be treated as a messaging app", MessagingPackages.isMessaging(pkg))
        }
    }

    @Test
    fun labelsTheAppOnTheCluster() {
        assertEquals("WhatsApp", MessagingPackages.labelOf("com.whatsapp"))
        assertEquals("Telegram", MessagingPackages.labelOf("org.telegram.messenger"))
        assertEquals("Signal", MessagingPackages.labelOf("org.thoughtcrime.securesms"))
        assertEquals("Messages", MessagingPackages.labelOf("com.google.android.apps.messaging"))
    }

    @Test
    fun hasALabelForEveryPackageItAccepts() {
        MessagingPackages.all.forEach { pkg ->
            assertTrue("$pkg is accepted but has no label", MessagingPackages.labelOf(pkg) != null)
        }
        assertEquals(13, MessagingPackages.all.size)
    }

    @Test
    fun hasNoLabelForAPackageItRejects() {
        assertNull(MessagingPackages.labelOf("com.instagram.android"))
    }
}
