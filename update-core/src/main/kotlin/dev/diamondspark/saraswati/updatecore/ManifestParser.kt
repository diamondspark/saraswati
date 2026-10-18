/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 4 — strict parsing of the update manifest. "Strict" here
// means: unknown schema versions are rejected rather than best-effort
// read, the APK URL must be a literal match for our own release prefix
// (never just "looks like GitHub"), the hash must be a well-formed sha256
// and nothing else, and the declared size can never exceed what
// DownloadPolicy is willing to fetch. A manifest that fails any of these is
// exactly as dangerous as a manifest that was never signed in the first
// place, because every one of these fields ends up driving a download and
// an install on a device that also holds banking apps.
package dev.diamondspark.saraswati.updatecore

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private const val SUPPORTED_SCHEMA = 1
private const val MAX_NOTES_LENGTH = 1000
private val ALLOWED_CHANNELS = setOf("stable", "beta")
private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

object ManifestParser {

    private val jsonFormat = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    fun parse(json: String): Result<UpdateManifest> {
        val manifest = try {
            jsonFormat.decodeFromString<UpdateManifest>(json)
        } catch (e: SerializationException) {
            return Result.failure(IllegalArgumentException("malformed manifest json: ${e.message}"))
        } catch (e: IllegalArgumentException) {
            return Result.failure(IllegalArgumentException("malformed manifest json: ${e.message}"))
        }

        validate(manifest)?.let { reason ->
            return Result.failure(IllegalArgumentException(reason))
        }
        return Result.success(manifest)
    }

    private fun validate(manifest: UpdateManifest): String? {
        if (manifest.schema != SUPPORTED_SCHEMA) {
            return "unsupported schema ${manifest.schema}"
        }
        if (manifest.channel !in ALLOWED_CHANNELS) {
            return "unknown channel '${manifest.channel}'"
        }
        if (manifest.versionCode <= 0) {
            return "invalid versionCode ${manifest.versionCode}"
        }
        if (manifest.notes.length > MAX_NOTES_LENGTH) {
            return "notes exceed $MAX_NOTES_LENGTH chars"
        }
        validateApkAsset(manifest.apk)?.let { return it }
        manifest.updater?.let { updater ->
            if (updater.versionCode <= 0) {
                return "invalid updater versionCode ${updater.versionCode}"
            }
            validateApkAsset(updater.apk)?.let { return it }
        }
        return null
    }

    private fun validateApkAsset(apk: ApkAsset): String? {
        if (!apk.url.startsWith(RELEASE_URL_PREFIX)) {
            return "apk url outside the release prefix"
        }
        if (!SHA256_HEX.matches(apk.sha256)) {
            return "apk sha256 is not 64 lowercase hex chars"
        }
        if (apk.size <= 0L || apk.size > DownloadPolicy.MAX_APK_BYTES) {
            return "apk size out of bounds"
        }
        return null
    }
}
