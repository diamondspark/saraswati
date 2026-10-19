/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 7 — the pairing link is the one new deep link this phase
// adds, so its parser is pinned as strictly as the YouTube link gate
// (DeepLinkGuardTest): exact scheme, host, port and path; the token only in
// the fragment; nothing that could smuggle a different host past a prefix or
// userinfo check.
package com.metrolist.music.saraswati.family

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingLinkTest {
    private val hub = "https://saraswati-sangat.example.workers.dev"
    private val token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun `build puts the token in the fragment of the hub's pair page`() {
        assertEquals("$hub/pair#$token", PairingLink.build(hub, token))
        assertEquals("$hub/pair#$token", PairingLink.build("$hub/", token))
    }

    @Test
    fun `build refuses an unconfigured hub or an unusable token`() {
        assertNull(PairingLink.build("", token))
        assertNull(PairingLink.build("not a url", token))
        assertNull(PairingLink.build(hub, "short"))
        assertNull(PairingLink.build(hub, "$token#more"))
    }

    @Test
    fun `a built link parses back to its token`() {
        assertEquals(token, PairingLink.tokenFrom(PairingLink.build(hub, token)!!, hub))
    }

    @Test
    fun `host comparison ignores case`() {
        assertEquals(token, PairingLink.tokenFrom("https://SARASWATI-Sangat.Example.Workers.DEV/pair#$token", hub))
    }

    @Test
    fun `rejects any other host, including prefix and userinfo tricks`() {
        listOf(
            "https://evil.example.com/pair#$token",
            "https://saraswati-sangat.example.workers.dev.evil.com/pair#$token",
            "https://evil-saraswati-sangat.example.workers.dev/pair#$token",
            "https://saraswati-sangat.example.workers.dev@evil.com/pair#$token",
            "https://saraswati-sangat.example.workers.dev:443@evil.com/pair#$token",
            "https://user:pw@saraswati-sangat.example.workers.dev/pair#$token",
        ).forEach { assertNull(it, PairingLink.tokenFrom(it, hub)) }
    }

    @Test
    fun `rejects a plain http link and other schemes`() {
        assertNull(PairingLink.tokenFrom("http://saraswati-sangat.example.workers.dev/pair#$token", hub))
        assertNull(PairingLink.tokenFrom("saraswati://saraswati-sangat.example.workers.dev/pair#$token", hub))
        assertNull(PairingLink.tokenFrom("javascript:alert(1)//pair#$token", hub))
    }

    @Test
    fun `rejects another port`() {
        assertNull(PairingLink.tokenFrom("https://saraswati-sangat.example.workers.dev:8443/pair#$token", hub))
        assertEquals(token, PairingLink.tokenFrom("https://saraswati-sangat.example.workers.dev:443/pair#$token", hub))
    }

    @Test
    fun `rejects any path other than exactly pair`() {
        listOf("/", "/pairs", "/pair/", "/pair/x", "/x/pair", "/Pair", "/pair%2Fx").forEach {
            assertNull(it, PairingLink.tokenFrom("$hub$it#$token", hub))
        }
    }

    @Test
    fun `the token is read only from the fragment`() {
        assertNull(PairingLink.tokenFrom("$hub/pair?token=$token", hub))
        assertNull(PairingLink.tokenFrom("$hub/pair?token=$token#$token", hub))
        assertNull(PairingLink.tokenFrom("$hub/pair", hub))
        assertNull(PairingLink.tokenFrom("$hub/pair#", hub))
    }

    @Test
    fun `rejects a fragment that is not exactly a hex token`() {
        listOf(
            "abc",
            "0123456789abcdef",
            token.uppercase(),
            token + token + "0",
            "${token.dropLast(1)}g",
            "$token&x=1",
            "$token/",
            "%30${token.drop(1)}",
            "$token $token",
        ).forEach { assertNull(it, PairingLink.tokenFrom("$hub/pair#$it", hub)) }
    }

    @Test
    fun `accepts the 32 hex character token form too`() {
        val short = token.take(32)
        assertEquals(short, PairingLink.tokenFrom("$hub/pair#$short", hub))
    }

    @Test
    fun `nothing parses against an unconfigured hub`() {
        assertNull(PairingLink.tokenFrom("$hub/pair#$token", ""))
        assertNull(PairingLink.tokenFrom("$hub/pair#$token", "garbage"))
        assertNull(PairingLink.tokenFrom("", hub))
        assertNull(PairingLink.tokenFrom("garbage", hub))
    }

    @Test
    fun `input accepts a bare token with surrounding whitespace`() {
        assertEquals(token, PairingLink.tokenFromInput("  $token\n", hub))
    }

    @Test
    fun `input accepts a link on its own or inside shared text`() {
        val link = "$hub/pair#$token"
        assertEquals(token, PairingLink.tokenFromInput(link, hub))
        assertEquals(token, PairingLink.tokenFromInput("Join our family music app: $link Thanks!", hub))
    }

    @Test
    fun `input rejects anything else`() {
        assertNull(PairingLink.tokenFromInput("", hub))
        assertNull(PairingLink.tokenFromInput("hello", hub))
        assertNull(PairingLink.tokenFromInput("https://evil.example.com/pair#$token", hub))
        assertNull(PairingLink.tokenFromInput("See https://evil.example.com/pair#$token and $hub/pair", hub))
    }

    @Test
    fun `token validity is exactly lowercase hex of 32 to 128 characters`() {
        assertTrue(PairingLink.isValidToken(token))
        assertTrue(PairingLink.isValidToken(token.take(32)))
        assertFalse(PairingLink.isValidToken(token.take(31)))
        assertTrue(PairingLink.isValidToken(token + token))
        assertFalse(PairingLink.isValidToken(token + token + "0"))
        assertFalse(PairingLink.isValidToken(token.uppercase()))
        assertFalse(PairingLink.isValidToken(""))
    }
}
