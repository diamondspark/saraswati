/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music

import android.app.ActivityManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.widget.Toast
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.allowHardware
import coil3.request.crossfade
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.ArtistConjunctions
import com.metrolist.innertube.models.YouTubeLocale
import com.metrolist.kugou.KuGou
import com.metrolist.lastfm.LastFM
import com.metrolist.music.BuildConfig
import com.metrolist.music.saraswati.SaraswatiFeatures
import com.metrolist.music.saraswati.car.CarHomeHolder
import com.metrolist.music.saraswati.offline.SmartDownloadExecutor
import com.metrolist.music.saraswati.offline.SmartDownloadScheduler
import com.metrolist.music.saraswati.saral.SaralActive
import com.metrolist.music.saraswati.security.OpenResult
import com.metrolist.music.saraswati.security.SaraswatiVault
import com.metrolist.music.saraswati.security.SecretScrubber
import com.metrolist.music.constants.*
import com.metrolist.music.di.ApplicationScope
import com.metrolist.music.extensions.toEnum
import com.metrolist.music.extensions.toInetSocketAddress
import com.metrolist.music.utils.CrashHandler
import com.metrolist.music.utils.ArtistNameAliases
import com.metrolist.music.utils.InnerTubeXPlayer
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import com.metrolist.music.utils.reportException
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.net.Authenticator
import java.net.PasswordAuthentication
import java.net.Proxy
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

