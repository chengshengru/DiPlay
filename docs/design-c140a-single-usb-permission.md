# DiPlay · GM C140A 单次 USB 授权设计文档

> 目标：在 GM C140A（Android 4.2.2 / API 17，MyLink Captive）车机上，把「连接 iPhone 时需要在车机点两次『允许读取 USB 数据』」收敛为**一次用户授权**，直接进入 CarPlay 信任界面。
> 约束：DiPlay 是侧载自用 App，**非系统/签名特权应用**，不持有 `android.permission.USB_PRIVILEGED` / `MANAGE_USB`。
> 状态：设计稿（v1）。仅框架层改动，禁止触碰爬虫/`Spiders/*`/`Crawlers/*`、KSPlayer 源码。

---

## 1. 问题定义与现状链路

「两次授权」= **同一个系统 USB 权限弹窗（`UsbPermissionActivity` / `UsbConfirmActivity`，文案「允许读取 USB 数据」「Allow DiPlay to access iPhone?」）出现两次**，不是 iPhone 屏幕上的「信任此电脑」对话框（那是第三类、必须点的）。

### 1.1 已确认的有线启动链路（file:line 实证）

| 阶段 | 代码 | 行为 |
|---|---|---|
| 发现 iPhone | `CarPlayController.kt:2034` `iphoneHost.discover().firstOrNull()` | 初始配置（未切 CarPlay 的配置集） |
| **第一次申请权限** | `CarPlayController.kt:2056` `iphoneHost.requestPermission(device)` → `IphoneUsbHost.kt:116-122` | `!hasPermission` 时 `usbManager.requestPermission(...)` → **弹窗 #1** |
| 读配置 / 决定是否重枚举 | `CarPlayController.kt:2090-2129` `handleIphonePermission` | 非 CarPlay 模式 → `WiredConfigurationAction.REQUEST_TRANSITION` |
| 触发 iPhone 重枚举 | `CarPlayController.kt:2172-2198` `beginReenumeration` → `IphoneUsbHost.kt:157-193` 发 Apple 私有请求 `0x52`（IN，wIndex=4） | iPhone 切到 CarPlay 配置集（config 6：NCM + USBMUX），**总线重枚举 = detach+attach** |
| 重枚举后再次发现 | `CarPlayController.kt:2263-2271` `onIphoneAttached(device)` → `requestIphonePermission(device)` | 重枚举设备被 Android 视为「新 attach」，权限被撤销 → **弹窗 #2** |
| 读重枚举后配置并继续 | `CarPlayController.kt:2090-2118` `handleIphonePermission` → `openDataPaths` | 此时已是 CarPlay 配置 → `USE` |

### 1.2 根因（铁证）

**Android 在 USB 设备 detach→attach（重枚举）后会撤销对该设备的 USB 权限授予**（API 17 的 `UsbSettingsManager` 对每次 attach 重新评估）。因此 `onIphoneAttached`（:2267）里的 `requestIphonePermission(device2)` 必然再次走到 `usbManager.requestPermission` → 弹窗 #2。

现有无障碍自动确认 `UsbAutoConfirmService`（`UsbAutoConfirmService.kt`）会尝试自动点选弹窗，但：
- 它依赖 `android:id/alwaysUse` 勾选「一律允许」使授权持久化（:55-59）；若 C140A 的弹窗**没有该控件**或文本不匹配，则授权为会话级、重枚举必丢。
- 第二弹窗的窗口类名/文案在 C140A 上若与 `isTargetPrompt`（:117-121）/`USB_ACTIVITIES`（:97-100）不匹配，自动确认会漏点 → 用户手动点两次。

### 1.3 硬约束（决定方案上限）

非系统应用**无法**用 `UsbManager.grantPermission(device)` 给自己授权（该 API 需 `USB_PRIVILEGED`，运行时 `SecurityException`）。因此：
- **不可能**对任意「新身份设备」彻底消灭系统弹窗；
- **可能**的是：让重枚举后的设备被识别为「同一台已授权 iPhone」，并在身份可保留时借 `alwaysUse` 持久化授权，把弹窗从 2 次降到 1 次；身份无法保留时，用无障碍自动确认把第 2 次「无感化」（用户只点第 1 次）。

---

## 2. 车型识别（对齐现有样板）

项目已有车型识别范式：`GeelyHeadUnit`（按 `Build.PRODUCT/DEVICE/MODEL/MANUFACTURER/BRAND` 正则）、`DiLinkGeneration`（按 `Build.PRODUCT/FINGERPRINT/DISPLAY` + 正则 + 系统属性反射，`setup/DiLinkGeneration.kt`）、`BydOutputSettings.kt:175-177`（按 `Build.MANUFACTURER/PRODUCT/DEVICE` 含 "BYD"）。

新增 `GmC140AHeadUnit`（放在 `common/src/main/java/com/shilapi/xcertplay/network/` 或 `setup/`，与 Geely 同级）：

