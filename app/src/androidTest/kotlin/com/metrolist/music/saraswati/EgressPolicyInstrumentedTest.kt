package com.metrolist.music.saraswati

// SARASWATI: Task 12 instrumented proof that the deny-by-default network
// security config (app/src/main/res/xml/network_security_config.xml) is
// actually enforced on-device, not just that the XML parses the way
// security/test_egress_policy.py expects. These are pure TLS/connectivity
// checks against fixed hosts; none of them needs a signed-in YouTube
// account, so they can run on the headless emulator as well as a phone.

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException

@RunWith(AndroidJUnit4::class)
class EgressPolicyInstrumentedTest {

    private fun openConnection(url: String): HttpsURLConnection {
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        return connection
    }

    /** "allowed host connects" (brief Step 1). */
    @Test
    fun allowedHostConnects() {
        val connection = openConnection("https://music.youtube.com/")
        try {
            val code = connection.responseCode
            assertTrue(
                "music.youtube.com is in the allowlist (domain youtube.com, includeSubdomains) " +
                    "and should complete the TLS handshake and return an HTTP response, got $code",
                code in 100..599,
            )
        } finally {
            connection.disconnect()
        }
    }

    /** "blocked host fails the TLS handshake" (brief Step 1). */
    @Test
    fun blockedHostFailsTheTlsHandshake() {
        val connection = openConnection("https://example.com/")
        try {
            connection.responseCode
            fail(
                "example.com is not in the allowlist; base-config's empty <trust-anchors/> " +
                    "should have failed the TLS handshake before any response was read",
            )
        } catch (expected: SSLHandshakeException) {
            // Expected: no domain-config covers example.com, so the platform
            // TrustManager has no trust anchor for it.
        } finally {
            connection.disconnect()
        }
    }

    /** "blocked listen-together host fails" (brief Step 1). */
    @Test
    fun blockedListenTogetherHostFails() {
        val connection = openConnection("https://metroserverx.meowery.eu/")
        try {
            connection.responseCode
            fail(
                "metroserverx.meowery.eu (the Listen Together server) is deliberately not " +
                    "allowlisted in Phase 1; the TLS handshake should have failed",
            )
        } catch (expected: SSLHandshakeException) {
            // Expected: Listen Together stays network-blocked until Phase 4
            // re-audits it (docs/security-audit.md, Residual risk #3).
        } finally {
            connection.disconnect()
        }
    }
}
