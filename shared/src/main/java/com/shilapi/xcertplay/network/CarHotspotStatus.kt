package com.shilapi.xcertplay.network

import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.net.wifi.WifiManager

/**
 * Reads whether the head unit's own Wi-Fi hotspot is on, for the "Car hotspot" link.
 *
 * The user can turn it on in car settings or opt into [CarHotspotTethering] after granting permission.
 */
object CarHotspotStatus {
    private const val ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    private const val EXTRA_WIFI_AP_STATE = "wifi_state"
    private const val EXTRA_WIFI_AP_MODE = "wifi_ap_mode"

    /**
     * True/false from the Wi-Fi AP state, or null when the firmware hides it (then callers must
     * not block the connection). Interface flags are not used: BYD keeps wlan1 up with an address
     * while tethering is off.
     */
    fun isEnabled(context: Context): Boolean? {
        val app = context.applicationContext
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        return read(
            state = { wifi?.let { WifiManager::class.java.getMethod("getWifiApState").invoke(it) as? Int } },
            enabled = { wifi?.let { WifiManager::class.java.getMethod("isWifiApEnabled").invoke(it) as? Boolean } },
            sticky = { app.registerReceiver(null, IntentFilter(ACTION_WIFI_AP_STATE_CHANGED)) },
        )
    }

    /**
     * Rich AP state used by [LegacyHotspotRecovery] to tell a genuinely enabled-but-interfaceless
     * hotspot apart from a transitional or owner-disabled one. Additive: the boolean [isEnabled]
     * path (with its BYD-specific `wifi_ap_mode` guard) stays authoritative for connection decisions.
     */
    enum class State { DISABLING, DISABLED, ENABLING, ENABLED, FAILED, UNKNOWN }

    fun fromLegacyState(value: Int): State = when (value) {
        10 -> State.DISABLING
        11 -> State.DISABLED
        12 -> State.ENABLING
        13 -> State.ENABLED
        14 -> State.FAILED
        else -> State.UNKNOWN
    }

    /** Clears the one-shot recovery budget for [ssid] once a healthy interface appears. */
    fun resetRecoveryBudget(ssid: String) = LegacyHotspotRecovery.resetBudget(ssid)

    fun state(context: Context): State {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return State.UNKNOWN
        return state(wifi)
    }

    internal fun state(wifi: WifiManager): State =
        runCatching {
            fromLegacyState(WifiManager::class.java.getMethod("getWifiApState").invoke(wifi) as Int)
        }.recoverCatching {
            if (WifiManager::class.java.getMethod("isWifiApEnabled").invoke(wifi) as Boolean) {
                State.ENABLED
            } else {
                State.DISABLED
            }
        }.getOrDefault(State.UNKNOWN)

    internal fun stateEnabled(state: Int?): Boolean? = state?.takeIf { it in 10..14 }?.let { it == 13 }

    internal fun read(state: () -> Int?, enabled: () -> Boolean?, sticky: () -> Intent?): Boolean? {
        stateEnabled(runCatching(state).getOrNull())?.let { return it }
        runCatching(enabled).getOrNull()?.let { return it }
        val intent = runCatching(sticky).getOrNull() ?: return null
        // Local-only AP state says nothing about the car's tethered hotspot, including on multi-AP firmware.
        if (intent.hasExtra(EXTRA_WIFI_AP_MODE) && intent.getIntExtra(EXTRA_WIFI_AP_MODE, -1) != 1) return null
        return stateEnabled(intent.getIntExtra(EXTRA_WIFI_AP_STATE, -1))
    }
}
