/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeAccount
import com.metrolist.innertube.utils.parseCookieString
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.AccountChannelHandleKey
import com.metrolist.music.constants.AccountEmailKey
import com.metrolist.music.constants.AccountNameKey
import com.metrolist.music.constants.DataSyncIdKey
import com.metrolist.music.constants.InnerTubeAuthUserKey
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.VisitorDataKey
import com.metrolist.music.saraswati.security.LoginUrlPolicy
import com.metrolist.music.saraswati.security.SaraswatiVault
import com.metrolist.music.saraswati.security.WebInputs
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.reportException
import com.metrolist.music.utils.safeDataStoreEdit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import timber.log.Timber

private enum class LoginStage {
    Authenticating,
    CheckingAccounts,
    SelectingAccount,
    SwitchingAccount,
    Completing,
}

private data class AuthData(
    val cookie: String,
    val visitorData: String,
    val dataSyncId: String,
    val authUser: String,
)

// SARASWATI: Task 9 fix round 1 — the sign-in is split in two so Saral (which has no nav
// graph at all) can host it. LoginScreen is the route's entry point and only supplies the two
// ways back; LoginContent is everything else, WebView and allowlist included, unchanged.
@Composable
fun LoginScreen(
    navController: NavController,
    isSwitchingChannel: Boolean = false,
) = LoginContent(
    onBack = navController::navigateUp,
    onBackLongPress = navController::backToMain,
    isSwitchingChannel = isSwitchingChannel,
)

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginContent(
    onBack: () -> Unit,
    onBackLongPress: () -> Unit,
    isSwitchingChannel: Boolean = false,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var loginStage by remember { mutableStateOf(LoginStage.Authenticating) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var accounts by remember { mutableStateOf<List<YouTubeAccount>>(emptyList()) }
    var selectedAccount by remember { mutableStateOf<YouTubeAccount?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // SARASWATI: restoreYouTubeCookies (below) writes the stored,
    // already-sealed session cookie back into the GLOBAL WebView
    // CookieManager singleton for a channel switch, before the user has
    // confirmed anything. If the user backs out of this screen before
    // finalizeLogin() runs, that plaintext copy would otherwise sit in the
    // cookie jar indefinitely — exactly the exposure Task 7/15 exist to
    // close. finalizeLogin's own success path already clears the jar
    // before this screen leaves composition, so this is a no-op then;
    // it only matters for an abandoned login/channel-switch.
    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                clearHistory()
                clearFormData()
                clearCache(true)
            }
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }
    }

    suspend fun extractAuthData(webView: WebView?): AuthData? {
        val view = webView ?: return null
        var hasAuthCookie = false
        var hasVisitorData = false
        repeat(20) {
            val cookie = CookieManager.getInstance().getCookie("https://music.youtube.com").orEmpty()
            val cookieMap = runCatching { parseCookieString(cookie) }.getOrDefault(emptyMap())

            // SARASWATI: no JS bridge (audit A7.2/A14-5) — read the page's own config values
            // back through evaluateJavascript's own result callback instead of
            // addJavascriptInterface, which was reachable from every frame on the page.
            val pageAuthData =
                withTimeoutOrNull(1_000) {
                    coroutineScope {
                        val visitorDataAsync =
                            async {
                                view.evaluateJsOrNull(
                                    "(function(){return window.yt&&window.yt.config_?" +
                                        "window.yt.config_.VISITOR_DATA:null;})()",
                                )
                            }
                        val dataSyncIdAsync =
                            async {
                                view.evaluateJsOrNull(
                                    "(function(){return window.yt&&window.yt.config_?" +
                                        "window.yt.config_.DATASYNC_ID:null;})()",
                                )
                            }
                        val authUserAsync =
                            async {
                                view.evaluateJsOrNull(
                                    "(function(){return window.yt&&window.yt.config_?" +
                                        "String(window.yt.config_.SESSION_INDEX||0):'0';})()",
                                )
                            }
                        WebAuthData(
                            visitorData = visitorDataAsync.await(),
                            dataSyncId = dataSyncIdAsync.await(),
                            authUser = authUserAsync.await().orEmpty(),
                        )
                    }
                }
            hasAuthCookie = "SAPISID" in cookieMap
            // SARASWATI: visitor data is shape-checked before it is trusted for anything (audit
            // A8.1) — an unexpected page/response can at most make login retry, never hand a
            // malformed value downstream.
            val visitorData = pageAuthData?.visitorData?.takeIf(WebInputs::isValidVisitorData)
            hasVisitorData = visitorData != null
            if (hasAuthCookie && pageAuthData != null && visitorData != null) {
                return AuthData(
                    cookie = cookie,
                    visitorData = visitorData,
                    dataSyncId = pageAuthData.dataSyncId.orEmpty().substringBefore("||"),
                    authUser = pageAuthData.authUser.filter(Char::isDigit).ifBlank { "0" },
                )
            }
            delay(500)
        }

        Timber.w(
            "Login: Timed out waiting for the YouTube Music session " +
                "(authCookie=$hasAuthCookie, visitorData=$hasVisitorData)",
        )
        return null
    }

    suspend fun finalizeLogin(authData: AuthData) {
        loginStage = LoginStage.Completing
        YouTube.cookie = authData.cookie
        YouTube.visitorData = authData.visitorData
        YouTube.dataSyncId = authData.dataSyncId
        YouTube.authUser = authData.authUser

        try {
            val accountInfo = YouTube.accountInfo().getOrThrow()
            val saved =
                withContext(Dispatchers.IO) {
                    context.safeDataStoreEdit { settings ->
                        // SARASWATI: seal the sign-in cookie before it ever touches disk.
                        settings[InnerTubeCookieKey] = SaraswatiVault.cookieVault.seal(authData.cookie)
                        settings[VisitorDataKey] = authData.visitorData
                        settings[DataSyncIdKey] = authData.dataSyncId
                        settings[InnerTubeAuthUserKey] = authData.authUser
                        settings[AccountNameKey] = accountInfo.name
                        settings[AccountEmailKey] = accountInfo.email.orEmpty()
                        settings[AccountChannelHandleKey] = accountInfo.channelHandle.orEmpty()
                    }
                }
            check(saved) { "Failed to persist account data" }

            // SARASWATI: the Google/YouTube session cookie is now sealed and persisted — only
            // past this point is it safe to strip the WebView's own plaintext copy (audit A7.4).
            // Order matters: clearing any earlier would risk wiping the cookie before it was
            // captured and saved above. This also closes every channel-switch path (A7.5), since
            // every one of them (initial login, default-account login, explicit account switch)
            // ends by calling finalizeLogin.
            webViewRef?.apply {
                stopLoading()
                clearHistory()
                clearFormData()
            }
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            webViewRef?.clearCache(true)

            withContext(Dispatchers.Main) {
                context.packageManager
                    .getLaunchIntentForPackage(context.packageName)
                    ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
                    ?.let(context::startActivity)
                Runtime.getRuntime().exit(0)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (throwable: Exception) {
            Timber.e(throwable, "Login: Failed to validate or save the selected YouTube channel")
            reportException(throwable)
            errorMessage =
                context.getString(
                    R.string.login_failed_with_error,
                    throwable.message ?: context.getString(R.string.error_unknown),
                )
            loginStage = LoginStage.Authenticating
        }
    }

    suspend fun checkAvailableAccounts(authData: AuthData) {
        loginStage = LoginStage.CheckingAccounts
        YouTube.cookie = authData.cookie
        YouTube.visitorData = authData.visitorData
        YouTube.dataSyncId = authData.dataSyncId
        YouTube.authUser = authData.authUser

        val availableAccounts =
            YouTube.accountsList().getOrElse { throwable ->
                Timber.e(throwable, "Login: Failed to retrieve YouTube channels")
                if (isSwitchingChannel) {
                    errorMessage = context.getString(R.string.youtube_channels_failed)
                    loginStage = LoginStage.Authenticating
                    return
                }
                emptyList()
            }

        if (isSwitchingChannel && availableAccounts.isEmpty()) {
            errorMessage = context.getString(R.string.youtube_channels_failed)
            loginStage = LoginStage.Authenticating
        } else if (availableAccounts.size > 1) {
            val currentAccount =
                availableAccounts.firstOrNull { account ->
                    account.pageId == authData.dataSyncId ||
                        account.dataSyncId?.substringBefore("||") == authData.dataSyncId
                } ?: availableAccounts.firstOrNull { it.isSelected }
            accounts = availableAccounts.map { it.copy(isSelected = it == currentAccount) }
            selectedAccount = accounts.firstOrNull { it.isSelected } ?: accounts.first()
            loginStage = LoginStage.SelectingAccount
        } else {
            finalizeLogin(authData)
        }
    }

    fun handleAuthenticatedPage() {
        when (loginStage) {
            LoginStage.Authenticating,
            LoginStage.SwitchingAccount,
            -> {
                val stage = loginStage
                loginStage = LoginStage.CheckingAccounts
                coroutineScope.launch {
                    val authData =
                        extractAuthData(webViewRef) ?: run {
                            errorMessage = context.getString(R.string.authentication_data_extraction_failed)
                            loginStage = stage
                            return@launch
                        }
                    if (stage == LoginStage.SwitchingAccount) {
                        val selectedDataSyncId = selectedAccount?.dataSyncId?.substringBefore("||")
                        finalizeLogin(
                            authData.copy(
                                dataSyncId = selectedDataSyncId?.takeIf(String::isNotBlank) ?: authData.dataSyncId,
                            ),
                        )
                    } else {
                        checkAvailableAccounts(authData)
                    }
                }
            }

            else -> Unit
        }
    }

    Box(
        modifier =
            Modifier
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                .fillMaxSize(),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { webViewContext ->
                WebView(webViewContext).apply {
                    webViewClient =
                        object : WebViewClient() {
                            // SARASWATI: exact-host, https-only allowlist (audit A7.1) — this
                            // WebView is where a family member types their Google password, so
                            // navigation anywhere else is blocked rather than merely logged. The
                            // `intent:` scheme used to be allowed to launch another app's
                            // activity straight off the login page with no tap or prompt (audit
                            // A7.3); there is no such carve-out any more — every non-allowlisted
                            // URL, including `intent:`, is simply blocked.
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                val url = request.url
                                if (LoginUrlPolicy.isAllowedNavigation(url.toString())) return false
                                Timber.w("LoginUrlPolicy blocked host=%s", url.host)
                                return true
                            }

                            override fun onPageFinished(
                                view: WebView,
                                url: String?,
                            ) {
                                val pageUri = url?.let(Uri::parse)
                                if (pageUri?.scheme == "https" && pageUri.host == "music.youtube.com") {
                                    handleAuthenticatedPage()
                                }
                            }
                        }
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        // SARASWATI: audit A8.3 — the sign-in page never needs to resolve a
                        // content:// URI.
                        allowContentAccess = false
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                    }
                    webViewRef = this
                    if (isSwitchingChannel) {
                        restoreYouTubeCookies(YouTube.cookie)
                    }
                    loadUrl(
                        if (isSwitchingChannel) {
                            "https://music.youtube.com"
                        } else {
                            "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"
                        },
                    )
                }
            },
        )

        if (loginStage == LoginStage.CheckingAccounts || loginStage == LoginStage.Completing) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }

        TopAppBar(
            title = {
                Text(
                    stringResource(
                        if (isSwitchingChannel) R.string.switch_youtube_channel else R.string.login,
                    ),
                )
            },
            navigationIcon = {
                IconButton(
                    onClick = onBack,
                    onLongClick = onBackLongPress,
                ) {
                    Icon(
                        painterResource(R.drawable.arrow_back),
                        contentDescription = null,
                    )
                }
            },
        )
    }

    if (loginStage == LoginStage.SelectingAccount) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.choose_youtube_channel)) },
            text = {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp),
                ) {
                    Text(
                        text = stringResource(R.string.choose_youtube_channel_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                    ) {
                        accounts.forEach { account ->
                            YouTubeAccountPickerItem(
                                account = account,
                                isSelected = account == selectedAccount,
                                onClick = { selectedAccount = account },
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = selectedAccount?.let { it.isSelected || it.signinUrl != null } == true,
                    onClick = {
                        val account = selectedAccount ?: return@Button
                        if (account.isSelected) {
                            loginStage = LoginStage.Completing
                            coroutineScope.launch {
                                val authData =
                                    extractAuthData(webViewRef) ?: run {
                                        errorMessage = context.getString(R.string.authentication_data_extraction_failed)
                                        loginStage = LoginStage.Authenticating
                                        return@launch
                                    }
                                finalizeLogin(authData)
                            }
                        } else {
                            val signinUrl = account.signinUrl ?: return@Button
                            val absoluteUrl =
                                when {
                                    signinUrl.startsWith("http://") || signinUrl.startsWith("https://") -> signinUrl
                                    signinUrl.startsWith("/") -> "https://music.youtube.com$signinUrl"
                                    else -> "https://music.youtube.com/$signinUrl"
                                }
                            // SARASWATI: audit A7.1 — this URL comes from the YouTube accounts
                            // list response, not a hardcoded host, so it is checked against the
                            // same allowlist as every other navigation before it is ever loaded.
                            if (!LoginUrlPolicy.isAllowedNavigation(absoluteUrl)) {
                                Timber.w(
                                    "LoginUrlPolicy blocked host=%s",
                                    runCatching { Uri.parse(absoluteUrl).host }.getOrNull(),
                                )
                                errorMessage = context.getString(R.string.youtube_channels_failed)
                                return@Button
                            }
                            loginStage = LoginStage.SwitchingAccount
                            webViewRef?.loadUrl(absoluteUrl)
                        }
                    },
                ) {
                    Text(stringResource(R.string.continue_action))
                }
            },
        )
    }

    errorMessage?.let { error ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text(stringResource(R.string.login_failed)) },
            text = { Text(error) },
            confirmButton = {
                Button(
                    onClick = {
                        errorMessage = null
                        webViewRef?.reload()
                    },
                ) {
                    Text(stringResource(R.string.retry))
                }
            },
        )
    }

    BackHandler(
        enabled = webViewRef?.canGoBack() == true && loginStage == LoginStage.Authenticating && errorMessage == null,
    ) {
        webViewRef?.goBack()
    }
}

