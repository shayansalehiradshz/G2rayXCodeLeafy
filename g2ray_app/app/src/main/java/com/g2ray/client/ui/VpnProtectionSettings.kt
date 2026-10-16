package com.g2ray.client.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings

internal fun openVpnProtectionSettings(context: Context): Boolean {
    for (action in listOf(Settings.ACTION_VPN_SETTINGS, Settings.ACTION_SETTINGS)) {
        try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: ActivityNotFoundException) {
            // Some OEMs do not expose a dedicated VPN settings activity.
        } catch (_: SecurityException) {
            // A managed device may restrict this destination.
        }
    }
    return false
}

internal fun vpnBlockingStatusLabel(enabled: Boolean?): String = when (enabled) {
    true -> "Enabled"
    false -> "Not enabled"
    null -> "Check Android settings"
}
