# HeadUnitWireless-CarPlay ↔ 本地 DiPlay 功能对比与迁移指南

> **生成日期**: 2026-10-09
> **对比对象**:
> - **本地（A）**: `chengshengru/DiPlay` 工作区 — 基于主线 **DiPlay v0.2.14** 下移到 **API 17 (Android 4.2.2)**，包名 `com.shihab.diplay` / `com.shilapi.xcertplay*`
> - **目标（B）**: `NineThree/HeadUnitWireless-CarPlay` — 基于 **DiPlay-Legacy-Android v0.2.7** 再改，目标 **Android 4.2+**，包名 `com.legacy.golfwireless` / `com.legacy.headunitwireless`
> **结论前置**: 两者同源（共同始祖 `shilapi/xcertplay` → `shihabal3amri/DiPlay`），但**基线版本相差 7 个小版本、功能取舍相反**。本指南主张**只把 B 的老安卓专属强化补丁 cherry-pick 进 A，绝不整体替换 A**（替换会回退到 0.2.7 并丢失 HUD/集群/ADB/media3/automotive）。

---

## 1. 血缘与版本基线对照

```
xcertplay (shilapi)  GPL-3.0  ← 共同始祖
└─ shihabal3amri/DiPlay  (主线)
   ├─ A: chengshengru/DiPlay        ← 主线 v0.2.14 下移到 API 17（本工作区）
   └─ programmerguohuajing/DiPlay-Legacy-Android  (v0.2.7 低版本分支)
        └─ B: NineThree/HeadUnitWireless-CarPlay   ← 在 0.2.7 上做社区适配
```

| 维度 | A（本地） | B（NineThree） |
|---|---|---|
| DiPlay 基线 | **v0.2.14**（最新主线） | **v0.2.7**（Legacy-Android） |
| 目标 SDK | API 17（minSdk=17, targetSdk=17/28） | API 17（minSdk=17, targetSdk=22） |
| 构建链 | AGP 7.4.2 / Gradle 7.6.4 / Kotlin 1.9.22 | AGP 9.x / Gradle 9.5.0 / Kotlin 现代版 |
| 模块 | common, mobile, **automotive**, shared, **maphost**, **home** | common, mobile, shared, **legacy-mdns** |
| 视频方案 | **保留 media3 1.1.1** + MediaCodec 路径 | **移除 media3**，自研老安卓 AVC 渲染器 |
| mDNS | jmdns **3.5.8**（库依赖） | **legacy-mdns** 模块（vendored 打补丁 JmDNS 3.6.3） |
| Product flavors | 无 | `golf` / `universal` 双 flavor |
| APK 体积/特性 | 含完整 BYD HUD/集群/ADB/仪表 | 删除厂商 HUD/仪表/电池/ADB，仅通用无线 |

---

## 2. 功能差异逐表

### 2.1 连接与热点

| 功能 | A（本地 v0.2.14） | B（NineThree v0.2.7） | 差异判定 |
|---|---|---|---|
| 有线 USB | ✅ | ✅（保留 iAP2/USB） | 同 |
| 无线 Wi-Fi Direct | ✅（`WirelessHotspotManager` 等 20+ 文件） | ✅（保留无线协议路径） | A 更全 |
| 车机热点 / 现有 Wi-Fi / 同局域网 | ✅（`ManualHotspotManager`, `CarHotspotSetup`） | ✅（手动热点配置） | A 更全 |
| **丢失接口的热点一次性恢复（API17–22）** | ❌ 无 `LegacyHotspotRecovery` | ✅ `shared/.../network/LegacyHotspotRecovery.kt`（`recoverIfNeeded`） | **B 独有 → 建议移植** |
| 服务发现 mDNS（旧安卓多播端口复用） | ⚠️ jmdns 3.5.8 库，未修 API17 多播 `SO_REUSEADDR` | ✅ `legacy-mdns` 模块（CHANGELOG golf.2：bind 前 `SO_REUSEADDR`） | **B 独有补丁 → 建议移植** |

### 2.2 HUD / 集群 / 厂商集成

