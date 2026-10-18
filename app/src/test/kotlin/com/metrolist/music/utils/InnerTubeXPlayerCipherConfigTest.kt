package com.metrolist.music.utils

// SARASWATI: regression test for audit A12-b (High). InnerTubeX's remote
// cipher-code channel downloads an unsigned JS program per player and runs
// it in the app's QuickJS runtime; this must never be re-enabled silently.
// Fails if AndroidPlayerConfigRepository.enabled ever reads true again.
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class InnerTubeXPlayerCipherConfigTest {
    @Test
    fun `remote player config repository is disabled`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val repository = InnerTubeXPlayer.AndroidPlayerConfigRepository(context)

        assertFalse(
            "AndroidPlayerConfigRepository.enabled must stay false (audit A12-b): " +
                "when true it fetches an unsigned JS cipher program over the network " +
                "and executes it in-process, bypassing this fork's egress policy.",
            repository.enabled,
        )
    }
}
