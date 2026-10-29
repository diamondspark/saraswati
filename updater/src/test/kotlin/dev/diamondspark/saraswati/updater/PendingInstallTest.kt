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
import dev.diamondspark.saraswati.updatecore.ApkAsset
import dev.diamondspark.saraswati.updatecore.UpdateManifest
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
        PendingInstall.markAttemptStarted(prefs, TARGET)
        assertTrue(PendingInstall.isAttemptPending(prefs))
        // Still waiting on the user inside the system confirmation dialog —
        // this is the exact window the CRITICAL finding was about: paused,
        // not dead, no terminal status yet.
        assertNull(PendingInstall.consumeTerminalStatus(prefs))
    }

    @Test
    fun `a recorded terminal status is consumed exactly once`() {
        val prefs = FakeSharedPreferences()
        PendingInstall.markAttemptStarted(prefs, TARGET)
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
        PendingInstall.markAttemptStarted(prefs, TARGET)
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
        PendingInstall.markAttemptStarted(prefs, TARGET)
        PendingInstall.recordTerminalStatus(prefs, 0)
        assertEquals(0, PendingInstall.consumeTerminalStatus(prefs))
    }

    // SARASWATI: fix round 2, dedicated re-review — decideInstallRecovery is
    // the exact logic that was wrong in fix round 1: it used to treat
    // "anything that isn't ReadyToInstall" as a crash, which wrongly caught
    // TurnPermissionOff too (reached by entirely ordinary navigation, e.g.
    // tapping "Open settings" pauses+resumes the Activity the same way the
    // system install-confirmation UI does), discarding its real version and
    // showing a misleading "Install didn't finish" instead of
    // "Done on version X" for what was actually a successful update.

    @Test
    fun `nothing pending means no recovery action regardless of checkpoint`() {
        assertEquals(
            InstallRecoveryAction.None,
            decideInstallRecovery(UiState.Checking, isAttemptPending = false, knownStatus = null),
        )
    }

    @Test
    fun `ReadyToInstall with a known status dispatches it`() {
        val m = manifest()
        assertEquals(
            InstallRecoveryAction.DispatchStatus(0),
            decideInstallRecovery(UiState.ReadyToInstall(m), isAttemptPending = true, knownStatus = 0),
        )
    }

    @Test
    fun `ReadyToInstall with no status yet waits`() {
        val m = manifest()
        assertEquals(
            InstallRecoveryAction.WaitForStatus,
            decideInstallRecovery(UiState.ReadyToInstall(m), isAttemptPending = true, knownStatus = null),
        )
    }

    // SARASWATI: relaunch with a pending attempt and the checkpoint reset to
    // Checking: the recorded target decides success vs. "re-check".

    @Test
    fun `relaunch with the target now installed is recovered as succeeded`() {
        // The Updater's own self-update kills the process; the new build's version >= target.
        assertEquals(
            InstallRecoveryAction.RecoverAsSucceeded("9"),
            decideInstallRecovery(UiState.Checking, true, null, TARGET, installedTargetVersionCode = 9L),
        )
        assertEquals(
            InstallRecoveryAction.RecoverAsSucceeded("9"),
            decideInstallRecovery(UiState.Checking, true, null, TARGET, installedTargetVersionCode = 12L),
        )
    }

    @Test
    fun `relaunch with the target not installed is a plain re-check, not a crash prompt`() {
        assertEquals(
            InstallRecoveryAction.Recheck,
            decideInstallRecovery(UiState.Checking, true, null, TARGET, installedTargetVersionCode = 8L),
        )
        assertEquals(
            InstallRecoveryAction.Recheck,
            decideInstallRecovery(UiState.Checking, true, null, TARGET, installedTargetVersionCode = null),
        )
        // A status that happens to be known does not change the decision.
        assertEquals(
            InstallRecoveryAction.Recheck,
            decideInstallRecovery(UiState.Checking, true, 2, TARGET, installedTargetVersionCode = 8L),
        )
    }

    @Test
    fun `relaunch with an attempt recorded by an older build re-checks`() {
        assertEquals(
            InstallRecoveryAction.Recheck,
            decideInstallRecovery(UiState.Checking, true, null),
        )
    }

    @Test
    fun `the attempt target is recorded and cleared with the attempt`() {
        val prefs = FakeSharedPreferences()
        assertNull(PendingInstall.peekTarget(prefs))
        PendingInstall.markAttemptStarted(prefs, TARGET)
        assertEquals(TARGET, PendingInstall.peekTarget(prefs))
        PendingInstall.clear(prefs)
        assertNull(PendingInstall.peekTarget(prefs))
    }

    @Test
    fun `TurnPermissionOff with an attempt still pending is NOT treated as a crash`() {
        // The exact regression: an ordinary pause+resume through
        // TurnPermissionOff (e.g. "Open settings") must fall through to the
        // normal onResume handling, which re-dispatches PermissionChecked
        // against the REAL, already-correct TurnPermissionOff(version) —
        // not get its version silently discarded by crash recovery.
        val result = decideInstallRecovery(UiState.TurnPermissionOff("1.42"), isAttemptPending = true, knownStatus = null)
        assertEquals(InstallRecoveryAction.None, result)
    }

    @Test
    fun `any other checkpoint with an attempt pending is also not a crash signature`() {
        assertEquals(
            InstallRecoveryAction.None,
            decideInstallRecovery(UiState.Done("1.42"), isAttemptPending = true, knownStatus = null),
        )
        assertEquals(
            InstallRecoveryAction.None,
            decideInstallRecovery(UiState.Installing, isAttemptPending = true, knownStatus = null),
        )
    }

    private val TARGET = PendingTarget("dev.diamondspark.saraswati.updater", 9L, "9")

    private fun manifest(): UpdateManifest = UpdateManifest(
        schema = 1,
        channel = "stable",
        versionCode = 1L,
        versionName = "1.0",
        apk = ApkAsset(
            url = "https://github.com/diamondspark/saraswati/releases/download/v1/saraswati.apk",
            sha256 = "a".repeat(64),
            size = 1L,
        ),
        urgent = false,
        notes = "",
        publishedAt = "2026-10-17T00:00:00Z",
    )
}
