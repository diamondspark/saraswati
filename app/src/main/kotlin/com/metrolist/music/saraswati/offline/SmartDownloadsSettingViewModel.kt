/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 15 — the Storage settings switch for "Raat ke Gaane" (smart downloads) and its status line.
// Off cancels the nightly job and stops the songs it has pending; on arms the job again. The status is read from the
// ledger: a run's count is kept as doneTonight for the night it belongs to, and it still shows for the morning after
// (the ledger's night is today's or yesterday's key). lastNightCount is the night before the ledger's night, so it is
// not the status count.
package com.metrolist.music.saraswati.offline

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModel
import com.metrolist.music.R
import com.metrolist.music.constants.SmartDownloadsEnabledKey
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/** The words the status line uses, so the view model stays free of Android resources in tests. */
interface SmartDownloadStatusLabels {
    fun lastNight(count: Int): String

    val notRunYet: String

    val off: String
}

@HiltViewModel
class SmartDownloadsSettingViewModel
    internal constructor(
        private val preferences: DataStore<Preferences>,
        private val ledger: SmartDownloadLedger,
        private val executor: SmartDownloadExecutor,
        private val arm: (Boolean) -> Unit,
        private val labels: SmartDownloadStatusLabels,
        private val clock: () -> Instant,
        private val zone: ZoneId,
        scope: CoroutineScope,
    ) : ViewModel(scope) {
        @Inject
        constructor(
            ledger: SmartDownloadLedger,
            executor: SmartDownloadExecutor,
            @ApplicationContext context: Context,
        ) : this(
            context.dataStore,
            ledger,
            executor,
            { SmartDownloadScheduler.ensureScheduled(context, it) },
            ResourceLabels(context),
            { Instant.now() },
            ZoneId.systemDefault(),
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )

        private val scope: CoroutineScope = scope

        /** The switch as stored; on until the person turns it off. */
        val enabled: StateFlow<Boolean> =
            preferences.data
                .catch { emit(emptyPreferences()) }
                .map { it[SmartDownloadsEnabledKey] ?: true }
                .stateIn(scope, SharingStarted.Eagerly, true)

        /** "Off", "Hasn't run yet", or "Last night: N songs". */
        val status: StateFlow<String> =
            combine(enabled, ledger.snapshots.catch { emit(LedgerSnapshot.EMPTY) }) { on, snapshot ->
                statusOf(on, snapshot)
            }.stateIn(scope, SharingStarted.Eagerly, labels.notRunYet)

        /** Writes the switch. Off cancels the nightly job and stops what it has pending; on arms the job. */
        fun setEnabled(on: Boolean) {
            scope.launch {
                try {
                    preferences.edit { it[SmartDownloadsEnabledKey] = on }
                    arm(on)
                    if (!on) executor.stopPending()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Smart downloads: could not change the setting")
                }
            }
        }

        private fun statusOf(
            on: Boolean,
            snapshot: LedgerSnapshot,
        ): String {
            if (!on) return labels.off
            val night = snapshot.night ?: return labels.notRunYet
            val now = clock()
            val today = SmartDownloadPlan.nightKey(now, zone)
            val yesterday = SmartDownloadPlan.nightKey(now.minusSeconds(SECONDS_PER_DAY), zone)
            val count = if (night == today || night == yesterday) snapshot.doneTonight else 0
            return labels.lastNight(count)
        }

        private companion object {
            const val SECONDS_PER_DAY = 24L * 60 * 60
        }
    }

private class ResourceLabels(
    private val context: Context,
) : SmartDownloadStatusLabels {
    override fun lastNight(count: Int): String =
        context.resources.getQuantityString(R.plurals.smart_downloads_last_night, count, count)

    override val notRunYet: String get() = context.getString(R.string.smart_downloads_not_run_yet)

    override val off: String get() = context.getString(R.string.smart_downloads_off)
}
