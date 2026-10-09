# 必须留在主 dex：确保 attachBaseContext 在 MultiDex.install 之前就能调用崩溃处理器，
# 从而捕获 multidex 安装失败 / 早期类初始化崩溃（这类早期崩溃会让车机桌面/前台 UI 一起崩）。
-keep class com.shilapi.xcertplay.CrashFileLogger { *; }
-keep class com.shilapi.xcertplay.DiPlayApplication { *; }
-keep class com.shilapi.xcertplay.BootReceiver { *; }
