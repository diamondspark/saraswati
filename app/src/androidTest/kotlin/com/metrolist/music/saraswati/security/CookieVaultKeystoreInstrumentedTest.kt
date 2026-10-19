/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.saraswati.security

// SARASWATI (C1 regression): a real Android Keystore key defaults to
// setRandomizedEncryptionRequired(true) and REJECTS a caller-supplied IV
// with InvalidAlgorithmParameterException("Caller-provided IV not
// permitted") when you call cipher.init(ENCRYPT_MODE, key,
// GCMParameterSpec(...)). CookieVault.seal() used to always build its own
// IV and pass it to cipher.init() that way — which SoftwareKeyProvider
// (plain JCE, used by every unit test before this fix) happily accepts, but
// a real Keystore key does not. On a real device that meant sign-in could
// never actually save the cookie: every login attempt would throw on the
// very first seal() call. This instrumented test exercises the real
// AndroidKeystoreKeyProvider (via SaraswatiVault.cookieVault, exactly what
// production code uses) on-device instead of SoftwareKeyProvider — this is
// the test that would have caught C1.
//
// It needs no sign-in and no network: seal()/open() are pure local
// Keystore operations, so this runs on the headless emulator the same way
// EgressPolicyInstrumentedTest does.

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CookieVaultKeystoreInstrumentedTest {
    @Test
    fun sealThenOpenRoundTripsThroughTheRealAndroidKeystore() {
        val cookie = "SAPISID=abc123; HSID=def456; __Secure-3PSID=ghi789"

        // If seal() still passed a caller-supplied IV to a real Keystore
        // key, this line would throw InvalidAlgorithmParameterException —
        // that is exactly the C1 failure mode, now fixed by letting the
        // Keystore generate its own IV and reading it back via cipher.iv.
        val sealed = SaraswatiVault.cookieVault.seal(cookie)
        val opened = SaraswatiVault.cookieVault.open(sealed)

        assertEquals("round trip through the real Keystore must return the original cookie", OpenResult.Opened(cookie), opened)
        assertFalse("sealed text must not contain the plaintext cookie", sealed.contains(cookie))
    }

    @Test
    fun sealingTheSamePlaintextTwiceProducesDifferentCiphertext() {
        // Catches a regression back to any form of fixed/reused IV: AES-GCM
        // with the same key and the same IV for two messages is broken, and
        // "the IV must be fresh every time" is exactly what Android
        // Keystore's "Caller-provided IV not permitted" default enforces.
        // Two seals of the same plaintext must differ because the IV the
        // Keystore generates differs each time.
        val cookie = "SAPISID=abc123; HSID=def456"

        val first = SaraswatiVault.cookieVault.seal(cookie)
        val second = SaraswatiVault.cookieVault.seal(cookie)

        assertNotEquals("two seals of the same plaintext must not be byte-identical", first, second)
        assertEquals(OpenResult.Opened(cookie), SaraswatiVault.cookieVault.open(first))
        assertEquals(OpenResult.Opened(cookie), SaraswatiVault.cookieVault.open(second))
    }
}
