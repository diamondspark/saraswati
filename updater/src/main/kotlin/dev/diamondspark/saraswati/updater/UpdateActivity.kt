/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 5 — the whole app is this one screen. It drives the pure
// `reduce` state machine (UpdateFlow.kt) with real network/PackageInstaller
// side effects, and is the one piece responsible for two things `reduce`
// deliberately *can't* do on its own (see UpdateFlow.kt's file header):
//
// 1. Keeping two separate notions of "state": [checkpoint] (fed back into
//    `reduce` as its `state` argument) and [displayed] (what's rendered).
//    They're almost always the same value — they only diverge while
//    [UiState.Downloading]/[UiState.Installing] are on screen, where
//    [checkpoint] stays pinned at the last manifest-bearing state
//    (UpdateReady/ReadyToInstall) so that the *next* real decision
//    (DownloadFinished, InstallStatus) still has the manifest `reduce`
//    needs to build NeedsInstallPermission(manifest)/ReadyToInstall(manifest)/
//    TurnPermissionOff(version), while [displayed] freely shows progress.
//
// 2. Surviving a crash mid-flow (Review Focus item 2's named risk). Before
//    the very first byte of the APK is written into a PackageInstaller
//    session, [PREF_PENDING_TURN_OFF] is persisted to disk. If this process
//    dies anywhere between that point and the user actually flipping the
//    permission off — mid-install, before the result broadcast, even
//    before this Activity ever sees InstallStatus — the NEXT time the
//    Updater opens, onResume sees that flag before it does anything else
//    and jumps straight to TurnPermissionOff, skipping the normal
//    Checking flow entirely, rather than silently forgetting that
//    REQUEST_INSTALL_PACKAGES might still be granted. It deliberately
//    recovers with version=null (never a guessed/stale version string) —
//    if the install actually DID succeed before the crash, a plain Retry
//    re-check will correctly land on UpToDate.
package dev.diamondspark.saraswati.updater

import android.app.Activity
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import dev.diamondspark.saraswati.updatecore.ApkVerifier
import dev.diamondspark.saraswati.updatecore.ManifestParser
import dev.diamondspark.saraswati.updatecore.UpdateManifest
import dev.diamondspark.saraswati.updatecore.VerifyResult
import dev.diamondspark.saraswati.updatecore.MANIFEST_BASE_URL
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val PREFS_NAME = "saraswati_updater"
private const val PREF_CHANNEL = "channel"
private const val PREF_PENDING_TURN_OFF = "pending_turn_off"
private const val DEFAULT_CHANNEL = "stable"
private const val EXTRA_CHANNEL = "channel"
private const val TARGET_PACKAGE_SARASWATI = "dev.diamondspark.saraswati"

/** Not a real PackageInstaller status: used only when starting the session itself threw. */
private const val INSTALL_STATUS_EXCEPTION = -100

class UpdateActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var executor: ExecutorService
    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpFetcher = HttpFetcher()
    private lateinit var installSession: InstallSession

    private lateinit var headline: TextView
    private lateinit var body: TextView
    private lateinit var progressIndeterminate: ProgressBar
    private lateinit var progressDownload: ProgressBar
    private lateinit var samsungHint: TextView
    private lateinit var buttonPrimary: Button

    /** Fed into `reduce` as `state`. See the file header: deliberately NOT always equal to [displayed]. */
    private var checkpoint: UiState = UiState.Checking

    /** What's actually on screen right now. */
    private var displayed: UiState = UiState.Checking

    private var checkInFlight = false

    /** True once the current flow is checking/installing the Updater itself, not Saraswati. */
    private var updatingSelf = false

    /** The most recently fetched manifest, kept around only to read its optional `updater` section afterwards. */
    private var lastFetchedManifest: UpdateManifest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update)

        headline = findViewById(R.id.headline)
        body = findViewById(R.id.body)
        progressIndeterminate = findViewById(R.id.progress_indeterminate)
        progressDownload = findViewById(R.id.progress_download)
        samsungHint = findViewById(R.id.samsung_hint)
        buttonPrimary = findViewById(R.id.button_primary)
        buttonPrimary.setOnClickListener { onPrimaryButtonTapped() }

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        executor = Executors.newSingleThreadExecutor()
        installSession = InstallSession(this)

        render(displayed)
    }

    override fun onResume() {
        super.onResume()
        InstallEvents.listener = { code -> mainHandler.post { dispatch(Event.InstallStatus(code)) } }

        if (recoverPendingTurnOffIfAny()) return

        when (checkpoint) {
            is UiState.NeedsInstallPermission, is UiState.TurnPermissionOff ->
                dispatch(Event.PermissionChecked(canInstall = packageManager.canRequestPackageInstalls()))
            UiState.Checking -> if (!checkInFlight) startCheck()
            else -> render(displayed)
        }
    }

    override fun onPause() {
        InstallEvents.listener = null
        super.onPause()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    // ---- recovery -----------------------------------------------------

    private fun recoverPendingTurnOffIfAny(): Boolean {
        if (checkpoint != UiState.Checking) return false
        if (!prefs.getBoolean(PREF_PENDING_TURN_OFF, false)) return false
        checkpoint = UiState.TurnPermissionOff(version = null)
        displayed = checkpoint
        render(displayed)
        dispatch(Event.PermissionChecked(canInstall = packageManager.canRequestPackageInstalls()))
        return true
    }

    // ---- the dispatch loop ---------------------------------------------

    private fun dispatch(event: Event) {
        val canInstallNow = packageManager.canRequestPackageInstalls()
        val source = checkpoint
        val next = reduce(source, event, canInstallNow)

        runSideEffects(source, event, next)

        displayed = next
        checkpoint = if (next is UiState.Downloading || next == UiState.Installing) source else next

        if (source is UiState.TurnPermissionOff && checkpoint !is UiState.TurnPermissionOff) {
            prefs.edit().remove(PREF_PENDING_TURN_OFF).apply()
        }

        render(displayed)

        if (!updatingSelf && (next is UiState.UpToDate || next is UiState.AlreadyAhead || next is UiState.Done)) {
            lastFetchedManifest?.let(::maybeStartSelfUpdateCheck)
        }
    }

    private fun runSideEffects(source: UiState, event: Event, next: UiState) {
        when {
            event == Event.UserTappedUpdate && next is UiState.Downloading && source is UiState.UpdateReady ->
                startDownload(source.manifest)

            event == Event.UserTappedUpdate && next == UiState.Installing && source is UiState.ReadyToInstall ->
                startInstall(source.manifest)

            event is Event.InstallStatus && source is UiState.ReadyToInstall ->
                downloadTargetFile(source.manifest.versionCode).delete()

            event == Event.Retry && next == UiState.Checking -> startCheck()
        }
    }

    // ---- user-initiated actions ----------------------------------------

    private fun onPrimaryButtonTapped() {
        when (val state = displayed) {
            is UiState.UpdateReady -> dispatch(Event.UserTappedUpdate)
            is UiState.ReadyToInstall -> dispatch(Event.UserTappedUpdate)
            is UiState.NeedsInstallPermission -> openInstallPermissionSettings()
            is UiState.TurnPermissionOff -> openInstallPermissionSettings()
            is UiState.Error -> if (state.canRetry) dispatch(Event.Retry)
            is UiState.UpToDate, is UiState.AlreadyAhead, is UiState.Done -> {
                checkpoint = UiState.Checking
                displayed = checkpoint
                render(displayed)
                startCheck()
            }
            else -> Unit
        }
    }

    private fun openInstallPermissionSettings() {
        val intent = android.content.Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:$packageName"),
        )
        startActivity(intent)
    }

    // ---- background work ------------------------------------------------

    private fun startCheck() {
        checkInFlight = true
        val channel = currentChannel()
        executor.execute {
            try {
                val json = httpFetcher.fetchText("$MANIFEST_BASE_URL$channel.json")
                val manifest = ManifestParser.parse(json).getOrThrow()
                val installed = installedVersionCode(TARGET_PACKAGE_SARASWATI)
                mainHandler.post {
                    checkInFlight = false
                    lastFetchedManifest = manifest
                    dispatch(Event.ManifestLoaded(manifest, installed))
                }
            } catch (e: Exception) {
                mainHandler.post {
                    checkInFlight = false
                    dispatch(Event.ManifestFailed(getString(R.string.cant_reach_update_server)))
                }
            }
        }
    }

    private fun startDownload(manifest: UpdateManifest) {
        val target = downloadTargetFile(manifest.versionCode)
        executor.execute {
            try {
                httpFetcher.download(manifest.apk.url, target) { bytesSoFar ->
                    val percent = percentOf(bytesSoFar, manifest.apk.size)
                    mainHandler.post { dispatch(Event.DownloadProgress(percent)) }
                }
            } catch (e: Exception) {
                target.delete()
                mainHandler.post { dispatch(Event.ManifestFailed(getString(R.string.cant_reach_update_server))) }
                return@execute
            }

            val verifyResult = ApkVerifier.verify(
                file = target,
                asset = manifest.apk,
                expectedPackage = targetPackageName(),
                expectedVersionCode = manifest.versionCode,
                pinnedSignerSha256 = pinnedCertSha256Set(),
                inspector = AndroidArchiveInspector(packageManager),
            )
            if (verifyResult is VerifyResult.Failed) {
                target.delete()
            }
            mainHandler.post { dispatch(Event.DownloadFinished(verifyResult)) }
        }
    }

    private fun startInstall(manifest: UpdateManifest) {
        // SARASWATI: persisted BEFORE the session is even created — see the
        // file header. This must be written before any use of
        // REQUEST_INSTALL_PACKAGES actually begins, not after, so a crash
        // at any point from here onward is still caught on the next launch.
        prefs.edit().putBoolean(PREF_PENDING_TURN_OFF, true).apply()
        val target = downloadTargetFile(manifest.versionCode)
        val expectedPackage = targetPackageName()
        executor.execute {
            try {
                installSession.install(target, expectedPackage)
            } catch (e: Exception) {
                mainHandler.post { dispatch(Event.InstallStatus(INSTALL_STATUS_EXCEPTION)) }
            }
        }
    }

    // ---- the Updater's own self-update, driven by the same manifest -----

    // SARASWATI: best-effort secondary pass, reusing the exact same `reduce`
    // machinery with a synthesized single-asset "manifest" built from the
    // real manifest's optional `updater` section. Deliberately scoped down
    // relative to the primary (Saraswati) flow above: it only ever runs
    // after the primary flow reaches a state with nothing left to do
    // (UpToDate/AlreadyAhead/Done), and a debug build's own applicationId
    // (".updater.debug") never matches a published release asset's package
    // name, so ApkVerifier fails closed on debug builds here by construction
    // — self-update is effectively a release-build-only path, same as the
    // whole publishing pipeline it depends on.
    private fun maybeStartSelfUpdateCheck(manifest: UpdateManifest) {
        val updaterAsset = manifest.updater ?: return
        if (updaterAsset.versionCode <= BuildConfig.VERSION_CODE) return
        updatingSelf = true
        val selfManifest = UpdateManifest(
            schema = manifest.schema,
            channel = manifest.channel,
            versionCode = updaterAsset.versionCode,
            versionName = updaterAsset.versionCode.toString(),
            apk = updaterAsset.apk,
            urgent = false,
            notes = "",
            publishedAt = manifest.publishedAt,
        )
        checkpoint = UiState.Checking
        dispatch(Event.ManifestLoaded(selfManifest, installed = BuildConfig.VERSION_CODE.toLong()))
    }

    private fun targetPackageName(): String = if (updatingSelf) BuildConfig.APPLICATION_ID else TARGET_PACKAGE_SARASWATI

    // ---- small helpers ----------------------------------------------------

    private fun currentChannel(): String {
        val fromIntent = intent?.getStringExtra(EXTRA_CHANNEL)?.lowercase()
        if (fromIntent == "beta" || fromIntent == "stable") {
            prefs.edit().putString(PREF_CHANNEL, fromIntent).apply()
            return fromIntent
        }
        return prefs.getString(PREF_CHANNEL, DEFAULT_CHANNEL) ?: DEFAULT_CHANNEL
    }

    private fun installedVersionCode(packageName: String): Long? = try {
        packageManager.getPackageInfo(packageName, 0).longVersionCode
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun pinnedCertSha256Set(): Set<String> = BuildConfig.PINNED_CERT_SHA256
        .split(",")
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()

    private fun downloadTargetFile(versionCode: Long): File {
        val dir = File(filesDir, "updates")
        dir.mkdirs()
        return File(dir, "$versionCode.apk")
    }

    private fun isSamsung(): Boolean = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    // ---- rendering ----------------------------------------------------

    private fun render(state: UiState) {
        progressIndeterminate.visibility = View.GONE
        progressDownload.visibility = View.GONE
        samsungHint.visibility = View.GONE
        buttonPrimary.visibility = View.GONE

        when (state) {
            UiState.Checking -> {
                headline.text = getString(R.string.app_name)
                body.text = getString(R.string.checking)
                progressIndeterminate.visibility = View.VISIBLE
            }

            is UiState.UpToDate -> {
                headline.text = getString(R.string.app_name)
                body.text = getString(R.string.up_to_date)
                showButton(getString(R.string.check_now))
            }

            is UiState.AlreadyAhead -> {
                headline.text = getString(R.string.app_name)
                body.text = getString(R.string.already_ahead)
                showButton(getString(R.string.check_now))
            }

            is UiState.UpdateReady -> {
                headline.text = if (state.manifest.urgent) {
                    getString(R.string.urgent_update_ready)
                } else {
                    getString(R.string.sudhaar_headline)
                }
                body.text = getString(R.string.update_ready) + " · " + state.manifest.versionName
                showButton(getString(R.string.install))
            }

            is UiState.Downloading -> {
                headline.text = getString(R.string.sudhaar_headline)
                body.text = getString(R.string.downloading, state.percent)
                progressDownload.visibility = View.VISIBLE
                progressDownload.progress = state.percent
            }

            is UiState.NeedsInstallPermission -> {
                headline.text = getString(R.string.sudhaar_headline)
                body.text = getString(R.string.allow_installing_one_time)
                showButton(getString(R.string.allow_installing_one_time))
                samsungHint.visibility = if (isSamsung()) View.VISIBLE else View.GONE
            }

            is UiState.ReadyToInstall -> {
                headline.text = getString(R.string.sudhaar_headline)
                body.text = getString(R.string.install)
                showButton(getString(R.string.install))
                samsungHint.visibility = if (isSamsung()) View.VISIBLE else View.GONE
            }

            UiState.Installing -> {
                headline.text = getString(R.string.sudhaar_headline)
                body.text = getString(R.string.installing)
                progressIndeterminate.visibility = View.VISIBLE
                samsungHint.visibility = if (isSamsung()) View.VISIBLE else View.GONE
            }

            is UiState.TurnPermissionOff -> {
                headline.text = getString(R.string.app_name)
                body.text = getString(R.string.turn_permission_off) + "\n\n" + getString(R.string.permission_off_hint)
                showButton(getString(R.string.open_settings))
            }

            is UiState.Done -> {
                headline.text = getString(R.string.app_name)
                body.text = getString(R.string.done_on_version, state.version)
                showButton(getString(R.string.check_now))
            }

            is UiState.Error -> {
                headline.text = getString(R.string.app_name)
                body.text = state.message
                if (state.canRetry) showButton(getString(R.string.retry))
            }
        }
    }

    private fun showButton(text: String) {
        buttonPrimary.text = text
        buttonPrimary.visibility = View.VISIBLE
    }
}

private fun percentOf(bytesSoFar: Long, totalBytes: Long): Int {
    if (totalBytes <= 0L) return 0
    return ((bytesSoFar * 100L) / totalBytes).toInt().coerceIn(0, 100)
}
