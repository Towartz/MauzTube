package com.mauztube.data.account

import android.accounts.Account
import android.accounts.AccountManager as AndroidAccountManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * Integrazione con gli account Google di sistema / microG / GmsCore tramite [AndroidAccountManager].
 * Funziona con qualsiasi brand o pacchetto (Google GMS, microG Services, ReVanced GmsCore,
 * Vanced microG, Morphe/RVX, ecc.) perché interroga gli autenticatori registrati per `"com.google"`.
 */
object MicroGHelper {

    private const val GOOGLE_ACCOUNT_TYPE = "com.google"
    private const val YOUTUBE_CONTINUE_URL =
        "https://www.youtube.com/signin?action_handle_signin=true&app=m&next=https%3A%2F%2Fm.youtube.com%2F"
    private val WEBLOGIN_AUTH_TOKEN_TYPE =
        "weblogin:continue=" + URLEncoder.encode(YOUTUBE_CONTINUE_URL, "UTF-8")

    /** True se sul dispositivo è presente un autenticatore `"com.google"` (GMS o qualsiasi fork microG). */
    fun hasGoogleAuthenticator(context: Context): Boolean = runCatching {
        AndroidAccountManager.get(context).authenticatorTypes.any { it.type == GOOGLE_ACCOUNT_TYPE }
    }.getOrDefault(false)

    /** Intent di sistema/microG per scegliere o aggiungere un account Google ("Account Ask to Sign"). */
    @Suppress("DEPRECATION")
    fun chooseAccountIntent(): Intent =
        AndroidAccountManager.newChooseAccountIntent(
            null,
            null,
            arrayOf(GOOGLE_ACCOUNT_TYPE),
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
     * Richiede a microG/GMS un URL `weblogin:continue=...` per accedere automaticamente a YouTube
     * nel WebView con l'account scelto, restituendo [WebLoginResult.NeedConsent] se l'utente
     * deve prima concedere il permesso.
     */
    suspend fun requestWebLoginUrl(context: Context, accountName: String): WebLoginResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val am = AndroidAccountManager.get(context)
                val future = am.getAuthToken(
                    Account(accountName, GOOGLE_ACCOUNT_TYPE),
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
                if (!tokenUrl.isNullOrBlank() && tokenUrl.startsWith("http") && "WILL_NOT_SIGN_IN" !in tokenUrl) {
                    WebLoginResult.Url(tokenUrl)
                } else {
                    val err = bundle.getString(AndroidAccountManager.KEY_ERROR_MESSAGE)
                        ?: "No weblogin URL returned"
                    WebLoginResult.Error(err)
                }
            }.getOrElse { e ->
                WebLoginResult.Error(e.message ?: e.javaClass.simpleName)
            }
        }
}
