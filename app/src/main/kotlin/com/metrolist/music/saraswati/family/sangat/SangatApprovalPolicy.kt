/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 9 — whether the host's phone lets a join request or a suggestion in by itself.
// While the host is driving (car mode) nobody can tap Approve, so everyone in the family is let in; outside
// car mode the host's own Listen Together preference decides, as before.
package com.metrolist.music.saraswati.family.sangat

object SangatApprovalPolicy {
    fun autoApprove(
        prefEnabled: Boolean,
        carMode: Boolean,
    ): Boolean = carMode || prefEnabled
}
