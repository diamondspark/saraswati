/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: owner-approved (Sangat item E) — auto-approve for family joins is on by default. The default is only a
// fallback for a key nobody has stored, so a person who turned it off keeps that choice. These tests run the real
// DataStore read the app uses, against a settings file in a temporary folder.
package com.metrolist.music.saraswati.family.sangat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.ListenTogetherAutoApprovalKey
import com.metrolist.music.utils.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AutoApprovalDefaultTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun readAsApp(): Boolean = runBlocking { dataStore.get(ListenTogetherAutoApprovalKey, true) }

    @Test
    fun `a family phone that never set the switch auto-approves`() {
        assertTrue(readAsApp())
    }

    @Test
    fun `a person who turned the switch off keeps it off`() {
        runBlocking { dataStore.edit { it[ListenTogetherAutoApprovalKey] = false } }
        assertFalse(readAsApp())
    }

    @Test
    fun `a person who turned the switch on keeps it on`() {
        runBlocking { dataStore.edit { it[ListenTogetherAutoApprovalKey] = true } }
        assertTrue(readAsApp())
    }

    @Test
    fun `the auto-approve call sites read the switch with the on default`() {
        val client = File("src/main/kotlin/com/metrolist/music/listentogether/ListenTogetherClient.kt").takeIf { it.isFile }
            ?: File("app/src/main/kotlin/com/metrolist/music/listentogether/ListenTogetherClient.kt")
        val settings = File("src/main/kotlin/com/metrolist/music/ui/screens/settings/integrations/ListenTogetherSettings.kt").takeIf { it.isFile }
            ?: File("app/src/main/kotlin/com/metrolist/music/ui/screens/settings/integrations/ListenTogetherSettings.kt")
        assertTrue(client.readText().contains("get(ListenTogetherAutoApprovalKey, true)"))
        assertTrue(settings.readText().contains("rememberPreference(ListenTogetherAutoApprovalKey, true)"))
        assertEquals(0, Regex("""ListenTogetherAutoApprovalKey, false\)""").findAll(client.readText() + settings.readText()).count())
    }
}
