/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: feedback — the Send feedback screen's view model against a fake hub and an in-memory store. Unconfined
// dispatching finishes each call before it returns, except a send held open on purpose to look at Sending.
package com.metrolist.music.saraswati.family.feedback

import com.metrolist.music.saraswati.family.FakeStore
import com.metrolist.music.saraswati.family.HubException
import com.metrolist.music.saraswati.family.HubIdentity
import com.metrolist.music.saraswati.family.OFFLINE
import com.metrolist.music.saraswati.family.hubFailure
import com.metrolist.music.saraswati.family.parivaar.FakeIdentity
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

private class SubmitCall(
    val bearer: String,
    val kind: FeedbackKind,
    val text: String,
    val diagnostics: FeedbackDiagnostics?,
)

private class FakeFeedbackHub : FeedbackHub {
    val calls = mutableListOf<SubmitCall>()
    var finished = 0
    var failure: Throwable? = null
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun submitFeedback(
        bearer: String,
        kind: FeedbackKind,
        text: String,
        diagnostics: FeedbackDiagnostics?,
    ): Result<String> {
        calls += SubmitCall(bearer, kind, text, diagnostics)
        gate?.await()
        finished++
        return failure?.let { Result.failure(it) } ?: Result.success("fb-1")
    }

    override suspend fun listFeedback(
        adminKey: String,
        status: String,
    ): Result<List<FeedbackItem>> = error("not used by the send screen")

    override suspend fun setFeedbackStatus(
        adminKey: String,
        id: String,
        status: FeedbackStatus,
    ): Result<Unit> = error("not used by the send screen")
}

class FeedbackViewModelTest {
    private lateinit var scope: CoroutineScope
    private lateinit var hub: FakeFeedbackHub
    private lateinit var store: FakeStore
    private lateinit var identity: FakeIdentity
    private lateinit var vm: FeedbackViewModel

