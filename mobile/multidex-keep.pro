# 必须留在主 dex：确保 attachBaseContext 在 MultiDex.install 之前就能调用崩溃处理器，
# 从而捕获 multidex 安装失败 / 早期类初始化崩溃（这类早期崩溃会让车机桌面/前台 UI 一起崩）。
-keep class com.shilapi.xcertplay.CrashFileLogger { *; }
-keep class com.shilapi.xcertplay.DiPlayApplication { *; }
-keep class com.shilapi.xcertplay.BootReceiver { *; }

# app_process 入口类：shell 子进程经 CLASSPATH=<apk> app_process /system/bin <class> 启动。
# Dalvik（API 17-20）只从 APK 加载 classes.dex，入口类若落入 classes2+.dex，
# 子进程启动即报 "ERROR: could not find class '<入口类>'"（API 21+ ART 原生多 dex 无此限制）。
# 新增 app_process 入口时必须在此登记，否则 API 17 车机上静默失败。
-keep class com.shilapi.xcertplay.hud.BydClusterSongTool { *; }
-keep class com.shilapi.xcertplay.hud.BydAmbientLightTool { *; }
-keep class com.shilapi.xcertplay.hud.BydCallPopupTool { *; }
-keep class com.shilapi.xcertplay.hud.BydCarPlayCallTool { *; }
-keep class com.shilapi.xcertplay.hud.Byd13CatalogProbeMain { *; }
-keep class com.shilapi.xcertplay.network.CarHotspotStartMain { *; }
-keep class com.shilapi.xcertplay.network.WifiScanPauseMain { *; }
-keep class com.shilapi.xcertplay.network.HotspotJoinRepairMain { *; }
-keep class com.shilapi.xcertplay.mfi.LocalMfiProbe { *; }