| 功能 | A | B | 差异判定 |
|---|---|---|---|
| BYD HUD 导航（箭头/距离/街名） | ✅（v0.2.14 验证固件） | ❌ golf.1 已删除 | A 更全，勿回退 |
| DiLink 3/4/5.1 仪表集群 | ✅ | ❌ 删除 | A 更全 |
| 电池 / 轮速 / 停车视频 | ✅ | ❌ 删除 | A 更全 |
| ADB 集成 | ✅ | ❌ 删除 | A 更全 |

### 2.3 音频 / 麦克风

| 功能 | A | B | 差异判定 |
|---|---|---|---|
| 本地认证 mFi | ✅（Carlinkit 提取身份） | ✅（同，Release 附身份） | 同 |
| 麦克风 / Siri 上行 | ✅（mic source fallback） | ✅（legacy AudioRecord，绑定 CarPlay 本地接口，实验性） | 各有实现，可互鉴 |
| 通话回声消除 / 清晰通话 | ✅（v0.2.14 新增实验项） | 部分（通话控制 HID） | A 更新 |

### 2.4 视频渲染 / 显示

| 功能 | A | B | 差异判定 |
|---|---|---|---|
| 渲染表面 | ✅ `CarPlayVideoSurface.kt:6` 已有 `TEXTURE/SURFACE` 双模式枚举 | ✅ 用不透明 `TextureView` + `LegacyFullscreen` | A 更灵活 |
| 画质预设（均衡/流畅/清晰 + 画布缩放） | ⚠️ `InterfaceSize.kt`（自动~200%） | ✅ `GolfDisplaySettings.kt`：BALANCED(8/10)/SMOOTH(6/10)/NATIVE(10/10) + 64dp 顶栏避让 | **B 的画布缩放预设更具体 → 部分可借鉴** |
| 帧率上限 | 配置项 | ✅ 硬限 **30fps H.264** | B 明确上限 |
| 老安卓全屏 flag（API17–19） | ⚠️ 现代 insets | ✅ `LegacyFullscreen.kt`（≤API19 旧 flag，避免首触消费） | **B 独有 → 建议移植** |
| 自研 AVC 解码器（API17–20 基本格式） | ❌（用 media3） | ✅ `LegacyAvcDecoderSelection` / `VideoDecodeQueue` / `VideoOutputDrain` | B 独有但**A 不需要（保留 media3）** |

### 2.5 方向盘按键

| 功能 | A | B | 差异判定 |
|---|---|---|---|
| 固定按键映射 | ✅ `WheelKeyService.kt` / `CarPlayRemoteKeys` / `CarPlayMediaKeys` | ✅ 上游同款 + 额外 | A 已有 |
| **显式 OEM 按键学习（扫描码，无猜测数值）** | ❌ | ✅ `GolfHardwareKeyDispatcher.kt` / `GolfKeyBindings.kt` / 学习对话框 | **B 独有 → 建议移植（可选）** |

### 2.6 启动 / 自动化

| 功能 | A | B | 差异判定 |
|---|---|---|---|
| 连接时自动开启 | ⚠️ `DiPlayActivity.kt` / `DiPlayBootstrap.kt` 已有相关设置 | ✅ `GolfBootReceiver` + `GolfAutoConnectionGate`（开机启动 + 最多等 90s 自动连） | 重叠，**核实后扩展** |

### 2.7 构建 / 工程

| 维度 | A | B | 说明 |
|---|---|---|---|
| automotive (AAOS car.app) | ✅ | ❌ | A 更全 |
| maphost/home 示例 | ✅ | ❌ | A 更全 |
| 多 flavor | ❌ | ✅ golf/universal | B 的 flavor 与本地 launcher（`CarPlayHostActivity`）不同，**不必移植** |
| 测试数 | 大量（本地 CI） | 315 项自动测试 | 均充分 |

---

## 3. 三类归并（决定哪些搬、哪些不搬）

