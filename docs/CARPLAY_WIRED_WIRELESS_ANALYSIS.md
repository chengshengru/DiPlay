# DiPlay `android-4.2.2` 分支有线 / 无线 CarPlay 支持性代码分析报告

> 分析范围：仅源码（Kotlin 实现 + 分支 diff），**不引用任何 README / 文档**。
> 目标分支：`android-4.2.2`（HEAD `74fe1a8`「移植到 Android 4.2.2」），对比基准 `origin/main`。
> 结论先行：**当前分支同时支持有线（USB）与无线（Wi-Fi）CarPlay，两条路径均完整、可达、有测试覆盖。**

---

## 一、从「用户设置」到「实际 transport」的完整链路

用户设置层是三态（`上次使用 / 无线 / USB`），但运行时最终必须落成一个二态的 `CarPlayTransport`。完整映射链路如下：

| 环节 | 文件 : 行 | 关键内容 |
|---|---|---|
| 模式枚举（三态） | `common/.../DiPlayBootstrap.kt:51-63` | `enum class DefaultConnectionMode { LAST_USED, WIRELESS, USB }`，其中 `wireless(lastUsedWireless: Boolean)` 把三态归约为布尔 |
| 偏好读取 | `common/.../DiPlayBootstrap.kt:77-78` | `autoConnectWireless() = defaultConnectionMode.wireless(loadWirelessEnabled)` |
| **上层写入 transport** | `shared/.../CarPlayHostActivity.kt:143, 176` | `createRuntimeConfig()` 中 `transport = if (wirelessEnabled) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED` |
| 枚举定义（二态） | `shared/.../orchestration/CarPlayRuntimeConfig.kt:9-12` | `enum class CarPlayTransport { WIRED, WIRELESS }` |

→ 设置里的「上次使用 / 无线 / USB」最终都收敛到 `config.transport`，**两条路径都能被选到**。

---

## 二、调度层双向分流（`CarPlayController.kt`）

`CarPlayController` 是连接调度的主干，对两种 transport 做了对称分支，而非偏废其一：

| 阶段 | 行号 | 分支逻辑 |
|---|---|---|
| 初始化 | `:435` | `if (config.transport == WIRED)` → 注册 USB 权限广播接收器，走有线路径 |
| 初始化 | `:454` | `else if (transport == WIRELESS)` → 调用 `restartWireless()`，走无线路径 |
| 启动 | `:1081` | 启动阶段再次按 `WIRELESS` / 否则分流，双路径均保留 |
| 拆除（无线栈） | `:615` | `transport == WIRELESS` 时关闭无线栈 |
| 拆除（NCM） | `:625` | 否则（WIRED）`service?.detach()` 分离 VPN/NCM |
| 业务标识 | `:2527 / :2535` | 按 transport 决定 MFi 目标与无线标志 |

---

## 三、两条路径的实现组件（均真实存在，非 stub）

### 3.1 有线（USB）

| 组件 | 文件 : 行 | 说明 |
|---|---|---|
| 宿主 | `IphoneUsbHost.kt:68`（类定义）；`CarPlayController.kt:185`（懒加载实例化） | USB 主机通道 |
| iAP2 控制 | `CarPlayController.kt:2029` `Iap2WiredControlClient(csm, …).run(…)`；`:2404` `Iap2WiredControlClient.NO_TIMEOUT_MILLIS` | 有线 iAP2 会话 |
| 网络层 | `NcmUsbBridge`（USB 网络共享）；`CarPlayController.kt:626` `service?.detach()` | USB 网卡桥接（NCM） |

### 3.2 无线（Wi-Fi）

| 组件 | 文件 : 行 | 说明 |
|---|---|---|
| iAP2 控制 | `Iap2WirelessControlClient.kt:19`（类定义）；`CarPlayController.kt:1382 / :1477`（实例化） | 无线 iAP2 会话 |
| 热点建立 | `WirelessHotspotManager`（import `:53`）；`CarPlayController.kt:1156` `startWirelessHotspot(...)` | Wi-Fi 热点（P2P / 本地认证） |
| 设备发现 | `CarPlayBonjour`（`:248 / :1190`） | Bonjour / mDNS 发现 iPhone |
| 承载隧道 | `CarPlayController.kt:1235` `CarPlayVpnService.attachWireless(...)` | AirPlay/CarPlay 隧道 |

---

## 四、分支相对 `origin/main` 的改动性质（证明移植未破坏，反而补强）

> 来源：`git diff origin/main...HEAD --stat`。所有命中文件均为「新增 / 扩展」，无删除逻辑。

