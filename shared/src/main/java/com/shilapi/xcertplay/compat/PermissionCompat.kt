package com.shilapi.xcertplay.compat

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 权限兼容性处理。
 *
 * API 17 没有运行时权限模型，所有权限在安装时授予；API 23+ 才引入运行时权限。
 * 该兼容层统一两种行为，调用方无需关心版本差异。
 *
 * @author TRAE AI
 * @since 2026-10-08
 */
object PermissionCompat {

    /**
     * 检查是否已授予单个权限。
     */
    fun hasPermission(context: Context, permission: String): Boolean {
        return if (ApiLevel.isBelow(ApiLevel.M)) {
            // API 17-22：权限在安装时授予，检查 manifest 声明即可
            val result = context.checkPermission(
                permission,
                android.os.Process.myPid(),
                android.os.Process.myUid()
            )
            result == PackageManager.PERMISSION_GRANTED
        } else {
            // API 23+：使用运行时权限检查
            ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 检查多个权限是否全部授予。
     */
    fun hasPermissions(context: Context, permissions: Array<String>): Boolean {
        return permissions.all { hasPermission(context, it) }
    }

    /**
     * 请求权限。低版本直接视为已授予并触发回调。
     */
    fun requestPermissions(
        activity: Activity,
        permissions: Array<String>,
        requestCode: Int
    ) {
        if (ApiLevel.isBelow(ApiLevel.M)) {
            // API 17-22：直接认为已授予，触发回调
            activity.onRequestPermissionsResult(
                requestCode,
                permissions,
                IntArray(permissions.size) { PackageManager.PERMISSION_GRANTED }
            )
        } else {
            // API 23+：请求运行时权限
            ActivityCompat.requestPermissions(activity, permissions, requestCode)
        }
    }

    /**
     * 检查是否需要显示权限用途说明。
     */
    fun shouldShowRequestPermissionRationale(
        activity: Activity,
        permission: String
    ): Boolean {
        return if (ApiLevel.isBelow(ApiLevel.M)) {
            // API 17-22：不需要说明
            false
        } else {
            // API 23+：检查是否需要说明
            ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        }
    }

    /**
     * 校验权限请求结果是否全部通过。
     */
    fun verifyPermissions(grantResults: IntArray): Boolean {
        return grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
    }
}

/**
 * 权限请求回调接口。
 */
interface PermissionRequestCallback {
    fun onPermissionsResult(permissions: Array<String>, grantResults: IntArray)
}
