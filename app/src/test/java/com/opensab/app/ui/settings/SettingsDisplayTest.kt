package com.opensab.app.ui.settings

import com.opensab.app.R
import com.opensab.domain.music.MediaAppInfo
import com.opensab.domain.music.MediaLibraryNode
import com.opensab.domain.settings.AppSettings
import com.opensab.domain.settings.RootMenuEntry
import com.opensab.domain.settings.Tunable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SettingsDisplayTest {

    private val apps = listOf(
        MediaAppInfo("com.spotify.music", "Spotify"),
        MediaAppInfo("com.aspiro.tidal", "TIDAL")
    )

    @Test
    fun namesAnInstalledPlayer() = assertEquals("TIDAL", apps.appNameFor("com.aspiro.tidal"))

    @Test
    fun namesNoPlayerForTheSystemDefault() = assertNull(apps.appNameFor(null))

    @Test
    fun namesNoPlayerThatIsNoLongerInstalled() = assertNull(apps.appNameFor("com.gone"))

    @Test
    fun showsThePinOfTheFavouritePlayer() {
        val settings = AppSettings(
            favoriteMediaPackage = "com.aspiro.tidal",
            coldStartMediaId = "MY_COLLECTION_TRACKS",
            coldStartMediaLabel = "My tracks",
            coldStartMediaPackage = "com.aspiro.tidal"
        )

        assertEquals("My tracks", settings.pinnedLabelForFavourite())
        assertEquals("MY_COLLECTION_TRACKS", settings.pinnedMediaIdForFavourite())
    }

    @Test
    fun hidesThePinOfAPlayerThatIsNoLongerTheFavourite() {
        val settings = AppSettings(
            favoriteMediaPackage = "com.spotify.music",
            coldStartMediaId = "MY_COLLECTION_TRACKS",
            coldStartMediaLabel = "My tracks",
            coldStartMediaPackage = "com.aspiro.tidal"
        )

        assertNull(settings.pinnedLabelForFavourite())
        assertNull(settings.pinnedMediaIdForFavourite())
    }

    @Test
    fun reportsTheLibraryRefusedOnlyForThePlayerThatRefused() {
        val settings = AppSettings(favoriteMediaPackage = "com.spotify.music")

        assertTrue(SettingsUiState(settings = settings, libraryUnavailableFor = "com.spotify.music").isLibraryRefused())
        assertFalse(SettingsUiState(settings = settings, libraryUnavailableFor = "com.aspiro.tidal").isLibraryRefused())
    }

    @Test
    fun countsNoQuickRepliesWhileTheDefaultsAreInUse() =
        assertNull(AppSettings(quickReplies = emptyList()).quickRepliesInUse(listOf("Yes", "No")))

    @Test
    fun countsTheRidersOwnQuickRepliesWithoutBlanks() =
        assertEquals(2, AppSettings(quickReplies = listOf("On my way", " ", "Riding")).quickRepliesInUse(listOf("Yes")))

    /** Clamped, because clamped is what the owner will do with it: a stored 999 never reads as 999. */
    @Test
    fun showsAStoredTunableAsItsOwnerWillReadIt() {
        assertEquals(10, Tunable.REPLY_SENT_LINGER.valueIn(AppSettings(replySentLingerSeconds = 999)))
        assertEquals(1, Tunable.REPLY_SENT_LINGER.valueIn(AppSettings(replySentLingerSeconds = -5)))
    }

    @Test
    fun everyTunableHasItsOwnRowTexts() {
        val texts = Tunable.entries.map { it.texts() }

        assertEquals(Tunable.entries.size, texts.map { it.title }.distinct().size)
        assertEquals(Tunable.entries.size, texts.map { it.description }.distinct().size)
    }

    @Test
    fun aTunableIsShownInItsOwnUnit() {
        assertEquals(R.string.settings_tunable_value_seconds, Tunable.REPLY_SENT_LINGER.texts().value)
        assertEquals(R.string.settings_tunable_value_minutes, Tunable.WEATHER_REFRESH.texts().value)
        assertEquals(R.string.settings_tunable_value_seconds, Tunable.SAB_MODE_WAIT.texts().value)
    }

    @Test
    fun countsTheVisibleRootMenuEntries() {
        val all = RootMenuEntry.entries.size
        val settings = AppSettings(rootMenuHidden = setOf(RootMenuEntry.entries.last().name))

        assertEquals(all - 1 to all, settings.rootMenuCounts())
    }

    @Test
    fun namesTheUntitledRootAndElidesAnUntitledInnerLevel() {
        val stack = listOf(
            MediaLibraryCrumb("root", ""),
            MediaLibraryCrumb("inner", ""),
            MediaLibraryCrumb("album", "Album")
        )

        assertEquals(listOf("Library", "…", "Album"), breadcrumbTitles(stack, rootLabel = "Library"))
    }

    @Test
    fun walksIntoABrowsableNode() = assertEquals(
        SettingsUiIntent.BrowseColdStartNode("folder", "Folder"),
        MediaLibraryNode("folder", "Folder", null, isPlayable = true, isBrowsable = true).pickIntent()
    )

    @Test
    fun pinsAPlayableLeaf() = assertEquals(
        SettingsUiIntent.PinColdStartMedia("track", "Track"),
        MediaLibraryNode("track", "Track", null, isPlayable = true, isBrowsable = false).pickIntent()
    )

    @Test
    fun keysPickerRowsUniquelyEvenWithBlankMediaIds() {
        val blank = MediaLibraryNode("", "A", null, isPlayable = true, isBrowsable = false)

        assertEquals("0-", pickerRowKey(0, blank))
        assertEquals("1-", pickerRowKey(1, blank))
    }

    @Test
    fun offersToPinTheOpenContainerOnlyWhenItHasATitle() {
        assertNull(emptyList<MediaLibraryCrumb>().pinHereTarget())
        assertNull(listOf(MediaLibraryCrumb("root", "")).pinHereTarget())
        assertEquals(
            MediaLibraryCrumb("album", "Album"),
            listOf(MediaLibraryCrumb("root", ""), MediaLibraryCrumb("album", "Album")).pinHereTarget()
        )
    }

    @Test
    fun formatsTheJournalUsageInMegabytesForTheLocale() {
        val journal = JournalSettingsUi(usedBytes = 3L * 1024 * 1024 + 512 * 1024, limitMb = 50, overLimit = false)

        assertEquals("3.5", journal.usedMegabytes(Locale.US))
        assertEquals("3,5", journal.usedMegabytes(Locale.forLanguageTag("es-ES")))
    }

    @Test
    fun selectsTheMatchingTab() = assertEquals(1, listOf("a", "b").selectedTabIndex("b"))

    @Test
    fun selectsTheFirstTabForAnUnknownValue() = assertEquals(0, listOf("a", "b").selectedTabIndex("z"))
}
