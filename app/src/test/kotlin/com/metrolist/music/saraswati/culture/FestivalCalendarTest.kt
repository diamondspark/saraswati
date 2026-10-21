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
import org.junit.Assume
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
    // Ghatasthapana to Vijayadashami; Dhanteras to Bhai Dooj. The other festivals are single days unless noted:
    // Ganesh Chaturthi runs Chaturthi to Anant Chaturdashi, and Chhath Puja its four days.
    private val pinned =
        listOf(
            pin("New Year's Day", "2026-01-01", "2026-01-01"),
            pin("Lohri", "2026-01-13", "2026-01-13"),
            pin("Makar Sankranti", "2026-01-14", "2026-01-14"),
            pin("Vasant Panchami", "2026-01-23", "2026-01-23"),
            pin("Republic Day", "2026-01-26", "2026-01-26"),
            pin("Valentine's Day", "2026-02-14", "2026-02-14"),
            pin("Maha Shivratri", "2026-02-15", "2026-02-15"),
            pin("Holi", "2026-03-03", "2026-03-04"),
            pin("Ram Navami", "2026-03-26", "2026-03-26"),
            pin("Hanuman Jayanti", "2026-04-02", "2026-04-02"),
            pin("Baisakhi", "2026-04-14", "2026-04-14"),
            pin("Canada Day", "2026-07-01", "2026-07-01"),
            pin("Guru Purnima", "2026-07-29", "2026-07-29"),
            pin("Independence Day", "2026-08-15", "2026-08-15"),
            pin("Raksha Bandhan", "2026-08-28", "2026-08-29"),
            pin("Krishna Janmashtami", "2026-09-04", "2026-09-05"),
            pin("Ganesh Chaturthi", "2026-09-14", "2026-09-24"),
            pin("Navratri", "2026-10-11", "2026-10-20"),
            pin("Karva Chauth", "2026-10-29", "2026-10-29"),
            pin("Diwali", "2026-11-06", "2026-11-11"),
            pin("Chhath Puja", "2026-11-13", "2026-11-16"),
            pin("Christmas", "2026-12-25", "2026-12-25"),
            pin("New Year's Day", "2027-01-01", "2027-01-01"),
            pin("Lohri", "2027-01-14", "2027-01-14"),
            pin("Makar Sankranti", "2027-01-15", "2027-01-15"),
            pin("Republic Day", "2027-01-26", "2027-01-26"),
            pin("Vasant Panchami", "2027-02-11", "2027-02-11"),
            pin("Valentine's Day", "2027-02-14", "2027-02-14"),
            pin("Maha Shivratri", "2027-03-06", "2027-03-06"),
            pin("Holi", "2027-03-21", "2027-03-22"),
            pin("Baisakhi", "2027-04-14", "2027-04-14"),
            pin("Ram Navami", "2027-04-15", "2027-04-15"),
            pin("Hanuman Jayanti", "2027-04-20", "2027-04-20"),
            pin("Canada Day", "2027-07-01", "2027-07-01"),
            pin("Guru Purnima", "2027-07-18", "2027-07-18"),
            pin("Independence Day", "2027-08-15", "2027-08-15"),
            pin("Raksha Bandhan", "2027-08-17", "2027-08-18"),
            pin("Krishna Janmashtami", "2027-08-25", "2027-08-26"),
            pin("Ganesh Chaturthi", "2027-09-04", "2027-09-14"),
            pin("Navratri", "2027-09-30", "2027-10-09"),
            pin("Thanksgiving", "2027-10-11", "2027-10-11"),
            pin("Karva Chauth", "2027-10-18", "2027-10-18"),
            pin("Diwali", "2027-10-27", "2027-10-31"),
            pin("Chhath Puja", "2027-11-02", "2027-11-05"),
            pin("Christmas", "2027-12-25", "2027-12-25"),
            pin("New Year's Day", "2028-01-01", "2028-01-01"),
            pin("Lohri", "2028-01-14", "2028-01-14"),
            pin("Makar Sankranti", "2028-01-15", "2028-01-15"),
            pin("Republic Day", "2028-01-26", "2028-01-26"),
            pin("Vasant Panchami", "2028-01-31", "2028-01-31"),
            pin("Valentine's Day", "2028-02-14", "2028-02-14"),
            pin("Maha Shivratri", "2028-02-23", "2028-02-23"),
            pin("Holi", "2028-03-10", "2028-03-11"),
            pin("Ram Navami", "2028-04-03", "2028-04-03"),
            pin("Hanuman Jayanti", "2028-04-09", "2028-04-09"),
            pin("Baisakhi", "2028-04-13", "2028-04-13"),
            pin("Canada Day", "2028-07-01", "2028-07-01"),
            pin("Guru Purnima", "2028-07-06", "2028-07-06"),
            pin("Raksha Bandhan", "2028-08-05", "2028-08-06"),
            pin("Krishna Janmashtami", "2028-08-13", "2028-08-14"),
            pin("Independence Day", "2028-08-15", "2028-08-15"),
            pin("Ganesh Chaturthi", "2028-08-23", "2028-09-02"),
            pin("Navratri", "2028-09-19", "2028-09-27"),
            pin("Karva Chauth", "2028-10-07", "2028-10-07"),
            pin("Thanksgiving", "2028-10-09", "2028-10-09"),
            pin("Diwali", "2028-10-15", "2028-10-19"),
            pin("Chhath Puja", "2028-10-21", "2028-10-24"),
            pin("Christmas", "2028-12-25", "2028-12-25"),
        )

    private val festivals = FestivalCalendar.all

    // --- the table is the sourced one --------------------------------------

    @Test
    fun `the table is exactly the sourced dates`() {
        val actual = festivals.map { Pinned(it.name, it.startDate, it.endDate) }

        assertEquals(pinned.sortedBy { it.start }, actual)
        assertEquals(68, festivals.size)
        assertEquals("year 2026", setOf("New Year's Day", "Lohri", "Makar Sankranti", "Vasant Panchami", "Republic Day", "Valentine's Day", "Maha Shivratri", "Holi", "Ram Navami", "Hanuman Jayanti", "Baisakhi", "Canada Day", "Guru Purnima", "Independence Day", "Raksha Bandhan", "Krishna Janmashtami", "Ganesh Chaturthi", "Navratri", "Karva Chauth", "Diwali", "Chhath Puja", "Christmas"), festivals.filter { it.startDate.year == 2026 }.map { it.name }.toSet())
        assertEquals("year 2027", setOf("New Year's Day", "Lohri", "Makar Sankranti", "Republic Day", "Vasant Panchami", "Valentine's Day", "Maha Shivratri", "Holi", "Baisakhi", "Ram Navami", "Hanuman Jayanti", "Canada Day", "Guru Purnima", "Independence Day", "Raksha Bandhan", "Krishna Janmashtami", "Ganesh Chaturthi", "Navratri", "Thanksgiving", "Karva Chauth", "Diwali", "Chhath Puja", "Christmas"), festivals.filter { it.startDate.year == 2027 }.map { it.name }.toSet())
        assertEquals("year 2028", setOf("New Year's Day", "Lohri", "Makar Sankranti", "Republic Day", "Vasant Panchami", "Valentine's Day", "Maha Shivratri", "Holi", "Ram Navami", "Hanuman Jayanti", "Baisakhi", "Canada Day", "Guru Purnima", "Raksha Bandhan", "Krishna Janmashtami", "Independence Day", "Ganesh Chaturthi", "Navratri", "Karva Chauth", "Thanksgiving", "Diwali", "Chhath Puja", "Christmas"), festivals.filter { it.startDate.year == 2028 }.map { it.name }.toSet())
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
        for (day in listOf("2026-05-15", "2026-03-05", "2026-06-15", "2026-08-30", "2026-12-31", "2027-05-15", "2027-06-01", "2028-12-31")) {
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
        assertEquals("Diwali songs", diwali.radioQueryHi)
    }

    @Test
    fun `every festival has an English greeting, a Hindi greeting in Devanagari and a radio search`() {
        val devanagariOrPunctuation = Regex("""^[ऀ-ॿ !]+$""")
        for (f in festivals) {
            assertTrue("${f.name} greetingEn", f.greetingEn.isNotBlank() && f.greetingEn.endsWith("!"))
            assertTrue("${f.name} greetingHi", devanagariOrPunctuation.matches(f.greetingHi))
            assertTrue("${f.name} greetingHi ends with a mark", f.greetingHi.endsWith("!"))
            assertTrue("${f.name} radioQueryHi", f.radioQueryHi.isNotBlank())
            assertTrue("${f.name} radioQueryEn", f.radioQueryEn.isNotBlank())
            assertTrue("${f.name} radioQueryEn names the festival", f.radioQueryEn.contains(f.name.substringBefore(" ")))
        }
    }

    @Test
    fun `a festival reads the same in every year`() {
        for ((name, years) in festivals.groupBy { it.name }) {
            assertEquals(name, 1, years.map { listOf(it.greetingEn, it.greetingHi, it.radioQueryHi, it.radioQueryEn) }.toSet().size)
        }
    }

    @Test
    fun `each festival's radio search is its own`() {
        val queries = festivals.groupBy { it.name }.mapValues { it.value.first().radioQueryHi }

        assertEquals(23, queries.values.toSet().size)
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
        val clock = Clock.fixed(Instant.parse("2026-05-01T05:00:00Z"), ZoneOffset.UTC)

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
            assertEquals("still the Navratri festival, with its own radio", "Navratri songs", last.radioQueryHi)

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
    fun `the table ends on 2028-12-25`() {
        assertEquals(LocalDate.parse("2028-12-25"), festivals.maxOf { it.endDate })
    }

    // The strict mode is for the weekly festival-table workflow and a deliberate manual run only, never for
    // saraswati-ci or a release: a stale hand-sourced calendar must not be able to hold back a fix (see
    // docs/release-runbook.md, "Before every release"). Gradle passes it on with -Psaraswati.strictFestivalTable=true.
    private fun strictMode() = System.getProperty("saraswati.strictFestivalTable") == "true"

    private fun extendTheTableMessage(
        ends: LocalDate,
        today: LocalDate,
    ) = "The festival table ends on $ends and today is $today: extend FestivalCalendar.all (and the pinned " +
        "copy in this test) with the next years from Drik Panchang before the table runs out. " +
        "See docs/release-runbook.md, \"Before every release\"."

    /**
     * The live check, with the clock and the mode handed in. Within six months of the table's end it prints a
     * `::warning::` line (GitHub shows it on the run) and then either fails (strict) or skips (the default).
     */
    private fun checkTable(
        today: LocalDate,
        ends: LocalDate,
        strict: Boolean,
        warn: (String) -> Unit = ::println,
    ) {
        val message = extendTheTableMessage(ends, today)
        val nearlyOut = tableNearlyOut(today, ends)
        if (nearlyOut) warn("::warning title=Festival table is running out::$message")
        if (strict) {
            assertTrue(message, !nearlyOut)
        } else {
            // A skip, not a failure: the app is fine without the next years (no banner shows), and this must never block a release.
            Assume.assumeTrue(message, !nearlyOut)
        }
    }

    @Test
    fun `the festival table is not within six months of running out`() {
        checkTable(LocalDate.now(), festivals.maxOf { it.endDate }, strictMode())
    }

    @Test
    fun `near the end of the table the default mode skips with the extend-the-table message and a warning line`() {
        val ends = LocalDate.parse("2028-10-19")
        val warnings = mutableListOf<String>()

        val skipped = runCatching { checkTable(LocalDate.parse("2028-05-01"), ends, strict = false, warn = warnings::add) }.exceptionOrNull()

        assertTrue("a skip, not a failure: $skipped", skipped is org.junit.AssumptionViolatedException)
        assertTrue(skipped!!.message!!.contains("extend FestivalCalendar.all"))
        assertTrue(skipped.message!!.contains("2028-10-19"))
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].startsWith("::warning title=Festival table is running out::"))
        assertTrue(warnings[0].contains("Before every release"))
    }

    @Test
    fun `near the end of the table the strict mode fails with the same message`() {
        val ends = LocalDate.parse("2028-10-19")
        val warnings = mutableListOf<String>()

        val failed = runCatching { checkTable(LocalDate.parse("2028-05-01"), ends, strict = true, warn = warnings::add) }.exceptionOrNull()

        assertTrue("a failure: $failed", failed is AssertionError)
        assertTrue(failed!!.message!!.contains("extend FestivalCalendar.all"))
        assertEquals(1, warnings.size)
    }

    @Test
    fun `well before the end of the table neither mode warns, skips or fails`() {
        val ends = LocalDate.parse("2028-10-19")
        val warnings = mutableListOf<String>()

        checkTable(LocalDate.parse("2026-10-19"), ends, strict = false, warn = warnings::add)
        checkTable(LocalDate.parse("2028-04-18"), ends, strict = true, warn = warnings::add)

        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `the strict mode is a system property that only the weekly workflow and a manual run switch on`() {
        val root = generateSequence(java.io.File(".").absoluteFile) { it.parentFile }.first { java.io.File(it, "settings.gradle.kts").isFile }
        val workflows = java.io.File(root, ".github/workflows")

        val strict = workflows.listFiles()!!.filter { it.isFile && it.readText().contains("saraswati.strictFestivalTable") }.map { it.name }

        assertEquals("only the scheduled festival-table check runs the strict mode", listOf("saraswati-festival-table.yml"), strict)
        val text = java.io.File(workflows, "saraswati-festival-table.yml").readText()
        assertTrue(text.contains("schedule:"))
        assertTrue(text.contains("--tests '*FestivalCalendarTest'"))
    }
}
