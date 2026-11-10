package com.opensab.app.source.text

import com.opensab.cluster.text.ClusterToken
import com.opensab.cluster.text.TextBudget
import com.opensab.domain.weather.WmoCaption
import com.opensab.sab.protocol.projection.ProjectionBuffer
import com.opensab.sab.session.projection.AsciiFoldingTextSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

/**
 * The `when` without an `else` in `ClusterPhrases.kt` already refuses to compile a token nobody
 * gave a string to; what is left here is that every entry points at a resource that exists, and
 * that no two entries point at the same one.
 *
 * Resource ids are read as the integers they are and never resolved. What a string *says* is
 * checked over the XML in `StringResourcesTest`, the only place both languages are visible.
 */
class ClusterPhrasesTest {

    @Test
    fun `every token declares a string`() {
        for (token in ClusterToken.entries) {
            assertNotEquals("ClusterToken.$token has no string resource", 0, token.string())
        }
    }

    /**
     * Two tokens on one resource is a token too many: the catalogue is the list of things the
     * cluster can say, and two names for one sentence is how the next translation gets forgotten
     * in one of the two places.
     */
    @Test
    fun `no two tokens share a string`() {
        val resources = ClusterToken.entries.map { it.string() }

        assertEquals(ClusterToken.entries.size, resources.toSet().size)
    }

    /**
     * The budget is the codec's, quoted and not copied. If `ProjectionBuffer` ever moves either
     * number, this table follows it instead of lying about it.
     *
     * Every token is a LINE today because no production path builds a `QueuedPopUp` yet; POPUP is
     * here because the modal budget is a real constant of the codec, not because a token uses it.
     */
    @Test
    fun `every budget quotes the codec instead of repeating it`() {
        assertEquals(ProjectionBuffer.MAX_TEXT_BYTES, TextBudget.LINE.maxBytes)
        assertEquals(ProjectionBuffer.MAX_POPUP_TEXT_BYTES, TextBudget.POPUP.maxBytes)
        assertEquals(
            emptyList<ClusterToken>(),
            ClusterToken.entries.filter { it.budget != TextBudget.LINE }
        )
    }

    @Test
    fun `every caption declares a string`() {
        for (caption in WmoCaption.entries) {
            assertNotEquals("WmoCaption.$caption has no string resource", 0, caption.string())
        }
    }

    @Test
    fun `no two captions share a string`() {
        val resources = WmoCaption.entries.map { it.string() }

        assertEquals(WmoCaption.entries.size, resources.toSet().size)
    }

    /** The catalogue is closed; the count lives here because it was once miscounted in prose. */
    @Test
    fun `the catalogue is the fifty-two lines and the seventeen captions`() {
        assertEquals(52, ClusterToken.entries.size)
        assertEquals(17, WmoCaption.entries.size)
    }

    /**
     * Nothing the cluster says turns into a question mark on a segment display.
     *
     * `VehicleFramePolicy` runs every header and every content line through
     * `AsciiFoldingTextSanitizer` when the cluster is an indicator one, so a Spanish accent already
     * arrives folded: «Meteorología» reaches a segment cluster as «Meteorologia».
     *
     * What the folder cannot do is guess: a character with no Latin base becomes `'?'`. `¿` and `¡`
     * are exactly that, and they are the punctuation a Spanish translation reaches for first.
     */
    @Test
    fun `every translation survives the segment fold`() {
        val sanitizer = AsciiFoldingTextSanitizer()
        val breaches = clusterStringsOfBothLanguages()
            .filter { (_, text) -> sanitizer.toAscii(text).contains('?') && !text.contains('?') }
            .map { (name, _) -> name }
            .sorted()

        assertEquals(
            "a segment cluster would paint a question mark instead of these",
            emptyList<String>(),
            breaches
        )
    }

    /**
     * `values/` and `values-es/` flattened into `("values-es/cluster_header_weather", "Meteorología")`
     * pairs. It reads the XML rather than `R`, because a resource id has one value and this test is
     * about both. The unescape is the one apostrophe of the catalogue.
     */
    private fun clusterStringsOfBothLanguages(): List<Pair<String, String>> {
        val appDir = findAppModuleDir()
        return listOf("values", "values-es").flatMap { dir ->
            Regex("""<string name="(cluster_[^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(File(appDir, "src/main/res/$dir/strings.xml").readText())
                .map { "$dir/${it.groupValues[1]}" to it.groupValues[2].replace("\\'", "'") }
                .toList()
        }
    }

    private fun findAppModuleDir(): File {
        var curr: File? = File(".").canonicalFile
        while (curr != null) {
            if (File(curr, "src/main/AndroidManifest.xml").isFile) {
                return curr
            }
            if (File(curr, "app/src/main/AndroidManifest.xml").isFile) {
                return File(curr, "app")
            }
            curr = curr.parentFile
        }
        error("Could not locate app module directory from ${File(".").canonicalPath}")
    }
}
