package com.mauztube.ui.account

import com.mauztube.util.tr

import android.accounts.AccountManager as AndroidAccountManager
import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.mauztube.data.account.AccountManager
import com.mauztube.data.account.MicroGHelper
import com.mauztube.util.Feedback
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(val account: AccountManager) : ViewModel()

/**
 * Login Google in un WebView: al termine i cookie di sessione di youtube.com restano nel
 * CookieManager e vengono riusati da [com.mauztube.data.innertube.InnerTubeClient].
 *
 * Google rifiuta i login dai WebView "riconoscibili". Per questo:
 *  - lo User-Agent è quello di Chrome mobile, senza il marcatore "; wv" (i WebView
 *    aggiornati non inviano più nemmeno l'header X-Requested-With).
 * Se Google mostra comunque "Questo browser potrebbe non essere sicuro", usare
 * Impostazioni → "Importa cookie" come alternativa.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(onDone: () -> Unit, vm: LoginViewModel = hiltViewModel()) {
    var loading by remember { mutableStateOf(true) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hasDeviceAuthenticator = remember { MicroGHelper.hasGoogleAuthenticator(context) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pendingAccount by remember { mutableStateOf<String?>(null) }

    fun loadAuthUrl(authUrl: String) {
        loading = true
        CookieManager.getInstance().removeAllCookies {
            webViewRef?.loadUrl(authUrl)
        }
    }

    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val acct = pendingAccount
        val directUrl = res.data?.getStringExtra(AndroidAccountManager.KEY_AUTHTOKEN)
        if (!directUrl.isNullOrBlank() && directUrl.startsWith("http") && "WILL_NOT_SIGN_IN" !in directUrl) {
            loadAuthUrl(directUrl)
            return@rememberLauncherForActivityResult
        }
        if (res.resultCode == Activity.RESULT_OK && acct != null) {
            loading = true
            scope.launch {
                when (val out = MicroGHelper.requestWebLoginUrl(context, acct)) {
                    is MicroGHelper.WebLoginResult.Url -> loadAuthUrl(out.url)
                    is MicroGHelper.WebLoginResult.Error -> {
                        loading = false
                        Feedback.show(out.message)
                    }
                    is MicroGHelper.WebLoginResult.NeedConsent -> {
                        loading = false
                        Feedback.show(tr("Account permission not granted", "Permesso account non concesso"))
                    }
                }
            }
        } else {
            loading = false
        }
    }

    val accountPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val acct = res.data?.getStringExtra(AndroidAccountManager.KEY_ACCOUNT_NAME) ?: return@rememberLauncherForActivityResult
        pendingAccount = acct
        loading = true
        scope.launch {
            when (val out = MicroGHelper.requestWebLoginUrl(context, acct)) {
                is MicroGHelper.WebLoginResult.Url -> loadAuthUrl(out.url)
                is MicroGHelper.WebLoginResult.NeedConsent -> {
                    loading = false
                    consentLauncher.launch(out.intent)
                }
                is MicroGHelper.WebLoginResult.Error -> {
                    loading = false
                    Feedback.show(tr("Device account sign-in unavailable: ${out.message}", "Accesso con account del dispositivo non disponibile: ${out.message}"))
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Sign in to Google", "Accedi a Google")) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Default.Close, tr("Close", "Chiudi")) } },
                actions = {
                    if (hasDeviceAuthenticator) {
                        TextButton(onClick = {
                            runCatching { accountPickerLauncher.launch(MicroGHelper.chooseAccountIntent()) }
                                .onFailure { Feedback.show(tr("No Google/microG account picker available", "Nessun selettore account Google/microG disponibile")) }
                        }) {
                            Text(tr("Device account", "Account dispositivo"))
                        }
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewRef = this
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            @Suppress("DEPRECATION")
                            settings.saveFormData = false
                            settings.userAgentString = CHROME_UA
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                    !AccountManager.isTrustedGoogleUrl(request.url.toString().toHttpUrlOrNull())

                                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                    loading = true
                                }

                                override fun onPageFinished(view: WebView, url: String) {
                                    loading = false
                                    // Login completato quando torniamo su youtube.com con i cookie di sessione.
                                    if (url.contains("youtube.com") && !url.contains("accounts.google")) {
                                        CookieManager.getInstance().flush()
                                        vm.account.refresh()
                                        if (vm.account.hasSession.value) onDone()
                                    }
                                }
                            }
                            loadUrl(LOGIN_URL)
                        }
                    },
                    onRelease = { it.stopLoading(); it.destroy() },
                )
            }
        }
    }
}

private const val LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true" +
        "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Dm" +
        "%26next%3Dhttps%253A%252F%252Fm.youtube.com%252F"

private const val CHROME_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/140.0.0.0 Mobile Safari/537.36"
