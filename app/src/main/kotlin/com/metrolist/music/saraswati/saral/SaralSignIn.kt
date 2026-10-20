/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 9 fix round 1 — the one other thing Saral hosts: the app's
// existing Google sign-in, for a phone that is signed out of YouTube. It is
// LoginContent, the WebView and its Google-only allowlist exactly as the
// full app's sign-in uses them, composed here as plain content. No nav graph,
// no route: back (the system gesture, the arrow, even a long-press on the
// arrow) goes to the tiles and nowhere else, and incoming intents stay
// dropped by MainActivity as they are for all of Saral. A finished sign-in
// restarts the app (LoginContent's own doing), which comes back to the tiles,
// now signed in.
//
// Fix wave B2: the sheet reports when it is saving the account (onFinishing), and Saral does
// not close it for the signed-in flip while it is: the cookie is written before the sheet
// has cleared its own copy and restarted the app.
package com.metrolist.music.saraswati.saral

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.constants.AppBarHeight
import com.metrolist.music.ui.screens.LoginContent

@Composable
internal fun SaralSignIn(
    onClose: () -> Unit,
    onFinishing: (Boolean) -> Unit,
) {
    // Registered before the login's own handler, so that one wins while the sign-in page can go back a page.
    BackHandler(onBack = onClose)
    CompositionLocalProvider(
        LocalPlayerAwareWindowInsets provides WindowInsets.systemBars.add(WindowInsets(top = AppBarHeight)),
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            LoginContent(onBack = onClose, onBackLongPress = onClose, onCompleting = onFinishing)
        }
    }
}
