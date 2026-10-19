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

import org.junit.Assert.assertEquals
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

    // The only places rememberSaveable may appear, by exact line text: a phone's display name and
    // the role card picked. Neither is a credential. (The import is what makes them compile.)
    private val allowedLines =
        mapOf(
            "JoinFamilyScreen.kt" to
                setOf(
                    "import androidx.compose.runtime.saveable.rememberSaveable",
                    "var name by rememberSaveable { mutableStateOf(Build.MODEL.orEmpty()) }",
                    "var role by rememberSaveable { mutableStateOf<DeviceRole?>(null) }",
                ),
        )

    /**
     * Every line that contains the token `rememberSaveable` and is not one of [allowed], whatever
     * shape it has: delegated or not, typed or not, split over lines, in a call or an import. Lines
     * that are wholly a comment are skipped, since a comment cannot save anything.
     */
    private fun saveableOffenders(
        files: Map<String, String>,
        allowed: Map<String, Set<String>>,
    ): List<String> =
        files.flatMap { (file, text) ->
            text
                .lines()
                .map { it.trim() }
                .filter { "rememberSaveable" in it }
                .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
                .filterNot { it in allowed[file].orEmpty() }
                .map { "$file: $it" }
        }

    private fun familySources(): Map<String, String> {
        val dir = listOf(File(familyDir), File("app/$familyDir")).firstOrNull { it.isDirectory }
        val files = requireNotNull(dir) { "cannot find $familyDir from ${File(".").absolutePath}" }.listFiles { f -> f.name.endsWith(".kt") }
        return requireNotNull(files).associate { it.name to it.readText() }
    }

    @Test
    fun `only non-credential screen state is saved across process death`() {
        val sources = familySources()
        assertTrue("expected to scan the Family screens", "JoinFamilyScreen.kt" in sources && "ManageFamilyScreen.kt" in sources)

        val offenders = saveableOffenders(sources, allowedLines)

        assertTrue(
            "These lines use rememberSaveable (the saved-state Bundle survives process death) " +
                "and are not on the exact-text allow list: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the allowed rememberSaveable lines are still there, so the allow list cannot go stale`() {
        val join = familySources().getValue("JoinFamilyScreen.kt").lines().map { it.trim() }

        for (line in allowedLines.getValue("JoinFamilyScreen.kt")) {
            assertTrue("allowed line no longer in JoinFamilyScreen.kt: $line", line in join)
        }
    }

    @Test
    fun `the scan catches a typed declaration, a non-delegated one, and the shapes the old pattern knew`() {
        val samples =
            mapOf(
                "ManageFamilyScreen.kt" to
                    """
                    var text: String by rememberSaveable { mutableStateOf("") }
                    val key = rememberSaveable { mutableStateOf("") }
                    var adminKey by rememberSaveable { mutableStateOf("") }
                    var split by
                        rememberSaveable { mutableStateOf("") }
                    val holder = rememberSaveableStateHolder()
                    """.trimIndent(),
                // The allow list is per file and exact: the allowed text elsewhere is still flagged.
                "FamilyScreen.kt" to "var name by rememberSaveable { mutableStateOf(Build.MODEL.orEmpty()) }",
                // A near miss of an allowed line is flagged too.
                "JoinFamilyScreen.kt" to "var name by rememberSaveable { mutableStateOf(token) }",
            )

        val offenders = saveableOffenders(samples, allowedLines)

        assertEquals(7, offenders.size)
        assertTrue(offenders.any { it.contains("var text: String by rememberSaveable") })
        assertTrue(offenders.any { it.contains("val key = rememberSaveable") })
        assertTrue(offenders.any { it.startsWith("FamilyScreen.kt") })
        assertTrue(offenders.any { it.contains("mutableStateOf(token)") })
    }

    @Test
    fun `the scan leaves the two allowed declarations, comments and nothing else`() {
        val clean =
            mapOf(
                "JoinFamilyScreen.kt" to
                    """
                    import androidx.compose.runtime.saveable.rememberSaveable
                        var name by rememberSaveable { mutableStateOf(Build.MODEL.orEmpty()) }
                        var role by rememberSaveable { mutableStateOf<DeviceRole?>(null) }
                    // never rememberSaveable for the token
                     * a doc line about rememberSaveable
                    """.trimIndent(),
            )

        assertTrue(saveableOffenders(clean, allowedLines).isEmpty())
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

    // SARASWATI: fix wave A (M2) — a phone waiting to be approved is not the owner's.
    @Test
    fun `the owner setup link is hidden while a claim is pending`() {
        val screen = source("$familyDir/FamilyScreen.kt")

        assertTrue("the screen reads the pending-claim state", "viewModel.hasPendingClaim" in screen)
        val guard = screen.indexOf("if (!hasPendingClaim)")
        val link = screen.indexOf("R.string.family_owner_setup_link")
        assertTrue("the link sits inside the guard", guard in 0 until link)
        assertEquals("the owner setup link is offered in one place only", 1, Regex("""family_owner_setup_link""").findAll(screen).count())
        assertEquals("and so is the route to owner setup", 1, Regex("""FamilyRoutes\.MANAGE""").findAll(screen.substringAfter("else ->")).count())
    }

    // SARASWATI: fix wave A (M7) — the admin key does not stay on the clipboard once it was saved.
    @Test
    fun `saving the admin key clears the clipboard through ClipboardHygiene`() {
        val screen = source("$familyDir/ManageFamilyScreen.kt")

        assertTrue(
            Regex("""viewModel\.saveAdminKey\(key\)\s*\{\s*saved\s*->\s*ClipboardHygiene\.clearIfHolds\(clipboard,\s*saved\)\s*\}""").containsMatchIn(screen),
        )
        assertEquals("the hygiene helper is the only thing that clears the clipboard", 0, Regex("""clearPrimaryClip""").findAll(screen).count())
    }
    // --- SARASWATI: fix wave B1 — the four-character code, the age and the expiry -----------------------

    private fun string(name: String): String {
        val strings = source("src/main/res/values/metrolist_strings.xml")
        return requireNotNull(Regex("""<string name="$name"[^>]*>(.*?)</string>""").find(strings)) { "string $name is missing" }.groupValues[1]
    }

    @Test
    fun `the code, age, expiry and hint strings say what the person expects`() {
        assertEquals("Code: %1\$s", string("family_join_code"))
        assertEquals("Code %1\$s", string("family_request_code"))
        assertEquals("2 min ago", String.format(string("family_request_age_minutes"), 2))
        assertEquals("3 h ago", String.format(string("family_request_age_hours"), 3))
        assertEquals("just now", string("family_request_age_now"))
        assertEquals("Expired", string("family_request_expired"))
        assertEquals("Say the code aloud with the person before you approve.", string("family_requests_hint"))
        assertEquals("This request has expired. Ask them to send a new request.", string("family_error_expired"))
    }

    @Test
    fun `the expired error has its own message`() {
        assertTrue("FamilyError.EXPIRED has a message", "FamilyError.EXPIRED -> R.string.family_error_expired" in source("$familyDir/FamilyUi.kt"))
    }

    @Test
    fun `the join waiting screen shows the code large, in a monospace face`() {
        val join = source("$familyDir/JoinFamilyScreen.kt")
        val waiting = join.substringAfter("private fun Waiting(").substringBefore("@Composable")

        assertTrue("Waiting takes the code", waiting.startsWith("code: String)"))
        assertTrue("the code uses the Code: string", "R.string.family_join_code" in waiting)
        assertTrue("monospace", "FontFamily.Monospace" in waiting)
        assertTrue("large", "MaterialTheme.typography.displayMedium" in waiting)
        assertTrue("the screen passes the state's code", "current is JoinUiState.Waiting -> Waiting(code = current.code" in join)
    }

    @Test
    fun `a pending row shows the code and age, and approving is disabled once it expired`() {
        val manage = source("$familyDir/ManageFamilyScreen.kt")
        val row = manage.substringAfter("private fun PendingRow(").substringBefore("@Composable")

        assertTrue("the row derives its state from the entry and the clock", "entry.pendingRequest(now)" in row)
        assertTrue("code shown", "R.string.family_request_code" in row)
        assertTrue("age shown", "R.string.family_request_age_minutes" in row && "R.string.family_request_age_hours" in row)
        assertTrue("expiry shown", "R.string.family_request_expired" in row)
        assertTrue(
            "Approve is enabled only when the request can still be approved",
            Regex("""Button\(onClick = onApprove,\s*enabled = request\.canApprove,""").containsMatchIn(row),
        )
        assertEquals("exactly one button is gated by the request", 1, Regex("""enabled\s*=""").findAll(row).count())
        assertTrue("Deny stays", Regex("""OutlinedButton\(onClick = onDeny,\s*modifier""").containsMatchIn(row))
    }

    @Test
    fun `the requests card tells the owner to say the code aloud`() {
        val manage = source("$familyDir/ManageFamilyScreen.kt")

        assertTrue("R.string.family_requests_hint" in manage)
        assertEquals(1, Regex("""R\.string\.family_requests_hint""").findAll(manage).count())
        assertTrue("each row gets the screen's clock", "PendingRow(" in manage && "now = now" in manage.substringAfter("pending.forEach"))
    }
}