- **A 已具备且更全（勿回退）**：BYD HUD/集群/电池/ADB、automotive 模块、media3 视频、maphost/home、WheelKeyService 固定映射。
- **B 独有、对老安卓有价值（建议移植）**：① `legacy-mdns` 补丁 JmDNS；② `LegacyHotspotRecovery`；③ `LegacyFullscreen` + `GolfDisplaySettings` 画布预设；④ 方向盘按键学习 UI；⑤ 开机/自动连接（视本地已有程度扩展）。
- **B 独有但 A 不应采用（不移植）**：自研 AVC 渲染器（A 保留 media3）、golf/universal flavor 与 `GolfWirelessActivity` launcher（A 用 `CarPlayHostActivity`）、被 B 删除的厂商集成（A 已保留）。

---

## 4. 迁移总原则

1. **只增不减**：把 B 的补丁作为新增文件/模块加入 A，不删除 A 的 HUD/集群/ADB/media3/automotive。
2. **基线不回退**：不要 `git merge` 或整体复制 B 的树（那会把 A 从 0.2.14 拉回 0.2.7 并丢掉上游新功能）。逐文件 cherry-pick + 手工对齐。
3. **命名隔离**：B 的 `Golf*` 类带入 A 后建议改名（如 `Legacy*` / `Api17*`），避免与 A 的通用实现混淆；B 的 `SharedPreferences` 名 `golf_wireless_display` 改为 A 的命名空间前缀。
4. **API 守卫一致**：沿用 A 已建立的 `compat/ApiLevel.kt`（`isAtLeast`/`isBelow`）做版本分支，不要新写 `Build.VERSION.SDK_INT` 裸比较。
5. **许可证**：全链路 GPL-3.0。B 的 `docs/THIRD_PARTY_NOTICES.md` 含上游署名，移植时保留；新增文件头保留原作者与 GPLv3 声明。

---

## 5. 逐模块迁移步骤

### M1 — 移植 `legacy-mdns`（JmDNS 多播补丁）【高优先·低风险】

**为什么**：A 用 jmdns 3.5.8 库，`CarPlayBonjour.kt:28-31,283` 直接 `JmDNS.create(...)`。API17 多播绑定若不 `SO_REUSEADDR` 会在热点/同 LAN 场景下端口占用失败（B 的 CHANGELOG golf.2 已证实并修复）。

**步骤**：
1. 把 B 的 `legacy-mdns/` 整个模块拷到 A 根目录（含 `build.gradle.kts`、`src/main/java/javax/jmdns/...`、`LICENSE`、`README.md`）。
2. A `settings.gradle.kts` 增加 `include(":legacy-mdns")`。
3. A `shared/build.gradle.kts` 把 `testImplementation(libs.jmdns)` 改为 `implementation(project(":legacy-mdns"))`（保留 test 用库或一并换）。
4. A `gradle/libs.versions.toml` 可保留 `jmdns = 3.5.8` 给测试，但主路径走 vendored 源码。
5. `CarPlayBonjour.kt` 的 `import javax.jmdns.*` 不变（包名一致），运行期即生效。

**风险**：低。vendored 源码包名与库一致，仅替换实现；需确认 A 的 `compileSdk`/desugaring 对该模块生效（它已 `multiDexEnabled` + `coreLibraryDesugaring`）。
**验证**：`shared/src/test/java/.../network/LegacyMdnsPortReuseTest.java`（B 自带回归测试，一并移植）→ 本地 `./gradlew :shared:testDebugUnitTest` 通过；实车同 LAN 场景下 mDNS 发现不再报端口占用。

### M2 — 移植 `LegacyHotspotRecovery` 接入本地热点编排【高优先·中风险】

**为什么**：A 热点子系统文件极多（`ManualHotspotManager.kt`、`CarHotspotStatus.kt`、`CarHotspotSetup.kt`、`WirelessHotspotManager.kt` 等），但无「已启用 AP 丢失接口后一次性恢复」。B 的 `LegacyHotspotRecovery.recoverIfNeeded` 正是此逻辑，且带 retry budget / 截止时间 / cancel 守卫。

