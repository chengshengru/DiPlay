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

    /**
     * Set when the driver connects although Android reports the car hotspot off. Some firmware reports it off
     * while the car's own hotspot is on (Geely Galaxy E8, #604), and DiPlay then refused every connection.
     */
    fun offReportIgnored(context: Context): Boolean = prefs(context).getBoolean("ignore_off_report", false)

    fun setOffReportIgnored(context: Context, ignored: Boolean) =
        prefs(context).edit().putBoolean("ignore_off_report", ignored).apply()

    /**
     * Whether to treat the car hotspot as off, from Android's [state]. A report of "on" clears the driver's
     * choice, so a head unit whose report is right warns again the next time its hotspot really is off.
     */
    fun reportedOff(context: Context, state: Boolean?): Boolean {
        if (state == true) {
            if (offReportIgnored(context)) setOffReportIgnored(context, false)
            return false
        }
        return state == false && !offReportIgnored(context)
    }

    /**
     * Called when an attempt that connected past Android's "off" report found no hotspot. When Android still
     * reports it off, the report was right: a BYD DiLink 2.0 keeps its hotspot switch on but starts the hotspot
     * only once its own hotspot screen opens (#809). The driver's choice is dropped so the next attempt says the
     * hotspot is off instead of retrying for a minute. Returns true when it was dropped.
     */
    fun hotspotNeverCameUp(context: Context, state: Boolean?): Boolean {
        if (state != false || !offReportIgnored(context)) return false
        setOffReportIgnored(context, false)
        return true
    }
}
