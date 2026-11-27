package com.mauztube.data.account

import android.accounts.Account
import android.accounts.AccountManager as AndroidAccountManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Integrazione con gli account Google di sistema / microG / GmsCore tramite [AndroidAccountManager].
 * Funziona con qualsiasi brand o pacchetto (Google GMS, microG Services, ReVanced GmsCore,
 * Vanced microG, Morphe/RVX, ecc.) perché interroga gli autenticatori registrati per `"com.google"`.
 */
object MicroGHelper {

    private const val GOOGLE_ACCOUNT_TYPE = "com.google"
    private const val WEBLOGIN_AUTH_TOKEN_TYPE =
        "weblogin:service=youtube&continue=https://www.youtube.com/signin?action_handle_signin=true&app=m&next=https%3A%2F%2Fm.youtube.com%2F"

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
     * Richiede a microG/GMS un URL `weblogin:` per accedere automaticamente a YouTube nel WebView
     * con l'account scelto, chiedendo il consenso all'utente se richiesto dal provider.
     */
    suspend fun requestWebLoginUrl(activity: Activity, accountName: String): WebLoginResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val am = AndroidAccountManager.get(activity)
                val future = am.getAuthToken(
                    Account(accountName, GOOGLE_ACCOUNT_TYPE),
                    WEBLOGIN_AUTH_TOKEN_TYPE,
                    null,
                    activity,
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
                if (!tokenUrl.isNullOrBlank() && tokenUrl.startsWith("http")) {
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
