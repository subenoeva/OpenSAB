package com.opensab.logging.journal

import java.time.ZoneId

val MADRID: ZoneId = ZoneId.of("Europe/Madrid")

/** Marker whose class loader resolves files under `src/test/resources/journal` on the test classpath. */
private object GoldenResources

/** Reads a golden vector at `journal/[name]` from test resources, one line per element. */
fun golden(name: String): Sequence<String> {
    val stream = GoldenResources::class.java.getResourceAsStream("/journal/$name")
        ?: error("missing golden resource: journal/$name")
    return stream.bufferedReader(Charsets.UTF_8).use { it.readLines() }.asSequence()
}
