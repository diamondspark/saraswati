// SARASWATI: Sangat push (gms flavor). Owns Firebase for this app: it is initialised here, by hand, from the ids in
// BuildConfig (app/firebase.properties), and only when every id is filled in and the application id is one of the
// family's own; otherwise nothing here touches Firebase. The decisions (paired, Sangat on, token new or a week old)
// are SangatPushSync's. Failures are silent: the tag is logged, never the token.
package com.metrolist.music.saraswati.family.sangat

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.metrolist.music.BuildConfig
import com.metrolist.music.constants.SangatPushRegisteredAtKey
import com.metrolist.music.constants.SangatPushDigestKey
import com.metrolist.music.saraswati.family.FamilyFeature
import com.metrolist.music.saraswati.family.FamilyFeatureGate
import com.metrolist.music.saraswati.family.HubIdentity
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class SangatPushRegistrar
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        hub: PushHubApi,
        identity: HubIdentity,
        private val gate: FamilyFeatureGate,
    ) {
        val isConfigured: Boolean =
            SangatPushConfig.isConfigured(
                BuildConfig.FIREBASE_PROJECT_ID,
                BuildConfig.FIREBASE_SENDER_ID,
                BuildConfig.FIREBASE_API_KEY,
                BuildConfig.FIREBASE_APP_ID,
                BuildConfig.APPLICATION_ID,
            )

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val sync =
            SangatPushSync(
                configured = isConfigured,
                hub = hub,
                store = DataStorePushRegistrationStore(context),
                sessionToken = identity::sessionToken,
                sangatEnabled = {
                    gate.awaitHydrated()
                    gate.isEnabled(FamilyFeature.SANGAT)
                },
                fetchToken = ::fetchToken,
            )

        /** Registers this phone's token with the hub if it is paired, Sangat is on, and the token is new or a week old. */
        suspend fun ensureRegistered() {
            if (!isConfigured) return
            log(withContext(Dispatchers.IO) { sync.ensureRegistered() })
        }

        /** [ensureRegistered] on a scope of its own, for callers (a screen's view model) that may be gone before it finishes. */
        fun ensureRegisteredAsync() {
            if (!isConfigured) return
            scope.launch { ensureRegistered() }
        }

        /** Firebase issued a new token: registers it at once. */
        suspend fun onNewToken(token: String) {
            if (!isConfigured) return
            log(withContext(Dispatchers.IO) { sync.onNewToken(token) })
        }

        private suspend fun fetchToken(): String? {
            if (firebaseApp() == null) return null
            return try {
                withTimeoutOrNull(TOKEN_TIMEOUT_MS) { FirebaseMessaging.getInstance().token.await() }?.takeIf { it.isNotBlank() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

        @Synchronized
        private fun firebaseApp(): FirebaseApp? =
            try {
                FirebaseApp.getApps(context).firstOrNull()
                    ?: FirebaseApp.initializeApp(
                        context,
                        FirebaseOptions
                            .Builder()
                            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                            .setApiKey(BuildConfig.FIREBASE_API_KEY)
                            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                            .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                            .build(),
                    )
            } catch (e: Exception) {
                null
            }

        private fun log(outcome: SangatPushSync.Outcome) {
            Timber.tag(TAG).d("push registration: %s", outcome)
        }

        private companion object {
            const val TAG = "SangatPush"
            const val TOKEN_TIMEOUT_MS = 20_000L
        }
    }

private class DataStorePushRegistrationStore(
    context: Context,
) : PushRegistrationStore {
    private val dataStore = context.dataStore

    override suspend fun read(): PushRegistration? {
        val prefs = dataStore.data.first()
        val hash = prefs[SangatPushDigestKey] ?: return null
        val at = prefs[SangatPushRegisteredAtKey] ?: return null
        return PushRegistration(hash, at)
    }

    override suspend fun write(registration: PushRegistration) {
        dataStore.edit {
            it[SangatPushDigestKey] = registration.tokenHash
            it[SangatPushRegisteredAtKey] = registration.registeredAt
        }
    }
}
