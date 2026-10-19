/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 7 — what went wrong, in the terms a screen can word. A
// failure from HubClient (or from this phone's own storage) is mapped here
// once, so no screen ever branches on an HTTP status or prints an exception
// message, which could carry request detail.
package com.metrolist.music.saraswati.family

import java.io.IOException

enum class FamilyError {
    /** This phone holds no admin key (it is not the owner's, or setup was not done). */
    NO_ADMIN_KEY,

    /** The user's own input was unusable (a bad link, a blank name or key), or the hub answered 400. */
    INVALID_INPUT,

    /** 401: the hub refused the admin key or session token. */
    UNAUTHORIZED,

    /** 404: unknown device, or a claim the hub no longer holds. */
    NOT_FOUND,

    /** 410: the pairing link expired or was already used. */
    GONE,

    /** 429: too many requests; trying again in a minute works. */
    RATE_LIMITED,

    /** No connection, or the hub did not answer. */
    OFFLINE,

    /** This build has no hub (foss and izzy flavors). */
    NOT_CONFIGURED,

    /** The hub answered with a server error or something unusable. */
    SERVER,

    UNKNOWN,
    ;

    companion object {
        fun from(failure: Throwable): FamilyError =
            when (failure) {
                is HubException ->
                    when (failure.status) {
                        400 -> INVALID_INPUT
                        401 -> UNAUTHORIZED
                        404 -> NOT_FOUND
                        410 -> GONE
                        429 -> RATE_LIMITED
                        else -> SERVER
                    }
                is IOException -> OFFLINE
                is IllegalArgumentException -> INVALID_INPUT
                is IllegalStateException -> NOT_CONFIGURED
                else -> UNKNOWN
            }
    }
}
