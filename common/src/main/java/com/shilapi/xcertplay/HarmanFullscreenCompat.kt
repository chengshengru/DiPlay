package com.shilapi.xcertplay

import android.os.Build
import android.view.View
import android.view.Window

/** Harman 固件上回收 OEM 侧栏的老式 system-UI 模式（实验性，main #613，作者：TRAE AI）。 */
@Suppress("DEPRECATION")
internal object HarmanFullscreenCompat {
    internal const val OEM_FULLSCREEN_BIT = 0x8

    // SYSTEM_UI_FLAG_IMMERSIVE 是 API 19+ 才有意义的行为位;API 17 上只写
    // FULLSCREEN/HIDE_NAVIGATION 与 OEM 位,避免未知标志位的固件行为差异。
    private val SYSTEM_UI_FLAGS =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) View.SYSTEM_UI_FLAG_IMMERSIVE else 0) or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            OEM_FULLSCREEN_BIT

    fun apply(window: Window) {
        window.decorView.systemUiVisibility = SYSTEM_UI_FLAGS
    }

    fun clear(window: Window) {
        window.decorView.systemUiVisibility =
            window.decorView.systemUiVisibility and OEM_FULLSCREEN_BIT.inv()
    }
}
