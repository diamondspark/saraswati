/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 9, Step 4 — DEFERRED, on purpose, and visible as skipped
// tests rather than silently dropped.
//
// The design's taste-seeding step (set_tasteprofile at setup) needs an InnerTube
// call this codebase does not have: grep of app/ and innertube/ finds no
// taste-profile endpoint, so there is nothing to call from the onboarding flow.
// Such a call also writes to a signed-in YouTube account, which a grandparent's
// phone may not have when it is paired. Building the genre-picker screen
// without the call would collect answers and throw them away, so neither half
// is built yet. When the endpoint exists, un-ignore these and build the step
// from the approved onboarding mockup (step 5: five genre chips).
//
// Never call the real endpoint against a real account from a test: use a fake
// InnerTube client.
package com.metrolist.music.saraswati.saral

import org.junit.Ignore
import org.junit.Test

class TasteSeedingDeferredTest {
    @Ignore("TODO(task-9, deferred): innertube has no set_tasteprofile call; add it, then seed from onboarding")
    @Test
    fun `the chosen genres are sent to the taste profile once, after the phone is approved`() {
    }

    @Ignore("TODO(task-9, deferred): needs the genre-picker step in the join flow")
    @Test
    fun `a grandparent phone starts with Hindi Bollywood, devotional and ghazal selected`() {
    }

    @Ignore("TODO(task-9, deferred): needs the genre-picker step in the join flow")
    @Test
    fun `every pre-selected genre can be deselected and nothing is forced`() {
    }

    @Ignore("TODO(task-9, deferred): needs the genre-picker step in the join flow")
    @Test
    fun `an adult phone is not shown the taste step`() {
    }

    @Ignore("TODO(task-9, deferred): needs a signed-in account; decide what an unsigned phone does")
    @Test
    fun `a phone with no signed-in account skips the step without an error`() {
    }
}
