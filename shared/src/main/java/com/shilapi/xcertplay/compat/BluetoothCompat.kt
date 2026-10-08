package com.shilapi.xcertplay.compat

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import java.util.UUID

/**
 * 蓝牙兼容性处理。
 *
 * API 17 的蓝牙 API 与高版本有显著差异：
 * - API 18+ 才提供 BluetoothManager，API 17 只能用 getDefaultAdapter()；
 * - API 31+ 需要 BLUETOOTH_CONNECT/BLUETOOTH_SCAN 运行时权限；
 * - 部分设备 createRfcommSocketToServiceRecord 失败，需反射兜底。
 *
 * @author TRAE AI
 * @since 2026-10-08
 */
object BluetoothCompat {

    /** CarPlay 使用的蓝牙 SPP UUID。 */
    val CARPLAY_UUID: UUID = UUID.fromString("0000110A-0000-1000-8000-00805F9B34FB")

    /**
     * 获取 [BluetoothAdapter]。
     */
    @Suppress("DEPRECATION")
    fun getAdapter(context: Context): BluetoothAdapter? {
        return if (ApiLevel.isAtLeast(ApiLevel.JELLY_BEAN_MR2)) {
            // API 18+：使用 BluetoothManager
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            manager?.adapter
        } else {
            // API 17：使用 getDefaultAdapter()
            BluetoothAdapter.getDefaultAdapter()
        }
    }

    /**
     * 检查蓝牙权限是否齐备。
     */
    fun hasBluetoothPermission(context: Context): Boolean {
        val permissions = if (ApiLevel.isAtLeast(ApiLevel.S)) {
            // API 31+：需要新的运行时权限
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            // API 17-30：只需要基础蓝牙权限（安装时授予）
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }

        return permissions.all { permission ->
            context.checkPermission(
                permission,
                android.os.Process.myPid(),
                android.os.Process.myUid()
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 创建 RFCOMM Socket，失败时通过反射兜底。
     */
    fun createRfcommSocket(
        device: BluetoothDevice,
        uuid: UUID = CARPLAY_UUID
    ): BluetoothSocket? {
        return try {
            device.createRfcommSocketToServiceRecord(uuid)
        } catch (_: Exception) {
            // 某些设备需要使用隐藏 API 创建
            createRfcommSocketByReflection(device)
        }
    }

    /**
     * 通过反射创建 RFCOMM Socket（备用方案）。
     */
    private fun createRfcommSocketByReflection(device: BluetoothDevice): BluetoothSocket? {
        return try {
            val method = device.javaClass.getMethod(
                "createRfcommSocket",
                Int::class.javaPrimitiveType
            )
            method.invoke(device, 1) as? BluetoothSocket
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 获取已配对设备列表。
     */
    @SuppressLint("MissingPermission")
    fun getBondedDevices(adapter: BluetoothAdapter): Set<BluetoothDevice> {
        return try {
            adapter.bondedDevices ?: emptySet()
        } catch (_: SecurityException) {
            // API 31+ 缺少 BLUETOOTH_CONNECT 时抛安全异常
            emptySet()
        }
    }

    /**
     * 检查设备是否已配对。
     */
    fun isBonded(device: BluetoothDevice): Boolean {
        return device.bondState == BluetoothDevice.BOND_BONDED
    }

    /**
     * 获取设备名称（缺权限时返回 null）。
     */
    @SuppressLint("MissingPermission")
    fun getDeviceName(device: BluetoothDevice): String? {
        return try {
            device.name
        } catch (_: SecurityException) {
            null
        }
    }

    /**
     * 获取蓝牙适配器地址（缺权限时返回 null）。
     */
    @SuppressLint("MissingPermission")
    fun getAddress(adapter: BluetoothAdapter): String? {
        return try {
            adapter.address
        } catch (_: SecurityException) {
            null
        }
    }
}