@HiltAndroidApp
class App :
    Application(),
    SingletonImageLoader.Factory {
    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    // SARASWATI: Phase 5 Task 11 — both asked for lazily; the executor touches the download database only when the
    // ledger lists unfinished smart downloads.
    @Inject
    lateinit var smartDownloadExecutor: dagger.Lazy<SmartDownloadExecutor>

    override fun onCreate() {
        super.onCreate()

        // CrashActivity runs in a separate process. Starting the full app there can make that
        // process claim WebView's data directory and crash the next main-process WebView.
        if (!isMainProcess()) return

        // Install crash handler first
        CrashHandler.install(this)
        // SARASWATI: fix wave B2 — a crash from here on (the rest of this method, a service, a receiver)
        // reaches the crash screen before MainActivity has run; it must already know if this is a Saral phone.
        runBlocking { SaralActive.initFrom(dataStore) }
        ArtistNameAliases.initialize(this)

        // SARASWATI: plant logging before anything else below that might log,
        // including the scrub try/catch a few lines down — it used to run
        // after this block, so its failure warning silently printed nowhere
        // in any build (no tree was planted yet to receive it). Moving the
        // plant() call earlier, rather than moving the scrub later, keeps
        // the scrub's "before any cookie read" ordering guarantee (Task 7)
        // unchanged: it still runs before observeSettingsChanges() below.
        // SARASWATI (audit A11-1): only in debug builds — unconditionally
        // planting DebugTree sent ~990 call sites to release Logcat,
        // including BotGuard responses and Listen Together room codes.
        // Timber tolerates nothing ever being planted: every Timber.*() call
        // elsewhere in the app is then simply a no-op in release.
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // preferencesDataStore uses filesDir/datastore; proactive mkdir reduces failures on odd ROM states
        try {
            val datastoreDir = File(filesDir, "datastore")
            if (!datastoreDir.isDirectory && !datastoreDir.mkdirs()) {
                Timber.w("Could not create DataStore directory at ${datastoreDir.path}")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to ensure DataStore directory")
        }

        // SARASWATI: scrub any plaintext secret preference (a YouTube
        // sign-in cookie, or an AI/Last.fm/Listen-Together/proxy
        // credential) that a restored Metrolist backup, a phone-to-phone
        // transfer, or a hand-copied legacy datastore file could have left
        // behind (audit NEW-3; Review Focus #4).
        //
        // SARASWATI (I4): the scrub is two phases (see SecretScrubber). Phase 1
        // (plaintext) needs no Keystore and still runs to completion before
        // this returns, so nothing in this process can read a plaintext secret:
        // Android does not create any Activity (and therefore cannot run any
        // Compose code that reads a secret via rememberPreference) until
        // Application.onCreate() returns, and observeSettingsChanges() below
        // starts after it. Phase 2 opens each vault-sealed value with the
        // Keystore and so runs on IO, not awaited: every read of a sealed value
        // opens it first and never returns one the vault rejects, so no reader
        // depends on phase 2 having run. Only the DataStore file work of phase 1
        // is still on Main, as it has always been; the Keystore is off Main.
        //
        // SARASWATI (fix round 1; updated for I1): a broken/custom-ROM
        // Keystore implementation (seen on some Samsung/Xiaomi phones) can
        // make AndroidKeystoreKeyProvider.key() throw. CookieVault.open()
        // reports that as Unavailable and phase 2 keeps the value and retries
        // next start, so a Keystore hiccup no longer deletes the hub
        // credentials (I1). These try/catches stay as defense-in-depth for
        // anything else the scrub could still throw (e.g. a DataStore IO
        // failure from the edit {} call): uncaught, it would crash the app on
        // every single launch with no recovery short of an uninstall.
        try {
            runBlocking { SecretScrubber.scrubPlaintext(dataStore) }
        } catch (e: Exception) {
            Timber.w(e, "SecretScrubber.scrubPlaintext failed, skipping this boot's scrub")
        }
        applicationScope.launch(Dispatchers.IO) {
            try {
                SecretScrubber.scrubSealed(dataStore, SaraswatiVault.cookieVault)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "SecretScrubber.scrubSealed failed, skipping this boot's check")
            }
        }

        InnerTubeXPlayer.initialize(this)

        // SARASWATI: Phase 5 Task 11 — re-arm the nightly smart-download job (it is not persisted, so every process
        // start restores it) and stop any smart download a dead process left running. Off the startup path.
        applicationScope.launch(Dispatchers.IO) {
            try {
                val enabled = dataStore.data.first()[SmartDownloadsEnabledKey] ?: true
                SmartDownloadScheduler.ensureScheduled(this@App, enabled)
                smartDownloadExecutor.get().stopPending()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Smart downloads: could not re-arm")
            }
        }

        // Pre-read Coil cache size on background to avoid runBlocking in newImageLoader
        applicationScope.launch(Dispatchers.IO) {
            cachedCoilCacheSize = dataStore.data.map { it[MaxImageCacheSizeKey] ?: 512 }.first()
        }

        // تهيئة إعدادات التطبيق عند الإقلاع
        applicationScope.launch {
            // Apply settings, including proxy configuration, before building extraction transport.
            initializeSettings()

            // Warm player config, cipher, and optional PO-token state off the first-play path.
            launch(Dispatchers.IO) {
                delay(2500)
                var waitedMs = 0
                while (YouTube.visitorData == null && waitedMs < 12_000) {
                    delay(500)
                    waitedMs += 500
                }
                runCatching { InnerTubeXPlayer.prewarm() }
            }

            observeSettingsChanges()
        }
    }

    private suspend fun initializeSettings() {
        val settings = dataStore.data.first()
        val locale = Locale.getDefault()
        val languageTag = locale.language

        ArtistConjunctions.conjunctions = listOf(
            R.string.and,
        ).mapNotNull { id ->
            runCatching { getString(id) }.getOrNull()
        }

        YouTube.locale =
            YouTubeLocale(
                gl =
                    settings[ContentCountryKey]?.takeIf { it != SYSTEM_DEFAULT }
                        ?: locale.country.takeIf { it in CountryCodeToName }
                        ?: "US",
                hl =
                    settings[ContentLanguageKey]?.takeIf { it != SYSTEM_DEFAULT }
                        ?: locale.language.takeIf { it in LanguageCodeToName }
                        ?: languageTag.takeIf { it in LanguageCodeToName }
                        ?: "en",
            )

        if (languageTag == "zh-TW") {
            KuGou.useTraditionalChinese = true
        }

        // Initialize LastFM with API keys from BuildConfig (GitHub Secrets)
        LastFM.initialize(
            apiKey = BuildConfig.LASTFM_API_KEY.takeIf { it.isNotEmpty() } ?: "",
            secret = BuildConfig.LASTFM_SECRET.takeIf { it.isNotEmpty() } ?: "",
        )

        // SARASWATI: proxy settings are hidden (audit A15.2); guard this too
        // so a stored proxy config restored from a legacy backup is never
        // honored. Task 7 scrubs any stored proxy keys on startup.
        if (SaraswatiFeatures.PROXY && settings[ProxyEnabledKey] == true) {
            val username = settings[ProxyUsernameKey].orEmpty()
            val password = settings[ProxyPasswordKey].orEmpty()
            val type = settings[ProxyTypeKey].toEnum(defaultValue = Proxy.Type.HTTP)

            if (username.isNotEmpty() || password.isNotEmpty()) {
                if (type == Proxy.Type.HTTP) {
                    YouTube.proxyAuth = Credentials.basic(username, password)
                } else {
                    Authenticator.setDefault(
                        object : Authenticator() {
                            override fun getPasswordAuthentication(): PasswordAuthentication =
                                PasswordAuthentication(username, password.toCharArray())
                        },
                    )
                }
            }
            try {
                settings[ProxyUrlKey]?.let {
                    YouTube.proxy = Proxy(type, it.toInetSocketAddress())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@App, getString(R.string.failed_to_parse_proxy), Toast.LENGTH_SHORT).show()
                }
                reportException(e)
            }
        }

        YouTube.useLoginForBrowse = settings[UseLoginForBrowse] ?: true

        val channel =
            NotificationChannel(
                "updates",
                getString(R.string.update_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.update_channel_desc)
            }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun observeSettingsChanges() {
        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it[VisitorDataKey] }
                .distinctUntilChanged()
                .collect { visitorData ->
                    YouTube.visitorData = visitorData?.takeIf { it != "null" }
                        ?: YouTube.visitorData().getOrNull()?.also { newVisitorData ->
                            try {
                                safeDataStoreEdit { settings ->
                                    settings[VisitorDataKey] = newVisitorData
                                }
                            } catch (e: IOException) {
                                Timber.e(e, "DataStore write failed for visitor data")
                                reportException(e)
                            }
                        }
                }
        }

        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it[DataSyncIdKey] }
                .distinctUntilChanged()
                .collect { dataSyncId ->
                    YouTube.dataSyncId =
                        dataSyncId?.let {
                            it.takeIf { !it.contains("||") }
                                ?: it.takeIf { it.endsWith("||") }?.substringBefore("||")
                                ?: it.substringAfter("||")
                        }
                }
        }

        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it[InnerTubeAuthUserKey] ?: "0" }
                .distinctUntilChanged()
                .collect { authUser ->
                    YouTube.authUser = authUser
                }
        }

        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it[InnerTubeCookieKey] }
                .distinctUntilChanged()
                .collect { stored ->
                    try {
                        // SARASWATI: stored is vault-sealed; open it before handing
                        // it to YouTube.cookie. The plaintext scrub finished before this
                        // collector started, so no plaintext cookie can arrive here; the
                        // sealed check (scrubSealed) runs on IO beside it, and open() never
                        // returns a value the vault rejects, so this does not wait for it.
                        // SARASWATI (I1): an unavailable Keystore says nothing about the
                        // cookie, so it keeps the current one; only a missing or rejected
                        // value clears it. Nothing is deleted here either way.
                        when (val opened = stored?.let { SaraswatiVault.cookieVault.open(it) }) {
                            is OpenResult.Opened -> YouTube.cookie = opened.plaintext
                            OpenResult.Unavailable -> Timber.w("Keystore unavailable; keeping the current YouTube cookie")
                            OpenResult.Rejected, null -> YouTube.cookie = null
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Could not parse cookie. Clearing existing cookie.")
                        forgetAccount(this@App)
                    }
                }
        }

        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it[LastFMSessionKey] }
                .distinctUntilChanged()
                .collect { session ->
                    try {
                        LastFM.sessionKey = session
                    } catch (e: Exception) {
                        Timber.e("Error while loading last.fm session key. %s", e.message)
                    }
                }
        }

        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { Triple(it[ContentCountryKey], it[ContentLanguageKey], it[AppLanguageKey]) }
                .distinctUntilChanged()
                .collect { (contentCountry, contentLanguage, appLanguage) ->
                    val systemLocale = Locale.getDefault()
                    val effectiveAppLocale =
                        appLanguage
                            ?.takeUnless { it == SYSTEM_DEFAULT }
                            ?.let { Locale.forLanguageTag(it) }
                            ?: systemLocale

                    YouTube.locale =
                        YouTubeLocale(
                            gl =
                                contentCountry?.takeIf { it != SYSTEM_DEFAULT }
                                    ?: effectiveAppLocale.country.takeIf { it in CountryCodeToName }
                                    ?: systemLocale.country.takeIf { it in CountryCodeToName }
                                    ?: "US",
                            hl =
                                contentLanguage?.takeIf { it != SYSTEM_DEFAULT }
                                    ?: effectiveAppLocale.toLanguageTag().takeIf { it in LanguageCodeToName }
                                    ?: effectiveAppLocale.language.takeIf { it in LanguageCodeToName }
                                    ?: "en",
                        )
                }
        }
    }

    @Volatile
    private var cachedCoilCacheSize: Int? = null

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val cacheSize = cachedCoilCacheSize ?: runBlocking {
            dataStore.data.map { it[MaxImageCacheSizeKey] ?: 512 }.first()
        }
        return ImageLoader
            .Builder(this)
            .apply {
                crossfade(true)
                allowHardware(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                // Memory cache for fast image loading (prevents network requests on recomposition)
                memoryCache {
                    MemoryCache
                        .Builder()
                        .maxSizePercent(context, 0.15)
                        .build()
                }
                if (cacheSize == 0) {
                    diskCachePolicy(CachePolicy.DISABLED)
                } else {
                    diskCache(
                        DiskCache
                            .Builder()
                            .directory(cacheDir.resolve("coil"))
                            .maxSizeBytes(cacheSize * 1024 * 1024L)
                            .build(),
                    )
                    // Allow reading from disk cache as fallback when network is unavailable
                    networkCachePolicy(CachePolicy.ENABLED)
                }
            }.build()
    }

    companion object {
        suspend fun forgetAccount(context: Context) {
            Timber.d("forgetAccount: Starting logout process")

            // Clear DataStore preferences
            Timber.d("forgetAccount: Clearing DataStore preferences")
            val cleared = context.safeDataStoreEdit { settings ->
                settings.remove(InnerTubeCookieKey)
                settings.remove(VisitorDataKey)
                settings.remove(DataSyncIdKey)
                settings.remove(InnerTubeAuthUserKey)
                settings.remove(AccountNameKey)
                settings.remove(AccountEmailKey)
                settings.remove(AccountChannelHandleKey)
            }
            if (!cleared) {
                Timber.e("forgetAccount: Failed to clear DataStore preferences — proceeding with in-memory cleanup only")
            } else {
                Timber.d("forgetAccount: DataStore preferences cleared")
            }

            // Immediately clear YouTube object's auth state
            Timber.d("forgetAccount: Clearing YouTube object auth state")
            YouTube.cookie = null
            YouTube.visitorData = null
            YouTube.dataSyncId = null
            YouTube.authUser = "0"
            Timber.d("forgetAccount: YouTube object auth state cleared")

            // SARASWATI: fix wave B3 — the car's Home tab keeps the last suggestions YouTube gave this account
            // (in memory, in a file, and in a refresh that may be under way); they go with the account. After the
            // auth state is cleared above, so a refresh that starts from here on is anonymous.
            runCatching { CarHomeHolder.forget(context) }

            // Clear WebView cookies to prevent auto-relogin
            Timber.d("forgetAccount: Clearing WebView CookieManager")
            withContext(Dispatchers.Main) {
                android.webkit.CookieManager.getInstance().apply {
                    removeAllCookies { removed ->
                        Timber.d("forgetAccount: CookieManager.removeAllCookies callback: removed=$removed")
                    }
                    flush()
                }
                // SARASWATI: audit A6.8/A7.4 — the login WebView's DOM/local storage can hold a
                // second copy of the session independent of the cookie jar above; clear it too
                // on logout.
                android.webkit.WebStorage.getInstance().deleteAllData()
            }
            Timber.d("forgetAccount: Logout process complete")
        }
    }

    private fun isMainProcess(): Boolean {
        val processName =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                runCatching {
                    File("/proc/self/cmdline").readText().substringBefore('\u0000')
                }.getOrNull()?.takeIf(String::isNotBlank)
                    ?: run {
                        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                        activityManager.runningAppProcesses
                            ?.firstOrNull { it.pid == Process.myPid() }
                            ?.processName
                    }
            }
        return processName == packageName
    }
}
