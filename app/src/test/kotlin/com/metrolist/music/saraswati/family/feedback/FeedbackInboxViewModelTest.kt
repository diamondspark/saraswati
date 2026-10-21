/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: feedback — the owner's Feedback inbox view model against a fake hub and an in-memory store. Unconfined
// dispatching finishes each call before it returns, except a status change held open on purpose to look at the
// optimistic row.
package com.metrolist.music.saraswati.family.feedback

import com.metrolist.music.saraswati.family.FakeStore
import com.metrolist.music.saraswati.family.OFFLINE
import com.metrolist.music.saraswati.family.hubFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class InboxHub : FeedbackHub {
    val listCalls = mutableListOf<Pair<String, String>>()
    val changeCalls = mutableListOf<Triple<String, String, FeedbackStatus>>()
    var listResult: Result<List<FeedbackItem>> = Result.success(emptyList())
    var changeResult: Result<Unit> = Result.success(Unit)
    var listThrows: Throwable? = null
    var changeGate: CompletableDeferred<Unit>? = null
    var listGate: CompletableDeferred<Unit>? = null
    var changesFinished = 0

    override suspend fun submitFeedback(
        bearer: String,
        kind: FeedbackKind,
        text: String,
        diagnostics: FeedbackDiagnostics?,
    ): Result<String> = error("not used by the inbox")

    override suspend fun listFeedback(
        adminKey: String,
        status: String,
    ): Result<List<FeedbackItem>> {
        listCalls += adminKey to status
        listThrows?.let { throw it }
        val answer = listResult // the answer is fixed when the request is sent, as on the wire
        listGate?.await()
        return answer
    }

    override suspend fun setFeedbackStatus(
        adminKey: String,
        id: String,
        status: FeedbackStatus,
    ): Result<Unit> {
        changeCalls += Triple(adminKey, id, status)
        changeGate?.await()
        changesFinished++
        return changeResult
    }
}

class FeedbackInboxViewModelTest {
    private lateinit var scope: CoroutineScope
    private lateinit var hub: InboxHub
    private lateinit var store: FakeStore
    private lateinit var vm: FeedbackInboxViewModel