@Composable
private fun YouTubeAccountPickerItem(
    account: YouTubeAccount,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (account.thumbnailUrl != null) {
                AsyncImage(
                    model = account.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape),
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.account),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = account.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                account.channelHandle?.let { channelHandle ->
                    Text(
                        text = channelHandle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                account.byline?.let { byline ->
                    Text(
                        text = byline,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            RadioButton(selected = isSelected, onClick = null)
        }
    }
}

private data class WebAuthData(
    val visitorData: String?,
    val dataSyncId: String?,
    val authUser: String,
)

// SARASWATI: replaces the removed `addJavascriptInterface` bridge (audit A7.2/A14-5) — reads a
// value straight out of `evaluateJavascript`'s own result callback instead of exposing a
// `@JavascriptInterface` object the page (or any iframe on it) could call directly. [script] is
// always one of this file's own static literals, never built from page or server data, so no
// splicing/escaping is needed here.
private suspend fun WebView.evaluateJsOrNull(script: String): String? =
    suspendCancellableCoroutine { cont ->
        evaluateJavascript(script) { rawResult ->
            if (cont.isActive) {
                val value =
                    rawResult
                        ?.takeIf { it.isNotBlank() && it != "null" }
                        ?.let { runCatching { JSONTokener(it).nextValue() as? String }.getOrNull() }
                cont.resumeWith(Result.success(value))
            }
        }
    }

private fun restoreYouTubeCookies(cookie: String?) {
    val cookieManager = CookieManager.getInstance()
    cookieManager.setAcceptCookie(true)
    cookie
        ?.let(::parseCookieString)
        .orEmpty()
        .forEach { (name, value) ->
            cookieManager.setCookie(
                "https://music.youtube.com",
                "$name=$value; Domain=.youtube.com; Path=/; Secure",
            )
        }
    cookieManager.flush()
}
