/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 9 fix round 1 — keeps this phone's StationsCache fresh from
// the hub. MainActivity runs it on every launch and resume for EVERY paired
// phone: an adult, non-owner phone never edits stations and never shows Saral,
// so without this its cache stays empty and the car's Stations tab with it.
//
// Quiet on purpose. No session (unpaired, pending, or the owner's own phone,
// which is kept fresh by its station edits) means no request. A hub that fails,
// a revoked phone, an empty list or a storage error all leave the cache as it
// was; a revoked phone (three counted 401s, see SessionVerdict) also forgets its
// session so it can join again. A second call while one is running returns at
// once, so a launch, a resume and Saral's own start never queue three requests.
//
// Fix wave B1: a call inside the minute after the last request is skipped too (the
// cache is only as old as that minute), so ten resumes in a minute send one request.
// Only a call that is about to ask the hub uses up the minute.
package com.metrolist.music.saraswati.family

import android.os.SystemClock
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class StationsRefresher
    internal constructor(
        private val hub: HubApi,
        private val family: FamilyStore,
        private val cache: StationsCache,
        private val throttle: ResumeThrottle,
        private val wallClockMs: () -> Long = System::currentTimeMillis,
    ) {
        @Inject
        constructor(hub: HubApi, family: FamilyStore, cache: StationsCache) :
            this(hub, family, cache, ResumeThrottle(ResumeThrottle.RESUME_INTERVAL_MS, SystemClock::elapsedRealtime))

        private val running = Mutex()

        suspend fun refresh() {
            if (!running.tryLock()) return
            try {
                val session = guarded { family.session() } ?: return
                if (!throttle.tryAcquire()) return
                val answer = hub.getStations(session.sessionToken)
                // SARASWATI: fix wave A (M3) — a revoked phone forgets its session so Join works. Fix wave B2:
                // after three counted 401s, not one (SessionVerdict); a success resets the count.
                judgeSessionAnswer(family, session, answer, wallClockMs)
                val fresh = answer.getOrNull() ?: return
                guarded { cache.replace(fresh) }
            } finally {
                running.unlock()
            }
        }

        private suspend fun <T> guarded(block: suspend () -> T): T? =
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
    }
