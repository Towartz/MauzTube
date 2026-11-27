package com.mauztube.data.account

import android.accounts.AccountManager as AndroidAccountManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/**
 * Rileva qualsiasi implementazione di microG / GmsCore installata sul dispositivo
 * (microG Services, Vanced microG, ReVanced GmsCore, Morphe/RVX o fork personalizzati),
 * indipendentemente dal nome del pacchetto o dal brand.
 */
object MicroGHelper {

    data class Provider(
        val packageName: String,
        val label: String,
        val versionName: String?,
    )

    private val KNOWN_PACKAGES = listOf(
        "app.revanced.android.gms",
        "com.revanced.android.gms",
        "com.mgoogle.android.gms",
        "org.microg.gms",
        "org.microg.gms.core",
        "app.morphe.android.gms",
        "app.rvx.android.gms",
        "com.google.android.gms",
    )

    private val BRAND_KEYWORDS = listOf("microg", "gmscore", "vanced", "revanced", "morphe", "rvx")

    fun detectProviders(context: Context): List<Provider> {
        val pm = context.packageManager
        val candidates = linkedSetOf<String>()

        // 1. Qualsiasi pacchetto registrato come autenticatore "com.google" (copre qualsiasi fork/brand).
        runCatching {
            AndroidAccountManager.get(context).authenticatorTypes
                .filter { it.type == "com.google" }
                .forEach { candidates.add(it.packageName) }
        }

        // 2. Pacchetti che espongono servizi microG / GmsCore.
        listOf(
            "org.microg.gms.ui.SETTINGS",
            "com.google.android.c2dm.intent.REGISTER",
            "android.accounts.AccountAuthenticator",
        ).forEach { action ->
            runCatching {
                pm.queryIntentServices(Intent(action), 0)
                    .mapNotNull { it.serviceInfo?.packageName }
                    .forEach { candidates.add(it) }
            }
            runCatching {
                pm.queryIntentActivities(Intent(action), 0)
                    .mapNotNull { it.activityInfo?.packageName }
                    .forEach { candidates.add(it) }
            }
        }

        // 3. Fallback sui pacchetti noti.
        candidates.addAll(KNOWN_PACKAGES)

        return candidates.mapNotNull { pkg ->
            val info = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
            val appInfo = info.applicationInfo ?: return@mapNotNull null
            val label = runCatching { appInfo.loadLabel(pm).toString() }.getOrNull().orEmpty().ifBlank { pkg }
            val isOfficialGms = pkg == "com.google.android.gms" &&
                BRAND_KEYWORDS.none { label.contains(it, ignoreCase = true) }
            val matchesBrand = pkg != "com.google.android.gms" && (
                pkg in KNOWN_PACKAGES ||
                    BRAND_KEYWORDS.any { pkg.contains(it, ignoreCase = true) || label.contains(it, ignoreCase = true) }
                )
            if (!matchesBrand && isOfficialGms) return@mapNotNull null
            if (!matchesBrand && pkg == "com.google.android.gms") return@mapNotNull null
            Provider(
                packageName = pkg,
                label = label,
                versionName = info.versionName,
            )
        }.distinctBy { it.packageName }
    }

    fun openProvider(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        val launch = pm.getLaunchIntentForPackage(packageName)
            ?: Intent("org.microg.gms.ui.SETTINGS").setPackage(packageName)
        if (launch.resolveActivity(pm) != null) {
            return runCatching {
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
        }
        return runCatching {
            val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(details)
            true
        }.getOrDefault(false)
    }
}
