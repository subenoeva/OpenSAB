package com.opensab.domain.nav

/**
 * Reads Google Maps guidance. The maneuver comes from the arrow Maps drew, already
 * matched to one of its drawings; the text gives the distance, street and arrival time. Measured on
 * Maps 26.38: the title is the maneuver phrase with its distance in front ("90 m · Gira …", a
 * no-break space between number and unit), the ETA is in subText, and text is null except on arrival.
 */
object GoogleMapsReader : NavigationReader {

    override val packageName: String = "com.google.android.apps.maps"

    private val DISTANCE_PREFIX = Regex("""^(\d[\d.,]*[\s\u00A0]?\p{L}+)\s·\s(.+)$""")
    private val ARRIVAL_TIME = Regex("""\d{1,2}[:.]\d{2}(\s?[AaPp]\.?\s?[Mm]\.?)?""")

    /** Whole words only, and "en dirección" before "en" so the two never split one connector. */
    private val CONNECTOR = Regex("""(?:^|\s)(?:hacia|por|en dirección|en)\s""", RegexOption.IGNORE_CASE)

    override fun read(notification: NavigationNotification): NavInstruction? {
        val title = notification.title?.trim().orEmpty()
        if (title.isEmpty()) return null

        val split = DISTANCE_PREFIX.find(title)
        val phrase = split?.groupValues?.get(2) ?: title
        val distance = split?.groupValues?.get(1)?.replace('\u00A0', ' ')
            ?: notification.shortCriticalText?.trim().orEmpty()
        val text = notification.text?.trim().orEmpty()

        return NavInstruction(
            title = phrase,
            streetName = if (text.isNotEmpty()) text.removePrefix("a ").trim() else streetOf(phrase),
            distanceFormatted = distance,
            maneuver = notification.arrow?.let { GoogleMapsArrows.byDrawable[it] } ?: ManeuverType.UNKNOWN,
            etaFormatted = notification.subText?.let { ARRIVAL_TIME.find(it)?.value }
        )
    }

    /** What follows the last connector; with none, the whole phrase ("250 m · Centro de Ocio Acuatico"). */
    private fun streetOf(phrase: String): String {
        val last = CONNECTOR.findAll(phrase).lastOrNull() ?: return phrase
        return phrase.substring(last.range.last + 1).trim().ifEmpty { phrase }
    }
}