**步骤**：
1. 拷贝 `shared/src/main/java/com/shilapi/xcertplay/network/LegacyHotspotRecovery.kt` 到 A 同路径。其 `Port` 接口需适配 A 的 `CarHotspotStatus`/热点管理器：实现 `state()`、`configuredSsid()`、`setEnabled()`，桥接到 A 的 `ManualHotspotManager` 或 `WirelessHotspotManager`。
2. 在 A 的热点连接编排处（参考 `shared/.../orchestration/CarPlayController.kt` 或 `common/.../CarHotspotSetup.kt` 的轮询/状态监听）加入：每轮检测 `interfaceReady`，未就绪且 `api in 17..22` 时调用 `recoverIfNeeded(interfaceReady, deadline, cancelled)`。
3. 复用 A 已有的 `WirelessStartupRetryBudget.kt`（A 已有 retry budget 概念）替换 B 的 `processBudget`，保持单一预算来源。
4. 移植 B 的 `shared/src/test/java/.../network/LegacyHotspotRecoveryTest.kt` 到 A。

**风险**：中。需把 B 的 `Port` 抽象落到 A 的具体热点管理器上，接口形态可能不一致；建议先只接 `ManualHotspotManager`（A 的手动热点路径）做最小闭环。
**验证**：`LegacyHotspotRecoveryTest` 通过；实车 API17–22 车机：手动关掉热点接口 5s 后观察是否自动恢复一次（诊断日志应有 `HotspotRecovery ... attempt=1`）。

### M3 — 移植方向盘按键学习 UI【可选·中风险】

**为什么**：A 已有固定 `WheelKeyService`/`CarPlayRemoteKeys`，但无「显式扫描 OEM 按键码、无猜测数值」的学习对话框。B 的 `GolfHardwareKeyDispatcher` + `GolfKeyBindings` + 学习对话框提供该能力。

**步骤**：
1. 拷贝 B `common/.../GolfHardwareKeyDispatcher.kt`、`GolfKeyBindings.kt` 及学习对话框（`GolfKeyLearningDialog*`）到 A，重命名为 `Api17KeyLearning*` 或并入 A 的 `settings/` 包。
2. `GolfKeyBindings` 的持久化（`SharedPreferences`）改用 A 的 `settings_` 前缀与 `SettingsTranslationsTest` 体系（A 的 AGENTS.md 要求 `settings_*` 字符串进多语言）。
3. 在 A 的 Settings（Connection/Vehicle 分类，见 `DiPlayActivity.kt` 的 `SettingsInformationArchitecture`）增加「方向盘按键学习」入口，复用 A 既有 `filteredSection(...)` 模式。
4. 移植 B 对应测试（`GolfKeyLearningDialogTest`、`GolfKeyBindingsTest`、`GolfHardwareKeysTest`）。

**风险**：中。A 的按键体系是 `CarPlayRemoteKeys`/`CarPlayMediaKeys` 常量式，B 是动态绑定；需决定是「并存」还是「替换」固定映射。建议并存：固定映射保底，学习映射覆盖。
**验证**：`SettingsTranslationsTest` 通过（新增 `settings_*` 字符串均有翻译）；对话框能录一次 prev/next/phone/voice 并落盘，重新进入显示已学映射。

### M4 — 显示质量预设 + `LegacyFullscreen`【中优先·低风险（全屏）/中风险（画布）】

**步骤**：
1. 拷贝 `LegacyFullscreen.kt` 到 A 同包（几乎零依赖，直接可用）。在 A 的 `CarPlayHostActivity` 进入全屏处（参考 `CarPlayVideoSurface.kt` 的表面初始化）对 `SDK_INT < 20` 调用 `LegacyFullscreen.apply(window, hideTop, hideBottom)`。
2. `GolfDisplaySettings.kt` 的 `Quality` 枚举（BALANCED/SMOOTH/NATIVE + `scaleTenths`）与 `CarPlayDisplayScale` 一并拷贝；与 A 的 `InterfaceSize.kt` **合并**而非覆盖——A 已有「自动~200%」，新增「画布缩放 60/80/100% + 30fps 上限」作为独立设置项，挂到 Display 分类。
3. `topBarInsetDp = 64` 顶栏避让作为独立开关（A 的 `CarPlayHostActivity` 已有系统栏感知，可复用 `SystemBarSettings`）。
4. 移植 `GolfDisplayDialogTest` / `UniversalDisplayTest`。

