/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 10 — where the festival shows. No JVM test can look at a
// Compose screen, so these read the two files that place it, the way
// SaralIsolationTest does:
//  - Home: the festival banner is above the Sudhaar "Update ready" banner and
//    below the (Phase 4) Sangat-live slot, which is not built yet.
//  - Saral: the ribbon sits above the tile grid in both orientations, and its
//    words are the approved Hindi copy, set in strings that are never translated.
package com.metrolist.music.saraswati.culture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FestivalSurfacesTest {
    private fun source(path: String): String {
        val candidates = listOf(File(path), File("app/$path"))
        val file = candidates.firstOrNull { it.isFile } ?: error("cannot find $path from ${File(".").absoluteFile}")
        return file.readText()
    }

    private val home get() = source("src/main/kotlin/com/metrolist/music/ui/screens/HomeScreen.kt")
    private val saral get() = source("src/main/kotlin/com/metrolist/music/saraswati/saral/SaralScreen.kt")

    private val resDir: File =
        listOf(File("src/main/res"), File("app/src/main/res")).firstOrNull { it.isDirectory }
            ?: error("cannot find src/main/res from ${File(".").absolutePath}")

    private fun strings(prefix: String): Map<String, Pair<String, String>> =
        Regex("""<string\s+([^>]*?)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(File(resDir, "values/metrolist_strings.xml").readText())
            .map { Triple(Regex("""name="([^"]+)"""").find(it.groupValues[1])!!.groupValues[1], it.groupValues[1], it.groupValues[2]) }
            .filter { it.first.startsWith(prefix) }
            .associate { it.first to (it.second to it.third) }

    // --- Home ----------------------------------------------------------------------

    @Test
    fun `Home shows the festival banner above the Sudhaar banner`() {
        val festival = home.indexOf("festival_banner")
        val sudhaar = home.indexOf("sudhaar_update_banner")

        assertTrue("the festival banner is on Home", festival >= 0)
        assertTrue("the Sudhaar banner is still on Home", sudhaar >= 0)
        assertTrue("festival before Sudhaar", festival < sudhaar)
    }

    @Test
    fun `the festival banner is only composed on a festival day`() {
        val banner = home.indexOf("FestivalBanner(")
        val guard = home.lastIndexOf("festival != null", banner)
        val guardLet = home.lastIndexOf("festival?.let", banner)

        assertTrue("FestivalBanner is on Home", banner >= 0)
        assertTrue("it is guarded by a non-null festival", guard >= 0 || guardLet >= 0)
    }

    // --- Saral ----------------------------------------------------------------------

    @Test
    fun `the Saral ribbon sits above the tile grid in both orientations`() {
        val screen = saral
        val ribbons = Regex("""\bFestivalRibbon\(""").findAll(screen).map { it.range.first }.toList()
        val tileGrids = Regex("""\bTiles\(tiles""").findAll(screen).map { it.range.first }.toList()

        assertEquals("one ribbon per layout, plus its definition", 3, ribbons.size)
        assertEquals("one tile grid per layout", 2, tileGrids.size)
        for ((layout, grid) in tileGrids.withIndex()) {
            val ribbon = ribbons[layout]
            assertTrue("layout $layout: the ribbon comes before the grid", ribbon in 0 until grid)
        }
    }

    @Test
    fun `the Saral ribbon is shown only while there is a festival and does nothing but start its radio`() {
        val screen = saral

        assertTrue(screen.contains("viewModel.festival.collectAsStateWithLifecycle()"))
        assertTrue(screen.contains("viewModel::onFestivalTapped"))
        assertFalse("the ribbon opens nothing", Regex("""FestivalRibbon[\s\S]{0,2000}startActivity""").containsMatchIn(screen))
    }

    @Test
    fun `the Saral ribbon is not a seventh tile`() {
        assertFalse("the ribbon does not feed the tile list", saral.contains("tiles + "))
        assertFalse(saral.contains("tiles.plus"))
    }

    // --- the words -------------------------------------------------------------------

    @Test
    fun `the Saral festival words are the approved Hindi and never translated`() {
        val festival = strings("saral_festival_")

        assertEquals("सुनें", festival["saral_festival_listen"]?.second)
        assertEquals("त्योहार के गीत तैयार हैं", festival["saral_festival_ready"]?.second)
        assertEquals("%1\$s के गीत सुनें", festival["saral_festival_listen_desc"]?.second)
        assertEquals("%1\$s का संगीत", festival["saral_festival_music"]?.second)
        assertEquals("दीपावली", festival["saral_festival_diwali"]?.second)
        assertEquals("होली", festival["saral_festival_holi"]?.second)
        assertEquals("नवरात्रि", festival["saral_festival_navratri"]?.second)
        assertEquals("रक्षाबंधन", festival["saral_festival_raksha_bandhan"]?.second)
        assertEquals("जन्माष्टमी", festival["saral_festival_janmashtami"]?.second)
        assertEquals("त्योहार", festival["saral_festival_generic"]?.second)
        val unmarked = festival.filterValues { !it.first.contains("""translatable="false"""") }.keys
        assertTrue("these are translatable: $unmarked", unmarked.isEmpty())
    }

    @Test
    fun `the Home banner words are in the English strings file`() {
        val home = strings("festival_")

        assertTrue(home["festival_banner_subtitle"]?.second?.contains("%1\$s") == true)
        assertTrue(home.containsKey("festival_radio_failed"))
    }

    // --- SARASWATI: fix wave B2 -------------------------------------------------------------------------------

    private val banner get() = source("src/main/kotlin/com/metrolist/music/saraswati/culture/FestivalBanner.kt")

    @Test
    fun `the banner title may take three lines, so a long greeting is not cut`() {
        val title = banner.substringAfter("festival.bannerTitle()").substringBefore("\n")

        assertTrue(title.contains("maxLines = 3"))
    }

    @Test
    fun `the banner's Play button is spoken from a string resource, as Play festival music`() {
        assertTrue(banner.contains("stringResource(R.string.festival_banner_play_desc)"))
        assertTrue(banner.contains("contentDescription = playLabel"))
        assertEquals("Play festival music", strings("festival_banner_play_desc")["festival_banner_play_desc"]?.second)
    }

    @Test
    fun `Home turns the banner's Play button off for a Listen Together guest, and does nothing if it is pressed anyway`() {
        val call = home.substring(home.indexOf("FestivalBanner("))
        val head = call.substringBefore("scope.launch")

        assertTrue(head.contains("enabled = !festivalStarting && !isListenTogetherGuest"))
        assertTrue(head.contains("if (isListenTogetherGuest) return@FestivalBanner"))
    }

    @Test
    fun `Home lists the update classes after the culture and saral ones, in import order`() {
        val imports = home.lines().filter { it.startsWith("import com.metrolist.music.saraswati.") }
        val update = imports.indexOfFirst { it.endsWith("update.SaraswatiUpdateHttpFetcher") }
        val culture = imports.indexOfLast { it.contains(".culture.") }
        val saral = imports.indexOfLast { it.contains(".saral.") }

        assertTrue("update comes after culture and saral", update > culture && update > saral)
    }
}
