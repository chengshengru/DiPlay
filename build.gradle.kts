// Top-level build file where you can configure project-wide settings.
buildscript {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
    }
    dependencies {
        // AGP 7.4.2 内置 D8/R8 4.0.52 无法解析 Kotlin 1.9 元数据（dexBuilder 报 kotlin.H），
        // 官方支持用 buildscript classpath 前置新版 R8 覆盖。
        classpath("com.android.tools:r8:8.2.42")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}

subprojects {
    // Java 8 字节码以支持 core library desugaring（API 17 兼容的前提）。
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        kotlinOptions {
            jvmTarget = "1.8"
        }
    }
}