**风险**：全屏部分低；画布缩放部分中——需确认 A 的 `CarPlayDisplayScale`/`AirPlayDisplayConfig` 是否存在（B 引用的 `airplay.AirPlayDisplayConfig` 在 A 中通常也有，因 A 含 AirPlay）。若 A 用 SurfaceView 路径，画布缩放仅影响投影像素，不影响底层 Surface。
**验证**：`GolfDisplayDialogTest` 通过；实车确认 API17/18 全屏不触发「首触消费导航栏」；画质切换在下次连接生效（B 设计：save 后 next-connection 应用，A 应一致）。

### M5 — 开机启动 + 自动连接【低优先·低风险】

**步骤**：
1. 拷贝 `GolfBootReceiver.kt` + `GolfAutoConnectionGate.kt`，改名为 `Api17BootReceiver`/`AutoConnectionGate`。
2. `GolfBootReceiver` 中 `GolfWirelessActivity` 改为 A 的 launcher（`CarPlayHostActivity` 或 `DiPlayActivity`）。
3. 在 A `AndroidManifest.xml` 注册 `BOOT_COMPLETED` 接收器（A 当前的 Manifest 已按 API17 裁剪过，见迁移指南 §3）。
4. 与 A 已有的 connect-on-open（`DiPlayActivity.kt`/`DiPlayBootstrap.kt`）**合并**：若 A 已支持「连接时自动开启」，则只补充「开机启动 + 最多 90s 等已存手机/蓝牙/热点后尝试一次」的逻辑，避免重复开关。
5. 默认关闭（B 设计：三个默认-off 选项），符合 A 的「实验项标记 (experimental)」约定。

**风险**：低。纯增量；注意 `BOOT_COMPLETED` 在 API17 无需运行时权限。
**验证**：开启开关→重启车机→确认仅打开 launcher 不自动开热点/录音；手动改热点后取消自动化逻辑。

### M6（不移植）— 自研 AVC 渲染器

B 的 `LegacyAvcDecoderSelection`/`VideoDecodeQueue`/`VideoOutputDrain` 是**为替换 media3 而写**。A 已保留 media3 1.1.1（minSdk21，API17–20 靠运行时版本保护，见 A `common/build.gradle.kts:60` 注释）。**不要**用 B 的渲染器替换 media3——会丢掉 A 更成熟的播放路径与大量 media3 测试。仅当后续 A 决定彻底移除 media3 时才考虑。

### M7（不移植）— golf/universal flavor 与厂商集成删除

B 的 `productFlavors { golf/universal }`、独立 `GolfWirelessActivity` launcher、以及 golf.1 删除的 HUD/仪表/电池/ADB，**均不应进入 A**。A 的 launcher 是 `CarPlayHostActivity`，且 A 刻意保留这些厂商功能（v0.2.14 验证过）。

---

## 6. 构建 / 依赖对齐注意事项

- A 用 AGP 7.4.2 + Gradle 7.6.4；B 用 AGP 9 + Gradle 9.5。**不要**为迁补丁而升级 A 的构建链（A 的迁移指南 §2.6 记录了一堆 AGP7.4.2 实测坑，升级需另立任务）。补丁文件本身不依赖 AGP9 语法，可在 AGP7.4.2 下编译。
- B 的 `legacy-mdns` `build.gradle.kts` 用了 `compileSdk { version = release(37) }`（AGP8+ 语法）。拷到 A 时需改为 A 的 `compileSdk = 36` 写法（`libs` 里没有 `release()` DSL）。
- B 的 `multiDexEnabled = true` + `coreLibraryDesugaring` 与 A 一致，无需改。
- B 的 robolectric 4.17 vs A 的 4.9：移植 B 的测试时按 A 的版本号调整依赖。

---

## 7. 许可证与署名

- 全链路 GPL-3.0。B 的 `docs/THIRD_PARTY_NOTICES.md` 含 `xcertplay`/`DiPlay`/`DiPlay-Legacy-Android` 上游署名，**移植补丁时保留**。
- 新增/修改文件头部保留原作者（NineThree / programmerguohuajing）与 GPLv3 声明；A 的 `docs/licenses` 已含 AGPL（DiAuto）与 GPL（xcertplay），补 `legacy-mdns` 的 JmDNS 许可证（BSD 风格，见其 `LICENSE`）。
- Release 打包：A 与 B 均把「实验性配件认证材料」排除在源码外、仅 Release APK 附带；移植不影响 A 现有的 `DIPLAY_AUTH_ASSETS_DIR` 外部资产流程。

