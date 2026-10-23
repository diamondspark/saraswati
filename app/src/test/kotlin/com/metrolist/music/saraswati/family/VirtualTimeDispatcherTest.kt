/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: regression coverage for the data race fixed in VirtualTimeDispatcher (see its doc comment in
// FamilyTestSupport.kt): its queue is a java.util.PriorityQueue, and every test using it runs its coroutines
// on virtual time except for the moments one of them bridges to a real dispatcher (e.g. Dispatchers.IO for a
// DataStore write) and gets resumed back here from that real thread. SangatLiveWatcherTest's "overlapping 503
// and periodic gate refreshes" test exercises that scenario end to end, but the actual corrupting interleaving
// only shows up inside a narrow, timing-dependent window, so it cannot be trusted alone to catch a regression
// here. These tests drive the dispatcher's own entry points directly from many real threads at once, racing
// the test thread's advanceBy()/runCurrent(), and demand that every piece of scheduled work ran exactly once
// — the property java.util.PriorityQueue corruption breaks first, either by losing a task or running it twice
// (observed for real as a NullPointerException out of PriorityQueue's comparator, and — under a different
// interleaving — as kotlinx.coroutines' "Already resumed" from a Task that survived a failed queue.remove()).
package com.metrolist.music.saraswati.family

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext

class VirtualTimeDispatcherTest {
    @Test
    fun `concurrent dispatch from real threads races advanceBy without losing or duplicating work`() {
        val time = VirtualTimeDispatcher()
        val threads = 8
        val perThread = 2_000
        val total = threads * perThread
        val ran = AtomicInteger(0)
        val submitted = CountDownLatch(total)
        val pool = Executors.newFixedThreadPool(threads)

        repeat(threads) {
            pool.submit {
                repeat(perThread) {
                    time.dispatch(EmptyCoroutineContext) { ran.incrementAndGet() }
                    submitted.countDown()
                }
            }
        }

        // Drains concurrently with the producers: the test thread mutates the queue from runCurrent()
        // while real pool threads are mutating it from dispatch(), exactly the hazard that corrupted it.
        while (submitted.count > 0) {
            time.runCurrent()
        }
        assertTrue("producer threads never finished", submitted.await(10, TimeUnit.SECONDS))
        time.runCurrent() // flush anything dispatched in the last few counted-down increments
        pool.shutdown()

        assertEquals("every dispatched unit of work must run exactly once", total, ran.get())
    }

    @Test
    fun `concurrent scheduleResumeAfterDelay from real threads races advanceBy without losing or duplicating work`() {
        val time = VirtualTimeDispatcher()
        val threads = 8
        val perThread = 500
        val total = threads * perThread
        val ran = AtomicInteger(0)
        val launched = CountDownLatch(total)
        val scope = CoroutineScope(SupervisorJob())
        val pool = Executors.newFixedThreadPool(threads)

        repeat(threads) {
            pool.submit {
                repeat(perThread) {
                    // CoroutineStart.UNDISPATCHED keeps the coroutine running on the calling (real pool)
                    // thread right up to delay(1), so scheduleResumeAfterDelay is itself invoked from that
                    // real thread — not from the test thread — concurrently with other pool threads doing
                    // the same and with the test thread draining via advanceBy() below.
                    scope.launch(time, start = CoroutineStart.UNDISPATCHED) {
                        delay(1)
                        ran.incrementAndGet()
                    }
                    launched.countDown()
                }
            }
        }

        assertTrue("producer threads never finished launching", launched.await(10, TimeUnit.SECONDS))
        var guard = 0
        while (ran.get() < total) {
            time.advanceBy(1)
            check(++guard < total * 20) { "advanceBy looped without ever reaching $total completions (stuck at ${ran.get()})" }
        }
        scope.cancel()
        pool.shutdown()

        assertEquals("every scheduled coroutine must resume exactly once", total, ran.get())
    }
}
