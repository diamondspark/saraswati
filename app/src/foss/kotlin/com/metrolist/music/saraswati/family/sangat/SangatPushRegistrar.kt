// SARASWATI: Sangat push — this flavor has no Firebase, so there is nothing to register and nothing to receive.
// The gms flavor has the same-named class that does the work.
package com.metrolist.music.saraswati.family.sangat

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SangatPushRegistrar
    @Inject
    constructor() {
        suspend fun ensureRegistered() = Unit

        fun ensureRegisteredAsync() = Unit
    }