---

## 8. 验证清单（迁移后）

| # | 验证项 | 命令 / 方法 | 期望 |
|---|---|---|---|
| 1 | legacy-mdns 端口复用测试 | `./gradlew :shared:testDebugUnitTest`（含 `LegacyMdnsPortReuseTest`） | PASS |
| 2 | 热点恢复单测 | `LegacyHotspotRecoveryTest` | PASS |
| 3 | 按键学习单测 | `GolfKeyLearningDialogTest`/`GolfKeyBindingsTest` | PASS |
| 4 | 显示对话框单测 | `GolfDisplayDialogTest`/`UniversalDisplayTest` | PASS |
| 5 | 多语言字符串 | `SettingsTranslationsTest`（A） | 新增 `settings_*` 均有各 locale 翻译 |
| 6 | 全量 CI | A 的 `.github/workflows/android.yml` | `:shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug` 全绿 |
| 7 | 实车-同 LAN 发现 | API17 车机，同 LAN 模式 | mDNS 不再报端口占用，iPhone 可被发现的间隔稳定 |
| 8 | 实车-热点恢复 | 手动制造接口丢失 | 5s 后自动恢复一次，诊断日志含 `HotspotRecovery` |
| 9 | 实车-全屏 | API17/18 车机 | 全屏不消费首触、顶栏避让生效 |
| 10 | 回归-不丢功能 | 对照 A 原有 HUD/集群/ADB/media3 | 全部仍可用（确认 M6/M7 未误移植） |

---

## 9. 一句话总结

B 是「在更老的 0.2.7 上做减法 + 老安卓补丁」的社区分支；A 是「在更新的 0.2.14 上做减法（仅下移到 API17）」的自用分支。**A 的上游功能更全，缺的是 B 的老安卓专属补丁**——所以正确动作是**把 B 的 `legacy-mdns` 补丁、热点恢复、老式全屏、画布预设、按键学习、开机自动连接 cherry-pick 进 A，并严格保留 A 的 HUD/集群/ADB/media3/automotive 与 v0.2.14 基线**。

---

## 10. 迁移执行记录（2026-10-09，第一轮：低风险项）

### 已完成

| 项 | 状态 | 改动 |
|---|---|---|
| **M1 `legacy-mdns`** | ✅ 已完成并验证 | 新增模块 `legacy-mdns/`（63 文件，vendored JmDNS 3.6.3，`JmDNSImpl.java:494 candidate.setReuseAddress(true)` 补丁）；`settings.gradle.kts:32` 注册；`shared/build.gradle:65` 改用 `project(':legacy-mdns')`；`common/build.gradle.kts:69` 测试依赖同步替换；`libs.versions.toml` 移除 `jmdns` 条目；移植回归测试 `shared/.../network/LegacyMdnsPortReuseTest.java`；`shared/build.gradle` 增 `testImplementation 'org.ow2.asm:asm:9.9'` |
| **M4 `LegacyFullscreen`** | ✅ 已完成并验证 | 新增 `common/.../LegacyFullscreen.kt`；`CarPlayHostActivity.kt:4892` 在 `applyFullscreenMode()` 插入 `if (LegacyFullscreen.apply(window, hideTop, hideBottom)) return` 守卫（API<20 接管，API≥20 返回 false 走原 `WindowInsetsControllerCompat` 路径） |
| **M5 开机/自动连接** | ✅ 无需迁移 | 本地已有 `common/.../BootReceiver.kt` + Manifest `BOOT_COMPLETED` 注册（绑定 `AirPlayPersistence.loadAutoStartOnBoot`），已覆盖 B 的 `GolfBootReceiver` 能力 |

### 验证结果

