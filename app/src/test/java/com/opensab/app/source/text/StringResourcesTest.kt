package com.opensab.app.source.text

import com.opensab.app.R
import com.opensab.app.source.speech.phrases
import com.opensab.cluster.text.ClusterToken
import com.opensab.cluster.text.TextBudget
import com.opensab.domain.speech.SpeechToken
import com.opensab.domain.weather.WmoCaption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the compiler cannot see about the string tables: the exhaustive `when`s only check
 * `values/`, so a string forgotten in `values-es/` compiles and shows the rider an English word on
 * a Spanish phone.
 */
class StringResourcesTest {

    private val base = File(resDir(), "values/strings.xml")
    private val spanish = File(resDir(), "values-es/strings.xml")

    @Test
    fun everyMirrorLabelAndSpokenPhraseIsTranslated() {
        val guarded = names(base).filter { it.startsWith("mirror_action_") || it.startsWith("speech_") }
        assertTrue("found only ${guarded.size} guarded strings", guarded.size >= 20)
        assertEquals(emptyList<String>(), guarded.filter { it !in names(spanish) }.sorted())
    }

    @Test
    fun everySpeechStringIsTranslated() = assertParity("speech_", floor = 15)

    @Test
    fun everyPermissionLabelIsTranslated() = assertParity("permission_", floor = 20)

    @Test
    fun everySettingsLabelIsTranslated() = assertParity("settings_", floor = 70)

    @Test
    fun everySessionsLabelIsTranslated() = assertParity("sessions_", floor = 18)

    @Test
    fun everyClusterTokenIsTranslated() = assertParity("cluster_", floor = 50)

    @Test
    fun everyPhraseWithHelpDeclaresBothPartsInXml() {
        val missing = mutableListOf<String>()
        for ((dir, strings) in valuesByDir()) {
            for (token in SpeechToken.entries) {
                val phrases = token.phrases()
                val help = phrases.help ?: continue
                for (id in listOf(phrases.tft, help)) {
                    val name = resourceNames().getValue(id)
                    if (strings[name].isNullOrBlank()) missing += "$dir/$name ($token)"
                }
            }
        }
        assertEquals(emptyList<String>(), missing.sorted())
    }

    /**
     * `ProjectionBuffer` truncates in silence, so a string one byte over is a word cut in half on
     * the cluster. Weather captions share the line with the temperature: `WeatherPageProvider`
     * paints `"${temperature}${unit} ${caption}"`, and the longest prefix, `-40°C `, is 7 bytes.
     */
    @Test
    fun everyClusterStringFitsItsSlot() {
        val values = valuesByDir()

        // An XML entity would be measured as its escape, not as the character that reaches the cluster.
        val escaped = values.flatMap { (dir, byName) ->
            byName.filter { (name, text) -> name.startsWith("cluster_") && "&" in text }.keys.map { "$dir/$it" }
        }
        assertEquals(emptyList<String>(), escaped)

        val breaches = mutableListOf<String>()
        for ((dir, byName) in values) {
            for (token in ClusterToken.entries) {
                val name = resourceNames().getValue(token.string())
                val bytes = byName.getValue(name).toByteArray(Charsets.UTF_8).size
                if (bytes > token.budget.maxBytes) breaches += "$dir/$name: $bytes B > ${token.budget.maxBytes}"
            }
            for (caption in WmoCaption.entries) {
                val name = resourceNames().getValue(caption.string())
                val bytes = byName.getValue(name).toByteArray(Charsets.UTF_8).size + WEATHER_PREFIX_BYTES
                if (bytes > TextBudget.LINE.maxBytes) breaches += "$dir/$name: $bytes B > ${TextBudget.LINE.maxBytes}"
            }
        }
        assertEquals(emptyList<String>(), breaches.sorted())
    }

    /** A plural without `quantity="other"` throws `Resources.NotFoundException` at runtime. */
    @Test
    fun everyPluralDeclaresTheOtherQuantity() {
        val blocks = Regex("""<plurals name="([^"]+)">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
        val all = listOf(base, spanish).flatMap { file ->
            blocks.findAll(file.readText()).map { file.parentFile!!.name to it }.toList()
        }
        assertTrue("found only ${all.size} plurals", all.size >= 4)
        val offenders = all
            .filterNot { (_, match) -> match.groupValues[2].contains("""quantity="other"""") }
            .map { (dir, match) -> "$dir/${match.groupValues[1]}" }
        assertEquals(emptyList<String>(), offenders.sorted())
    }

    private fun assertParity(prefix: String, floor: Int) {
        val en = names(base).filter { it.startsWith(prefix) }.toSet()
        val es = names(spanish).filter { it.startsWith(prefix) }.toSet()
        assertTrue("found only ${en.size} $prefix strings", en.size >= floor)
        assertEquals("only in English", emptyList<String>(), (en - es).sorted())
        assertEquals("only in Spanish", emptyList<String>(), (es - en).sorted())
    }

    private fun names(file: File): Set<String> =
        Regex("""<(?:string|plurals) name="([^"]+)"""").findAll(file.readText()).map { it.groupValues[1] }.toSet()

    private fun valuesByDir(): Map<String, Map<String, String>> =
        mapOf("values" to base, "values-es" to spanish).mapValues { (_, file) ->
            Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(file.readText())
                .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }
        }

    private fun resourceNames(): Map<Int, String> =
        R.string::class.java.fields
            .filter { it.type == Int::class.javaPrimitiveType }
            .associate { it.getInt(null) to it.name }

    private fun resDir(): File =
        listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    private companion object {
        const val WEATHER_PREFIX_BYTES = 7
    }
}
