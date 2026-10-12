plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.shilapi.xcertplay.host"
    // 本机 android-33/34/35 jar 为裁剪版（缺 TetheringManager 等类），使用完整的 android-36
    compileSdk = 36

    defaultConfig {
        minSdk = 17
        targetSdk = 17
        // API 17-20：desugaring 要求 legacy multidex
        multiDexEnabled = true
    }

    compileOptions {
        // 使用 Java 8 以支持 desugaring
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        // Compose 不支持 API 17，改用 View 系统
        viewBinding = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // The UI suite covers several SDKs and locale-specific resource sandboxes.
        unitTests.all { it.maxHeapSize = "2g" }
    }

    lint {
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }
}

dependencies {
    api(project(":shared"))

    // Core library desugaring - 必须
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // API 17-20 legacy multidex（DiPlayApplication 继承 MultiDexApplication，需暴露给壳应用）
    api(libs.androidx.multidex)

    // AndroidX 兼容库
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // main #488：WorkManager 每日后台更新检查（2.8.1 兼容 AGP 7.4.2 与 API 17）
    implementation(libs.androidx.work.runtime)
    // main #598：设置备份二维码（zxing core 纯 Java 库，兼容 API 17）
    implementation(libs.zxing.core)

    // 移除所有 Compose 依赖（API 17 不支持）

    // 车机视频播放：media3 1.1.1（minSdk 21），API 17-20 设备靠运行时版本保护
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)

    testImplementation(libs.junit)
    // Robolectric 4.10 支持 SDK 16-33，可对 API 17（Android 4.2.2）做 JVM 单元测试
    testImplementation("org.robolectric:robolectric:4.10")
    testImplementation("org.mockito:mockito-core:5.2.0")
    // 测试用同一 vendored JmDNS（避免与 shared 运行时里的实现重复类）
    testImplementation(project(":legacy-mdns"))
}
