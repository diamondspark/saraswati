/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 10 — which festival, if any, today falls in. The dates are
// lunar-calendar dates that move every year, so they are PINNED below exactly as
// they were read from Drik Panchang (New Delhi) and never computed. This test
// holds a second, independently typed copy of that list: a slipped digit in the
// table fails here instead of greeting the family on the wrong day.
package com.metrolist.music.saraswati.culture

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class FestivalCalendarTest {
    private data class Pinned(
        val name: String,
        val start: LocalDate,
        val end: LocalDate,
    )

    private fun pin(
        name: String,
        start: String,
        end: String,
    ) = Pinned(name, LocalDate.parse(start), LocalDate.parse(end))

    // Window = the festival's own days: Holika Dahan to Rangwali Holi; Rakhi and Janmashtami to the day after;
    // Ghatasthapana to Vijayadashami; Dhanteras to Bhai Dooj.
    private val pinned =
        listOf(
            pin("Holi", "2026-03-03", "2026-03-04"),
            pin("Raksha Bandhan", "2026-08-28", "2026-08-29"),
            pin("Krishna Janmashtami", "2026-09-04", "2026-09-05"),
            pin("Navratri", "2026-10-11", "2026-10-20"),
            pin("Diwali", "2026-11-06", "2026-11-11"),
            pin("Holi", "2027-03-21", "2027-03-22"),
            pin("Raksha Bandhan", "2027-08-17", "2027-08-18"),
            pin("Krishna Janmashtami", "2027-08-25", "2027-08-26"),
            pin("Navratri", "2027-09-30", "2027-10-09"),
            pin("Diwali", "2027-10-27", "2027-10-31"),
            pin("Holi", "2028-03-10", "2028-03-11"),
            pin("Raksha Bandhan", "2028-08-05", "2028-08-06"),
            pin("Krishna Janmashtami", "2028-08-13", "2028-08-14"),
            pin("Navratri", "2028-09-19", "2028-09-27"),
            pin("Diwali", "2028-10-15", "2028-10-19"),
        )

    private val festivals = FestivalCalendar.all

    // --- the table is the sourced one --------------------------------------

    @Test
    fun `the table is exactly the sourced dates, five festivals for each of three years`() {
        val actual = festivals.map { Pinned(it.name, it.startDate, it.endDate) }

        assertEquals(pinned.sortedBy { it.start }, actual)
        assertEquals(15, festivals.size)
        for (year in 2026..2028) {
            assertEquals(
                "year $year",
                setOf("Diwali", "Holi", "Navratri", "Raksha Bandhan", "Krishna Janmashtami"),
                festivals.filter { it.startDate.year == year }.map { it.name }.toSet(),
            )
        }
    }

    @Test
    fun `the table runs to the end of 2028 and no further`() {
        assertEquals(2028, festivals.maxOf { it.endDate.year })
        assertEquals(2026, festivals.minOf { it.startDate.year })
    }

    // --- looking a day up ----------------------------------------------------

    @Test
    fun `the first and last day of every sourced window return that festival`() {
        for (window in pinned) {
            assertEquals("${window.name} first day", window.name, FestivalCalendar.current(window.start)?.name)
            assertEquals("${window.name} last day", window.name, FestivalCalendar.current(window.end)?.name)
            val middle = window.start.plusDays(window.start.until(window.end, java.time.temporal.ChronoUnit.DAYS) / 2)
            assertEquals("${window.name} middle", window.name, FestivalCalendar.current(middle)?.name)
        }
    }

    @Test
    fun `the day before a window and the day after it are not that festival`() {
        for (window in pinned) {
            assertTrue("${window.name} eve", FestivalCalendar.current(window.start.minusDays(1))?.startDate != window.start)
            assertTrue("${window.name} day after", FestivalCalendar.current(window.end.plusDays(1))?.startDate != window.start)
        }
    }

    @Test
    fun `a day between festivals has none`() {
        for (day in listOf("2026-01-01", "2026-03-05", "2026-06-15", "2026-08-30", "2026-12-25", "2027-01-01", "2027-06-01", "2028-12-31")) {
            assertNull(day, FestivalCalendar.current(LocalDate.parse(day)))
        }
    }

    @Test
    fun `before the table starts and after it ends there is none`() {
        assertNull(FestivalCalendar.current(LocalDate.parse("2025-11-01")))
        assertNull(FestivalCalendar.current(LocalDate.parse("2029-03-01")))
        assertNull(FestivalCalendar.current(LocalDate.parse("2029-10-30")))
    }

    @Test
    fun `the same festival in another year is not found on this year's dates`() {
        // Diwali 2027 is not Diwali 2026's day, even though it is the same festival.
        assertEquals("Diwali", FestivalCalendar.current(LocalDate.parse("2026-11-08"))?.name)
        assertNull(FestivalCalendar.current(LocalDate.parse("2027-11-08")))
        assertEquals("Diwali", FestivalCalendar.current(LocalDate.parse("2027-10-29"))?.name)
    }

    @Test
    fun `today is read from the clock when no day is given`() {
        // Not asserting which festival: only that the default does not throw and agrees with an explicit today.
        assertEquals(FestivalCalendar.current(LocalDate.now()), FestivalCalendar.current())
    }

    // --- the table's structure ------------------------------------------------

    @Test
    fun `every window has a start on or before its end`() {
        for (f in festivals) assertTrue(f.name, !f.startDate.isAfter(f.endDate))
    }

    @Test
    fun `no two festival windows overlap anywhere in the table`() {
        val sorted = festivals.sortedBy { it.startDate }

        for ((a, b) in sorted.zipWithNext()) {
            assertTrue(
                "${a.name} ${a.startDate}..${a.endDate} runs into ${b.name} ${b.startDate}..${b.endDate}",
                a.endDate.isBefore(b.startDate),
            )
        }
    }

    @Test
    fun `the table is kept in date order`() {
        assertEquals(festivals.sortedBy { it.startDate }, festivals)
    }

    @Test
    fun `no day belongs to two festivals`() {
        var day = LocalDate.parse("2026-01-01")
        val last = LocalDate.parse("2029-01-01")
        while (day.isBefore(last)) {
            val matches = festivals.count { !day.isBefore(it.startDate) && !day.isAfter(it.endDate) }
            assertTrue("$day is in $matches windows", matches <= 1)
            day = day.plusDays(1)
        }
    }

    // --- the copy --------------------------------------------------------------

    @Test
    fun `Diwali's Hindi greeting is the approved copy`() {
        val diwali = festivals.first { it.name == "Diwali" }

        assertEquals("दीपावली की शुभकामनाएं!", diwali.greetingHi)
        assertEquals("Happy Diwali!", diwali.greetingEn)
        assertEquals("Diwali songs", diwali.radioQuery)
    }

    @Test
    fun `every festival has an English greeting, a Hindi greeting in Devanagari and a radio search`() {
        val devanagariOrPunctuation = Regex("""^[ऀ-ॿ !]+$""")
        for (f in festivals) {
            assertTrue("${f.name} greetingEn", f.greetingEn.isNotBlank() && f.greetingEn.endsWith("!"))
            assertTrue("${f.name} greetingHi", devanagariOrPunctuation.matches(f.greetingHi))
            assertTrue("${f.name} greetingHi ends with a mark", f.greetingHi.endsWith("!"))
            assertTrue("${f.name} radioQuery", f.radioQuery.isNotBlank())
            assertTrue("${f.name} radioQuery names the festival", f.radioQuery.contains(f.name.substringBefore(" ")))
        }
    }

    @Test
    fun `a festival reads the same in every year`() {
        for ((name, years) in festivals.groupBy { it.name }) {
            assertEquals(name, 1, years.map { Triple(it.greetingEn, it.greetingHi, it.radioQuery) }.toSet().size)
        }
    }

    @Test
    fun `each festival's radio search is its own`() {
        val queries = festivals.groupBy { it.name }.mapValues { it.value.first().radioQuery }

        assertEquals(5, queries.values.toSet().size)
    }

    // --- the Home banner's title -------------------------------------------------

    @Test
    fun `the Home banner title joins both greetings the way the mockup does`() {
        val diwali = festivals.first { it.name == "Diwali" }

        assertEquals("Happy Diwali! · दीपावली की शुभकामनाएं", diwali.bannerTitle())
    }

    // --- following the clock --------------------------------------------------------

    @Test
    fun `the flow starts with the festival of the clock's day`() {
        val clock = Clock.fixed(Instant.parse("2026-11-08T05:00:00Z"), ZoneOffset.UTC)

        val first = runBlocking { currentFestivalFlow(clock).first() }

        assertEquals("Diwali", first?.name)
    }

    @Test
    fun `the flow starts with none on an ordinary day`() {
        val clock = Clock.fixed(Instant.parse("2026-12-25T05:00:00Z"), ZoneOffset.UTC)

        assertNull(runBlocking { currentFestivalFlow(clock).first() })
    }

    @Test
    fun `the day is the phone's own local day, not UTC`() {
        // 2026-11-05 20:00 UTC is already 2026-11-06 in India: Dhanteras, the first day of the Diwali window.
        val clock = Clock.fixed(Instant.parse("2026-11-05T20:00:00Z"), ZoneId.of("Asia/Kolkata"))

        assertEquals("Diwali", runBlocking { currentFestivalFlow(clock).first() }?.name)
    }

    @Test
    fun `the wait until the day changes is measured to the next local midnight`() {
        val noon = ZonedDateTime.of(2026, 11, 8, 12, 0, 0, 0, ZoneId.of("Asia/Kolkata"))
        val lateEvening = ZonedDateTime.of(2026, 11, 8, 23, 59, 30, 0, ZoneId.of("Asia/Kolkata"))

        assertEquals(12L * 60 * 60 * 1000, millisUntilNextDay(noon))
        assertEquals(30_000L, millisUntilNextDay(lateEvening))
    }

    @Test
    fun `the ninth day of Navratri 2026 is found without a clock`() {
        assertNotNull(FestivalCalendar.current(LocalDate.parse("2026-10-19")))
        assertEquals("Navratri", FestivalCalendar.current(LocalDate.parse("2026-10-19"))?.name)
    }

    // --- SARASWATI: fix wave B2 (M6) — the last day of Navratri is Dussehra ---------------------------------

    @Test
    fun `the last day of Navratri greets with Vijayadashami, every year, and no other day does`() {
        for (window in pinned.filter { it.name == "Navratri" }) {
            val last = FestivalCalendar.current(window.end)!!
            assertEquals("Happy Dussehra!", last.greetingEn)
            assertEquals("विजयादशमी की शुभकामनाएं!", last.greetingHi)
            assertEquals("the banner title carries both", "Happy Dussehra! · विजयादशमी की शुभकामनाएं", last.bannerTitle())
            assertEquals("still the Navratri festival, with its own radio", "Navratri songs", last.radioQuery)

            val eve = FestivalCalendar.current(window.end.minusDays(1))!!
            assertEquals("Happy Navratri!", eve.greetingEn)
            assertEquals("नवरात्रि की शुभकामनाएं!", eve.greetingHi)
            assertEquals("Happy Navratri!", FestivalCalendar.current(window.start)!!.greetingEn)
        }
    }

    @Test
    fun `no other festival has a different greeting on its last day`() {
        for (window in pinned.filter { it.name != "Navratri" }) {
            val last = FestivalCalendar.current(window.end)!!
            val first = FestivalCalendar.current(window.start)!!
            assertEquals(window.name, first.greetingHi, last.greetingHi)
            assertEquals(window.name, first.greetingEn, last.greetingEn)
        }
    }

    // --- SARASWATI: fix wave B2 (M5) — the zone is read when the day is worked out, not once -----------------

    @Test
    fun `the clock is asked again each time, so a phone that changed time zone gets the new zone's day`() {
        val instant = Instant.parse("2026-11-06T02:00:00Z")
        var zone = ZoneId.of("Asia/Kolkata")
        val clock = FestivalClock({ Clock.fixed(instant, zone) })

        assertEquals("07:30 on 6 November in India: the first day of Diwali", "Diwali", clock.current()?.name)

        zone = ZoneId.of("America/Los_Angeles")

        assertNull("18:00 on 5 November in California: not yet", clock.current())
    }

    @Test
    fun `the flow asks the clock each time round too`() {
        val instant = Instant.parse("2026-11-06T02:00:00Z")
        var zone = ZoneId.of("America/Los_Angeles")

        assertNull(runBlocking { currentFestivalFlow { Clock.fixed(instant, zone) }.first() })
        zone = ZoneId.of("Asia/Kolkata")
        assertEquals("Diwali", runBlocking { currentFestivalFlow { Clock.fixed(instant, zone) }.first() }?.name)
    }

    @Test
    fun `the default clock is built per call, not once`() {
        val source = java.io.File("src/main/kotlin/com/metrolist/music/saraswati/culture/FestivalCalendar.kt")
            .takeIf { it.isFile }
            ?: java.io.File("app/src/main/kotlin/com/metrolist/music/saraswati/culture/FestivalCalendar.kt")
        val text = source.readText()

        assertTrue(text.contains("clock: () -> Clock = { Clock.systemDefaultZone() }"))
        assertTrue("no field holds a zone for good", !Regex("""val\s+\w+\s*=\s*Clock\.systemDefaultZone\(\)""").containsMatchIn(text))
    }

    // --- SARASWATI: fix wave B2 (M2) — the table is hand-sourced and runs out -----------------------------------

    /** True once [today] is within six months of the day the table ends. */
    private fun tableNearlyOut(
        today: LocalDate,
        tableEnds: LocalDate,
    ) = !today.isBefore(tableEnds.minusMonths(6))

    @Test
    fun `the reminder fires six months before the table ends, not before`() {
        val ends = LocalDate.parse("2028-10-19")

        assertTrue(!tableNearlyOut(LocalDate.parse("2028-04-18"), ends))
        assertTrue(tableNearlyOut(LocalDate.parse("2028-04-19"), ends))
        assertTrue(tableNearlyOut(LocalDate.parse("2028-10-19"), ends))
        assertTrue(tableNearlyOut(LocalDate.parse("2029-01-01"), ends))
    }

    @Test
    fun `the table ends on 2028-10-19`() {
        assertEquals(LocalDate.parse("2028-10-19"), festivals.maxOf { it.endDate })
    }

    @Test
    fun `the festival table is not within six months of running out`() {
        val ends = festivals.maxOf { it.endDate }

        assertTrue(
            "The festival table ends on $ends and today is ${LocalDate.now()}: extend FestivalCalendar.all (and the pinned " +
                "copy in this test) with the next years from Drik Panchang before the next release. " +
                "See docs/release-runbook.md, \"Before every release\".",
            !tableNearlyOut(LocalDate.now(), ends),
        )
    }
}
