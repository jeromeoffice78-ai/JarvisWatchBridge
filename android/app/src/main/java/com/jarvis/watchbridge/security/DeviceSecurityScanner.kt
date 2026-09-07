package com.jarvis.watchbridge.security

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings

data class SecurityFinding(
    val severity: String,
    val packageName: String?,
    val title: String,
    val detail: String,
    val remediation: String
)

data class SecurityReport(
    val scannedApps: Int,
    val findings: List<SecurityFinding>,
    val vpnActive: Boolean,
    val enabledAccessibilityServices: List<String>,
    val activeDeviceAdmins: List<String>
)

class DeviceSecurityScanner(private val context: Context) {
    private val pm = context.packageManager

    fun scan(): SecurityReport {
        val findings = mutableListOf<SecurityFinding>()
        val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)

        for (pkg in packages) {
            val app = pkg.applicationInfo ?: continue
            if ((app.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue

            val requested = pkg.requestedPermissions?.toSet().orEmpty()
            val signals = POWERFUL_PERMISSIONS.filterKeys { requested.contains(it) }.values.toList()
            val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(pkg.packageName)

            if (signals.size >= 2) {
                findings += SecurityFinding(
                    "high", pkg.packageName,
                    "$label has multiple high-impact capabilities",
                    signals.joinToString("; "),
                    "Review permissions and app origin. Disable unnecessary access or uninstall if you do not trust it."
                )
            } else if (signals.size == 1) {
                findings += SecurityFinding(
                    "review", pkg.packageName,
                    "$label has a powerful capability",
                    signals.first(),
                    "Verify the capability is necessary."
                )
            }

            val installer = installSource(pkg.packageName)
            if (installer == null || installer !in TRUSTED_INSTALLERS) {
                findings += SecurityFinding(
                    "review", pkg.packageName,
                    "$label may be sideloaded",
                    "Installer source: ${installer ?: "unknown"}",
                    "Confirm you intentionally installed this app from this source."
                )
            }
        }

        val accessibility = enabledAccessibilityServices()
        val admins = activeDeviceAdmins()
        val vpn = vpnActive()

        if (accessibility.isNotEmpty()) {
            findings += SecurityFinding(
                "review", null, "Accessibility services enabled",
                accessibility.joinToString(),
                "Verify every enabled Accessibility service is expected."
            )
        }
        if (admins.isNotEmpty()) {
            findings += SecurityFinding(
                "review", null, "Device administrator apps active",
                admins.joinToString(),
                "Remove administrator access from any app you do not recognize before uninstalling it."
            )
        }
        if (vpn) {
            findings += SecurityFinding(
                "info", null, "VPN connection active",
                "Android reports an active VPN transport.",
                "Confirm the VPN is one you intentionally enabled."
            )
        }

        return SecurityReport(
            packages.count { ((it.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) == 0 },
            findings.sortedByDescending { rank(it.severity) },
            vpn,
            accessibility,
            admins
        )
    }

    fun openAppSecuritySettings(packageName: String) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun requestUninstall(packageName: String) {
        context.startActivity(
            Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun installSource(packageName: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(packageName)
        }
    }.getOrNull()

    private fun enabledAccessibilityServices(): List<String> =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').map { it.trim() }.filter { it.isNotBlank() }

    private fun activeDeviceAdmins(): List<String> =
        context.getSystemService(DevicePolicyManager::class.java).activeAdmins.orEmpty()
            .map { it.flattenToShortString() }

    private fun vpnActive(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.any { network ->
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    private fun rank(value: String) = when (value) {
        "high" -> 3
        "review" -> 2
        else -> 1
    }

    companion object {
        private val POWERFUL_PERMISSIONS = linkedMapOf(
            "android.permission.REQUEST_INSTALL_PACKAGES" to "can request installation of other apps",
            "android.permission.SYSTEM_ALERT_WINDOW" to "can draw over other apps",
            "android.permission.MANAGE_EXTERNAL_STORAGE" to "can request broad file access",
            "android.permission.READ_SMS" to "requests SMS access",
            "android.permission.RECEIVE_SMS" to "requests incoming SMS access",
            "android.permission.READ_CALL_LOG" to "requests call-log access",
            "android.permission.WRITE_CALL_LOG" to "requests call-log modification",
            "android.permission.PACKAGE_USAGE_STATS" to "can request app-usage access"
        )
        private val TRUSTED_INSTALLERS = setOf(
            "com.android.vending",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.samsung.android.packageinstaller"
        )
    }
}
