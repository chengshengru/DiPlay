package com.shilapi.xcertplay

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.compat.PermissionCompat

/**
 * 用户在连接设置中选定的 iPhone 通过蓝牙重连时，可选地唤醒 DiPlay（main #439 移植）。
 *
 * API 17 适配：运行时权限检查改用 [PermissionCompat]（API 17-22 无 checkSelfPermission），
 * 蓝牙唤醒仅在 API 31+ 才需要 BLUETOOTH_CONNECT，低版本直接跳过该检查。
 *
 * @author TRAE AI
 * @since 2026-10-11
 */
class PhoneBluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED ||
            !DiPlayPreferences.connectOnPhoneBluetooth(context) ||
            CarPlayBackgroundSession.hasSession()) return
        val selected = DiPlayPreferences.phoneAddress(context) ?: return
        // API 31+ 才需要 BLUETOOTH_CONNECT；PermissionCompat 兼容 API 17-22 的安装时授予模型。
        if (Build.VERSION.SDK_INT >= 31 &&
            !PermissionCompat.hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)) return
        val device = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        } ?: return
        val address = runCatching { device.address }.getOrNull() ?: return
        if (!address.equals(selected, ignoreCase = true)) return

        // 车机在一次唤醒中可能发出多个 ACL 事件。先提交防抖时间戳再启动，
        // 避免另一个接收器实例从同一事件风暴中打开第二条连接。
        val prefs = context.getSharedPreferences("diplay_phone_wake", Context.MODE_PRIVATE)
        val now = SystemClock.elapsedRealtime()
        val previous = prefs.getLong("last_launch_elapsed", 0)
        if (prefs.contains("last_launch_elapsed") && now >= previous && now - previous < 15_000) return
        prefs.edit().putLong("last_launch_elapsed", now).commit()
        PhoneWakeDiagnostics.record(context, "matched")
        val launch = Intent(context, DiPlayActivity::class.java).apply {
            putExtra(EXTRA_AUTO_CONNECT, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { context.startActivity(launch) }
            .onSuccess { PhoneWakeDiagnostics.record(context, "startActivity-returned") }
            .onFailure { error ->
                PhoneWakeDiagnostics.record(context, "failed", error)
                Log.w("DiPlayPhoneWake", "Bluetooth wake launch failed: ${error.javaClass.simpleName}")
            }
    }

    companion object {
        const val EXTRA_AUTO_CONNECT = "com.shihab.diplay.AUTO_CONNECT_SELECTED_PHONE"
    }
}

/** 只记录时间戳和固定结果；蓝牙地址与广播附加信息不进入诊断报告。 */
internal object PhoneWakeDiagnostics {
    private const val PREFS = "diplay_phone_wake_diagnostics"

    fun record(context: Context, result: String, error: Throwable? = null) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("at", System.currentTimeMillis())
            .putString("result", result)
            .putString("failureClass", error?.javaClass?.simpleName ?: "none")
            .apply()
    }

    fun report(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong("at", 0)
        return if (at == 0L) "Phone Bluetooth wake: no matching event recorded"
        else "Phone Bluetooth wake: matchedAtEpochMs=$at result=${prefs.getString("result", "unknown")} " +
            "failureClass=${prefs.getString("failureClass", "none")}; " +
            "startActivity-returned does not prove the car displayed DiPlay."
    }
}
