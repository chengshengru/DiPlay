package com.shilapi.xcertplay.compat

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * USB 权限兼容性处理。
 *
 * API 17 的 USB 权限模型与高版本不同：
 * - PendingIntent 在 API 31+ 需要显式指定 FLAG_MUTABLE/FLAG_IMMUTABLE；
 * - Intent 取值在 API 33+ 需要类型化 getParcelableExtra；
 * - 注册广播在 API 33+ 需要显式声明导出行为。
 *
 * @author TRAE AI
 * @since 2026-10-08
 */
object UsbPermissionCompat {

    private const val ACTION_USB_PERMISSION = "com.shihab.diplay.USB_PERMISSION"

    /**
     * 请求 USB 设备权限（阻塞等待授权结果）。
     *
     * @param context 上下文
     * @param usbManager USB 管理器
     * @param device USB 设备
     * @param timeoutMillis 超时时间（毫秒）
     * @return 是否获得权限
     */
    fun requestPermission(
        context: Context,
        usbManager: UsbManager,
        device: UsbDevice,
        timeoutMillis: Long = 10_000
    ): Boolean {
        // 检查是否已有权限
        if (usbManager.hasPermission(device)) {
            return true
        }

        val permissionGranted = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        // 创建 PendingIntent
        val pendingIntent = if (ApiLevel.isAtLeast(ApiLevel.S)) {
            // API 31+：必须显式指定可变性
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_MUTABLE
            )
        } else {
            // API 17-30：使用普通 PendingIntent
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_USB_PERMISSION),
                0
            )
        }

        // 注册广播接收器
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_USB_PERMISSION) {
                    val granted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED, false
                    )
                    val returnedDevice = if (ApiLevel.isAtLeast(ApiLevel.TIRAMISU)) {
                        intent.getParcelableExtra(
                            UsbManager.EXTRA_DEVICE,
                            UsbDevice::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    }

                    if (granted && returnedDevice?.deviceName == device.deviceName) {
                        permissionGranted.set(true)
                    }
                    latch.countDown()
                }
            }
        }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (ApiLevel.isAtLeast(ApiLevel.TIRAMISU)) {
            // API 33+：显式声明接收器不导出
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        // 请求权限
        usbManager.requestPermission(device, pendingIntent)

        // 等待结果
        return try {
            latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
            permissionGranted.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } finally {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
                // 已注销则忽略
            }
        }
    }

    /**
     * 检查是否有 USB 权限。
     */
    fun hasPermission(usbManager: UsbManager, device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }
}
