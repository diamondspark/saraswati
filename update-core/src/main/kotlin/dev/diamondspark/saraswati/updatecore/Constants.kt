/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 4 — the two hosts an update ever legitimately comes from.
// Every acceptance check in this module (manifest parsing, download
// policy) is anchored to one of these literal strings, never to a parsed
// or substring-matched fragment of a URL.
package dev.diamondspark.saraswati.updatecore

/**
 * Every APK this module will ever accept must be served from a GitHub
 * Release asset under this exact repository. Checked as a literal prefix
 * of the full URL (scheme + host + path), which also makes it immune to
 * authority-confusion tricks such as `https://github.com@evil.example/...`:
 * that string does not start with this prefix, because the character right
 * after `github.com` would have to be `/`, not `@`.
 */
const val RELEASE_URL_PREFIX: String = "https://github.com/diamondspark/saraswati/releases/download/"

/** Where the Saraswati Updates installer app (Task 5) fetches the manifest itself. */
const val MANIFEST_BASE_URL: String = "https://diamondspark.github.io/saraswati/"
