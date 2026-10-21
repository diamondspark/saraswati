/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: feedback — the owner's Feedback inbox: the reports family members sent, filtered, with the status the
// owner has given each.
//
// Every report's text, name and phone info is untrusted: this class only carries it, never parses or logs it, and a
// failure is reduced to an [InboxFailure] the screen words, never an exception message. The admin key is read for each
// call and never held. A status change shows at once and is put back if the hub refuses it.
package com.metrolist.music.saraswati.family.feedback

import androidx.lifecycle.ViewModel
import com.metrolist.music.saraswati.family.FamilyError
import com.metrolist.music.saraswati.family.FamilyStore
import com.metrolist.music.saraswati.family.HubClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URI
import javax.inject.Inject

/** The three views the owner picks between; [wire] is the hub's `status` query. */
enum class InboxFilter(
    val wire: String,
) {
    Open("open"),
    Done("done"),
    All("all"),
}

/** Why the inbox could not load or change something, in the terms a screen can word. */
enum class InboxFailure { NoKey, KeyRejected, Offline, RateLimited, NotAvailable, ItemGone, Other }

data class FeedbackInboxState(
    val filter: InboxFilter = InboxFilter.Open,
    val items: List<FeedbackItem> = emptyList(),
    val loading: Boolean = false,
    /** True once a load of the current [filter] has succeeded, so an empty list can be told from "not loaded yet". */
    val loaded: Boolean = false,
    /** The last load failed. Items from before it stay on screen. */
    val error: InboxFailure? = null,
    /** The last status change failed and was put back. */
    val changeError: InboxFailure? = null,
    /** Status changes shown but not yet confirmed by the hub: report id to the status shown meanwhile. */
    val pendingChanges: Map<String, FeedbackStatus> = emptyMap(),
    val expanded: Set<String> = emptySet(),
)

internal fun inboxFailureOf(
    failure: Throwable,
    changingStatus: Boolean = false,
): InboxFailure =
    when (FamilyError.from(failure)) {
        FamilyError.OFFLINE -> InboxFailure.Offline
        FamilyError.RATE_LIMITED -> InboxFailure.RateLimited
        FamilyError.UNAUTHORIZED -> InboxFailure.KeyRejected
        // On a change a 404 is "no such report"; on a list it is a hub that has no /feedback yet.
        FamilyError.NOT_FOUND -> if (changingStatus) InboxFailure.ItemGone else InboxFailure.NotAvailable
        else -> InboxFailure.Other
    }

/**
 * The GitHub issue link to offer, or null. The hub's value is data from outside the app's control, so only a plain
 * `https://github.com/...` address is ever opened: no other scheme or host, no user-info trick like
 * `https://github.com@elsewhere/`, no port.
 */
internal fun safeIssueUrl(url: String?): String? {
    if (url == null || !url.startsWith("https://github.com/")) return null
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val plain = uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null && uri.port == -1
    return url.takeIf { plain }
}

@HiltViewModel
class FeedbackInboxViewModel
    internal constructor(
        private val hub: FeedbackHub,
        private val store: FamilyStore,
        private val scope: CoroutineScope,
    ) : ViewModel(scope) {
        @Inject
        constructor(hub: HubClient, store: FamilyStore) :
            this(hub, store, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

        private val _state = MutableStateFlow(FeedbackInboxState())
        val state: StateFlow<FeedbackInboxState> = _state.asStateFlow()

        private var started = false
        private var loadJob: Job? = null
        private var loadGeneration = 0

        /** First load. Safe to call from every composition: the hub allows few owner requests a minute. */
        fun start() {
            if (started) return
            started = true
            refresh()
        }

        fun setFilter(filter: InboxFilter) {
            if (filter == _state.value.filter) return
            _state.update {
                it.copy(filter = filter, items = emptyList(), loaded = false, error = null, changeError = null)
            }
            started = true
            refresh()
        }

        fun refresh() {
            loadJob?.cancel()
            val generation = ++loadGeneration
            val filter = _state.value.filter
            _state.update { it.copy(loading = true) }
            loadJob =
                scope.launch {
                    val result = listOrFailure(filter)
                    if (generation != loadGeneration) return@launch
                    result
                        .onSuccess { items ->
                            _state.update { now ->
                                now.copy(
                                    items = items.map { it.withPending(now.pendingChanges) },
                                    loading = false,
                                    loaded = true,
                                    error = null,
                                )
                            }
                        }.onFailure { failure ->
                            _state.update { it.copy(loading = false, error = failure.asFailure()) }
                        }
                }
        }

        fun setStatus(
            id: String,
            status: FeedbackStatus,
        ) {
            val before = _state.value
            val item = before.items.firstOrNull { it.id == id } ?: return
            if (id in before.pendingChanges || item.status == status) return
            val prior = item.status
            _state.update {
                it.copy(
                    items = it.items.map { row -> if (row.id == id) row.copy(status = status) else row },
                    pendingChanges = it.pendingChanges + (id to status),
                    changeError = null,
                )
            }
            scope.launch {
                val outcome = changeOrFailure(id, status)
                _state.update { now ->
                    val settled = now.copy(pendingChanges = now.pendingChanges - id)
                    outcome.fold(
                        onSuccess = { settled },
                        onFailure = { failure ->
                            settled.copy(
                                items = settled.items.map { row -> if (row.id == id) row.copy(status = prior) else row },
                                changeError = failure.asFailure(changingStatus = true),
                            )
                        },
                    )
                }
            }
        }

        fun toggleExpanded(id: String) {
            _state.update { it.copy(expanded = if (id in it.expanded) it.expanded - id else it.expanded + id) }
        }

        fun dismissChangeError() {
            _state.update { it.copy(changeError = null) }
        }

        private fun FeedbackItem.withPending(pending: Map<String, FeedbackStatus>): FeedbackItem =
            pending[id]?.let { copy(status = it) } ?: this

        // A missing key is not a hub failure: it is told apart so no request is made without one.
        private class NoKeyException : Exception()

        private fun Throwable.asFailure(changingStatus: Boolean = false): InboxFailure =
            if (this is NoKeyException) InboxFailure.NoKey else inboxFailureOf(this, changingStatus)

        private suspend fun listOrFailure(filter: InboxFilter): Result<List<FeedbackItem>> {
            val key = adminKey() ?: return Result.failure(NoKeyException())
            return guardedCall { hub.listFeedback(key, filter.wire) }
        }

        private suspend fun changeOrFailure(
            id: String,
            status: FeedbackStatus,
        ): Result<Unit> {
            val key = adminKey() ?: return Result.failure(NoKeyException())
            return guardedCall { hub.setFeedbackStatus(key, id, status) }
        }

        private suspend fun <T> guardedCall(call: suspend () -> Result<T>): Result<T> =
            try {
                call()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }

        private suspend fun adminKey(): String? =
            try {
                store.adminKey()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
    }
