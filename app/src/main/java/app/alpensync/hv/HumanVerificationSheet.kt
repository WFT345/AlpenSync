// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// The WebView flow mirrors protoncore (GPL-3.0) human-verification/
// presentation/.../ui/hv3/HV3DialogFragment.kt (setupWebView ~line 133):
// javaScriptEnabled + domStorageEnabled, the `AndroidInterface` JS bridge
// name, and a WebViewClient confining navigation to the challenge host.
// Deviations: navigation OFF verify.proton.me is blocked outright (the
// embedded page never navigates away), and no extra headers are sent —
// protoncore's are for its DoH/account infra we don't have.

package app.alpensync.hv

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.alpensync.R
import app.alpensync.ui.theme.AlpenBg
import app.alpensync.ui.theme.AlpenFg
import app.alpensync.ui.theme.AlpenIce

/**
 * The in-app human-verification sheet (ADR 0004 Q3): a near-fullscreen
 * dialog hosting Proton's verify.proton.me challenge page. On a solved
 * challenge [onSuccess] receives the token + tokenType to persist; closing
 * the dialog (or a main-frame load error) reports [onCancel], whose caller
 * falls back to the manual-instructions error state.
 *
 * Secrets discipline (Rule 1): the challenge URL contains the server-issued
 * start token; it is never logged and never leaves this composable except
 * into the WebView itself.
 */
@Composable
internal fun HumanVerificationSheet(
    startToken: String,
    methods: List<String>,
    onSuccess: (token: String, tokenType: String) -> Unit,
    onCancel: () -> Unit,
) {
    val url = remember(startToken, methods) {
        buildHumanVerificationUrl(startToken, methods, darkTheme = true)
    }
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AlpenBg)
                .padding(16.dp),
        ) {
            HvChrome(onCancel)
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context -> createChallengeWebView(context, url, onSuccess, onCancel) },
                onRelease = { webView -> webView.destroy() },
            )
        }
    }
}

@Composable
private fun HvChrome(onCancel: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.hv_title),
            color = AlpenFg,
            style = MaterialTheme.typography.titleMedium,
        )
        TextButton(onClick = onCancel) {
            Text(
                text = stringResource(R.string.hv_cancel),
                color = AlpenIce,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * Hardened WebView for the challenge page: JS + DOM storage on (the page
 * requires both), file/content access off, mixed content off, navigation
 * confined to [VERIFY_HOST]. [onMainFrameFailure] maps to cancel.
 */
@SuppressLint("SetJavaScriptEnabled") // required by the challenge flow
private fun createChallengeWebView(
    context: android.content.Context,
    url: String,
    onSuccess: (String, String) -> Unit,
    onMainFrameFailure: () -> Unit,
): WebView = WebView(context).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    webViewClient = ChallengeWebViewClient(onMainFrameFailure)
    addJavascriptInterface(HV3Bridge(onSuccess), JS_INTERFACE_NAME)
    loadUrl(url)
}

private const val JS_INTERFACE_NAME = "AndroidInterface"

/** Blocks every navigation away from the verify.proton.me host. */
private class ChallengeWebViewClient(
    private val onMainFrameFailure: () -> Unit,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        return !(url.scheme == "https" && url.host == VERIFY_HOST)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) onMainFrameFailure()
    }
}

/**
 * JS bridge the challenge page calls as `AndroidInterface.dispatch(json)`.
 * Parsing is fail-closed: notifications, resize/loaded pings, and garbage
 * are ignored here (the page renders its own feedback); only a SUCCESS
 * message carrying token + type fires [onSuccess]. Runs on the WebView's
 * JavaBridge thread — the callback must be thread-safe (Compose state and
 * coroutine launches are).
 */
private class HV3Bridge(
    private val onSuccess: (token: String, tokenType: String) -> Unit,
) {
    @JavascriptInterface
    fun dispatch(response: String) {
        val message = parseHV3ResponseMessage(response) ?: return
        val (token, tokenType) = message.successToken() ?: return
        onSuccess(token, tokenType)
    }
}
