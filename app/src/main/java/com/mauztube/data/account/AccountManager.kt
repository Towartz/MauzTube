package com.mauztube.data.account

import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.HttpUrl
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sessione Google/YouTube basata sui cookie del WebView di login.
 *
 * Non esiste un'API pubblica per leggere la home personalizzata: l'unico modo è comportarsi
 * come www.youtube.com, cioè inviare i cookie di sessione più l'header
 * `Authorization: SAPISIDHASH …` richiesto da InnerTube per le richieste autenticate.
 * I cookie restano nel CookieManager di sistema (persistente e cifrato a riposo dal sistema).
 */
@Singleton
class AccountManager @Inject constructor() {

    private val cookies: CookieManager? by lazy {
        runCatching { CookieManager.getInstance() }
            .onFailure { Log.w(TAG, "WebView non disponibile", it) }
            .getOrNull()
    }

    /** C'è una sessione Google salvata sul dispositivo. */
    private val _hasSession = MutableStateFlow(false)
    val hasSession: StateFlow<Boolean> = _hasSession

    /**
     * MODALITÀ ANONIMO (solo per la sessione corrente): l'account non viene usato e nulla
     * viene registrato (cronologia, posizioni, ricerche, ping a YouTube).
     */
    private val _incognito = MutableStateFlow(false)
    val incognito: StateFlow<Boolean> = _incognito

    /** Account utilizzabile: sessione presente e modalità anonimo spenta. */
    private val _loggedIn = MutableStateFlow(false)
    val loggedIn: StateFlow<Boolean> = _loggedIn

    init { refresh() }

    fun refresh() {
        _hasSession.value = cookieMap()[SAPISID] != null || cookieMap()[SAPISID_3P] != null
        _loggedIn.value = _hasSession.value && !_incognito.value
    }

    fun setIncognito(enabled: Boolean) {
        _incognito.value = enabled
        _loggedIn.value = _hasSession.value && !enabled
    }

    fun cookieHeader(): String? = cookies?.getCookie(YT_URL)?.takeIf { it.isNotBlank() }

    /** Esporta l'header Cookie della sessione attiva (null se non autenticato). */
    fun exportCookieHeader(): String? = if (_hasSession.value) cookieHeader() else null

    /** Salva i Set-Cookie ricevuti da YouTube, scartando intestazioni malformate o per altri domini. */
    fun storeResponseCookies(setCookies: List<String>) {
        val cm = cookies ?: return
        var wrote = false
        for (raw in setCookies) {
            val clean = raw.replace("\r", "").replace("\n", "").trim()
            if (clean.isEmpty() || '=' !in clean.substringBefore(';')) continue
            val domain = clean.split(';').drop(1)
                .map { it.trim() }
                .firstOrNull { it.startsWith("domain=", ignoreCase = true) }
                ?.substringAfter('=')?.trim()?.trimStart('.')?.lowercase()
            if (domain != null && domain != "youtube.com" && !domain.endsWith(".youtube.com")) continue
            cm.setCookie(YT_URL, clean)
            wrote = true
        }
        if (wrote) cm.flush()
    }

    /** Header di autenticazione InnerTube, o mappa vuota se non loggato. */
    fun authHeaders(origin: String = ORIGIN): Map<String, String> {
        if (_incognito.value) return emptyMap()
        val map = cookieMap()
        val cookie = cookieHeader() ?: return emptyMap()
        val ts = System.currentTimeMillis() / 1000
        val parts = buildList {
            (map[SAPISID] ?: map[SAPISID_3P])?.let { add("SAPISIDHASH ${ts}_${sha1("$ts $it $origin")}") }
            map[SAPISID_1P]?.let { add("SAPISID1PHASH ${ts}_${sha1("$ts $it $origin")}") }
            map[SAPISID_3P]?.let { add("SAPISID3PHASH ${ts}_${sha1("$ts $it $origin")}") }
        }
        if (parts.isEmpty()) return emptyMap()
        return mapOf(
            "Cookie" to cookie,
            "Authorization" to parts.joinToString(" "),
            "X-Goog-AuthUser" to "0",
            "X-Origin" to origin,
            "Origin" to origin,
        )
    }

    /**
     * Login alternativo per quando Google rifiuta il WebView: incolla l'header Cookie
     * copiato da un browser desktop (DevTools → Network → una richiesta a youtube.com).
     * Valida prima la presenza di SAPISID/3PAPISID e pulisce i vecchi cookie solo a
     * validazione riuscita, impostando Secure + HttpOnly per ridurre il rischio di furto.
     */
    fun importCookieHeader(raw: String): Boolean {
        val cm = cookies ?: return false
        val sanitized = raw.replace("\r", " ").replace("\n", " ").trim()
            .removePrefix("Cookie:").removePrefix("cookie:").trim()
        val pairs = sanitized.split(';')
            .map { it.trim() }
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val name = part.substring(0, idx).trim()
                val value = part.substring(idx + 1).trim()
                if (!COOKIE_NAME.matches(name) || name.lowercase() in COOKIE_ATTRS) return@mapNotNull null
                name to value
            }
        val hasAuth = pairs.any { (n, v) -> (n == SAPISID || n == SAPISID_3P) && v.isNotEmpty() }
        if (!hasAuth) return false

        cm.removeAllCookies(null)
        pairs.forEach { (name, value) ->
            val directive = if (name.startsWith("__Host-")) {
                "$name=$value; Path=/; Secure; HttpOnly"
            } else {
                "$name=$value; Domain=.youtube.com; Path=/; Secure; HttpOnly"
            }
            cm.setCookie(YT_URL, directive)
        }
        cm.flush()
        refresh()
        return _hasSession.value
    }

    fun logout() {
        cookies?.removeAllCookies(null)
        cookies?.flush()
        runCatching { WebStorage.getInstance().deleteAllData() }
        _hasSession.value = false
        _loggedIn.value = false
    }

    private fun cookieMap(): Map<String, String> =
        cookieHeader().orEmpty().split(';').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
        }.toMap()

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "AccountManager"
        const val YT_URL = "https://www.youtube.com"
        const val ORIGIN = "https://www.youtube.com"
        private const val SAPISID = "SAPISID"
        private const val SAPISID_1P = "__Secure-1PAPISID"
        private const val SAPISID_3P = "__Secure-3PAPISID"
        private val COOKIE_NAME = Regex("""^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$""")
        private val COOKIE_ATTRS = setOf("domain", "path", "expires", "max-age", "secure", "httponly", "samesite", "priority", "partitioned")
        private val TRUSTED_SUFFIXES = listOf("youtube.com", "google.com", "googleapis.com", "googlevideo.com", "gstatic.com", "googleusercontent.com")

        fun isTrustedGoogleUrl(url: HttpUrl?): Boolean {
            if (url == null || url.scheme != "https") return false
            val host = url.host.lowercase()
            return TRUSTED_SUFFIXES.any { host == it || host.endsWith(".$it") }
        }
    }
}
