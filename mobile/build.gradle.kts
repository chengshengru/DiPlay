plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 可选的本地认证资产输入；CI 与普通源码构建不包含配件身份。
// 优先使用 DIPLAY_AUTH_ASSETS_DIR 环境变量；未设置时回退到本地 .private/runtime-assets
// （已被 gitignore 排除）。这样在 IDEA 直接 Run 时也会自动打包认证文件，而 CI 上该目录
// 不存在，行为保持 source-only 不变。
val localAuthenticationAssets = (providers.environmentVariable("DIPLAY_AUTH_ASSETS_DIR").orNull
    ?: File(rootDir, ".private/runtime-assets").takeIf {
        it.resolve("offline-mfi/identity.pk8").isFile && it.resolve("offline-mfi/certificate.p7b").isFile
    }?.path)?.let { file(it).canonicalFile }

android {
    namespace = "com.shilapi.xcertplay"
    // 本机 android-33/34/35 jar 为裁剪版（缺 TetheringManager 等类），使用完整的 android-36
    compileSdk = 36

    defaultConfig {
        applicationId = "com.shihab.diplay"
        minSdk = 17
        targetSdk = 17
        versionCode = 37
        versionName = "0.2.18-api17"

        // API 17-20：desugaring 要求 legacy multidex
        multiDexEnabled = true

        // 保证崩溃处理器类在主 dex：attachBaseContext 在 MultiDex.install 之前就要调用它
        multiDexKeepProguard = file("multidex-keep.pro")

        // 低版本设备只安装 ARM 产物，保持与原有 ABI 一致
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
    }

    localAuthenticationAssets?.let { sourceSets.getByName("main").assets.srcDir(it) }

    signingConfigs {
        create("release") {
            storeFile = file(
                providers.environmentVariable("ANDROID_KEYSTORE_PATH")
                    .getOrElse("missing-release-keystore.jks"),
            )
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").getOrElse("")
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").getOrElse("")
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").getOrElse("")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".hudtest"
            versionNameSuffix = "-hud-test"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        // 使用 Java 8 以支持 desugaring
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
        // 启用 core library desugaring（java.time / Optional / CompletableFuture）
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        // Compose 不支持 API 17，改用 View 系统
        viewBinding = true
    }
    lint {
        // API 17 兼容分支中大量使用已废弃的旧 API，属预期行为
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":shared"))

    // Core library desugaring - 必须
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // AndroidX 兼容库
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // 移除所有 Compose 依赖（API 17 不支持）
}

// No implicit import. Only the two explicitly selected local runtime assets are allowed.
// AGP 7.4.2：AndroidSourceDirectorySet 使用 srcDirs（AGP 9 的 directories 属性不存在）
val credentialAssets = files(android.sourceSets.flatMap { source ->
    source.assets.srcDirs.map { directory ->
        fileTree(directory) {
            include("**/offline-mfi/**", "**/*.pk8", "**/*.p7b", "**/*.key",
                "**/*.pem", "**/*.p12", "**/*.pfx", "**/*.jks", "**/*.keystore")
        }
    }
})
val rejectBundledCredentials by tasks.registering {
    group = "verification"
    description = "Reject unexpected credential files in APK assets."
    val filesToCheck = credentialAssets
    val allowed = localAuthenticationAssets?.let { dir ->
        listOf("identity.pk8", "certificate.p7b").map { dir.resolve("offline-mfi/$it").canonicalFile }.toSet()
    } ?: emptySet()
    inputs.files(filesToCheck)
    doLast {
        check(allowed.all { it.isFile }) { "Explicit local authentication assets are incomplete" }
        val unexpected = filesToCheck.files.filter { it.canonicalFile !in allowed }
        check(unexpected.isEmpty()) { "Unexpected credential files in APK assets" }
    }
}
tasks.named("preBuild") { dependsOn(rejectBundledCredentials) }

// Car-test packages must be standalone. Keep ordinary source/CI builds identity-free.
val verifyStandaloneAuthentication by tasks.registering {
    group = "build"
    description = "Require the explicit runtime authentication input for a standalone car-test APK."
    val directory = localAuthenticationAssets
    doLast {
        check(directory != null) {
            "Standalone car builds require DIPLAY_AUTH_ASSETS_DIR; assembleDebug alone is source-only."
        }
        check(listOf("identity.pk8", "certificate.p7b").all {
            directory.resolve("offline-mfi/$it").let { file -> file.isFile && file.length() > 0 }
        }) { "Standalone CarPlay authentication files are missing or empty" }
    }
}
tasks.named("preBuild") { mustRunAfter(verifyStandaloneAuthentication) }
tasks.register("assembleStandaloneDebug") {
    group = "build"
    description = "Build a standalone car-test APK with explicitly provisioned authentication."
    dependsOn(verifyStandaloneAuthentication, "assembleDebug")
}
