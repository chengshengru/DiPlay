pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    // 0.5.0 要求 Gradle 7.6+（1.0.0 要求 Gradle 8+）；当前 wrapper 为 7.6.4（腾讯云镜像）
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.5.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "xcertplay"
include(":common")
include(":mobile")
include(":automotive")
include(":shared")
// vendored JmDNS 3.6.3（含 API17 多播 SO_REUSEADDR 补丁），替换 org.jmdns:jmdns 二进制
include(":legacy-mdns")
include(":maphost")
project(":maphost").projectDir = file("samples/maphost")
include(":home")
project(":home").projectDir = file("samples/home")
