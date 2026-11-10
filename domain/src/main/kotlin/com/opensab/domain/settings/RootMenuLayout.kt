package com.opensab.domain.settings

/**
 * The entries of the cluster's root carousel, by name only: the engine's `RootMenuItem` carries
 * the icon and colours, so the domain can order and hide entries without knowing how they paint.
 */
enum class RootMenuEntry { WEATHER, NAVIGATION, CALL, MESSAGE, MUSIC, VOLUME }

data class RootMenuRow(val entry: RootMenuEntry, val visible: Boolean)

/**
 * Names and not enums are stored because `DataStoreSettingsRepository` answers any decoding
 * failure with the defaults: one unknown enum value would wipe every setting. Unknown names are
 * dropped, duplicates keep their first place, missing entries are appended, and a list with
 * nothing visible gets its first entry back.
 */
fun resolveRootMenu(order: List<String>, hidden: Set<String>): List<RootMenuRow> {
    val byName = RootMenuEntry.entries.associateBy { it.name }
    val ordered = (order.mapNotNull { byName[it] } + RootMenuEntry.entries).distinct()
    val rows = ordered.map { RootMenuRow(it, visible = it.name !in hidden) }
    return if (rows.any { it.visible }) rows else rows.mapIndexed { i, row -> row.copy(visible = i == 0) }
}

fun List<RootMenuRow>.visibleEntries(): List<RootMenuEntry> = filter { it.visible }.map { it.entry }

fun List<RootMenuRow>.moved(from: Int, to: Int): List<RootMenuRow> {
    if (from !in indices || to !in indices || from == to) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

/** False only for the last visible entry: the carousel is never empty. */
fun List<RootMenuRow>.canHide(entry: RootMenuEntry): Boolean =
    count { it.visible } > 1 || none { it.entry == entry && it.visible }

fun List<RootMenuRow>.toggled(entry: RootMenuEntry): List<RootMenuRow> {
    val row = firstOrNull { it.entry == entry } ?: return this
    if (row.visible && !canHide(entry)) return this
    return map { if (it.entry == entry) it.copy(visible = !it.visible) else it }
}

fun AppSettings.withRootMenu(rows: List<RootMenuRow>): AppSettings = copy(
    rootMenuOrder = rows.map { it.entry.name },
    rootMenuHidden = rows.filterNot { it.visible }.map { it.entry.name }.toSet()
)
