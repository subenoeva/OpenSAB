package com.opensab.domain.speech

import java.util.Locale

/**
 * Which voice a body of text has to be said in, decided from **the alphabet it is written in**.
 *
 * A message is read in its own language, not the rider's. There is no language identifier here:
 * what there is is the script, and the script is what separates the case that actually breaks the
 * voice from the case that merely accents it. A Chinese message read with a
 * Spanish voice is noise; a French one read with a Spanish voice is French with an accent.
 *
 * **So this deliberately does not know what it cannot know.** Spanish and French are both Latin and
 * both come back `null`, which means "the rider's language", which is what the app already did.
 * Naming that limit is the point — a detector that guessed between them would be inventing signal
 * out of a 26-letter alphabet.
 *
 * `TextClassifier.detectLanguage` was the alternative and it loses on three counts: it is API 29
 * against a `minSdk` of 26, so this would still have to exist as the fallback; it puts Android in
 * the decision path, which is the rule that kept this package pure; and it is slow enough to be
 * felt on a page that has to speak the instant it mounts.
 */
fun voiceLocaleFor(text: String): Locale? {
    if (text.isBlank()) return null

    val counts = mutableMapOf<Character.UnicodeScript, Int>()
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        index += Character.charCount(codePoint)
        if (!Character.isLetter(codePoint)) continue
        val script = Character.UnicodeScript.of(codePoint)
        if (script in IGNORED_SCRIPTS) continue
        counts[script] = (counts[script] ?: 0) + 1
    }

    val latin = counts[Character.UnicodeScript.LATIN] ?: 0

    // Kana before Han, and this order is load-bearing: Japanese writes with kanji *and* kana, so a
    // Han-first reading would say every Japanese message in Chinese. A single kana character is
    // discriminator enough because Chinese never has one — but the weight that has to beat Latin is
    // the whole of the Japanese writing, kanji included.
    val kana = (counts[Character.UnicodeScript.HIRAGANA] ?: 0) +
        (counts[Character.UnicodeScript.KATAKANA] ?: 0)
    if (kana > 0) {
        val japanese = kana + (counts[Character.UnicodeScript.HAN] ?: 0)
        return JAPANESE.takeIf { japanese > latin }
    }

    val dominant = counts
        .filterKeys { it != Character.UnicodeScript.LATIN }
        .maxByOrNull { it.value }
        ?: return null

    // Strictly more than the Latin letters, so a brand name inside a Russian message does not turn
    // the voice and neither does a kanji quoted inside a Spanish one. A tie goes to the rider: two
    // alphabets saying the same amount is no signal at all.
    return if (dominant.value > latin) VOICES[dominant.key] else null
}

/**
 * Scripts that vote for nobody.
 *
 * `COMMON` and `INHERITED` are punctuation, digits and combining marks — they belong to every
 * alphabet and therefore to none.
 *
 * `LATIN` is deliberately **not** here. It is the fallback rather than a candidate — a Latin
 * message is read in the rider's language, which is `null` and not a locale of its own — but it
 * still has to be counted, because the bar another script has to clear is how much Latin there is.
 */
private val IGNORED_SCRIPTS = setOf(
    Character.UnicodeScript.COMMON,
    Character.UnicodeScript.INHERITED,
    Character.UnicodeScript.UNKNOWN
)

private val JAPANESE: Locale = Locale.forLanguageTag("ja")

/**
 * One voice per script this app can tell apart, and nothing beyond that.
 *
 * A script missing from here reads in the rider's language, which is the same answer Latin gets and
 * a great deal better than a wrong voice.
 */
private val VOICES: Map<Character.UnicodeScript, Locale> = mapOf(
    Character.UnicodeScript.HAN to Locale.forLanguageTag("zh"),
    Character.UnicodeScript.HANGUL to Locale.forLanguageTag("ko"),
    Character.UnicodeScript.CYRILLIC to Locale.forLanguageTag("ru"),
    Character.UnicodeScript.GREEK to Locale.forLanguageTag("el"),
    Character.UnicodeScript.ARABIC to Locale.forLanguageTag("ar"),
    Character.UnicodeScript.HEBREW to Locale.forLanguageTag("he"),
    Character.UnicodeScript.THAI to Locale.forLanguageTag("th"),
    Character.UnicodeScript.DEVANAGARI to Locale.forLanguageTag("hi")
)
