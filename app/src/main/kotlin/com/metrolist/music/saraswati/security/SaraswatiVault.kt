/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: process-wide lazy singleton holding the Keystore-backed
// CookieVault (Ruling R6 — the plan left open how DataStore.kt's
// Context-extension helpers would obtain a vault instance without
// threading a Context or a DI graph through every call site). Built
// lazily on first use so touching this object before the Keystore is
// ready (e.g. in a JVM unit test that never calls into it) has no cost
// and no side effect.
package com.metrolist.music.saraswati.security

object SaraswatiVault {
    val cookieVault: CookieVault by lazy { CookieVault(AndroidKeystoreKeyProvider()) }
}
