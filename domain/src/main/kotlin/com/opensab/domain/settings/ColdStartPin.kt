package com.opensab.domain.settings

/** The pinned media ID for [packageName], or null: a pin issued by another app is never handed out. */
fun AppSettings.coldStartMediaIdFor(packageName: String): String? =
    coldStartMediaId?.takeIf { coldStartMediaPackage == packageName }