    private fun item(
        id: String,
        status: FeedbackStatus = FeedbackStatus.New,
        text: String = "text of $id",
        issueUrl: String? = null,
    ) = FeedbackItem(id, FeedbackKind.Bug, text, status, "Asha", 1_760_000_000_000L, null, issueUrl)

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        hub = InboxHub()
        store = FakeStore(admin = "owner-key")
        vm = FeedbackInboxViewModel(hub, store, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun statuses() = vm.state.value.items.associate { it.id to it.status }

    // --- loading and filters ---

    @Test
    fun `start loads the open list with the admin key, once`() {
        hub.listResult = Result.success(listOf(item("a"), item("b")))

        vm.start()
        vm.start()

        assertEquals(listOf("owner-key" to "open"), hub.listCalls)
        assertEquals(listOf("a", "b"), vm.state.value.items.map { it.id })
        assertTrue(vm.state.value.loaded)
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `filter changes ask the hub for done and all, and back to open`() {
        vm.start()
        vm.setFilter(InboxFilter.Done)
        vm.setFilter(InboxFilter.All)
        vm.setFilter(InboxFilter.Open)

        assertEquals(listOf("open", "done", "all", "open"), hub.listCalls.map { it.second })
        assertEquals(InboxFilter.Open, vm.state.value.filter)
    }

    @Test
    fun `choosing the filter already shown does not ask the hub again`() {
        vm.start()
        vm.setFilter(InboxFilter.Open)

        assertEquals(1, hub.listCalls.size)
    }

    @Test
    fun `switching filter drops the other filter's rows at once`() {
        hub.listResult = Result.success(listOf(item("a")))
        vm.start()
        hub.listResult = Result.success(listOf(item("z", FeedbackStatus.Done)))

        vm.setFilter(InboxFilter.Done)

        assertEquals(listOf("z"), vm.state.value.items.map { it.id })
    }

    @Test
    fun `refresh asks the hub again with the current filter`() {
        vm.start()
        vm.setFilter(InboxFilter.All)
        vm.refresh()

        assertEquals(listOf("open", "all", "all"), hub.listCalls.map { it.second })
    }

    @Test
    fun `an empty list is loaded and empty, not an error`() {
        vm.start()

        assertTrue(vm.state.value.loaded)
        assertTrue(vm.state.value.items.isEmpty())
        assertNull(vm.state.value.error)
    }

    // --- failures ---

    @Test
    fun `offline is reported and keeps nothing loaded`() {
        hub.listResult = Result.failure(OFFLINE)

        vm.start()

        assertEquals(InboxFailure.Offline, vm.state.value.error)
        assertFalse(vm.state.value.loaded)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun `a refresh that fails keeps the rows already shown`() {
        hub.listResult = Result.success(listOf(item("a")))
        vm.start()
        hub.listResult = Result.failure(OFFLINE)

        vm.refresh()

        assertEquals(listOf("a"), vm.state.value.items.map { it.id })
        assertEquals(InboxFailure.Offline, vm.state.value.error)
    }

    @Test
    fun `a later good load clears the error`() {
        hub.listResult = Result.failure(OFFLINE)
        vm.start()
        hub.listResult = Result.success(listOf(item("a")))

        vm.refresh()

        assertNull(vm.state.value.error)
        assertEquals(1, vm.state.value.items.size)
    }

    @Test
    fun `401 sets the key-rejected state`() {
        hub.listResult = Result.failure(hubFailure(401))

        vm.start()

        assertEquals(InboxFailure.KeyRejected, vm.state.value.error)
    }

    @Test
    fun `429 is rate limited and 404 is a hub without the inbox`() {
        hub.listResult = Result.failure(hubFailure(429))
        vm.start()
        assertEquals(InboxFailure.RateLimited, vm.state.value.error)

        hub.listResult = Result.failure(hubFailure(404))
        vm.refresh()
        assertEquals(InboxFailure.NotAvailable, vm.state.value.error)
    }

    @Test
    fun `a 500 and an unexpected exception both end as Other, never a crash`() {
        hub.listResult = Result.failure(hubFailure(500))
        vm.start()
        assertEquals(InboxFailure.Other, vm.state.value.error)

        hub.listResult = Result.failure(RuntimeException("a body with the report text in it"))
        vm.refresh()
        assertEquals(InboxFailure.Other, vm.state.value.error)

        hub.listThrows = IllegalStateException("thrown, not returned")
        vm.refresh()
        assertEquals(InboxFailure.Other, vm.state.value.error)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun `no admin key makes no network call and says so`() {
        store.admin = null

        vm.start()

        assertTrue(hub.listCalls.isEmpty())
        assertEquals(InboxFailure.NoKey, vm.state.value.error)
        assertFalse(vm.state.value.loading)
    }

    // --- status changes ---

    @Test
    fun `a status change shows at once, then the hub confirms it`() {
        hub.listResult = Result.success(listOf(item("a"), item("b")))
        vm.start()
        hub.changeGate = CompletableDeferred()

        vm.setStatus("a", FeedbackStatus.Done)

        assertEquals(FeedbackStatus.Done, statuses().getValue("a"))
        assertEquals(setOf("a"), vm.state.value.pendingChanges.keys)

        hub.changeGate!!.complete(Unit)

        assertEquals(listOf(Triple("owner-key", "a", FeedbackStatus.Done)), hub.changeCalls)
        assertEquals(FeedbackStatus.Done, statuses().getValue("a"))
        assertEquals(FeedbackStatus.New, statuses().getValue("b"))
        assertTrue(vm.state.value.pendingChanges.isEmpty())
        assertNull(vm.state.value.changeError)
    }

    @Test
    fun `a failed status change is put back with a message`() {
        hub.listResult = Result.success(listOf(item("a", FeedbackStatus.Seen)))
        vm.start()
        hub.changeResult = Result.failure(OFFLINE)

        vm.setStatus("a", FeedbackStatus.WontFix)

        assertEquals(FeedbackStatus.Seen, statuses().getValue("a"))
        assertEquals(InboxFailure.Offline, vm.state.value.changeError)
        assertTrue(vm.state.value.pendingChanges.isEmpty())

        vm.dismissChangeError()
        assertNull(vm.state.value.changeError)
    }

    @Test
    fun `a status change the hub does not know is put back as gone`() {
        hub.listResult = Result.success(listOf(item("a")))
        vm.start()
        hub.changeResult = Result.failure(hubFailure(404))

        vm.setStatus("a", FeedbackStatus.Planned)

        assertEquals(FeedbackStatus.New, statuses().getValue("a"))
        assertEquals(InboxFailure.ItemGone, vm.state.value.changeError)
    }

    @Test
    fun `a 401 on a status change is the key-rejected message and the row is put back`() {
        hub.listResult = Result.success(listOf(item("a")))
        vm.start()
        hub.changeResult = Result.failure(hubFailure(401))

        vm.setStatus("a", FeedbackStatus.Done)

        assertEquals(FeedbackStatus.New, statuses().getValue("a"))
        assertEquals(InboxFailure.KeyRejected, vm.state.value.changeError)
    }

    @Test
    fun `no admin key on a status change makes no call and puts the row back`() {
        hub.listResult = Result.success(listOf(item("a")))
        vm.start()
        store.admin = null

        vm.setStatus("a", FeedbackStatus.Done)

        assertTrue(hub.changeCalls.isEmpty())
        assertEquals(FeedbackStatus.New, statuses().getValue("a"))
        assertEquals(InboxFailure.NoKey, vm.state.value.changeError)
    }

    @Test
    fun `changing to the status a row already has, or an unknown row, makes no call`() {
        hub.listResult = Result.success(listOf(item("a", FeedbackStatus.Seen)))
        vm.start()

        vm.setStatus("a", FeedbackStatus.Seen)
        vm.setStatus("nope", FeedbackStatus.Done)

        assertTrue(hub.changeCalls.isEmpty())
    }

    @Test
    fun `a second change to a row still waiting on the hub is ignored`() {
        hub.listResult = Result.success(listOf(item("a")))
        vm.start()
        hub.changeGate = CompletableDeferred()

        vm.setStatus("a", FeedbackStatus.Seen)
        vm.setStatus("a", FeedbackStatus.Done)
        hub.changeGate!!.complete(Unit)

        assertEquals(1, hub.changeCalls.size)
        assertEquals(FeedbackStatus.Seen, statuses().getValue("a"))
    }

    @Test
    fun `a refresh that lands while a change is waiting keeps the shown status`() {
        hub.listResult = Result.success(listOf(item("a")))
        vm.start()
        hub.changeGate = CompletableDeferred()
        vm.setStatus("a", FeedbackStatus.Done)

        vm.refresh()

        assertEquals(FeedbackStatus.Done, statuses().getValue("a"))
        hub.changeGate!!.complete(Unit)
        assertEquals(FeedbackStatus.Done, statuses().getValue("a"))
    }

    @Test
    fun `a slow list sent before a confirmed change cannot undo it when it lands late`() {
        hub.listResult = Result.success(listOf(item("a")))
        vm.start()
        // A refresh is sent while the hub still says New, and answers only after the change is confirmed.
        hub.listGate = CompletableDeferred()
        vm.refresh()
        hub.listResult = Result.success(listOf(item("a", FeedbackStatus.Done)))

        vm.setStatus("a", FeedbackStatus.Done)
        assertTrue(vm.state.value.pendingChanges.isEmpty())

        hub.listGate!!.complete(Unit)

        assertEquals(FeedbackStatus.Done, statuses().getValue("a"))
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `leaving the screen mid-change does not cancel the hub call`() {
        hub.listResult = Result.success(listOf(item("a")))
        vm.start()
        hub.changeGate = CompletableDeferred()
        vm.setStatus("a", FeedbackStatus.Done)

        scope.cancel() // what clearing the view model does

        hub.changeGate!!.complete(Unit)
        assertEquals(1, hub.changesFinished)
    }

    // --- expanding ---

    @Test
    fun `tapping a report expands it and tapping again collapses it`() {
        vm.toggleExpanded("a")
        assertEquals(setOf("a"), vm.state.value.expanded)
        vm.toggleExpanded("a")
        assertTrue(vm.state.value.expanded.isEmpty())
    }

    // --- unknown or odd hub data ---

    @Test
    fun `odd report data is carried as is and never breaks the list`() {
        val odd =
            listOf(
                item("1", text = ""),
                item("2", text = "</details> @everyone ```\n[x](javascript:alert(1)) '; DROP TABLE feedback;--"),
                item("3", text = "नमस्ते 😀 مرحبا"),
                item("4").copy(nickname = "", createdAt = 0L),
                item("5").copy(createdAt = -1L, diagnostics = FeedbackDiagnostics("", "", "")),
                item("6", text = "a".repeat(100_000), issueUrl = "javascript:alert(1)"),
            )
        hub.listResult = Result.success(odd)

        vm.start()

        assertEquals(odd, vm.state.value.items)
        assertNull(vm.state.value.error)
    }

    // --- the one link ---

    @Test
    fun `only a plain github dot com https address is ever offered as the issue link`() {
        val good = "https://github.com/diamondspark/saraswati-feedback/issues/12"
        assertEquals(good, safeIssueUrl(good))
        assertEquals("https://github.com/a_b/c-d.e/issues/7", safeIssueUrl("https://github.com/a_b/c-d.e/issues/7"))

        listOf(
            null,
            "",
            "http://github.com/x/y/issues/1",
            "javascript:alert(1)",
            "intent://github.com/#Intent;scheme=https;end",
            "file:///sdcard/x",
            "https://github.com",
            "https://github.com.evil.example/x",
            "https://github.com@evil.example/x",
            "https://github.com:8443/x",
            "https://evil.example/https://github.com/x",
            "HTTPS://github.com/x",
            "https://github.com/ x",
            "https://github.com/x\u0000",
            "https://github.com/x",
            "https://github.com/diamondspark",
            "https://github.com/diamondspark/saraswati-feedback",
            "https://github.com/o/r/releases/download/v1/app.apk",
            "https://github.com/o/r/pull/1",
            "https://github.com/o/r/issues/",
            "https://github.com/o/r/issues/abc",
            "https://github.com/o/r/issues/1/",
            "https://github.com/o/r/issues/1/extra",
            "https://github.com/o/r/issues/1?x=1",
            "https://github.com/o/r/issues/1#c",
            "https://github.com/o/r/issues/1\n",
            "https://GITHUB.com/o/r/issues/1",
            "https://Github.com/o/r/issues/1",
            " https://github.com/x",
            "https://gist.github.com/x",
            "https://api.github.com/x",
            "https://github.com\\@evil.example/",
        ).forEach { assertNull("must not open: $it", safeIssueUrl(it)) }
    }
}
