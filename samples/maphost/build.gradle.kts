// Sample: how a launcher embeds DiPlay's live dashboard map. See docs/LAUNCHER_INTEGRATION.md.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.diplay.maphost"
    // 本机 android-33/34/35 jar 为裁剪版（缺 TetheringManager 等类），使用完整的 android-36
    compileSdk = 36

    defaultConfig {
        applicationId = "com.diplay.maphost"
        // 该示例依赖 SurfaceView.getHostToken 与 setChildSurfacePackage（API 30+），
        // 面向新设备，不参与 API 17 迁移
        minSdk = 30
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
