/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 10 — Saral says everything in Hindi, so the status line and
// the ribbon button's spoken label need each festival's name in Hindi. The
// calendar's own names are English (the Home banner uses them), so they are
// mapped here, the same way SaralLabels maps the four starting stations. An
// unknown festival gets the plain word for "festival" rather than English.
package com.metrolist.music.saraswati.saral

import androidx.annotation.StringRes
import com.metrolist.music.R
import com.metrolist.music.saraswati.culture.Festival

object SaralFestivalNames {
    private val HINDI_BY_NAME: Map<String, Int> =
        mapOf(
            "Diwali" to R.string.saral_festival_diwali,
            "Holi" to R.string.saral_festival_holi,
            "Navratri" to R.string.saral_festival_navratri,
            "Raksha Bandhan" to R.string.saral_festival_raksha_bandhan,
            "Krishna Janmashtami" to R.string.saral_festival_janmashtami,
            "New Year's Day" to R.string.saral_festival_new_year,
            "Lohri" to R.string.saral_festival_lohri,
            "Makar Sankranti" to R.string.saral_festival_makar_sankranti,
            "Vasant Panchami" to R.string.saral_festival_vasant_panchami,
            "Republic Day" to R.string.saral_festival_republic_day,
            "Maha Shivratri" to R.string.saral_festival_maha_shivratri,
            "Ram Navami" to R.string.saral_festival_ram_navami,
            "Hanuman Jayanti" to R.string.saral_festival_hanuman_jayanti,
            "Baisakhi" to R.string.saral_festival_baisakhi,
            "Canada Day" to R.string.saral_festival_canada_day,
            "Guru Purnima" to R.string.saral_festival_guru_purnima,
            "Independence Day" to R.string.saral_festival_independence_day,
            "Ganesh Chaturthi" to R.string.saral_festival_ganesh_chaturthi,
            "Karva Chauth" to R.string.saral_festival_karva_chauth,
            "Thanksgiving" to R.string.saral_festival_thanksgiving,
            "Chhath Puja" to R.string.saral_festival_chhath_puja,
            "Christmas" to R.string.saral_festival_christmas,
            "Valentine's Day" to R.string.saral_festival_valentines_day,
        )

    @StringRes
    fun hindiNameFor(festival: Festival): Int = HINDI_BY_NAME[festival.name] ?: R.string.saral_festival_generic
}
