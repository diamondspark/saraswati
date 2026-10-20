/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 10 — "Raat ke Gaane": which songs the phone saves overnight, how many, and which old
// ones make room. Pure functions over a ledger snapshot, so the rules (about 50 a night, at most 300 kept,
// a song that failed twice is left alone) are tested without a phone.
//
// A "night" runs from local noon to the next local noon, so a run that starts at 23:50 and ends at 00:20 is one
// night, not two.
package com.metrolist.music.saraswati.offline

import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.saraswati.localmedia.LocalSongIds
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Where a candidate came from; the order is the order they are downloaded in. */
enum class CandidateSource { PARIVAAR_MIX, STATION, TOP_PLAYED, LIKED }

data class Candidate(
    val metadata: MediaMetadata,
    val source: CandidateSource,
) {
    val id: String get() = metadata.id
}

@Serializable
data class LedgerEntry(
    /** Epoch ms the download finished, or null while it has not. */
    val downloadedAt: Long? = null,
    /** Epoch ms a download was asked for and has not finished or failed since, or null. */
    val pendingSince: Long? = null,
    val failures: Int = 0,
) {
    val isCompleted: Boolean get() = downloadedAt != null
}

@Serializable
data class LedgerSnapshot(
    val entries: Map<String, LedgerEntry> = emptyMap(),
    /** The night [doneTonight] and [failuresInARow] belong to, or null before the first run. */
    val night: String? = null,
    val doneTonight: Int = 0,
    val failuresInARow: Int = 0,
    /** How many songs the night before [night] saved. */
    val lastNightCount: Int = 0,
) {
    /**
     * The snapshot as it stands in [night]. A different night moves the old count into [lastNightCount]
     * and starts the new night at zero.
     */
    fun rolledTo(night: String): LedgerSnapshot =
        if (this.night == night) {
            this
        } else {
            copy(
                night = night,
                doneTonight = 0,
                failuresInARow = 0,
                lastNightCount = if (this.night == null) lastNightCount else doneTonight,
            )
        }

    companion object {
        val EMPTY = LedgerSnapshot()
    }
}

object SmartDownloadPlan {
    const val NIGHTLY_MAX = 50
    const val KEEP_MAX = 300
    const val MAX_FAILURES_PER_SONG = 2

    private const val NIGHT_OFFSET_HOURS = 12L
    private const val NOON_HOUR = 12

    /** The ISO date of [now] minus 12 hours in local time, so a night changes at local noon, not midnight. */
    fun nightKey(
        now: Instant,
        zone: ZoneId,
    ): String = now.atZone(zone).minusHours(NIGHT_OFFSET_HOURS).toLocalDate().toString()

    /** The first local noon strictly after [now]: when the next night begins. */
    fun nextNightStart(
        now: Instant,
        zone: ZoneId,
    ): Instant {
        val local: ZonedDateTime = now.atZone(zone)
        var noon = local.toLocalDate().atTime(NOON_HOUR, 0).atZone(zone)
        if (!noon.toInstant().isAfter(now)) noon = local.toLocalDate().plusDays(1).atTime(NOON_HOUR, 0).atZone(zone)
        return noon.toInstant()
    }

    /**
     * The songs to download now, in order: the family mix, then stations, then top played, then liked. A song in
     * two lists is taken from the first. Local files, songs already downloaded and songs that failed twice are
     * skipped, and no more than [NIGHTLY_MAX] go in a night counting what [ledger] says is done.
     */
    fun pick(
        candidates: List<Candidate>,
        isDownloaded: (String) -> Boolean,
        ledger: LedgerSnapshot,
        night: String,
    ): List<Candidate> {
        val room = (NIGHTLY_MAX - ledger.rolledTo(night).doneTonight).coerceAtLeast(0)
        if (room == 0) return emptyList()
        val seen = HashSet<String>()
        return candidates
            .sortedBy { it.source.ordinal }
            .filter { seen.add(it.id) }
            .filterNot { isLocal(it.id) || isDownloaded(it.id) || failures(ledger, it.id) >= MAX_FAILURES_PER_SONG }
            .take(room)
    }

    /**
     * The ids to remove: while more than [KEEP_MAX] songs are completed in [ledger], the oldest one that is not in
     * [keep]. A song the ledger does not know (one the user downloaded) is never returned.
     */
    fun evict(
        ledger: LedgerSnapshot,
        keep: Set<String>,
    ): List<String> {
        val completed = ledger.entries.filterValues { it.isCompleted }
        val excess = completed.size - KEEP_MAX
        if (excess <= 0) return emptyList()
        return completed
            .filterKeys { it !in keep }
            .entries
            .sortedBy { it.value.downloadedAt }
            .take(excess)
            .map { it.key }
    }

    private fun failures(
        ledger: LedgerSnapshot,
        id: String,
    ) = ledger.entries[id]?.failures ?: 0

    // Songs on this phone never leave it, so they are never downloaded.
    private fun isLocal(id: String) = LocalSongIds.isLocal(id)
}
