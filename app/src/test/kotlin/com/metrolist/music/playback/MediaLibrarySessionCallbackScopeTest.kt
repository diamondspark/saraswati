/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: device fix F1 — on the DHU the car's "Start Sangat" row fails its `future` on purpose; with a plain Job as
// the scope's parent that cancelled the scope, so the toggle never ran and every later list showed "No items".
package com.metrolist.music.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MediaLibrarySessionCallbackScopeTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    @Test
    fun a_failed_request_does_not_cancel_the_job_launched_before_it_or_the_requests_after_it() {
        val scope = newCarCallbackScope(dispatcher)
        val toggled = CompletableDeferred<String>()
        // Single thread: the launch cannot start until the failing future's body has finished, as on the device.
        scope.launch { toggled.complete("toggled") }
        val failed = scope.future { throw UnsupportedOperationException("the Sangat row changes no queue") }
        try {
            failed.get(5, TimeUnit.SECONDS)
            error("the failing future should have failed")
        } catch (e: ExecutionException) {
            assertTrue(e.cause is UnsupportedOperationException)
        }

        val later = scope.future { "children" }
        assertEquals("children", later.get(5, TimeUnit.SECONDS))
        assertEquals("toggled", runBlocking { toggled.await() })
        assertTrue(scope.coroutineContext[Job]!!.isActive)
    }

    @Test
    fun a_request_after_a_failure_is_not_a_cancellation() {
        val scope = newCarCallbackScope(dispatcher)
        runCatching { scope.future { throw IllegalStateException("boom") }.get(5, TimeUnit.SECONDS) }
        try {
            assertEquals(1, scope.future { 1 }.get(5, TimeUnit.SECONDS))
        } catch (e: ExecutionException) {
            assertFalse("later request was cancelled: ${e.cause}", e.cause is CancellationException)
            throw e
        }
    }

    @Test
    fun cancel_makes_the_scope_inactive() {
        val scope = newCarCallbackScope(dispatcher)
        assertTrue(scope.coroutineContext[Job]!!.isActive)
        scope.cancel()
        assertFalse(scope.coroutineContext[Job]!!.isActive)
    }
}