| # | 项 | 方法 | 结果 |
|---|---|---|---|
| 1 | 模块编译 | `./gradlew :legacy-mdns:compileJava :shared:compileDebugKotlin :common:compileDebugKotlin`（JDK17） | ✅ BUILD SUCCESSFUL，无重复类冲突，零新增警告 |
| 2 | 补丁功能 | 独立 harness 编译并以 JUnitCore 实跑 `LegacyMdnsPortReuseTest`（绕过本机既有坏的测试源集） | ✅ **OK (2 tests)** |
| 3 | 端到端链接 | `./gradlew :mobile:compileDebugKotlin` | 见下方结果 |
| 4 | 既有测试源集 | `:shared:testDebugUnitTest` | ⚠️ 本机既有编译失败（`RemoteMfiAuthenticationClientTest.kt` 引用 `sun.net.httpserver`、`HotspotJoinPlatformTest.kt` 引用隐藏 API `setPassphrase`），**与本次迁移无关**，属 JDK17 环境预先存在问题 |

### 待办（中风险，下一轮）

- **M2 `LegacyHotspotRecovery`**：接入本地 `ManualHotspotManager`（需适配 `Port` 接口）。→ 见 §11，已完成。
- **M3 方向盘按键学习 UI**：改名 + 并入本地 `settings_` 体系与多语言。→ 见 §11，**逻辑层已完成，UI 层仍待办**。
- **M4 画布预设**：与本地 `InterfaceSize.kt` 合并（保留 A 的自动~200%，新增 60/80/100% + 30fps）。→ 见 §11，已完成。

### 本地构建环境提示（复用）

默认 `JAVA_HOME` 指向 JDK 25，会使 Gradle 7.6.4 解析 `*.gradle.kts` 失败；须：
```sh
export JAVA_HOME=/Users/chengshengru/Library/Java/JavaVirtualMachines/corretto-17.0.20.1/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
```

---

## 11. 迁移执行记录（2026-10-09，第二轮：中风险项 M2 / M3 / M4）

### 已完成

| 项 | 状态 | 改动（位置 | 改动） |
|---|---|---|
| **M2 热点丢接口恢复** | ✅ 代码完成并实跑验证 | `shared/.../network/CarHotspotStatus.kt`：**增量**补上 `State` 枚举 + `fromLegacyState`/`stateEnabled` 旁路（保留 A 原有 `isEnabled`/`read`，不改既有调用点）；新增 `shared/.../network/LegacyHotspotRecovery.kt`（B 逐字移植 + 署名头）；`ManualHotspotReadiness.kt`：`await()` 增加**向后兼容默认参数** `onPoll: () -> Unit = {}` 轮询钩子；`ManualHotspotManager.kt`：构造 `LegacyHotspotRecovery` 并在 `onPoll` 中按 `api in 17..22` 调用 `recoverIfNeeded`；`CarHotspotSettings.kt` 新增 `legacyRecoveryEnabled`（默认 **false**，opt-in）；`CarPlayController.kt:2117` 附近传入开关 |
| **M3 方向盘按键（逻辑层）** | ✅ 代码完成并实跑验证 | 新增 `common/.../HardwareKeyBindings.kt`（B `GolfKeyBindings` 去前缀移植，prefs 文件 `diplay_hardware_keys`，**无硬编码中文**，标签交给设置 UI 本地化）；`CarPlayHostActivity.kt:1132-1152`：`dispatchKeyEvent` 增加**守卫式一次性重映射**（命中已学映射才把 OEM 信号换成逻辑键再递归分发，无映射时完全不改变原行为） |
| **M4 显示质量预设** | ✅ 代码完成并实跑验证 | 新增 `common/.../settings/DisplayQualityPresets.kt`（`BALANCED 0.8x` / `SMOOTH 0.6x` / `NATIVE 1.0x` + `TOP_BAR_INSET_DP=64` + `previewSize` + `topBarInsetPx`），**建立在本地上游更全的 `CarPlayDisplayScale`（已支持 30%–160% 任意百分比）之上**，是薄映射而非重复实现 |

### 与 B 的**刻意差异**（重要）