```kotlin
package com.shilapi.xcertplay.setup

import android.os.Build

/** GM C140A (MyLink Captive, Android 4.2.2 / API 17) head-unit detection. */
object GmC140AHeadUnit {
    private val TOKENS = Regex("C140A|MYLINK|CAPTIVE|CHEVROLET|GMC|GM[ _-]?", RegexOption.IGNORE_CASE)
    private val FIELDS = listOf(Build.MODEL, Build.PRODUCT, Build.DEVICE, Build.MANUFACTURER, Build.BRAND)

    /** 仅当任一字段命中 GM/C140A/MyLink 标记时为真；其余车机零影响。 */
    fun isGmC140A(): Boolean = FIELDS.any { it?.let(TOKENS::containsMatchIn) == true }

    /** 诊断用：把命中的字段回显，便于真机确认识别是否准确。 */
    fun evidence(): String = FIELDS.mapIndexedNotNull { i, v ->
        if (v?.let(TOKENS::containsMatchIn) == true) "${arrayOf("MODEL","PRODUCT","DEVICE","MANUFACTURER","BRAND")[i]}=$v" else null
    }.joinToString(" ") { it }
}
```

> 注：C140A 的 `Build` 字段需真机回显确认（见 §6）。若 `MODEL` 不是字面 "C140A"，按真机实际值收窄 `TOKENS`。

---

## 3. 多方案选项

| 方案 | 思路 | 能否彻底 1 次 | 风险 | 可行性 |
|---|---|---|---|---|
| **A. 同机识别 + alwaysUse 持久化（推荐）** | C140A 上缓存首次已授权 iPhone 签名；重枚举后若身份一致且 `hasPermission` 仍 true，直接 `openDataPaths` 跳过二次申请；并强制勾选 `alwaysUse` | 若 C140A 重枚举保留身份 → **真 1 次**；否则退化为 B | 低（仅框架层、车型门控） | 高 |
| **B. 无障碍自动确认强化（兜底/并行）** | 增强 `UsbAutoConfirmService` 对 C140A 第二弹窗的命中（文本兜底「一律允许/总是允许/默认使用」、窗口类放宽） | 用户点 1 次，第 2 次被自动点 | 极低 | 高（已有服务） |
| **C. 反射 `grantPermission`（特权兜底）** | C140A 上对重枚举设备反射调用隐藏 `UsbManager.grantPermission` | 仅当 App 恰持有 `USB_PRIVILEGED` 时有效 | 中（反射、无特权时 no-op 需优雅降级） | 低（自用侧载无特权，仅作保险） |

**推荐组合：A 为主 + B 必做兜底 + C 作为无副作用的保险调用。** A 把「身份可保留」场景做到真 1 次；B 保证「身份不可保留」时用户只点一次；C 在极少数已 root/系统签场景直接免弹。

---

## 4. 详细设计（改动点 + 代码片段）

### 4.1 改动 1：C140A 同机识别缓存（新增常量 + 缓存字段）

文件：`CarPlayController.kt`

- 新增常量与字段（约 :191 `usbManager` 附近）：
```kotlin
private val gmC140A = GmC140AHeadUnit.isGmC140A().also { if (it) debugLog("head unit detected: GM C140A (${GmC140AHeadUnit.evidence()})") }
// 缓存首次已授权 iPhone 的稳定签名（vid/pid/serial），用于重枚举后「同机即已授权」快速判断
private var grantedIphoneSignature: Triple<Int, Int, String?>? = null
```
- 在 `handleIphonePermission` 的 `Granted` 分支（:2093 后）记录签名：
```kotlin
if (gmC140A) {
    grantedIphoneSignature = Triple(result.device.vendorId, result.device.productId, result.device.serialNumber)
}
```

### 4.2 改动 2：`onIphoneAttached` 跳过二次申请（核心，file:line :2263）

```kotlin
private fun onIphoneAttached(device: UsbDevice) {
    when (phase) {
        Phase.REENUMERATION, Phase.IPHONE -> {
            availabilityPollGeneration.incrementAndGet()
            // C140A 优化：重枚举设备若为同一台已授权 iPhone 且权限仍在，直接开数据路径，跳过第二次弹窗
            if (gmC140A && grantedIphoneSignature != null) {
                val (v, p, s) = grantedIphoneSignature!!
                val sameDevice = device.vendorId == v && device.productId == p &&
                        (s == null || device.serialNumber == null || device.serialNumber == s)
                if (sameDevice && requireUsbManager().hasPermission(device)) {
                    connectionDiagnostic("C140A: re-enumerated iPhone matches granted signature; skipping 2nd permission request")
                    openDataPaths(device)
                    return
                }
                connectionDiagnostic("C140A: re-enumerated device differs or permission revoked; will re-request (vid=0x${device.vendorId.toString(16)} pid=0x${device.productId.toString(16)} hasPerm=${requireUsbManager().hasPermission(device)})")
            }
            requestIphonePermission(device)
        }
        else -> Unit
    }
}
```

