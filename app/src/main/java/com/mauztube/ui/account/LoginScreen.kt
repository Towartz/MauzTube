package com.mauztube.ui.account

import com.mauztube.util.tr

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.mauztube.data.account.AccountManager
import dagger.hilt.android.lifecycle.HiltViewModel
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
 * "Importa cookie" direttamente dalla barra in alto.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(onDone: () -> Unit, vm: LoginViewModel = hiltViewModel()) {
    var loading by remember { mutableStateOf(true) }
    var cookieDialog by remember { mutableStateOf(false) }

    if (cookieDialog) {
        ImportCookieDialog(
            account = vm.account,
            onDismiss = { cookieDialog = false },
            onImported = { cookieDialog = false; onDone() },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Sign in to Google", "Accedi a Google")) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Default.Close, tr("Close", "Chiudi")) } },
                actions = {
                    TextButton(onClick = { cookieDialog = true }) {
                        Text(tr("Import cookies", "Importa cookie"))
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

@Composable
fun ImportCookieDialog(
    account: AccountManager,
    onDismiss: () -> Unit,
    onImported: () -> Unit = onDismiss,
) {
    val context = LocalContext.current
    var raw by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                val buf = CharArray(65_536)
                val read = reader.read(buf)
                if (read > 0) String(buf, 0, read) else ""
            }
        }.getOrNull().orEmpty()
        val parsed = parseCookieFile(text)
        raw = parsed
        if (account.importCookieHeader(parsed)) onImported() else error = true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Import YouTube cookies", "Importa cookie di YouTube")) },
        text = {
            Column {
                Text(
                    tr(
                        "Alternative if Google blocks sign-in inside the app. Paste the \"Cookie\" header from a desktop browser signed in to youtube.com, or import a cookies.txt file.",
                        "Alternativa se Google blocca il login nell'app. Incolla l'header \"Cookie\" da un browser desktop loggato su youtube.com, oppure importa un file cookies.txt.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = raw,
                    onValueChange = { raw = it; error = false },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    maxLines = 4,
                    isError = error,
                    supportingText = { if (error) Text(tr("SAPISID cookie not found", "Cookie SAPISID non trovato")) },
                )
                Row {
                    TextButton(onClick = {
                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                        val clip = cm?.primaryClip?.takeIf { it.itemCount > 0 }
                        val pasted = clip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        if (pasted.isNotBlank()) { raw = pasted; error = false }
                    }) { Text(tr("Paste", "Incolla")) }
                    TextButton(onClick = {
                        runCatching { filePicker.launch(arrayOf("text/*", "*/*")) }
                    }) { Text(tr("Import file", "Importa file")) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (account.importCookieHeader(raw)) onImported() else error = true
            }) { Text(tr("Import", "Importa")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "Annulla")) } },
    )
}

/** Estrae cookie `name=value` da un file Netscape `cookies.txt` (youtube.com) o testo grezzo. */
private fun parseCookieFile(raw: String): String {
    val netscape = raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val cols = line.split('\t')
            if (cols.size >= 7 && cols[0].lowercase().let { it.endsWith("youtube.com") || it == ".youtube.com" }) {
                "${cols[5]}=${cols[6]}"
            } else null
        }
        .toList()
    return if (netscape.isNotEmpty()) netscape.joinToString("; ") else raw
}

private const val LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true" +
        "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Dm" +
        "%26next%3Dhttps%253A%252F%252Fm.youtube.com%252F"

private const val CHROME_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/140.0.0.0 Mobile Safari/537.36"