| 改动文件 | 性质 | 对有线 / 无线的影响 |
|---|---|---|
| `transport/IphoneUsbHost.kt` (+68)、`transport/NcmUsbBridge.kt` (+68)、`Iap2LinkChannel.kt` (+3)、`UsbReadQueuePolicy.kt` (+16) | 增量扩展 | **有线 USB** 路径被适配/增强，未删逻辑 |
| `network/CarPlayDiscovery.kt` (+258)、`network/ExternalWifiConnectionManager.kt` (+346，新增)、`network/ExternalWifiMode.kt` (+117，新增)、`WifiP2pGroupManager.kt` (+51)、`WifiP2pLegacyChannelsApi17.kt` (+120，新增) | 无线栈重写 / 新增 | **无线**能力在本分支被显著强化 |
| `compat/*`（UsbPermissionCompat、NetworkCompat、BluetoothCompat、ApiLevel 等，大量新增） | 平台兼容桥接层 | 专门让 USB 权限、网络、蓝牙在 **Android 4.2.2（API 17）** 旧 API 上可用 → 是在**保住**有线 + 无线两条路径 |

结论：本分支是把主线能力向 Android 4.2.2 平台下沉，新增 `compat` 桥接层让旧系统也能跑通 USB 与 Wi-Fi 子系统，而非裁剪功能。

---

## 五、测试覆盖（两条路径都有验证）

| 路径 | 相关测试 |
|---|---|
| 有线（WIRED） | `CarPlayControllerUsbServiceTest`（WIRED 用例）、`ManualHotspotConfigTest`（WIRED）、`ExistingWifiSettingsTest`（WIRED 断言） |
| 无线（WIRELESS） | `WirelessStartupControllerTest`、`CarHotspotFallbackTest`、`WirelessHandoffWatchdogTest`、`CarPlayControllerUsbServiceTest`（WIRELESS 用例）、`ExistingWifiSettingsTest`（WIRELESS 断言） |

---

## 六、结论与实机验证提醒

✅ **支持有线 + 无线 CarPlay**：`android-4.2.2` 分支两条路径均完整、可达、且被测试覆盖。无线路径在分支上反而得到增强（外部 Wi-Fi 连接管理、P2P 旧频道兼容、Discovery 重写），有线路径通过新增 `compat` 层完成旧平台适配。

⚠️ **唯一需实机/固件验证的点**：Android 4.2.2 是 API 17，**Wi-Fi P2P（5 GHz）** 在该年代固件上支持有限。代码已做多后端兜底：

- `CarPlayRuntimeConfig.kt:21-26` 定义外部 Wi-Fi 三模式（`LOCAL_ONLY_HOTSPOT` / `MANUAL` / `EXISTING_WIFI`）
- `CarPlayController.kt:102-147` 对应校验分支
- `WifiP2pLegacyChannelsApi17.kt` 提供旧频道兼容实现

这是**平台能力约束，不是「不支持无线」**——无线的三条热点模式都仍可用（本地认证热点 / 手动配置 / 已有 Wi-Fi），P2P 5 GHz 受限时可由其余模式兜底。

---

## 附：核心证据文件清单

```
common/src/main/java/com/shilapi/xcertplay/DiPlayBootstrap.kt          (DefaultConnectionMode 枚举 / 偏好读取)
shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt          (双向分流调度主干)
shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayRuntimeConfig.kt       (CarPlayTransport 枚举 / 无线热点校验)
shared/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt     (设置 → transport 写入点)
shared/src/main/java/com/shilapi/xcertplay/transport/IphoneUsbHost.kt                 (有线 USB 宿主)
shared/src/main/java/com/shilapi/xcertplay/transport/NcmUsbBridge.kt                  (有线 NCM 桥接)
shared/src/main/java/com/shilapi/xcertplay/transport/Iap2WiredControlClient.kt         (有线 iAP2)
shared/src/main/java/com/shilapi/xcertplay/transport/Iap2WirelessControlClient.kt      (无线 iAP2)
shared/src/main/java/com/shilapi/xcertplay/network/CarPlayDiscovery.kt                 (无线 Bonjour 发现)
shared/src/main/java/com/shilapi/xcertplay/network/ExternalWifiConnectionManager.kt    (无线外部 Wi-Fi 管理，新增)
shared/src/main/java/com/shilapi/xcertplay/network/WifiP2pLegacyChannelsApi17.kt       (无线 P2P 旧频道兼容，新增)
```
