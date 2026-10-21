/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 10 — which festival, if any, today belongs to, for the
// Home banner and Saral's ribbon.
//
// The table covers Hindu, Sikh/Punjabi, Indian national, Western/Canadian and
// Valentine's Day observances. The lunar/solar Hindu and Sikh dates move every
// year and are NOT computed here; the rest sit on fixed calendar dates. The
// moving dates were read off Drik Panchang (drikpanchang.com, New Delhi) on
// 2026-10-19; FestivalCalendarTest holds a second copy of them and the Task 10
// report lists the source page of each. The table runs 2026 to 2028; a later day
// finds no festival (the banner simply does not show) until a release adds the
// next years.
//
// A window is the festival's own days, not a single date:
//  - Diwali: Dhanteras to Bhai Dooj, the five days of the festival.
//  - Holi: Holika Dahan (the eve) to Rangwali Holi.
//  - Navratri: Ghatasthapana to Vijayadashami (Shardiya Navratri, the autumn nine nights
//    and Dussehra; Chaitra Navratri in spring is not included).
//  - Raksha Bandhan and Krishna Janmashtami: the day itself and the day after
//    (Dahi Handi), which also covers the families that keep Janmashtami a day apart.
// No two windows overlap in any of the three years. One omission is deliberate:
// Thanksgiving (Canada) is left out of 2026 alone, because its date there
// (2026-10-12) falls inside Navratri's window (2026-10-11..20).
//
// Fix wave B2: the last day of Navratri is Vijayadashami (Dussehra), and that day's
// greeting says so; the phone's time zone is read each time the day is worked out, not
// once when the screen was built; and FestivalCalendarTest fails once today is within
// six months of the end of this table, so the next years get added in time.
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
    val radioQueryHi: String,
    val radioQueryEn: String,
    // The greeting for the festival's last day, when that day is a festival of its own.
    val lastDayGreetingEn: String? = null,
    val lastDayGreetingHi: String? = null,
) {
    /** This festival as it greets on [day]: the last day may carry its own greeting. */
    fun onDay(day: LocalDate): Festival =
        if (day == endDate && lastDayGreetingEn != null && lastDayGreetingHi != null) {
            copy(greetingEn = lastDayGreetingEn, greetingHi = lastDayGreetingHi)
        } else {
            this
        }
}

object FestivalCalendar {
    private fun diwali(
        start: String,
        end: String,
    ) = Festival("Diwali", LocalDate.parse(start), LocalDate.parse(end), "Happy Diwali!", "दीपावली की शुभकामनाएं!", "Diwali songs", "Diwali songs English")

    private fun holi(
        start: String,
        end: String,
    ) = Festival("Holi", LocalDate.parse(start), LocalDate.parse(end), "Happy Holi!", "होली की शुभकामनाएं!", "Holi songs", "Holi songs English")

