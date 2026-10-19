/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: fix round 1 — checks on the Family screens' source that no JVM
// unit test can make by running them. The pasted pairing link or token and the
// owner's admin key are credentials, and rememberSaveable writes its value
// into the saved-state Bundle, which survives process death; so every
// rememberSaveable in these screens must be one this list names on purpose.
// (There is no Compose UI test in this project to observe it at run time.)
package com.metrolist.music.saraswati.family

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FamilyScreensSourceTest {
    private fun source(path: String): String {
        val file = listOf(File(path), File("app/$path")).firstOrNull { it.exists() }
        return requireNotNull(file) { "cannot find $path from ${File(".").absolutePath}" }.readText()
    }

    private val familyDir = "src/main/kotlin/com/metrolist/music/saraswati/family"

    // Variables that may be saved across process death: a phone's display name and the role card
    // picked. Neither is a credential.
    private val mayBeSaved = setOf("name", "role")

    @Test
    fun `only non-credential screen state is saved across process death`() {
        val declaration = Regex("""va[rl]\s+(\w+)\s+by\s+rememberSaveable""")
        val offenders =
            listOf("JoinFamilyScreen.kt", "ManageFamilyScreen.kt", "FamilyScreen.kt").flatMap { file ->
                declaration.findAll(source("$familyDir/$file")).map { file to it.groupValues[1] }.toList()
            }.filter { (_, name) -> name !in mayBeSaved }

        assertTrue(
            "These screen states go into the saved-state Bundle but are not on the allow list: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the paste field's text is plain remember`() {
        val join = source("$familyDir/JoinFamilyScreen.kt")

        assertTrue(Regex("""var\s+text\s+by\s+remember\s*\{""").containsMatchIn(join))
    }

    @Test
    fun `the join screen's header does not claim the typed text is saved`() {
        // The header is a //-comment wrapped over several lines; read it as running text.
        val header =
            source("$familyDir/JoinFamilyScreen.kt")
                .substringBefore("package ")
                .replace(Regex("""\s*//\s*"""), " ")

        assertFalse(header.contains("only the text the person typed or pasted is"))
        assertTrue(header.contains("never kept in saved instance state"))
    }

    @Test
    fun `the join screen asks for a new link on every resume, not only on first open`() {
        val join = source("$familyDir/JoinFamilyScreen.kt")

        assertTrue(join.contains("LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.start() }"))
    }

    @Test
    fun `the waiting text does not promise the wait continues once the screen is closed`() {
        val strings = source("src/main/res/values/metrolist_strings.xml")
        val waiting = Regex("""<string name="family_join_waiting_desc">(.*?)</string>""").find(strings)?.groupValues?.get(1)

        assertTrue("family_join_waiting_desc is missing", waiting != null)
        assertFalse(waiting!!, waiting.contains("keeps waiting", ignoreCase = true))
        assertFalse(waiting, waiting.contains("close the app", ignoreCase = true))
        assertTrue(waiting, waiting.contains("Keep this screen open"))
    }
}
