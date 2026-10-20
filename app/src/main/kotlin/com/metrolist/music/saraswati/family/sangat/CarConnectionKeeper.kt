/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 fix wave (Task 15 review, Important 3) — a Sangat hosted from the car is synced through a
// player connection. The app screen normally provides one, but it binds and unbinds with the screen. This keeps a
// service-made connection for exactly as long as the phone hosts a room with no screen bound, and hands it back
// (disposed) the moment it is not needed, so its listeners cannot leak.
package com.metrolist.music.saraswati.family.sangat

class CarConnectionKeeper<C : Any>(
    private val dispose: (C) -> Unit,
) {
    private var create: (() -> C)? = null
    private var car: C? = null

    /** Null forgets the factory (the service that owned it is gone). */
    fun register(create: (() -> C)?) {
        this.create = create
    }

    fun isCar(connection: C?): Boolean = connection != null && connection === car

    /**
     * The connection the manager should hold: the app's if there is one, else the car's while [hosting], else none.
     * Creates the car's but never disposes anything; [settle] does that once the manager has switched over.
     */
    fun resolve(
        app: C?,
        hosting: Boolean,
    ): C? {
        if (app != null) return app
        if (!hosting) return null
        val factory = create ?: return null
        return car ?: factory().also { car = it }
    }

    /** Disposes the car's connection unless [held] is it. */
    fun settle(held: C?) {
        val old = car ?: return
        if (old === held) return
        car = null
        dispose(old)
    }
}
