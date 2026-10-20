/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: fix wave B1 (final review I2) — nothing ever called HubApi.reportHealth, so the owner's
// health view was always empty. A paired phone now reports its version and time zone when the app
// opens and when it comes back to the front, at most once every five minutes (the hub drops anything
// sooner), quietly, off the main thread, and never from a phone with no session.
package com.metrolist.music.saraswati.family

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.TimeZone
import java.util.concurrent.Executors

class HealthReporterTest {
    private val minute = 60_000L

    private lateinit var hub: FakeHub
    private lateinit var family: FakeStore
    private lateinit var clock: TestClock
    private lateinit var reporter: HealthReporter

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "fake-io") }
    private val io = executor.asCoroutineDispatcher()

    @Before
    fun setUp() {
        hub = FakeHub()
        family = FakeStore().apply { session = FamilySession("device-1", "the-session-token") }
        clock = TestClock(nowMs = 4_000)
        reporter = newReporter(hub)
    }

    @After
    fun tearDown() {
        io.close()
        executor.shutdownNow()
    }

    private fun newReporter(api: HubApi) =
        HealthReporter(
            api,
            family,
            ResumeThrottle(HealthReporter.MIN_INTERVAL_MS, clock.reading),
            versionCode = 160,
            versionName = "13.7.0-s160",
            timezone = { "Asia/Kolkata" },
            io = io,
        )

    private fun report() = runBlocking { reporter.report() }

    // --- who reports ------------------------------------------------------------------------------------

    @Test
    fun `a paired phone reports its own device id, version and time zone with its session token`() {
        report()

        assertEquals(
            listOf(HealthCall("the-session-token", "device-1", 160, "13.7.0-s160", "Asia/Kolkata")),
            hub.healthReports.toList(),
        )
    }

    @Test
    fun `the time zone is the phone's own unless a test says otherwise`() {
        val before = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
            val plain = HealthReporter(hub, family, ResumeThrottle(HealthReporter.MIN_INTERVAL_MS, clock.reading), 160, "13.7.0-s160", io = io)

            runBlocking { plain.report() }

            assertEquals("Pacific/Auckland", hub.healthReports.single().timezone)
        } finally {
            TimeZone.setDefault(before)
        }
    }

    @Test
    fun `an unpaired phone sends nothing`() {
        family.session = null

        report()

        assertTrue(hub.callsTo("reportHealth").isEmpty())
    }

    @Test
    fun `a phone still waiting for approval sends nothing`() {
        family.session = null
        family.claim = PendingClaim("device-9", "secret")

        report()

        assertTrue(hub.callsTo("reportHealth").isEmpty())
    }

    @Test
    fun `the owner's phone, which holds only an admin key, sends nothing`() {
        family.session = null
        family.admin = TEST_ADMIN_KEY

        report()

        assertTrue(hub.calls.isEmpty())
    }

    @Test
    fun `the admin key is never what a report is sent with`() {
        family.admin = TEST_ADMIN_KEY

        report()

        assertEquals("the-session-token", hub.healthReports.single().sessionToken)
        assertFalse(hub.calls.any { it.contains(TEST_ADMIN_KEY) })
    }

    @Test
    fun `a phone whose storage cannot be read sends nothing and does not throw`() {
        family.failReads = IOException("keystore unavailable")

        report()

        assertTrue(hub.calls.isEmpty())
    }

    // --- how often --------------------------------------------------------------------------------------

    @Test
    fun `the first report after the process starts is sent`() {
        report()

        assertEquals(1, hub.healthReports.size)
    }

    @Test
    fun `a second report inside five minutes is not sent, and one after the window is`() {
        report()
        clock.advance(5 * minute)
        report()
        assertEquals("exactly five minutes is still inside the hub's window and the margin", 1, hub.healthReports.size)

        clock.nowMs = 4_000 + HealthReporter.MIN_INTERVAL_MS - 1
        report()
        assertEquals(1, hub.healthReports.size)

        clock.nowMs = 4_000 + HealthReporter.MIN_INTERVAL_MS
        report()
        assertEquals(2, hub.healthReports.size)
    }

    @Test
    fun `the interval is at least the hub's five minutes`() {
        assertTrue(HealthReporter.MIN_INTERVAL_MS >= 5 * minute)
    }

    @Test
    fun `a phone with no session does not use up the window`() {
        family.session = null
        report()
        family.session = FamilySession("device-1", "the-session-token")
        clock.advance(1_000)

        report()

        assertEquals(1, hub.healthReports.size)
    }

    // --- quiet failure ----------------------------------------------------------------------------------

    @Test
    fun `every kind of hub failure is swallowed`() {
        val failures = listOf(OFFLINE, hubFailure(400), hubFailure(401), hubFailure(404), hubFailure(429), hubFailure(500), RuntimeException("boom"))
        for (failure in failures) {
            hub.failWith("reportHealth", failure)
            clock.advance(HealthReporter.MIN_INTERVAL_MS)

            report()
        }

        assertEquals(failures.size, hub.callsTo("reportHealth").size)
    }

    @Test
    fun `a hub that throws instead of returning a failure is swallowed too`() {
        val throwing =
            object : HubApi by hub {
                override suspend fun reportHealth(
                    sessionToken: String,
                    deviceId: String,
                    versionCode: Int,
                    versionName: String,
                    timezone: String,
                ): Result<Unit> = throw IllegalStateException("hub client bug")
            }

        runBlocking { newReporter(throwing).report() }
    }

    @Test
    fun `a failed report still uses up the window, so a struggling hub is not retried on every resume`() {
        hub.failWith("reportHealth", OFFLINE)
        report()
        hub.heal("reportHealth")
        clock.advance(minute)

        report()

        assertEquals(1, hub.callsTo("reportHealth").size)
    }

    @Test
    fun `a failed report leaves the session alone, even a 401`() {
        hub.failWith("reportHealth", hubFailure(401))

        report()

        assertEquals(FamilySession("device-1", "the-session-token"), family.session)
        assertTrue(family.cleared.isEmpty())
    }

    // --- off the main thread ----------------------------------------------------------------------------

    @Test
    fun `the report is made on the io dispatcher, not on the caller's thread`() {
        var thread = ""
        val recording =
            object : HubApi by hub {
                override suspend fun reportHealth(
                    sessionToken: String,
                    deviceId: String,
                    versionCode: Int,
                    versionName: String,
                    timezone: String,
                ): Result<Unit> {
                    thread = Thread.currentThread().name
                    return Result.success(Unit)
                }
            }

        runBlocking { newReporter(recording).report() }

        // The coroutine debug agent appends " @coroutine#N" to the thread's name.
        assertTrue(thread, thread.startsWith("fake-io"))
        assertFalse(thread.startsWith(Thread.currentThread().name))
    }
    // --- where it is called from -------------------------------------------------------------------------

    @Test
    fun `MainActivity reports on every launch and resume, as its own job beside the station refresh`() {
        val path = "src/main/kotlin/com/metrolist/music/MainActivity.kt"
        val main = listOf(java.io.File(path), java.io.File("app/$path")).first { it.exists() }.readText()
        val resumed = main.substringAfter("repeatOnLifecycle(Lifecycle.State.RESUMED)").substringBefore("// Defer migration")

        assertTrue(Regex("""launch\s*\{\s*stationsRefresher\.refresh\(\)\s*\}""").containsMatchIn(resumed))
        assertTrue(Regex("""launch\s*\{\s*healthReporter\.report\(\)\s*\}""").containsMatchIn(resumed))
        assertEquals("one call site", 1, Regex("""healthReporter\.report\(\)""").findAll(main).count())
    }
}
