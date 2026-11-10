package com.opensab.app.ble

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GattTransportFactoryTest {
    @Test
    fun gattTransportFactoryContractDoesNotImportAndroid() {
        val file = File("src/main/java/com/opensab/app/ble/GattTransportFactory.kt").let {
            if (it.exists()) it else File("app", it.path)
        }
        val lines = file.readLines()
        val androidImports = lines.filter { it.startsWith("import android.") || it.startsWith("import androidx.") }
        assertTrue(
            "GattTransportFactory must be a pure Kotlin contract without Android imports, found: $androidImports",
            androidImports.isEmpty()
        )
    }
}