| # | 决策 | 理由 |
|---|---|---|
| 1 | **不移植 `GolfHardwareKeyDispatcher`** | A 已有完整按键路由（`super.dispatchKeyEvent` → MediaSession → `CarPlayMediaKeys`，外加 `CarPlayRemoteKeys` / `WheelKeyService` / `CarPlayCallKeys` / `legacySiriPresses`）。B 的 dispatcher 是为 B 自己的后端抽象写的；搬进 A 会变成**重复路由 + 未接线死代码**，故只保留「绑定表 + 一次性重映射」 |
| 2 | **不替换 A 的 `WirelessStartupRetryBudget`** | 那是「连接重试」语义，与 B 的「热点恢复预算」不是一回事，语义混用会引入难查的回归 |
| 3 | **M2 默认关闭** | `CarHotspotSettings.legacyRecoveryEnabled` 默认 `false`，需车机实测（丢接口时序因固件而异）后再默认开启 |
| 4 | **M4 不新增 60/80/100% 滑杆** | A 的 `CarPlayDisplayScale.applyPercent` 已覆盖 30%–160%，重复造滑杆无收益；只补 B 独有的**命名预设**与**顶栏避让**语义 |

### 验证结果

| # | 项 | 方法 | 结果 |
|---|---|---|---|
| 1 | 模块编译 | `./gradlew :legacy-mdns:compileJava :shared:compileDebugKotlin :common:compileDebugKotlin :mobile:compileDebugKotlin`（JDK17） | ✅ **BUILD SUCCESSFUL**（49 tasks），零新增警告 |
| 2 | M2 回归 | 独立 harness：`kotlinc` 编译 `LegacyHotspotRecoveryTest.kt` + `JUnitCore` 实跑 | ✅ **OK (15 tests)** |
| 3 | M4 回归 | 独立 harness：`kotlinc` 编译 `DisplayQualityPresetsTest.kt` + `JUnitCore` 实跑 | ✅ **OK (5 tests)** |
| 4 | M3 回归 | 独立 harness：`kotlinc` 编译 `HardwareKeyBindingsTest.kt` + Robolectric 4.9 + `JUnitCore` 实跑 | ✅ **OK (5 tests)** |
| 5 | 「未学习时为 no-op」 | 由 M3 测试用例 `observedOemCodesAreUnmappedUntilOwnerSavesThem` 覆盖 | ✅ |

### 环境坑（下次复用，省时间）

1. **`:common:testDebugUnitTest` 在本仓库当前状态下编译不过**（既有 18 个错误），根因是**测试写在 Robolectric 4.16 API 上而 `common/build.gradle.kts:66` 钉死 4.9**，例如
   `ShadowDisplayManager.addDisplay(String)` 被传多参、`GraphicsMode` 未解析、`setIsGroupOwner` 未解析等。
   → **与本轮迁移无关**，但会让 `AGENTS.md` 里的 CI 命令直接失败，建议单独一轮把 `robolectric` 升到 `4.16`（`libs.versions.toml:20` + `common/build.gradle.kts:66`）。
2. **Robolectric 测试在 SDK > 28 会被静默跳过**：本模块 `targetSdk = 17`（`common/build.gradle.kts:13`），Robolectric 因此选 legacy 资源模式，报 `doesn't support legacy mode after P` 后 **skip 而不 fail**。故 M3 移植测试的 `@Config` 用 **`sdk = [28]`** 而非 B 原版的 29（逻辑与 SDK 无关，28 在 legacy/binary 两种模式下都能跑）。
3. **独立 Robolectric harness 必备**：`conscrypt-openjdk-uber:2.5.2.jar`（否则 `NoClassDefFoundError: org/conscrypt/OpenSSLProvider`）；classpath 用一次性的 Gradle init 脚本从 `compileDebugUnitTestKotlin.libraries` 打印取得（94 项）。
4. `kotlinc` 命令行需 `kotlin-compiler-embeddable` + `trove4j` + `kotlin-daemon-embeddable` + `annotations-23.0.0`；访问 `internal` 需 `-Xfriend-paths=<模块 class 目录>`。

### 仍未完成（下一轮）

- **M3 的 UI 层**：学习界面（显示候选 OEM 码 → 绑定到 上/下/电话/语音）、设置入口、`settings_*` 多语言（按 `AGENTS.md` 需写入 `values/` 与全部 `values-xx/`）。
- **M2 真机验证**：`legacyRecoveryEnabled` 打开后在有丢接口现象的车机上确认恢复时序，再考虑默认值。