    private fun navratri(
        start: String,
        end: String,
    ) = Festival(
        "Navratri",
        LocalDate.parse(start),
        LocalDate.parse(end),
        "Happy Navratri!",
        "नवरात्रि की शुभकामनाएं!",
        "Navratri songs",
        "Navratri Garba songs English",
        lastDayGreetingEn = "Happy Dussehra!",
        lastDayGreetingHi = "विजयादशमी की शुभकामनाएं!",
    )

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
            "Raksha Bandhan songs English",
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
            "Krishna Janmashtami songs English",
        )

    private fun festival(
        name: String,
        start: String,
        end: String,
        greetingEn: String,
        greetingHi: String,
        radioQueryHi: String,
        radioQueryEn: String,
    ) = Festival(name, LocalDate.parse(start), LocalDate.parse(end), greetingEn, greetingHi, radioQueryHi, radioQueryEn)

    /** In date order. Sources: drikpanchang.com, New Delhi, read 2026-10-19 (see the Task 10 report for each page). */
    val all: List<Festival> =
        listOf(
            // 2026
            festival("New Year's Day", "2026-01-01", "2026-01-01", "Happy New Year!", "नव वर्ष की शुभकामनाएं!", "New Year songs", "New Year songs English"),
            festival("Lohri", "2026-01-13", "2026-01-13", "Happy Lohri!", "लोहड़ी की शुभकामनाएं!", "Lohri songs", "Lohri songs English"),
            festival("Makar Sankranti", "2026-01-14", "2026-01-14", "Happy Makar Sankranti!", "मकर संक्रांति की शुभकामनाएं!", "Makar Sankranti songs", "Makar Sankranti songs English"),
            festival("Vasant Panchami", "2026-01-23", "2026-01-23", "Happy Vasant Panchami!", "वसंत पंचमी की शुभकामनाएं!", "Vasant Panchami Saraswati Puja songs", "Vasant Panchami songs English"),
            festival("Republic Day", "2026-01-26", "2026-01-26", "Happy Republic Day!", "गणतंत्र दिवस की शुभकामनाएं!", "Desh bhakti geet", "Indian Republic Day patriotic songs"),
            festival("Valentine's Day", "2026-02-14", "2026-02-14", "Happy Valentine's Day!", "वैलेंटाइन डे की शुभकामनाएं!", "Valentine's Day love songs", "Valentine's Day songs English"),
            festival("Maha Shivratri", "2026-02-15", "2026-02-15", "Happy Maha Shivratri!", "महाशिवरात्रि की शुभकामनाएं!", "Maha Shivratri bhajan", "Maha Shivratri songs English"),
            holi("2026-03-03", "2026-03-04"),
            festival("Ram Navami", "2026-03-26", "2026-03-26", "Happy Ram Navami!", "राम नवमी की शुभकामनाएं!", "Ram Navami bhajan", "Ram Navami songs English"),
            festival("Hanuman Jayanti", "2026-04-02", "2026-04-02", "Happy Hanuman Jayanti!", "हनुमान जयंती की शुभकामनाएं!", "Hanuman Jayanti bhajan", "Hanuman Jayanti songs English"),
            festival("Baisakhi", "2026-04-14", "2026-04-14", "Happy Baisakhi!", "बैसाखी की शुभकामनाएं!", "Baisakhi Bhangra songs", "Baisakhi songs English"),
            festival("Canada Day", "2026-07-01", "2026-07-01", "Happy Canada Day!", "कनाडा दिवस की शुभकामनाएं!", "Canada Day songs", "Canada Day songs"),
            festival("Guru Purnima", "2026-07-29", "2026-07-29", "Happy Guru Purnima!", "गुरु पूर्णिमा की शुभकामनाएं!", "Guru Purnima bhajan", "Guru Purnima songs English"),
            festival("Independence Day", "2026-08-15", "2026-08-15", "Happy Independence Day!", "स्वतंत्रता दिवस की शुभकामनाएं!", "Azadi ke geet", "Indian Independence Day patriotic songs"),
            rakshaBandhan("2026-08-28", "2026-08-29"),
            janmashtami("2026-09-04", "2026-09-05"),
            festival("Ganesh Chaturthi", "2026-09-14", "2026-09-24", "Happy Ganesh Chaturthi!", "गणेश चतुर्थी की शुभकामनाएं!", "Ganesh Chaturthi songs", "Ganesh Chaturthi songs English"),
            navratri("2026-10-11", "2026-10-20"),
            festival("Karva Chauth", "2026-10-29", "2026-10-29", "Happy Karva Chauth!", "करवा चौथ की शुभकामनाएं!", "Karva Chauth songs", "Karva Chauth songs English"),
            diwali("2026-11-06", "2026-11-11"),
            festival("Chhath Puja", "2026-11-13", "2026-11-16", "Happy Chhath Puja!", "छठ पूजा की शुभकामनाएं!", "Chhath Puja geet", "Chhath Puja songs English"),
            festival("Christmas", "2026-12-25", "2026-12-25", "Merry Christmas!", "क्रिसमस की शुभकामनाएं!", "Bollywood Christmas songs", "Christmas songs"),
            // 2027
            festival("New Year's Day", "2027-01-01", "2027-01-01", "Happy New Year!", "नव वर्ष की शुभकामनाएं!", "New Year songs", "New Year songs English"),
            festival("Lohri", "2027-01-14", "2027-01-14", "Happy Lohri!", "लोहड़ी की शुभकामनाएं!", "Lohri songs", "Lohri songs English"),
            festival("Makar Sankranti", "2027-01-15", "2027-01-15", "Happy Makar Sankranti!", "मकर संक्रांति की शुभकामनाएं!", "Makar Sankranti songs", "Makar Sankranti songs English"),
            festival("Republic Day", "2027-01-26", "2027-01-26", "Happy Republic Day!", "गणतंत्र दिवस की शुभकामनाएं!", "Desh bhakti geet", "Indian Republic Day patriotic songs"),
            festival("Vasant Panchami", "2027-02-11", "2027-02-11", "Happy Vasant Panchami!", "वसंत पंचमी की शुभकामनाएं!", "Vasant Panchami Saraswati Puja songs", "Vasant Panchami songs English"),
            festival("Valentine's Day", "2027-02-14", "2027-02-14", "Happy Valentine's Day!", "वैलेंटाइन डे की शुभकामनाएं!", "Valentine's Day love songs", "Valentine's Day songs English"),
            festival("Maha Shivratri", "2027-03-06", "2027-03-06", "Happy Maha Shivratri!", "महाशिवरात्रि की शुभकामनाएं!", "Maha Shivratri bhajan", "Maha Shivratri songs English"),
            holi("2027-03-21", "2027-03-22"),
            festival("Baisakhi", "2027-04-14", "2027-04-14", "Happy Baisakhi!", "बैसाखी की शुभकामनाएं!", "Baisakhi Bhangra songs", "Baisakhi songs English"),
            festival("Ram Navami", "2027-04-15", "2027-04-15", "Happy Ram Navami!", "राम नवमी की शुभकामनाएं!", "Ram Navami bhajan", "Ram Navami songs English"),
            festival("Hanuman Jayanti", "2027-04-20", "2027-04-20", "Happy Hanuman Jayanti!", "हनुमान जयंती की शुभकामनाएं!", "Hanuman Jayanti bhajan", "Hanuman Jayanti songs English"),
            festival("Canada Day", "2027-07-01", "2027-07-01", "Happy Canada Day!", "कनाडा दिवस की शुभकामनाएं!", "Canada Day songs", "Canada Day songs"),
            festival("Guru Purnima", "2027-07-18", "2027-07-18", "Happy Guru Purnima!", "गुरु पूर्णिमा की शुभकामनाएं!", "Guru Purnima bhajan", "Guru Purnima songs English"),
            festival("Independence Day", "2027-08-15", "2027-08-15", "Happy Independence Day!", "स्वतंत्रता दिवस की शुभकामनाएं!", "Azadi ke geet", "Indian Independence Day patriotic songs"),
            rakshaBandhan("2027-08-17", "2027-08-18"),
            janmashtami("2027-08-25", "2027-08-26"),
            festival("Ganesh Chaturthi", "2027-09-04", "2027-09-14", "Happy Ganesh Chaturthi!", "गणेश चतुर्थी की शुभकामनाएं!", "Ganesh Chaturthi songs", "Ganesh Chaturthi songs English"),
            navratri("2027-09-30", "2027-10-09"),
            festival("Thanksgiving", "2027-10-11", "2027-10-11", "Happy Thanksgiving!", "थैंक्सगिविंग की शुभकामनाएं!", "Thanksgiving songs Canada", "Thanksgiving songs Canada"),
            festival("Karva Chauth", "2027-10-18", "2027-10-18", "Happy Karva Chauth!", "करवा चौथ की शुभकामनाएं!", "Karva Chauth songs", "Karva Chauth songs English"),
            diwali("2027-10-27", "2027-10-31"),
            festival("Chhath Puja", "2027-11-02", "2027-11-05", "Happy Chhath Puja!", "छठ पूजा की शुभकामनाएं!", "Chhath Puja geet", "Chhath Puja songs English"),
            festival("Christmas", "2027-12-25", "2027-12-25", "Merry Christmas!", "क्रिसमस की शुभकामनाएं!", "Bollywood Christmas songs", "Christmas songs"),
            // 2028
            festival("New Year's Day", "2028-01-01", "2028-01-01", "Happy New Year!", "नव वर्ष की शुभकामनाएं!", "New Year songs", "New Year songs English"),
            festival("Lohri", "2028-01-14", "2028-01-14", "Happy Lohri!", "लोहड़ी की शुभकामनाएं!", "Lohri songs", "Lohri songs English"),
            festival("Makar Sankranti", "2028-01-15", "2028-01-15", "Happy Makar Sankranti!", "मकर संक्रांति की शुभकामनाएं!", "Makar Sankranti songs", "Makar Sankranti songs English"),
            festival("Republic Day", "2028-01-26", "2028-01-26", "Happy Republic Day!", "गणतंत्र दिवस की शुभकामनाएं!", "Desh bhakti geet", "Indian Republic Day patriotic songs"),
            festival("Vasant Panchami", "2028-01-31", "2028-01-31", "Happy Vasant Panchami!", "वसंत पंचमी की शुभकामनाएं!", "Vasant Panchami Saraswati Puja songs", "Vasant Panchami songs English"),
            festival("Valentine's Day", "2028-02-14", "2028-02-14", "Happy Valentine's Day!", "वैलेंटाइन डे की शुभकामनाएं!", "Valentine's Day love songs", "Valentine's Day songs English"),
            festival("Maha Shivratri", "2028-02-23", "2028-02-23", "Happy Maha Shivratri!", "महाशिवरात्रि की शुभकामनाएं!", "Maha Shivratri bhajan", "Maha Shivratri songs English"),
            holi("2028-03-10", "2028-03-11"),
            festival("Ram Navami", "2028-04-03", "2028-04-03", "Happy Ram Navami!", "राम नवमी की शुभकामनाएं!", "Ram Navami bhajan", "Ram Navami songs English"),
            festival("Hanuman Jayanti", "2028-04-09", "2028-04-09", "Happy Hanuman Jayanti!", "हनुमान जयंती की शुभकामनाएं!", "Hanuman Jayanti bhajan", "Hanuman Jayanti songs English"),
            festival("Baisakhi", "2028-04-13", "2028-04-13", "Happy Baisakhi!", "बैसाखी की शुभकामनाएं!", "Baisakhi Bhangra songs", "Baisakhi songs English"),
            festival("Canada Day", "2028-07-01", "2028-07-01", "Happy Canada Day!", "कनाडा दिवस की शुभकामनाएं!", "Canada Day songs", "Canada Day songs"),
            festival("Guru Purnima", "2028-07-06", "2028-07-06", "Happy Guru Purnima!", "गुरु पूर्णिमा की शुभकामनाएं!", "Guru Purnima bhajan", "Guru Purnima songs English"),
            rakshaBandhan("2028-08-05", "2028-08-06"),
            janmashtami("2028-08-13", "2028-08-14"),
            festival("Independence Day", "2028-08-15", "2028-08-15", "Happy Independence Day!", "स्वतंत्रता दिवस की शुभकामनाएं!", "Azadi ke geet", "Indian Independence Day patriotic songs"),
            festival("Ganesh Chaturthi", "2028-08-23", "2028-09-02", "Happy Ganesh Chaturthi!", "गणेश चतुर्थी की शुभकामनाएं!", "Ganesh Chaturthi songs", "Ganesh Chaturthi songs English"),
            navratri("2028-09-19", "2028-09-27"),
            festival("Karva Chauth", "2028-10-07", "2028-10-07", "Happy Karva Chauth!", "करवा चौथ की शुभकामनाएं!", "Karva Chauth songs", "Karva Chauth songs English"),
            festival("Thanksgiving", "2028-10-09", "2028-10-09", "Happy Thanksgiving!", "थैंक्सगिविंग की शुभकामनाएं!", "Thanksgiving songs Canada", "Thanksgiving songs Canada"),
            diwali("2028-10-15", "2028-10-19"),
            festival("Chhath Puja", "2028-10-21", "2028-10-24", "Happy Chhath Puja!", "छठ पूजा की शुभकामनाएं!", "Chhath Puja geet", "Chhath Puja songs English"),
            festival("Christmas", "2028-12-25", "2028-12-25", "Merry Christmas!", "क्रिसमस की शुभकामनाएं!", "Bollywood Christmas songs", "Christmas songs"),
        )

    /** The festival whose window holds [today] (both ends included), or null. */
    fun current(today: LocalDate = LocalDate.now()): Festival? =
        all.firstOrNull { !today.isBefore(it.startDate) && !today.isAfter(it.endDate) }?.onDay(today)
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
internal fun currentFestivalFlow(clock: () -> Clock = { Clock.systemDefaultZone() }): Flow<Festival?> =
    flow {
        while (true) {
            // The clock is asked each time round, so a phone whose time zone changed while the screen
            // was away works out its day in the new zone.
            val now = ZonedDateTime.now(clock())
            emit(FestivalCalendar.current(now.toLocalDate()))
            delay(millisUntilNextDay(now) + MIDNIGHT_SLACK_MS)
        }
    }.distinctUntilChanged()

internal fun currentFestivalFlow(clock: Clock): Flow<Festival?> = currentFestivalFlow { clock }

private const val MIDNIGHT_SLACK_MS = 1_000L

/**
 * Reads the day from a clock, so a test can pick it. [clock] is asked on every read: the default is the system
 * clock in whatever zone the phone is in at that moment, not the zone it was in when this was built.
 */
class FestivalClock(
    private val clock: () -> Clock = { Clock.systemDefaultZone() },
) {
    constructor(clock: Clock) : this({ clock })

    fun current(): Festival? = FestivalCalendar.current(LocalDate.now(clock()))

    fun changes(): Flow<Festival?> = currentFestivalFlow(clock)
}
