package com.shilapi.xcertplay.network

import android.content.Context
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

object CarHotspotSettings {
    private fun prefs(context: Context) = context.getSharedPreferences("diplay_car_hotspot", Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean("auto_enable", false)

    fun setEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean("auto_enable", enabled).apply()

    /**
     * Opt-in legacy AP recovery for API 17..22 head units whose already-enabled hotspot loses its
     * network interface. Default off; the recovery itself is a no-op outside API 17..22, so enabling
     * it is harmless on newer firmware.
     */
    fun legacyRecoveryEnabled(context: Context): Boolean =
        prefs(context).getBoolean("legacy_recovery", false)

    fun setLegacyRecoveryEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean("legacy_recovery", enabled).apply()

    // Visibility is independent of the saved choice: ADB is only needed to grant permission.
    fun visible(bydAvailable: Boolean, access: LocalAdb.Access): Boolean =
        bydAvailable && (access == LocalAdb.Access.READY || access == LocalAdb.Access.NOT_APPROVED)

    fun shouldEnable(context: Context, wireless: Boolean, mode: WirelessHotspotMode): Boolean =
        enabled(context) && wireless && mode == WirelessHotspotMode.MANUAL
}
