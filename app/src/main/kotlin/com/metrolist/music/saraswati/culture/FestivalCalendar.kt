/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 10 — which Hindu festival, if any, today belongs to, for the
// Home banner and Saral's ribbon.
//
// These are lunar-calendar festivals: their dates move every year and are NOT
// computed here. Every date below was read off Drik Panchang (drikpanchang.com,
// New Delhi) on 2026-10-19; FestivalCalendarTest holds a second copy of them and
// the Task 10 report lists the source page of each. The table runs 2026 to 2028; a
// later day finds no festival (the banner simply does not show) until a release
// adds the next years.
//
// A window is the festival's own days, not a single date:
//  - Diwali: Dhanteras to Bhai Dooj, the five days of the festival.
//  - Holi: Holika Dahan (the eve) to Rangwali Holi.
//  - Navratri: Ghatasthapana to Vijayadashami (Shardiya Navratri, the autumn nine nights
//    and Dussehra; Chaitra Navratri in spring is not included).
//  - Raksha Bandhan and Krishna Janmashtami: the day itself and the day after
//    (Dahi Handi), which also covers the families that keep Janmashtami a day apart.
// No two windows overlap in any of the three years.
package com.metrolist.music.saraswati.culture

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime

data class Festival(
    val name: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val greetingEn: String,
    val greetingHi: String,
    val radioQuery: String,
)

object FestivalCalendar {
    private fun diwali(
        start: String,
        end: String,
    ) = Festival("Diwali", LocalDate.parse(start), LocalDate.parse(end), "Happy Diwali!", "दीपावली की शुभकामनाएं!", "Diwali songs")

    private fun holi(
        start: String,
        end: String,
    ) = Festival("Holi", LocalDate.parse(start), LocalDate.parse(end), "Happy Holi!", "होली की शुभकामनाएं!", "Holi songs")

    private fun navratri(
        start: String,
        end: String,
    ) = Festival("Navratri", LocalDate.parse(start), LocalDate.parse(end), "Happy Navratri!", "नवरात्रि की शुभकामनाएं!", "Navratri songs")

    private fun rakshaBandhan(
        start: String,
        end: String,
    ) =
        Festival(
            "Raksha Bandhan",
            LocalDate.parse(start),
            LocalDate.parse(end),
            "Happy Raksha Bandhan!",
            "रक्षाबंधन की शुभकामनाएं!",
            "Raksha Bandhan songs",
        )

    private fun janmashtami(
        start: String,
        end: String,
    ) =
        Festival(
            "Krishna Janmashtami",
            LocalDate.parse(start),
            LocalDate.parse(end),
            "Happy Janmashtami!",
            "जन्माष्टमी की शुभकामनाएं!",
            "Krishna Janmashtami songs",
        )

    /** In date order. Sources: drikpanchang.com, New Delhi, read 2026-10-19 (see the Task 10 report for each page). */
    val all: List<Festival> =
        listOf(
            // 2026
            holi("2026-03-03", "2026-03-04"),
            rakshaBandhan("2026-08-28", "2026-08-29"),
            janmashtami("2026-09-04", "2026-09-05"),
            navratri("2026-10-11", "2026-10-20"),
            diwali("2026-11-06", "2026-11-11"),
            // 2027
            holi("2027-03-21", "2027-03-22"),
            rakshaBandhan("2027-08-17", "2027-08-18"),
            janmashtami("2027-08-25", "2027-08-26"),
            navratri("2027-09-30", "2027-10-09"),
            diwali("2027-10-27", "2027-10-31"),
            // 2028
            holi("2028-03-10", "2028-03-11"),
            rakshaBandhan("2028-08-05", "2028-08-06"),
            janmashtami("2028-08-13", "2028-08-14"),
            navratri("2028-09-19", "2028-09-27"),
            diwali("2028-10-15", "2028-10-19"),
        )

    /** The festival whose window holds [today] (both ends included), or null. */
    fun current(today: LocalDate = LocalDate.now()): Festival? = all.firstOrNull { !today.isBefore(it.startDate) && !today.isAfter(it.endDate) }
}

/** The Home banner's title, as mocked: the English greeting, then the Hindi one without its mark. */
fun Festival.bannerTitle(): String = "$greetingEn · ${greetingHi.trimEnd('!')}"

/** Milliseconds from [now] to the start of the next local day. */
internal fun millisUntilNextDay(now: ZonedDateTime): Long =
    Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.zone)).toMillis()

/**
 * The festival of the phone's local day: now, then again each midnight. A screen that
 * collects this only while it is showing starts it afresh on every return, so a phone that
 * slept through midnight shows the right day the moment it wakes.
 */
internal fun currentFestivalFlow(clock: Clock = Clock.systemDefaultZone()): Flow<Festival?> =
    flow {
        while (true) {
            val now = ZonedDateTime.now(clock)
            emit(FestivalCalendar.current(now.toLocalDate()))
            delay(millisUntilNextDay(now) + MIDNIGHT_SLACK_MS)
        }
    }.distinctUntilChanged()

private const val MIDNIGHT_SLACK_MS = 1_000L

/** Reads the day from a clock, so a test can pick it. */
class FestivalClock(
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    fun current(): Festival? = FestivalCalendar.current(LocalDate.now(clock))

    fun changes(): Flow<Festival?> = currentFestivalFlow(clock)
}
