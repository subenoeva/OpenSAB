package com.opensab.domain.music

data class MediaAppInfo(
    val packageName: String,
    val appName: String,
    val serviceClassName: String? = null
)