    private val info = FeedbackDiagnostics("13.7.0-s4119", "17 (API 37)", "Google Pixel 8 Pro")

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        hub = FakeFeedbackHub()
        store = FakeStore()
        identity = FakeIdentity(token = "phone-session")
        vm = FeedbackViewModel(hub, store, identity, { info }, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    // --- validation ---

    @Test
    fun `empty and whitespace-only text is not valid`() {
        assertFalse(FeedbackDraft(text = "").isValid)
        assertFalse(FeedbackDraft(text = "   \n\t  ").isValid)
    }

    @Test
    fun `text is valid up to exactly 1000 characters after trimming`() {
        assertTrue(FeedbackDraft(text = "a").isValid)
        assertTrue(FeedbackDraft(text = "a".repeat(1000)).isValid)
        assertFalse(FeedbackDraft(text = "a".repeat(1001)).isValid)
        assertTrue(FeedbackDraft(text = "  " + "a".repeat(1000) + "\n").isValid)
    }

    @Test
    fun `length counts UTF-16 units like the hub, so 501 emoji are too long`() {
        assertTrue(FeedbackDraft(text = "😀".repeat(500)).isValid)
        assertFalse(FeedbackDraft(text = "😀".repeat(501)).isValid)
    }

    @Test
    fun `the draft starts as a bug report with the info switch on`() {
        assertEquals(FeedbackDraft(FeedbackKind.Bug, "", true), vm.draft.value)
        assertEquals(FeedbackUiState.Idle, vm.state.value)
    }

    @Test
    fun `an invalid draft is never sent`() {
        vm.setText("   ")
        vm.send()
        vm.setText("a".repeat(1001))
        vm.send()

        assertTrue(hub.calls.isEmpty())
        assertEquals(FeedbackUiState.Idle, vm.state.value)
    }

    // --- diagnostics ---

    @Test
    fun `the diagnostics are sent when the switch is on`() {
        vm.setText("It crashed")
        vm.send()

        assertEquals(info, hub.calls.single().diagnostics)
    }

    @Test
    fun `switching info off sends null diagnostics`() {
        vm.setText("It crashed")
        vm.setIncludeInfo(false)
        vm.send()

        assertNull(hub.calls.single().diagnostics)
    }

    @Test
    fun `build diagnostics hold only version, Android and phone in the contract format`() {
        assertEquals(info, buildDiagnostics("13.7.0-s4119", "17", 37, "Google", "Pixel 8 Pro"))
    }

    // --- state transitions ---

    @Test
    fun `a send goes Idle to Sending to Sent and clears the text only then`() {
        hub.gate = CompletableDeferred()
        vm.setKind(FeedbackKind.Idea)
        vm.setText("  Add a sleep timer  ")

        vm.send()

        assertEquals(FeedbackUiState.Sending, vm.state.value)
        assertEquals("  Add a sleep timer  ", vm.draft.value.text)

        hub.gate!!.complete(Unit)

        assertEquals(FeedbackUiState.Sent, vm.state.value)
        assertEquals("", vm.draft.value.text)
        assertEquals(FeedbackKind.Idea, vm.draft.value.kind)
        val call = hub.calls.single()
        assertEquals(FeedbackKind.Idea, call.kind)
        assertEquals("Add a sleep timer", call.text)
    }

    @Test
    fun `leaving the screen mid-send does not cancel the hub call`() {
        hub.gate = CompletableDeferred()
        vm.setText("one")
        vm.send()

        scope.cancel() // what clearing the view model does

        hub.gate!!.complete(Unit)
        assertEquals(1, hub.finished)
    }

    @Test
    fun `a second send while one is in flight is ignored`() {
        hub.gate = CompletableDeferred()
        vm.setText("one")

        vm.send()
        vm.send()

        assertEquals(1, hub.calls.size)
    }

    @Test
    fun `text typed while a send is in flight is kept`() {
        hub.gate = CompletableDeferred()
        vm.setText("first")
        vm.send()
        vm.setText("first and more")

        hub.gate!!.complete(Unit)

        assertEquals(FeedbackUiState.Sent, vm.state.value)
        assertEquals("first and more", vm.draft.value.text)
    }

    @Test
    fun `send another returns to a blank Idle form that keeps kind and switch`() {
        vm.setKind(FeedbackKind.Idea)
        vm.setIncludeInfo(false)
        vm.setText("hello")
        vm.send()

        vm.sendAnother()

        assertEquals(FeedbackUiState.Idle, vm.state.value)
        assertEquals(FeedbackDraft(FeedbackKind.Idea, "", false), vm.draft.value)
    }

    // --- errors ---

    private fun failWith(
        failure: Throwable,
        expected: FeedbackFailure,
    ) {
        hub.failure = failure
        vm.setText("Something is wrong")
        vm.send()
        assertEquals(failure.toString(), FeedbackUiState.Error(expected), vm.state.value)
    }

    @Test
    fun `every hub failure maps to one friendly reason`() {
        failWith(OFFLINE, FeedbackFailure.Offline)
        failWith(hubFailure(429), FeedbackFailure.RateLimited)
        failWith(hubFailure(404), FeedbackFailure.NotSwitchedOn)
        failWith(hubFailure(503), FeedbackFailure.NotSwitchedOn)
        failWith(hubFailure(400), FeedbackFailure.Invalid)
        failWith(hubFailure(413), FeedbackFailure.Invalid)
        failWith(hubFailure(401), FeedbackFailure.NotPaired)
        failWith(hubFailure(500), FeedbackFailure.NotSwitchedOn)
        failWith(IllegalStateException("hub base URL is not configured"), FeedbackFailure.Other)
        failWith(RuntimeException("boom"), FeedbackFailure.Other)
        failWith(HubException(410, "gone"), FeedbackFailure.Other)
    }

    @Test
    fun `no token at all is NotPaired and makes no network call`() {
        identity.token = null
        store.admin = null
        vm.setText("Hello")

        vm.send()

        assertEquals(FeedbackUiState.Error(FeedbackFailure.NotPaired), vm.state.value)
        assertTrue(hub.calls.isEmpty())
        assertEquals("Hello", vm.draft.value.text)
    }

    @Test
    fun `an identity that cannot be read counts as no token`() {
        val broken =
            object : HubIdentity {
                override suspend fun sessionToken(): String? = throw RuntimeException("keystore")

                override suspend fun deviceId(): String? = null
            }
        vm = FeedbackViewModel(hub, store, broken, { info }, scope)
        vm.setText("Hello")

        vm.send()

        assertEquals(FeedbackUiState.Error(FeedbackFailure.NotPaired), vm.state.value)
        assertTrue(hub.calls.isEmpty())
    }

    @Test
    fun `the draft is kept on every error, and editing clears the error`() {
        hub.failure = OFFLINE
        vm.setKind(FeedbackKind.Idea)
        vm.setIncludeInfo(false)
        vm.setText("Please keep me")
        vm.send()

        assertEquals(FeedbackDraft(FeedbackKind.Idea, "Please keep me", false), vm.draft.value)

        vm.setText("Please keep me!")

        assertEquals(FeedbackUiState.Idle, vm.state.value)
    }

    @Test
    fun `retrying after an error can succeed`() {
        hub.failure = OFFLINE
        vm.setText("Try twice")
        vm.send()
        hub.failure = null

        vm.send()

        assertEquals(FeedbackUiState.Sent, vm.state.value)
        assertEquals(2, hub.calls.size)
    }

    // --- text and bearer ---

    @Test
    fun `Hindi emoji and markup pass through unchanged apart from trimming`() {
        val text = "गाना रुक जाता है 😀 `code` @everyone </details> ' OR 1=1 -- مرحبا"
        vm.setText("  $text \n")

        vm.send()

        assertEquals(text, hub.calls.single().text)
    }

    @Test
    fun `a phone sends with its session token`() {
        vm.setText("From a phone")
        vm.send()

        assertEquals("phone-session", hub.calls.single().bearer)
    }

    @Test
    fun `the owner sends with the admin key`() {
        store.admin = "owner-admin-key"
        vm.setText("From the owner")
        vm.send()

        assertEquals("owner-admin-key", hub.calls.single().bearer)
    }
}
