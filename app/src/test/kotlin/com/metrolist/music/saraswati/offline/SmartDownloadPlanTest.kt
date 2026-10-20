package com.metrolist.music.saraswati.offline

import com.metrolist.music.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class SmartDownloadPlanTest {
    private fun song(
        id: String,
        source: CandidateSource,
    ) = Candidate(MediaMetadata(id = id, title = id, artists = emptyList(), duration = 200), source)

    private fun pick(
        candidates: List<Candidate>,
        ledger: LedgerSnapshot = LedgerSnapshot.EMPTY,
        night: String = "2026-10-19",
        downloaded: Set<String> = emptySet(),
    ) = SmartDownloadPlan.pick(candidates, { it in downloaded }, ledger, night).map { it.id }

    private fun done(
        vararg ids: Pair<String, Long>,
    ) = ids.associate { (id, at) -> id to LedgerEntry(downloadedAt = at) }

    @Test
    fun `orders mix, then stations, then top played, then liked`() {
        val picked =
            pick(
                listOf(
                    song("liked1", CandidateSource.LIKED),
                    song("top1", CandidateSource.TOP_PLAYED),
                    song("station1", CandidateSource.STATION),
                    song("mix1", CandidateSource.PARIVAAR_MIX),
                    song("mix2", CandidateSource.PARIVAAR_MIX),
                    song("top1", CandidateSource.PARIVAAR_MIX),
                ),
            )

        // top1 sits in two lists; the first source (the mix) wins, so it moves up with it.
        assertEquals(listOf("mix1", "mix2", "top1", "station1", "liked1"), picked)
    }

    @Test
    fun `skips local, downloaded and twice-failed songs`() {
        val ledger =
            LedgerSnapshot(
                entries =
                    mapOf(
                        "failedTwice" to LedgerEntry(failures = 2),
                        "failedOnce" to LedgerEntry(failures = 1),
                    ),
            )

        val picked =
            pick(
                listOf(
                    song("local:42", CandidateSource.PARIVAAR_MIX),
                    song("have", CandidateSource.PARIVAAR_MIX),
                    song("failedTwice", CandidateSource.STATION),
                    song("failedOnce", CandidateSource.STATION),
                    song("fresh", CandidateSource.LIKED),
                ),
                ledger = ledger,
                downloaded = setOf("have"),
            )

        assertEquals(listOf("failedOnce", "fresh"), picked)
    }

    @Test
    fun `never exceeds 50 a night across calls`() {
        val candidates = (1..120).map { song("s$it", CandidateSource.TOP_PLAYED) }
        var ledger = LedgerSnapshot()
        val taken = mutableSetOf<String>()

        repeat(5) {
            val batch = SmartDownloadPlan.pick(candidates, { it in taken }, ledger, "2026-10-19").take(8)
            batch.forEach { taken += it.id }
            ledger = ledger.rolledTo("2026-10-19").let { it.copy(doneTonight = it.doneTonight + batch.size) }
        }
        assertEquals(40, taken.size)

        val last = SmartDownloadPlan.pick(candidates, { it in taken }, ledger, "2026-10-19")
        assertEquals(10, last.size)

        val full = ledger.copy(doneTonight = SmartDownloadPlan.NIGHTLY_MAX)
        assertTrue(SmartDownloadPlan.pick(candidates, { false }, full, "2026-10-19").isEmpty())
        assertTrue(SmartDownloadPlan.pick(candidates, { false }, full.copy(doneTonight = 60), "2026-10-19").isEmpty())
    }

    @Test
    fun `a new night resets the count and records last night's`() {
        val ledger = LedgerSnapshot(night = "2026-10-18", doneTonight = 37, failuresInARow = 2, lastNightCount = 5)

        val next = ledger.rolledTo("2026-10-19")

        assertEquals("2026-10-19", next.night)
        assertEquals(0, next.doneTonight)
        assertEquals(0, next.failuresInARow)
        assertEquals(37, next.lastNightCount)
        assertEquals(ledger, ledger.rolledTo("2026-10-18"))
        assertEquals(50, pick((1..80).map { song("s$it", CandidateSource.LIKED) }, ledger.copy(doneTonight = 50), "2026-10-19").size)
        assertEquals(0, pick((1..80).map { song("s$it", CandidateSource.LIKED) }, ledger.copy(doneTonight = 50), "2026-10-18").size)
    }

    @Test
    fun `night changes at noon local time, not midnight`() {
        val zone = ZoneId.of("Asia/Kolkata")

        fun key(
            hour: Int,
            minute: Int,
            day: Int = 19,
        ) = SmartDownloadPlan.nightKey(
            java.time.LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant(),
            zone,
        )

        assertEquals("2026-10-18", key(0, 30))
        assertEquals("2026-10-18", key(11, 59))
        assertEquals("2026-10-19", key(12, 0))
        assertEquals("2026-10-19", key(23, 59))
        assertEquals("2026-10-19", key(0, 30, day = 20).also { assertEquals(key(23, 59), it) })
        assertEquals("2026-10-19", SmartDownloadPlan.nightKey(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC))
    }

    @Test
    fun `the next night starts at the next local noon`() {
        val zone = ZoneId.of("Asia/Kolkata")
        val before = java.time.LocalDateTime.of(2026, 10, 19, 3, 0).atZone(zone).toInstant()
        val after = java.time.LocalDateTime.of(2026, 10, 19, 15, 0).atZone(zone).toInstant()
        val noon = java.time.LocalDateTime.of(2026, 10, 19, 12, 0).atZone(zone).toInstant()

        assertEquals(noon, SmartDownloadPlan.nextNightStart(before, zone))
        assertEquals(noon.plusSeconds(86_400), SmartDownloadPlan.nextNightStart(after, zone))
        assertEquals(noon.plusSeconds(86_400), SmartDownloadPlan.nextNightStart(noon, zone))
    }

    @Test
    fun `evicts beyond 300 oldest first`() {
        val ledger = LedgerSnapshot(entries = done(*(1..305).map { "s$it" to it * 1000L }.toTypedArray()))

        assertEquals(listOf("s1", "s2", "s3", "s4", "s5"), SmartDownloadPlan.evict(ledger, emptySet()))
        assertTrue(SmartDownloadPlan.evict(LedgerSnapshot(entries = done(*(1..300).map { "s$it" to it * 1000L }.toTypedArray())), emptySet()).isEmpty())
    }

    @Test
    fun `never evicts current candidates`() {
        val ledger = LedgerSnapshot(entries = done(*(1..305).map { "s$it" to it * 1000L }.toTypedArray()))

        assertEquals(listOf("s2", "s4", "s5", "s6", "s7"), SmartDownloadPlan.evict(ledger, setOf("s1", "s3")))
    }

    @Test
    fun `never evicts a user download`() {
        val entries = done(*(1..305).map { "s$it" to it * 1000L }.toTypedArray()) + ("pending" to LedgerEntry(pendingSince = 1L))
        val evicted = SmartDownloadPlan.evict(LedgerSnapshot(entries = entries), emptySet())

        // A user download is not in the ledger at all, so it can never be returned; an unfinished one is not counted.
        assertEquals(5, evicted.size)
        assertTrue("userDownload" !in evicted)
        assertTrue("pending" !in evicted)
    }
}
