package com.shilapi.xcertplay

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager

/**
 * 老安卓（API < 20）的老式全屏实现。
 *
 * 移植自 NineThree/HeadUnitWireless-CarPlay（GPL-3.0），上游文字说明：
 * `WindowInsetsControllerCompat` 在 API 20 以下不实现系统栏可见性，故需回退到
 * `FLAG_FULLSCREEN` + `decorView.systemUiVisibility`。注意 API 17/18 的临时隐藏导航栏
 * 会吞掉首次触摸，因此仅在 API >= 19 且需要隐藏导航栏时才设置 HIDE_NAVIGATION。
 */
internal object LegacyFullscreen {
    /**
     * 应用老式全屏标志。
     * @return 仅在 API < 20（本对象接管）时返回 true；API >= 20 返回 false，调用方应回退到
     *         `WindowInsetsControllerCompat` 路径。
     */
    @Suppress("DEPRECATION")
    fun apply(window: Window, hideTop: Boolean, hideBottom: Boolean): Boolean {
        if (Build.VERSION.SDK_INT >= 20) return false
        if (hideTop) window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        // 不要绘制到 OEM 可能拒绝隐藏的系统栏之后；保留无关标志位。
        val owned = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_IMMERSIVE or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        var flags = window.decorView.systemUiVisibility and owned.inv()
        if (hideTop) flags = flags or View.SYSTEM_UI_FLAG_FULLSCREEN
        // API17/18 的临时导航栏隐藏会消费首次触摸；保持其可见。
        if (Build.VERSION.SDK_INT >= 19 && hideBottom) {
            flags = flags or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        window.decorView.systemUiVisibility = flags
        return true
    }
}
