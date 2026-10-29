/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Sangat push — when this phone tells the hub its FCM token, kept free of Firebase and Android so the
// decisions are unit tested. It registers when the phone is paired and Sangat is on, and the token is new (its
// SHA-256 differs from the one last registered) or the last successful registration is older than
// [REREGISTER_AFTER_MS]. The hash and time are stored only after the hub answered success. The token is never
// stored, logged or put in an error.
package com.metrolist.music.saraswati.family.sangat

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

/** What the last successful registration left behind: the SHA-256 of the token (hex) and when ([clock] milliseconds). */
data class PushRegistration(
    val tokenHash: String,
    val registeredAt: Long,
)

interface PushRegistrationStore {
    suspend fun read(): PushRegistration?

    suspend fun write(registration: PushRegistration)
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
    private val sangatEnabled: suspend () -> Boolean,
    private val fetchToken: suspend () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Outcome { NOT_CONFIGURED, NOT_PAIRED, SANGAT_OFF, NO_TOKEN, UP_TO_DATE, REGISTERED, FAILED }

    private val lock = Mutex()

    /** Registers the current token if it is new or the last registration is old. Never throws (but cancellation). */
    suspend fun ensureRegistered(): Outcome =
        lock.withLock {
            val session = session() ?: return@withLock refusal()
            val token = safely { fetchToken() } ?: return@withLock Outcome.NO_TOKEN
            val hash = sha256Hex(token)
            val last = safely { store.read() }
            if (last != null && last.tokenHash == hash && clock() - last.registeredAt in 0 until REREGISTER_AFTER_MS) {
                return@withLock Outcome.UP_TO_DATE
            }
            register(session, token, hash)
        }

    /** Firebase handed out a new token: registers it at once, whatever was registered before. */
    suspend fun onNewToken(token: String): Outcome =
        lock.withLock {
            val session = session() ?: return@withLock refusal()
            register(session, token, sha256Hex(token))
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
        const val REREGISTER_AFTER_MS = 7L * 24 * 60 * 60 * 1000

        fun sha256Hex(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
