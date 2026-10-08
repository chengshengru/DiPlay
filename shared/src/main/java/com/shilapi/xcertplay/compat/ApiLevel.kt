package com.shilapi.xcertplay.compat

import android.os.Build

/**
 * API 级别兼容性检查工具。
 *
 * 统一管理不同 Android 版本的 API 可用性，避免在各处散落
 * [Build.VERSION.SDK_INT] 的裸比较，便于低版本（API 17）兼容分支维护。
 *
 * @author TRAE AI
 * @since 2026-10-08
 */
object ApiLevel {
    // API 级别常量
    const val JELLY_BEAN = 17          // Android 4.2
    const val JELLY_BEAN_MR1 = 17      // Android 4.2
    const val JELLY_BEAN_MR2 = 18      // Android 4.3
    const val KITKAT = 19              // Android 4.4
    const val KITKAT_WATCH = 20        // Android 4.4W
    const val LOLLIPOP = 21            // Android 5.0
    const val LOLLIPOP_MR1 = 22        // Android 5.1
    const val MARSHMALLOW = 23         // Android 6.0
    const val NOUGAT = 24              // Android 7.0
    const val NOUGAT_MR1 = 25          // Android 7.1
    const val OREO = 26                // Android 8.0
    const val OREO_MR1 = 27            // Android 8.1
    const val PIE = 28                 // Android 9.0
    const val Q = 29                   // Android 10
    const val R = 30                   // Android 11
    const val S = 31                   // Android 12
    const val S_V2 = 32                // Android 12L
    const val TIRAMISU = 33            // Android 13
    const val UPSIDE_DOWN_CAKE = 34    // Android 14

    /** 常用别名，保持与文档中 `ApiLevel.M` / `ApiLevel.N` 等写法兼容。 */
    const val M = MARSHMALLOW
    const val N = NOUGAT

    /**
     * 检查当前 API 级别是否至少为指定版本。
     */
    @JvmStatic
    fun isAtLeast(level: Int): Boolean = Build.VERSION.SDK_INT >= level

    /**
     * 检查当前 API 级别是否低于指定版本。
     */
    @JvmStatic
    fun isBelow(level: Int): Boolean = Build.VERSION.SDK_INT < level

    /**
     * 检查当前 API 级别是否落在 [minLevel, maxLevel] 闭区间内。
     */
    @JvmStatic
    fun isInRange(minLevel: Int, maxLevel: Int): Boolean =
        Build.VERSION.SDK_INT in minLevel..maxLevel

    /**
     * 获取当前 API 级别。
     */
    @JvmStatic
    fun current(): Int = Build.VERSION.SDK_INT

    /**
     * 获取 Android 版本的可读名称，用于诊断日志。
     */
    @JvmStatic
    fun versionName(): String = when (current()) {
        JELLY_BEAN, JELLY_BEAN_MR1 -> "4.2 Jelly Bean"
        JELLY_BEAN_MR2 -> "4.3 Jelly Bean"
        KITKAT -> "4.4 KitKat"
        KITKAT_WATCH -> "4.4W KitKat"
        LOLLIPOP -> "5.0 Lollipop"
        LOLLIPOP_MR1 -> "5.1 Lollipop"
        MARSHMALLOW -> "6.0 Marshmallow"
        NOUGAT -> "7.0 Nougat"
        NOUGAT_MR1 -> "7.1 Nougat"
        OREO -> "8.0 Oreo"
        OREO_MR1 -> "8.1 Oreo"
        PIE -> "9.0 Pie"
        Q -> "10"
        R -> "11"
        S -> "12"
        S_V2 -> "12L"
        TIRAMISU -> "13"
        UPSIDE_DOWN_CAKE -> "14"
        else -> "Unknown"
    }
}
