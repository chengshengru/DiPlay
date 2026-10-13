package com.shilapi.xcertplay

import android.content.Context
import android.provider.Settings
import com.shilapi.xcertplay.compat.BluetoothCompat

internal object DiPlayBluetooth {
    fun localAddress(context: Context): String? {
        // 波次十一批次A（main #641）：统一走 BluetoothCompat.getAdapter（含 API 17 的 getDefaultAdapter 兜底），
        // 避免 getSystemService(BluetoothManager::class.java) 这一 API 23 调用在 API 17 上 NoSuchMethodError。
        val adapter = runCatching { BluetoothCompat.getAdapter(context)?.address }.getOrNull()
        val setting = runCatching { Settings.Secure.getString(context.contentResolver, "bluetooth_address") }.getOrNull()
        return listOfNotNull(adapter, setting).firstOrNull {
            Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(it) &&
                !it.startsWith("02:00:00:00:00:") && it != "00:00:00:00:00:00"
        }
    }
}
