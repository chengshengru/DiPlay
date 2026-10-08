package com.shilapi.xcertplay

import androidx.multidex.MultiDexApplication

/**
 * API 17 迁移：minSdk 降到 17 后，API 17-20 设备使用 legacy multidex。
 * Core library desugaring 要求 multidex，因此应用类继承 [MultiDexApplication]，
 * 在 attachBaseContext 中自动安装第二、三个 dex。
 *
 * @author TRAE AI
 */
class DiPlayApplication : MultiDexApplication()
