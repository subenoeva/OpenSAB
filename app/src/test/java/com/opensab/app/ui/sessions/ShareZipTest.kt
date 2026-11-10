package com.opensab.app.ui.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ShareZipTest {

    @Test
    fun specDerivesTheAuthorityFromTheApplicationId() {
        val spec = ShareZip.spec("com.opensab")

        assertEquals(ShareZip.Spec("com.opensab.journal", "application/zip", true), spec)
    }

    @Test
    fun theManifestDeclaresTheProviderWithTheSpecAuthority() {
        val text = readSource("src/main/AndroidManifest.xml")

        assertTrue(text.contains("android:authorities=\"\${applicationId}${ShareZip.AUTHORITY_SUFFIX}\""))
        assertTrue(text.contains("android.support.FILE_PROVIDER_PATHS"))
        assertTrue(text.contains("@xml/journal_exports"))
    }

    @Test
    fun theExportPathsPointAtTheCacheExportsDirectory() {
        val text = readSource("src/main/res/xml/journal_exports.xml")

        assertTrue(text.contains("<cache-path"))
        assertTrue(text.contains("name=\"exports\""))
        assertTrue(text.contains("path=\"exports/\""))
    }

    private fun readSource(path: String): String =
        listOf(File(path), File("app/$path")).first { it.isFile }.readText()
}
