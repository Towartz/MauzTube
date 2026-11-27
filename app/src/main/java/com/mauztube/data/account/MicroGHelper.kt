package com.mauztube.data.account

import android.accounts.Account
import android.accounts.AccountManager as AndroidAccountManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder

/**
 * Integrazione con qualsiasi fork di microG / GmsCore (microG Services, ReVanced GmsCore,
 * Morphe microG, Vanced microG, RVX, ecc.) tramite [AndroidAccountManager].
 *
 * Sui dispositivi non-root i fork microG registrano tipi di account diversi da `"com.google"`
 * (es. `"app.revanced"`, `"app.morphe"`, `"com.mgoogle"`): li rileva tutti dinamicamente
 * escludendo GMS ufficiale.
 */
object MicroGHelper {

    private const val OFFICIAL_GMS_PACKAGE = "com.google.android.gms"
    private const val YOUTUBE_CONTINUE_URL =
        "https://www.youtube.com/signin?action_handle_signin=true&app=m&next=https%3A%2F%2Fm.youtube.com%2F"
    private val WEBLOGIN_AUTH_TOKEN_TYPE =
        "weblogin:continue=" + URLEncoder.encode(YOUTUBE_CONTINUE_URL, "UTF-8")

    private val BRAND_KEYWORDS = listOf("microg", "gmscore", "vanced", "revanced", "morphe", "rvx", "mgoogle")

    /** Tipi di account registrati da qualsiasi microG / GmsCore sul dispositivo (escluso GMS ufficiale). */
    fun microGAccountTypes(context: Context): List<String> = runCatching {
        val pm = context.packageManager
        AndroidAccountManager.get(context).authenticatorTypes
            .filter { auth ->
                val pkg = auth.packageName
                val type = auth.type
                if (pkg == OFFICIAL_GMS_PACKAGE) {
                    val label = runCatching { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }.getOrNull().orEmpty()
                    BRAND_KEYWORDS.any { label.contains(it, ignoreCase = true) }
                } else {
                    pkg.endsWith(".gms") ||
                        pkg.endsWith(".android.gms") ||
                        BRAND_KEYWORDS.any {
                            pkg.contains(it, ignoreCase = true) || type.contains(it, ignoreCase = true)
                        } ||
                        runCatching { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }.getOrNull().orEmpty()
                            .let { label -> BRAND_KEYWORDS.any { label.contains(it, ignoreCase = true) } }
                }
            }
            .map { it.type }
            .distinct()
    }.getOrDefault(emptyList())

    fun hasMicroGAuthenticator(context: Context): Boolean = microGAccountTypes(context).isNotEmpty()

    /** Intent per scegliere o aggiungere un account da uno dei provider microG rilevati. */
    @Suppress("DEPRECATION")
    fun chooseAccountIntent(accountTypes: List<String>): Intent =
        AndroidAccountManager.newChooseAccountIntent(
            null,
            null,
            accountTypes.toTypedArray(),
            null,
            null,
            null,
            null,
        )

    sealed interface WebLoginResult {
        data class Url(val url: String) : WebLoginResult
        data class NeedConsent(val intent: Intent) : WebLoginResult
        data class Error(val message: String) : WebLoginResult
    }

    /**
     * Richiede a microG/GmsCore un URL `weblogin:continue=...` per accedere automaticamente a YouTube
     * nel WebView con l'account scelto, restituendo [WebLoginResult.NeedConsent] se l'utente
     * deve prima concedere il permesso.
     */
    suspend fun requestWebLoginUrl(
        context: Context,
        accountName: String,
        accountType: String,
    ): WebLoginResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val am = AndroidAccountManager.get(context)
                val future = am.getAuthToken(
                    Account(accountName, accountType),
                    WEBLOGIN_AUTH_TOKEN_TYPE,
                    null,
                    false,
                    null,
                    null,
                )
                val bundle: Bundle = future.result
                val consentIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    bundle.getParcelable(AndroidAccountManager.KEY_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    bundle.getParcelable(AndroidAccountManager.KEY_INTENT)
                }
                if (consentIntent != null) {
                    return@runCatching WebLoginResult.NeedConsent(consentIntent)
                }
                val tokenUrl = bundle.getString(AndroidAccountManager.KEY_AUTHTOKEN)
                if (!tokenUrl.isNullOrBlank() &&
                    AccountManager.isTrustedGoogleUrl(tokenUrl.toHttpUrlOrNull()) &&
                    "WILL_NOT_SIGN_IN" !in tokenUrl
                ) {
                    WebLoginResult.Url(tokenUrl)
                } else {
                    val err = bundle.getString(AndroidAccountManager.KEY_ERROR_MESSAGE)
                        ?: "Google rejected one-tap weblogin for third-party apps"
                    WebLoginResult.Error(err)
                }
            }.getOrElse { e ->
                WebLoginResult.Error(e.message ?: e.javaClass.simpleName)
            }
        }
}
