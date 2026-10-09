package com.shilapi.xcertplay

import android.content.Context
import androidx.multidex.MultiDexApplication

/**
 * API 17 迁移：minSdk 降到 17 后，API 17-20 设备使用 legacy multidex。
 * Core library desugaring 要求 multidex，因此应用类继承 [MultiDexApplication]，
 * 在 attachBaseContext 中自动安装第二、三个 dex。
 *
 * @author TRAE AI
 */
class DiPlayApplication : MultiDexApplication() {
    override fun attachBaseContext(base: Context) {
        // Install the crash handler BEFORE MultiDex.install runs inside super.attachBaseContext,
        // so we also capture multidex failures and early class-init crashes — the most likely
        // cause of a crash-at-startup that takes down the head unit's launcher / causes a boot loop.
        CrashFileLogger.install(this)
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
    }
}
