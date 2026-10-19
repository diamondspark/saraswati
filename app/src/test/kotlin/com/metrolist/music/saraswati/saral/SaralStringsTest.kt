/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 9 fix round 1 — Saral's words are Hindi on purpose and are
// not translated (no other language should ever show on that screen), so every
// saral_* string is marked translatable="false", which also keeps translation
// tooling from queueing them. The approved copy is pinned verbatim.
package com.metrolist.music.saraswati.saral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SaralStringsTest {
    private val resDir: File =
        listOf(File("src/main/res"), File("app/src/main/res")).firstOrNull { it.isDirectory }
            ?: error("cannot find src/main/res from ${File(".").absolutePath}")

    private val stringElement = Regex("""<string\s+([^>]*?)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

    private fun saralStrings(): List<Triple<String, String, String>> =
        stringElement
            .findAll(File(resDir, "values/metrolist_strings.xml").readText())
            .map { Triple(Regex("""name="([^"]+)"""").find(it.groupValues[1])!!.groupValues[1], it.groupValues[1], it.groupValues[2]) }
            .filter { it.first.startsWith("saral_") }
            .toList()

    @Test
    fun `every saral string is marked not translatable`() {
        val strings = saralStrings()

        assertTrue("expected the saral_* strings", strings.size >= 20)
        val unmarked = strings.filterNot { it.second.contains("""translatable="false"""") }.map { it.first }
        assertTrue("these saral strings are translatable: $unmarked", unmarked.isEmpty())
    }

    @Test
    fun `no other language file defines a saral string`() {
        val offenders =
            resDir
                .listFiles { f -> f.isDirectory && f.name.startsWith("values-") }
                .orEmpty()
                .flatMap { dir -> dir.listFiles { f -> f.extension == "xml" }.orEmpty().toList() }
                .filter { it.readText().contains("name=\"saral_") }
                .map { it.path }

        assertTrue("saral strings must not be translated: $offenders", offenders.isEmpty())
    }

    @Test
    fun `the approved Hindi copy is exactly as the mockup has it`() {
        val byName = saralStrings().associate { it.first to it.third }

        val approved =
            mapOf(
                "saral_station_bollywood" to "बॉलीवुड क्लासिक्स",
                "saral_station_bhajan" to "भजन और भक्ति गीत",
                "saral_station_ghazal" to "ग़ज़लें और पुराने गीत",
                "saral_station_meditation" to "शांति और ध्यान",
                "saral_idle" to "टाइल दबाएं और सुनें",
                "saral_now_playing" to "%1\$s बज रहा है",
                "saral_play" to "चलाएं",
                "saral_pause" to "रोकें",
                "saral_next" to "आगे वाला गीत",
                "saral_previous" to "पिछला गीत",
                "saral_voice_search" to "बोलकर खोजें",
                "saral_listening" to "सुन रहे हैं…",
                "saral_say_song" to "गाने का नाम बोलें",
                "saral_searching" to "ढूँढ रहे हैं…",
                "saral_sign_in" to "यूट्यूब म्यूज़िक में साइन इन करें · Sign in",
            )
        for ((name, text) in approved) {
            assertEquals(name, text, byName[name])
        }
    }

    @Test
    fun `every message the screen can show has a string`() {
        val names = saralStrings().map { it.first }.toSet()

        for (needed in
            listOf(
                "saral_starting", "saral_paused", "saral_problem_offline", "saral_problem_could_not_play",
                "saral_voice_not_found", "saral_voice_not_heard", "saral_voice_offline", "saral_voice_failed",
                "saral_voice_unavailable", "saral_empty", "saral_sign_in",
            )
        ) {
            assertTrue("missing $needed", needed in names)
        }
    }

    // SARASWATI: fix wave B2 — the "voice search failed" line says what failed (the search), plainly.
    // Needs a native-speaker read like the other lines that are not in the approved mockup.
    @Test
    fun `the voice failure line is the short one`() {
        assertEquals("अभी खोज नहीं हो सकी", saralStrings().associate { it.first to it.third }["saral_voice_failed"])
    }
}
