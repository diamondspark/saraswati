/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 11 — the two words the car's new tabs added. The unit-test
// build has no resource table, so the strings file is read directly. The
// Family folder's words (Phase 4 Task 15) are the approved copy.
package com.metrolist.music.saraswati.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class CarStringsTest {
    private val resDir: File =
        listOf(File("src/main/res"), File("app/src/main/res")).firstOrNull { it.isDirectory }
            ?: error("cannot find src/main/res from ${File(".").absolutePath}")

    private fun valueOf(name: String): String? =
        Regex("""<string\s+name="$name"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(File(resDir, "values/metrolist_strings.xml").readText())
            ?.groupValues
            ?.get(1)

    @Test
    fun `the Family folder's rows and the car's short errors are in the approved words`() {
        assertEquals("Parivaar Mix · Family mix", valueOf("car_family_parivaar_mix"))
        assertEquals("Start Sangat", valueOf("car_family_sangat_start"))
        assertEquals("End Sangat · %1\$d joined", valueOf("car_family_sangat_end"))
        assertEquals("End Sangat", valueOf("car_sangat_button_end"))
        assertEquals("Pair this phone first", valueOf("car_sangat_pair_first"))
        assertEquals("Sangat is off", valueOf("car_sangat_off"))
        assertEquals("No connection", valueOf("car_sangat_no_connection"))
    }

    @Test
    fun `the coming-soon row is gone`() {
        assertEquals(null, valueOf("car_family_sangat_soon"))
    }

    @Test
    fun `the Stations tab is called Stations`() {
        assertEquals("Stations", valueOf("car_tab_stations"))
    }

    @Test
    fun `no other language file defines the car's strings`() {
        val offenders =
            resDir
                .listFiles { f -> f.isDirectory && f.name.startsWith("values-") }
                .orEmpty()
                .flatMap { dir -> dir.listFiles { f -> f.extension == "xml" }.orEmpty().toList() }
                .filter { file -> file.readText().let { it.contains("name=\"car_tab_stations\"") || it.contains("name=\"car_family_sangat_start\"") } }
                .map { it.path }

        assertFalse("new strings go in the default English file only: $offenders", offenders.isNotEmpty())
    }
}
