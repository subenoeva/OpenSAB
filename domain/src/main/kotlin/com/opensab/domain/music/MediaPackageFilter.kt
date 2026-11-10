package com.opensab.domain.music

object MediaPackageFilter {

    /** Packages never treated as music players (video, VoIP, browsers, system utilities). */
    val BLACKLISTED_PACKAGES: Set<String> = setOf(
        // Video & TV Streaming
        "com.stremio.one",
        "com.google.android.youtube", // Main YouTube video app (not YouTube Music)
        "com.netflix.mediaclient",
        "com.amazon.avod.thirdpartyclient", // Prime Video
        "com.plexapp.android",
        "com.crunchyroll.crunchyroid",
        "com.disney.disneyplus",
        "com.hbo.hbonow",
        "com.wbd.stream", // Max
        "tv.twitch.android.app",
        "com.mxtech.videoplayer.ad",
        "com.mxtech.videoplayer.pro",
        "is.xyz.mpv",
        "lk.bhasha.mobitv",

        // Messengers, VoIP & Social
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.thunderdog.challegram",
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.discord",
        "com.facebook.orca",
        "org.thoughtcrime.securesms",
        "com.bbm",
        "com.tumblr",

        // Web Browsers
        "com.android.chrome",
        "org.mozilla.firefox",
        "org.mozilla.fenix",
        "com.opera.browser",
        "com.brave.browser",
        "com.microsoft.emmx",
        "com.sec.android.app.sbrowser",
        "com.duckduckgo.mobile.android",

        // Books, News, Recorders & System Utilities
        "com.google.android.apps.books",
        "com.google.android.apps.magazines",
        "com.amazon.kindle",
        "com.medium.reader",
        "com.sec.android.app.voicerecorder",
        "com.google.android.apps.recorder",
        "com.estrongs.android.pop",
        "com.sec.android.app.mediasync",
        "com.sec.android.mmapp",
        "com.sec.android.app.ve",
        "com.vlingo.midas",
        "com.endomondo.android"
    )

    /** Curated catalog of known audio players queried during scanning. */
    val CURATED_MUSIC_PACKAGES: List<String> = listOf(
        // Official streaming
        "com.spotify.music",
        "com.google.android.apps.youtube.music",
        "com.amazon.mp3",
        "com.apple.android.music",
        "com.aspiro.tidal",
        "deezer.android.app",
        "com.soundcloud.android",
        "com.qobuz.music",
        "com.pandora.android",
        "com.bandcamp.android",
        "tunein.player",
        "com.tunein.player",
        "com.clearchannel.iheartradio.controller",
        "com.jio.media.jiobeats",
        "com.gaana",

        // Podcasts
        "de.danoeh.antennapod",
        "au.com.shiftyjelly.pocketcasts",
        "com.bambuna.podcastaddict",
        "fm.player",
        "com.google.android.apps.podcasts",

        // Local / offline players
        "in.krosbits.musicolet",
        "com.maxmpz.audioplayer",
        "com.aimp.player",
        "org.videolan.vlc",
        "com.sec.android.app.music",
        "another.music.player",
        "code.name.monkey.retromusic",
        "com.hardcodecoder.pulsemusic",
        "com.kunkunlabs.otomusic",
        "com.rhmsoft.pulsar",
        "gonemad.gmmp",
        "com.foobar2000.foobar2000",

        // Mods & FOSS clients
        "app.revanced.android.apps.youtube.music",
        "app.rvx.android.apps.youtube.music",
        "app.morphe.android.apps.youtube.music",
        "com.morphe.android.apps.youtube.music",
        "com.vanced.android.apps.youtube.music",
        "app.revanced.android.apps.spotify",
        "app.rvx.android.apps.spotify",
        "com.spotify.music.revanced",
        "com.vimusic.app",
        "com.innertune.app",
        "com.zionhuang.music",
        "com.metareal.musify",
        "com.shadow.music",
        "org.schabi.newpipe",
        "com.github.krtkush.spotube",
        "oss.krtapp.spotube",
        "com.brouken.player",
        "com.maxrave.simpmusic",
        "io.github.zyrouge.symphony"
    )

    fun isBlacklisted(packageName: String): Boolean {
        return BLACKLISTED_PACKAGES.contains(packageName)
    }

    fun isAllowed(packageName: String): Boolean {
        return !isBlacklisted(packageName)
    }
}
