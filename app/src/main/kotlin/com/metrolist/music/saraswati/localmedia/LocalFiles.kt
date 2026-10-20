/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 3 — the Local files switch. It is on by default only once the person has allowed access
// (the system permission); turned off it means no scanning, no MediaStore reads, no "On this phone" row in the car,
// and the songs already found stay hidden. Turning it off never deletes them, so turning it on again is instant.
package com.metrolist.music.saraswati.localmedia

object LocalFiles {
    /** Whether songs on this phone are in use right now. [stored] is the saved switch, null when never touched. */
    fun enabled(
        stored: Boolean?,
        access: LocalAccess,
    ): Boolean = access == LocalAccess.GRANTED && stored != false

    /**
     * What the car and the library entry should treat access as. A switched-off feature looks like an
     * unsupported phone: nothing is shown and nothing asks for permission.
     */
    fun visibleAccess(
        access: LocalAccess,
        stored: Boolean?,
    ): LocalAccess = if (stored == false) LocalAccess.UNSUPPORTED else access
}
