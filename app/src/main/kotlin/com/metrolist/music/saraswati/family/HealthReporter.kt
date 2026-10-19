/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: fix wave B1 (final review I2) — a paired phone's heartbeat. Nothing
// called HubApi.reportHealth, so the owner's health view (version, local time,
// last seen) was always empty. MainActivity calls report() beside the station
// refresh on every launch and resume; the phone sends its device id, app version
// and time zone with its own session token.
//
// Quiet by design, like StationsRefresher: no session (unpaired, pending, or the
// owner's phone, which holds only an admin key and is not in the roster) means
// nothing is sent; a hub that fails, a revoked phone or a storage error change
// nothing and show nothing, because a heartbeat must never become an error a
// person sees. The hub keeps one report per device per five minutes and drops
// the rest without an error, so the phone sends at most one per MIN_INTERVAL_MS
// (the hub's window plus a margin for the two clocks and the network). The time
// of the last attempt is in memory only; the first report after the process
// starts always goes. A failed attempt counts too, so a struggling hub is not
// asked again on every resume.
package com.metrolist.music.saraswati.family

import android.os.SystemClock
import com.metrolist.music.BuildConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class HealthReporter
    internal constructor(
        private val hub: HubApi,
        private val family: FamilyStore,
        private val throttle: ResumeThrottle,
        private val versionCode: Int,
        private val versionName: String,
        private val timezone: () -> String = { TimeZone.getDefault().id },
        private val io: CoroutineDispatcher = Dispatchers.IO,
    ) {
        @Inject
        constructor(hub: HubApi, family: FamilyStore) :
            this(
                hub,
                family,
                ResumeThrottle(MIN_INTERVAL_MS, SystemClock::elapsedRealtime),
                BuildConfig.VERSION_CODE,
                BuildConfig.VERSION_NAME,
            )

        suspend fun report() {
            withContext(io) {
                try {
                    val session = family.session() ?: return@withContext
                    if (!throttle.tryAcquire()) return@withContext
                    val answer = hub.reportHealth(session.sessionToken, session.deviceId, versionCode, versionName, timezone())
                    // SARASWATI: fix wave B2 — an accepted report proves the session works, so it resets the run of
                    // 401s. A failed one (a 401 included) is not counted: the heartbeat never ends a pairing.
                    if (answer.isSuccess) markSessionWorking(family, session)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Nothing to do: the next launch or resume reports again.
                }
            }
        }

        companion object {
            private const val HUB_WINDOW_MS = 5 * 60_000L
            private const val CLOCK_MARGIN_MS = 15_000L

            /** At least the hub's five minutes between two reports from this phone. */
            const val MIN_INTERVAL_MS = HUB_WINDOW_MS + CLOCK_MARGIN_MS
        }
    }
