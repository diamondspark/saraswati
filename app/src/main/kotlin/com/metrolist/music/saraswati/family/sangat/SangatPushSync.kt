/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Sangat push — when this phone tells the hub its FCM token, kept free of Firebase and Android so the
// decisions are unit tested. It registers when the phone is paired and Sangat is on, and the registration is new (the
// SHA-256 of this phone's hub device id and the token differs from the one last registered, so a re-pair registers
// again at once) or the last successful registration is older than
// [REREGISTER_AFTER_MS] (a day, so a token the hub dropped by pruning or a restore is back within a day). The hash and
// time are stored only after the hub answered success. The token is never stored, logged or put in an error. A Saral
// phone never registers (it ignores every push) and, if it had registered before, tells the hub to forget its token once.
package com.metrolist.music.saraswati.family.sangat

import com.metrolist.music.saraswati.family.FamilyFeature
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

/** What the last successful registration left behind: the SHA-256 of device id and token (hex) and when ([clock] milliseconds). */
data class PushRegistration(
    val tokenHash: String,
    val registeredAt: Long,
)

interface PushRegistrationStore {
    suspend fun read(): PushRegistration?

    suspend fun write(registration: PushRegistration)

    suspend fun clear()
}

/** Whether this build may use Firebase at all: every id filled in, and one of the two family application ids. */
object SangatPushConfig {
    val APPLICATION_IDS = setOf("dev.diamondspark.saraswati", "dev.diamondspark.saraswati.debug")

    fun isConfigured(
        projectId: String,
        senderId: String,
        apiKey: String,
        appId: String,
        applicationId: String,
    ): Boolean = listOf(projectId, senderId, apiKey, appId).all { it.isNotBlank() } && applicationId in APPLICATION_IDS
}

class SangatPushSync(
    private val configured: Boolean,
    private val hub: PushHubApi,
    private val store: PushRegistrationStore,
    private val sessionToken: suspend () -> String?,
    private val deviceId: suspend () -> String?,
    private val sangatEnabled: suspend () -> Boolean,
    private val fetchToken: suspend () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val isSaral: () -> Boolean = { false },
) {
    enum class Outcome { NOT_CONFIGURED, SARAL, NOT_PAIRED, SANGAT_OFF, NO_TOKEN, UP_TO_DATE, REGISTERED, FAILED }

    private val lock = Mutex()

    /** Registers the current token if it is new or the last registration is old. Never throws (but cancellation). */
    suspend fun ensureRegistered(): Outcome =
        lock.withLock {
            saralRefusal()?.let { return@withLock it }
            val session = session() ?: return@withLock refusal()
            val token = safely { fetchToken() } ?: return@withLock Outcome.NO_TOKEN
            val hash = fingerprint(token)
            val last = safely { store.read() }
            if (last != null && last.tokenHash == hash && clock() - last.registeredAt in 0 until REREGISTER_AFTER_MS) {
                return@withLock Outcome.UP_TO_DATE
            }
            register(session, token, hash)
        }

    /** Firebase handed out a new token: registers it at once, whatever was registered before. */
    suspend fun onNewToken(token: String): Outcome =
        lock.withLock {
            saralRefusal()?.let { return@withLock it }
            val session = session() ?: return@withLock refusal()
            register(session, token, fingerprint(token))
        }

    // SARASWATI: Sangat push — a Saral phone spends none of the family push budget. If it had registered before it was
    // switched to Saral, the hub is told to forget the token and the stored fingerprint goes; a failed call leaves the
    // fingerprint, so the next try asks again. With nothing stored there is no call at all.
    private suspend fun saralRefusal(): Outcome? {
        if (!configured || !isSaral()) return null
        if (safely { store.read() } != null) {
            val session = safely { sessionToken() }
            if (session != null && safely { hub.unregisterPushToken(session) }?.isSuccess == true) safely { store.clear() }
        }
        return Outcome.SARAL
    }

    private suspend fun register(
        session: String,
        token: String,
        hash: String,
    ): Outcome {
        val result = safely { hub.registerPushToken(session, token) } ?: return Outcome.FAILED
        if (result.isFailure) return Outcome.FAILED
        safely { store.write(PushRegistration(hash, clock())) }
        return Outcome.REGISTERED
    }

    // SARASWATI: Sangat push — the hub keeps a token per device, so what was registered is the pair, not the token alone.
    private suspend fun fingerprint(token: String): String = fingerprintOf(safely { deviceId() }, token)

    // The session token when every condition holds; otherwise null, with [refusal] saying which one failed.
    private var refused = Outcome.NOT_CONFIGURED

    private suspend fun session(): String? {
        if (!configured) return null.also { refused = Outcome.NOT_CONFIGURED }
        val session = safely { sessionToken() } ?: return null.also { refused = Outcome.NOT_PAIRED }
        // A gate that cannot answer is not "off": fail open, like the rest of the family features.
        if (safely { sangatEnabled() } == false) return null.also { refused = Outcome.SANGAT_OFF }
        return session
    }

    private fun refusal() = refused

    private suspend fun <T> safely(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    companion object {
        const val REREGISTER_AFTER_MS = 24L * 60 * 60 * 1000

        /** SHA-256 (hex) of `deviceId + "\n" + token`; a phone with no device id yet hashes an empty one. */
        fun fingerprintOf(
            deviceId: String?,
            token: String,
        ): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest("${deviceId.orEmpty()}\n$token".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /** Emits each time Sangat goes from off to on in [enabled] (the feature gate's state), not for the value it starts at. */
        fun sangatTurnedOn(enabled: Flow<Map<FamilyFeature, Boolean>>): Flow<Unit> =
            enabled
                .map { it[FamilyFeature.SANGAT] ?: true }
                .distinctUntilChanged()
                .drop(1)
                .filter { it }
                .map { }
    }
}
