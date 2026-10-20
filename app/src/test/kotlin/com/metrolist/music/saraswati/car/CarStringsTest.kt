/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 11 — the two words the car's new tabs added. The unit-test
// build has no resource table, so the strings file is read directly. The
// Family row's title is the approved copy for "Sangat · Listen together (coming soon)".
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
    fun `the Family row says Sangat is coming soon, in the approved words`() {
        assertEquals("Sangat · Listen together (coming soon)", valueOf("car_family_sangat_soon"))
    }

    // SARASWATI: fix wave A (M8) — the car names Sangat the way the app does ("Name · what it is"), so a
    // driver who has seen the Features list recognises the row.
    @Test
    fun `the Family row follows the Sangat subtitle pattern the app uses`() {
        val appName = valueOf("family_feature_sangat")

        assertEquals("Sangat · Listen together", appName)
        assertEquals("$appName (coming soon)", valueOf("car_family_sangat_soon"))
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
                .filter { file -> file.readText().let { it.contains("name=\"car_tab_stations\"") || it.contains("name=\"car_family_sangat_soon\"") } }
                .map { it.path }

        assertFalse("new strings go in the default English file only: $offenders", offenders.isNotEmpty())
    }
}
