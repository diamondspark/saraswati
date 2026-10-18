/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 5 (fix round 1, dedicated review) — exercises the exact
// persisted-state logic that closes the "install permission left on after
// a plain pause, not just a crash" gap, independent of UpdateActivity's
// full lifecycle (which a plain JVM unit test can't drive anyway). A
// hand-rolled in-memory SharedPreferences fake is enough here — no
// Robolectric needed — because PendingInstall only ever talks to the
// SharedPreferences/Editor *interfaces*, never to a concrete Android class.
package dev.diamondspark.saraswati.updater

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSharedPreferences : SharedPreferences {
    val data = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = data.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST") (data[key] as? MutableSet<String>) ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor(this)
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
}

private class FakeEditor(private val owner: FakeSharedPreferences) : SharedPreferences.Editor {
    private val pending = mutableMapOf<String, Any?>()
    private val removals = mutableSetOf<String>()
    private var clearAll = false

    override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { key?.let { pending[it] = value } }
    override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
        apply { key?.let { pending[it] = values } }
    override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { key?.let { pending[it] = value } }
    override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { key?.let { pending[it] = value } }
    override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { key?.let { pending[it] = value } }
    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { key?.let { pending[it] = value } }
    override fun remove(key: String?): SharedPreferences.Editor = apply { key?.let { removals.add(it) } }
    override fun clear(): SharedPreferences.Editor = apply { clearAll = true }

    override fun commit(): Boolean {
        apply()
        return true
    }

    override fun apply() {
        if (clearAll) owner.data.clear()
        removals.forEach { owner.data.remove(it) }
        owner.data.putAll(pending)
    }
}

class PendingInstallTest {

    @Test
    fun `no attempt started means nothing is pending`() {
        val prefs = FakeSharedPreferences()
        assertFalse(PendingInstall.isAttemptPending(prefs))
        assertNull(PendingInstall.consumeTerminalStatus(prefs))
    }

    @Test
    fun `an attempt is pending as soon as it starts, before any status arrives`() {
        val prefs = FakeSharedPreferences()
        PendingInstall.markAttemptStarted(prefs)
        assertTrue(PendingInstall.isAttemptPending(prefs))
        // Still waiting on the user inside the system confirmation dialog —
        // this is the exact window the CRITICAL finding was about: paused,
        // not dead, no terminal status yet.
        assertNull(PendingInstall.consumeTerminalStatus(prefs))
    }

    @Test
    fun `a recorded terminal status is consumed exactly once`() {
        val prefs = FakeSharedPreferences()
        PendingInstall.markAttemptStarted(prefs)
        PendingInstall.recordTerminalStatus(prefs, 0)

        assertEquals(0, PendingInstall.consumeTerminalStatus(prefs))
        // Consuming clears it: a second read must not see a stale value
        // (the exact leak that would otherwise corrupt a later, unrelated
        // install attempt's recovery).
        assertNull(PendingInstall.consumeTerminalStatus(prefs))
        // The attempt itself is still pending until explicitly cleared —
        // TurnPermissionOff must keep nagging until the permission is off,
        // not just until the status is known.
        assertTrue(PendingInstall.isAttemptPending(prefs))
    }

    @Test
    fun `clear resolves the attempt fully`() {
        val prefs = FakeSharedPreferences()
        PendingInstall.markAttemptStarted(prefs)
        PendingInstall.recordTerminalStatus(prefs, 2)

        PendingInstall.clear(prefs)

        assertFalse(PendingInstall.isAttemptPending(prefs))
        assertNull(PendingInstall.consumeTerminalStatus(prefs))
    }

    @Test
    fun `recording a status of zero is distinguishable from no status at all`() {
        // PackageInstaller.STATUS_SUCCESS is 0 — a naive "0 means absent"
        // sentinel would silently swallow every successful install.
        val prefs = FakeSharedPreferences()
        PendingInstall.markAttemptStarted(prefs)
        PendingInstall.recordTerminalStatus(prefs, 0)
        assertEquals(0, PendingInstall.consumeTerminalStatus(prefs))
    }
}
