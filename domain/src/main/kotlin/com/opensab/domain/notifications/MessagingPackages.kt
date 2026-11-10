package com.opensab.domain.notifications

/**
 * The messaging apps whose notifications reach the cluster, with the label each one carries there.
 *
 * A table and not `PackageManager.getApplicationLabel` for two reasons: it keeps the filter in pure
 * Kotlin, where the suite can cover it, and it keeps the label deterministic instead of whatever
 * the launcher happens to call the app on this phone.
 *
 * Nothing outside this list interrupts the rider. `CATEGORY_MESSAGE` was deliberately not used: a
 * cluster at 90 km/h is no place for a bot or a bank alert that declares itself messaging.
 */
object MessagingPackages {

    private val LABELS: Map<String, String> = mapOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "org.telegram.messenger" to "Telegram",
        "org.thoughtcrime.securesms" to "Signal",
        "com.facebook.orca" to "Messenger",
        "jp.naver.line.android" to "LINE",
        "com.viber.voip" to "Viber",
        "com.tencent.mm" to "WeChat",
        "com.kakao.talk" to "KakaoTalk",
        "com.icq.mobile.client" to "ICQ",
        "com.google.android.apps.messaging" to "Messages",
        "com.google.android.talk" to "Hangouts",
        "com.samsung.android.messaging" to "Samsung Messages"
    )

    val all: Set<String> = LABELS.keys

    fun isMessaging(packageName: String): Boolean = packageName in LABELS

    /** Null for a package that never reaches the cluster. */
    fun labelOf(packageName: String): String? = LABELS[packageName]
}
