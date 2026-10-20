/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 10 — AskRepository: no call without a session or with the switch off, a timeout that
// means Unavailable, one call and no retry, and the daily limit with its reset time.
package com.metrolist.music.saraswati.ask

import com.metrolist.music.saraswati.family.FakeHub
import com.metrolist.music.saraswati.family.OFFLINE
import com.metrolist.music.saraswati.family.FamilyFeatureGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

class AskRepositoryTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var hub: FakeAskHub
    private lateinit var identity: FixedIdentity
    private lateinit var familyHub: FakeHub
    private lateinit var gate: FamilyFeatureGate
    private val now = Instant.parse("2026-10-19T10:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        hub = FakeAskHub()
        identity = FixedIdentity()
        familyHub = FakeHub()
        gate =
            askGate(scope, askDataStore(scope, folder.newFile("gate.preferences_pb")), familyHub, identity)
                .also { runBlocking { withTimeout(5_000) { it.awaitHydrated() }; it.refresh() } }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun repo() = AskRepository(hub, identity, gate, clock)

    private fun switchOff() =
        runBlocking {
            familyHub.killSwitches = mapOf("ask_saraswati" to false)
            gate.refresh()
        }

    @Test
    fun `no session token is Unavailable without a call`() =
        runBlocking {
            identity.token = null

            assertEquals(AskOutcome.Unavailable, repo().ask("kal ka mausam"))
            assertTrue(hub.calls.isEmpty())
        }

    @Test
    fun `the switch off is Unavailable without a call`() {
        switchOff()

        runBlocking {
            assertEquals(AskOutcome.Unavailable, repo().ask("kal ka mausam"))
        }
        assertTrue(hub.calls.isEmpty())
    }

    @Test
    fun `a reply is passed through with the session and the phone's locale`() =
        runBlocking {
            hub.answer = Result.success(AskReply(AskIntent.Sangat, fallback = false))

            val outcome = repo().ask("sangat kholo")

            assertEquals(AskOutcome.Reply(AskReply(AskIntent.Sangat, fallback = false)), outcome)
            assertEquals(listOf("sess"), hub.calls.map { it.token })
            assertEquals(listOf(Locale.getDefault().language), hub.calls.map { it.locale.substringBefore('-') })
        }

    @Test
    fun `a slow hub times out after 6 seconds as Unavailable`() =
        runBlocking {
            hub.delayMs = 10_000

            val started = System.nanoTime()
            val outcome = repo().ask("kuch bhi")
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertEquals(AskOutcome.Unavailable, outcome)
            assertTrue("took $elapsedMs ms", elapsedMs in 5_900..8_000)
        }

    @Test
    fun `a failure is Unavailable and is never retried`() =
        runBlocking {
            hub.answer = Result.failure(OFFLINE)

            assertEquals(AskOutcome.Unavailable, repo().ask("kuch bhi"))
            assertEquals(1, hub.calls.size)
        }

    @Test
    fun `the daily limit is LimitReached with its reset time`() =
        runBlocking {
            val resetAt = Instant.parse("2026-10-20T00:00:00Z")
            hub.answer = Result.failure(AskLimitReached(resetAt))

            assertEquals(AskOutcome.LimitReached(resetAt), repo().ask("kuch bhi"))
        }

    @Test
    fun `a limit whose reset time has passed is not shown as one`() =
        runBlocking {
            hub.answer = Result.failure(AskLimitReached(Instant.parse("2026-10-18T00:00:00Z")))

            assertEquals(AskOutcome.Unavailable, repo().ask("kuch bhi"))
        }

    @Test
    fun `the locale is xx or xx-YY, English when the language is not two letters`() {
        assertEquals("hi-IN", appLocale(Locale("hi", "IN")))
        assertEquals("en", appLocale(Locale("en")))
        assertEquals("en-US", appLocale(Locale.US))
        assertEquals("en", appLocale(Locale.ROOT))
    }
}
