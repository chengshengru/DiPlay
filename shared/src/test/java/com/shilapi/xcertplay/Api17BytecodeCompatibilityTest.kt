package com.shilapi.xcertplay

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * API 17 字节码防线（本分支 minSdk 17，车机是 Android 4.2.2 / Dalvik）。
 *
 * 背景：`PlatformTetheringStart` 曾用 `catch (ReflectiveOperationException)`。这个类在 Java 7 / Android
 * API 19 才出现，Dalvik 验证器解析 catch 类型失败会**拒绝整个类**，于是在 API 17 上打开设置页
 * （`CarHotspotTethering.appStartSupported()` 首次触碰该类）直接 `VerifyError` 崩溃。
 *
 * 修复后只捕获 API 1 就存在的具体异常。但有一个必须守住的包名事实：
 *
 * | 异常 | 真实包名 | 备注 |
 * | --- | --- | --- |
 * | NoSuchMethodException | `java.lang` | API 1，`java.lang.reflect` 没有这个类 |
 * | NoSuchFieldException | `java.lang` | 同上 |
 * | IllegalAccessException | `java.lang` | 同上 |
 * | InstantiationException | `java.lang` | 同上 |
 * | ReflectiveOperationException | `java.lang` | 仅 API 19+，本分支禁止引用 |
 * | InvocationTargetException | `java.lang.reflect` | API 1，唯一在 reflect 包里的那个 |
 *
 * `java.lang.reflect.X` 版本的这几个类**在任何 JDK 与任何 Android 版本上都不存在**。一旦有人用
 * 「显式 import java.lang.reflect.X」的方式，「修复」缺失的编译期解析，APK 里就会带上指向不存在类的
 * 引用，真机照样 VerifyError——而这在 JVM 单测里完全不报错。本测试扫描本模块编译产物，把这两类问题
 * 都挡在编译期。
 */
class Api17BytecodeCompatibilityTest {

    /**
     * 任何 JDK / Android 版本都不存在的类。本地 SDK 的 android.jar 曾被注入这几个伪造类以让错误
     * 的 import 编译通过，那属于构建环境被污染，不是许可证。字符串拆件拼接，避免本测试自身的
     * 类文件常量池命中扫描模式。
     */
    private val phantomClasses = listOf(
        "java/lang/reflect/" + "NoSuchMethodException",
        "java/lang/reflect/" + "NoSuchFieldException",
        "java/lang/reflect/" + "IllegalAccessException",
        "java/lang/reflect/" + "InstantiationException",
        "java/lang/reflect/" + "ReflectiveOperationException",
    )

    /** 真实存在但高于 minSdk 17 的类：引用它同样会让 API 17 的 Dalvik 拒绝整类。 */
    private val aboveMinSdkClasses = listOf(
        "java/lang/" + "ReflectiveOperationException",
    )

    @Test
    fun compiledClassesNeverReferenceClassesThatDoNotExistOnAndroid() {
        val roots = listOf(
            // 主源码编译产物（进 APK 的字节码）
            com.shilapi.xcertplay.network.PlatformTetheringStart::class.java.protectionDomain.codeSource.location,
            // 测试源码编译产物（JVM 上运行，但同样不应携带幻影绑定）
            Api17BytecodeCompatibilityTest::class.java.protectionDomain.codeSource.location,
        ).map { File(it.toURI()) }.distinctBy { it.absolutePath }

        var scanned = 0
        val hits = mutableListOf<String>()
        for (root in roots) {
            val classFiles: List<Pair<String, ByteArray>> = when {
                root.isDirectory -> root.walkTopDown()
                    .filter { it.isFile && it.extension == "class" && !it.name.startsWith("Api17BytecodeCompatibilityTest") }
                    .map { it.relativeTo(root).path to it.readBytes() }
                    .toList()
                root.isFile && root.extension == "jar" -> ZipFile(root).use { zip ->
                    zip.entries().asSequence()
                        .filter { !it.isDirectory && it.name.endsWith(".class") && !it.name.contains("Api17BytecodeCompatibilityTest") }
                        .map { it.name to zip.getInputStream(it).readBytes() }
                        .toList()
                }
                else -> emptyList()
            }
            assertTrue("无法定位模块编译产物: ${root.absolutePath}", classFiles.isNotEmpty())
            for ((name, bytes) in classFiles) {
                scanned++
                val text = String(bytes, Charsets.ISO_8859_1)
                phantomClasses.forEach { landmine ->
                    if (text.contains(landmine)) hits += "$name -> $landmine（不存在于任何 Android 版本）"
                }
                aboveMinSdkClasses.forEach { landmine ->
                    if (text.contains(landmine)) hits += "$name -> $landmine（API 19+，minSdk 17 会 VerifyError）"
                }
            }
        }
        assertTrue("扫描了 $scanned 个类文件", scanned > 100)
        assertTrue(
            "发现 API 17 上车机将 VerifyError 的字节码引用:\n" + hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }
}
