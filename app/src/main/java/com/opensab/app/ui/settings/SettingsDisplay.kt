package com.opensab.app.ui.settings

import androidx.annotation.StringRes
import com.opensab.app.R
import com.opensab.domain.music.MediaAppInfo
import com.opensab.domain.music.MediaLibraryNode
import com.opensab.domain.settings.AppSettings
import com.opensab.domain.settings.Tunable
import com.opensab.domain.settings.resolveQuickReplies
import com.opensab.domain.settings.resolveRootMenu
import java.util.Locale

/** Null for the system default, and for a player that is no longer installed. */
fun List<MediaAppInfo>.appNameFor(packageName: String?): String? =
    find { it.packageName == packageName }?.appName

/**
 * A pin belonging to a player that is no longer the favourite is not shown: the engine would
 * ignore it too, so showing it would be a lie.
 */
fun AppSettings.pinnedLabelForFavourite(): String? =
    coldStartMediaLabel?.takeIf { coldStartMediaPackage == favoriteMediaPackage }

fun AppSettings.pinnedMediaIdForFavourite(): String? =
    coldStartMediaId?.takeIf { coldStartMediaPackage == favoriteMediaPackage }

fun SettingsUiState.isLibraryRefused(): Boolean = libraryUnavailableFor == settings.favoriteMediaPackage

/** Null while the defaults are in use, so the row can say so rather than show a count. */
fun AppSettings.quickRepliesInUse(defaults: List<String>): Int? =
    if (quickReplies.isEmpty()) null else resolveQuickReplies(quickReplies, defaults).size

/** The three strings of a tunable's row; [value] takes the number in the tunable's own unit. */
data class TunableTexts(
    @param:StringRes val title: Int,
    @param:StringRes val description: Int,
    @param:StringRes val value: Int
)

fun Tunable.texts(): TunableTexts = when (this) {
    Tunable.REPLY_SENT_LINGER -> TunableTexts(
        title = R.string.settings_reply_linger_title,
        description = R.string.settings_reply_linger_desc,
        value = R.string.settings_tunable_value_seconds
    )
    Tunable.WEATHER_REFRESH -> TunableTexts(
        title = R.string.settings_weather_refresh_title,
        description = R.string.settings_weather_refresh_desc,
        value = R.string.settings_tunable_value_minutes
    )
    Tunable.SAB_MODE_WAIT -> TunableTexts(
        title = R.string.settings_sab_mode_wait_title,
        description = R.string.settings_sab_mode_wait_desc,
        value = R.string.settings_tunable_value_seconds
    )
}

/** Visible entries to total entries. */
fun AppSettings.rootMenuCounts(): Pair<Int, Int> {
    val rootMenu = resolveRootMenu(rootMenuOrder, rootMenuHidden)
    return rootMenu.count { it.visible } to rootMenu.size
}

/** The root crumb is stored without a title: nothing named it, the player just opened. */
fun breadcrumbTitles(stack: List<MediaLibraryCrumb>, rootLabel: String): List<String> =
    stack.mapIndexed { index, crumb -> crumb.title.ifBlank { if (index == 0) rootLabel else "…" } }

/** Browsable rows walk in; a playable row is the choice itself. */
fun MediaLibraryNode.pickIntent(): SettingsUiIntent =
    if (isBrowsable) {
        SettingsUiIntent.BrowseColdStartNode(mediaId, title)
    } else {
        SettingsUiIntent.PinColdStartMedia(mediaId, title)
    }

/**
 * A media ID is unique within one level, but a player is free to hand back a blank one; pairing it
 * with the index keeps the key unique whatever the player sends.
 */
fun pickerRowKey(index: Int, node: MediaLibraryNode): String = "$index-${node.mediaId}"

/** The open container, which is how a whole album or playlist gets pinned instead of a track. */
fun List<MediaLibraryCrumb>.pinHereTarget(): MediaLibraryCrumb? = lastOrNull()?.takeIf { it.title.isNotBlank() }

fun JournalSettingsUi.usedMegabytes(locale: Locale): String = "%.1f".format(locale, usedBytes / (1024.0 * 1024.0))

fun <T> List<T>.selectedTabIndex(selected: T): Int = indexOf(selected).coerceAtLeast(0)