> 说明：若 C140A 重枚举**保留身份**（`hasPermission` 仍为 true），此分支直接 `openDataPaths` → **零第二次弹窗**。若身份变化/被撤销，则仍走 `requestIphonePermission`，由 §4.3 的自动确认兜底。

### 4.3 改动 3：`UsbAutoConfirmService` 强化（B 方案，兜底）

文件：`UsbAutoConfirmService.kt`

- `alwaysUse` 文本兜底（:55-59 的 `viewIdResourceName == "android:id/alwaysUse"` 之外，增加按文案命中）：
```kotlin
private val ALWAYS_USE_LABELS = setOf("一律允许", "总是允许", "默认使用", "默认使用该设备", "Always allow", "Use by default")
// 在 visit 中：node.isCheckable && !node.isChecked && node.isEnabled &&
//   (node.viewIdResourceName == "android:id/alwaysUse" || node.text?.toString()?.trim() in ALWAYS_USE_LABELS)
```
- `isTargetPrompt`（:117-121）保持「应用名 + USB 词」双命中；`USB_DEVICE_WORDS`（:106）已含 `iPhone`，对第二弹窗同样生效。若 C140A 第二弹窗类名不是 `android.app.Dialog`/`UsbPermissionActivity`，放宽 `isSystemUsbWindow`（:108-109）的 `SYSTEM_PACKAGES` / 类名白名单（先诊断确认，见 §6）。

### 4.4 改动 4（可选保险）：反射 `grantPermission`（C 方案）

文件：`IphoneUsbHost.kt` 在 `requestPermission`（:116）前，对 C140A 且 `!hasPermission` 尝试一次反射授权：
```kotlin
if (GmC140AHeadUnit.isGmC140A() && !usbManager.hasPermission(device)) {
    runCatching { UsbGrantReflection.grant(usbManager, device) } // 无特权时抛异常被吞，无副作用
}
```
`UsbGrantReflection` 用 `Class.forName("android.hardware.usb.UsbManager").getMethod("grantPermission", UsbDevice::class.java)` 反射。仅在持有 `USB_PRIVILEGED` 时生效，否则 `SecurityException` 被 `runCatching` 吞掉，退回标准弹窗 + 自动确认。

---

## 5. 风险分级

| 风险 | 等级 | 说明 / 缓解 |
|---|---|---|
| C140A `Build` 字段不含预设 token → 识别失效 | 中 | §6 先真机回显 `evidence()` 收窄 `TOKENS`；识别失败则整段不生效，回归现状（不引入回归） |
| 同机签名靠 `serialNumber`，部分 iPhone 该字段为 null | 低 | 代码中 `s == null` 时仅比对 vid/pid，仍安全跳过 |
| 过度放宽 `UsbAutoConfirmService` 误点非目标弹窗 | 中 | `isTargetPrompt` 维持「应用名 + USB 词」双命中，不会误点其它系统弹窗 |
| 反射 `grantPermission` 在部分 ROM 触发 `NoSuchMethod` | 低 | `runCatching` 全吞，无副作用 |
| 重枚举后 `hasPermission` 在 C140A 必定 false（身份必变） | 中 | 退化为 B：用户点 1 次，第 2 次无障碍自动确认；仍达成「只手动授权一次」 |

---

## 6. 验证（真机判据）

1. **先确认识别**：在 `CarPlayController` 启动处加 `debugLog("head unit: ${GmC140AHeadUnit.evidence()}")`，侧载后看诊断报告确认是否命中 C140A。
2. **确认双弹窗根因（用已有诊断）**：`recoverFromReenumeration`（:2230-2234）已打印重枚举设备的 `vid/pid/hasPermission`。连一次，看日志：
   - 若 `hasPermission=true` → 身份保留 → 方案 A 直接消灭第 2 弹窗（预期真 1 次）。
   - 若 `hasPermission=false` → 身份被撤 → 依赖方案 B 自动确认第 2 弹窗（用户只点 1 次）。
3. **构建**：本机无 Android SDK（相对 `sdk.dir` 不存在），在构建机 `./gradlew :mobile:assembleDebug`，零警告门禁（`Locale`/`UUID` 等 import 不得冗余）。
4. **真机**：连 iPhone，确认车机只弹一次「允许读取 USB 数据」，随后进入 iPhone 的「信任此电脑」→ CarPlay。
5. **回归**：非 C140A 车机（如 Geely/BYD）行为不变（`gmC140A=false` 整段跳过）。

---

## 7. 交付与回滚

- 改动集中在 `CarPlayController.kt`、`UsbAutoConfirmService.kt`（新增 `GmC140AHeadUnit.kt`、`UsbGrantReflection.kt` 可选）。
- 全部车型门控（`gmC140A` 开关），非 C140A 路径与现状字节一致，可安全回滚（移除 `GmC140AHeadUnit` 引用 + 还原 `onIphoneAttached`）。
- 不触碰：爬虫/`Spiders/*`/`Crawlers/*`、KSPlayer、USB 底层 `UsbDescriptorCompat` 协议逻辑（仅在其上层编排处加判断）。
