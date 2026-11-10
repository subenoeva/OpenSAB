package com.opensab.domain.music

/**
 * Whether a new session tells the favourite player to play. Never without a favourite: the setting
 * is about that player, and the generic media key would wake whatever app Android picks.
 */
fun shouldAutoResume(enabled: Boolean, favoritePackage: String?, status: PlaybackStatus): Boolean =
    enabled && !favoritePackage.isNullOrBlank() && status != PlaybackStatus.PLAYING
