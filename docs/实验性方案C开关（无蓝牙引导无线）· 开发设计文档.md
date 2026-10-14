# 实验性方案C开关（无蓝牙引导无线）· 开发设计文档

> 分支：`android-4.2.2`（GM C140A / API 17）
> 作者：依 spec-first 流程，先出设计文档，按本文档逐步实现。
> 最后更新：2026-10-14（据 0.2.19 迁移后最新代码 + 双蓝牙架构诊断结论修订；同步代码标识符重命名为 `NoBluetoothBootstrap` 家族，并修正 §4/§7.3 的 config 注入事实）
> 范围：新增一个实验性开关，打开后无线 CarPlay 启动**彻底绕开蓝牙引导（含预检门禁、中部门禁、RFCOMM 引导段、iAP2 控制隧道）**，仅依赖「车机 Wi-Fi 端点 + Bonjour/mDNS 广播 + AirPlay listener」让 iPhone 在已连的同一 Wi-Fi 上自主发现并主动发起 CarPlay 会话。
> 约束：代码改动严格限制在框架层，不触碰 `Spiders/*`、`Crawlers/*`、KSPlayer 等第三方实现；本开关与既有「实验性② 无线连接绕过蓝牙开关配置」互补但不同（见 §1.3）。
> 命名约定：代码中本开关标识符统一为 `NoBluetoothBootstrap`（无线连接无蓝牙引导）家族，与实验性②的 `WirelessSkipBluetoothConfig`（跳过蓝牙*配置*、仍走 RFCOMM 引导）明确区分；本文档叙述仍以「方案C（无蓝牙引导无线）」作为功能昵称。

---

## 1. 背景与目标

### 1.1 当前无线 CarPlay 的连接模型（据 0.2.19 迁移后代码坐实）

`CarPlayController.runWireless(generation)`（`shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt:1212`）是当前无线启动的唯一主流程。下列行号均为当前代码实态（0.2.19）：

```
runWireless(generation)                                         :1212
 ├─ closeWirelessStack + generation 校验                         :1213-1222
 ├─ FirstTcpWatchdog（等待首个 AirPlay TCP）                      :1225
 ├─ MFi coprocessor client 校验                                  :1245
 ├─ ── 蓝牙预检门禁（方案A 在此）──
 │   └─ bluetoothAdapter / probeConnectedWirelessIphone          :1250-1263
 │       → adapter 为空 / 关且未暂停且无可连接 iPhone 则 throw
 ├─ startWirelessHotspot(generation)                             :1264  (定义 :2361)
 ├─ HotspotReady / WaitingForPairedIphone 状态                    :1341-1351
 ├─ ── 蓝牙中部门禁（方案A 在此再判）──
 │   ├─ bluetoothAdapter 取用 + BydBluetoothSuspend.resumeAndWait :1353-1356
 │   ├─ probeConnectedWirelessIphone / selectWirelessBluetoothDevice :1359-1372
 │   └─ !isEnabled && 无可连接 iPhone → throw RADIO_OFF
 ├─ wirelessAirPlayConfig = airPlayConfig.copy(deviceId, btMac)   :1378-1381
 ├─ attachWireless（AirPlay listener 绑定 + wirelessSessionListener）:1390-1410
 ├─ CarPlayBonjour.start()（mDNS 广播 CarPlay 服务）             :1422-1440
 ├─ ── RFCOMM 引导段（蓝牙）──
 │   ├─ createRfcommSocketToServiceRecord → connect              :1447-1487
 │   ├─ Iap2Session.openWireless（iAP2 CSM 通道）                 :1494
 │   ├─ Iap2WirelessCarPlayEndpoint(ssid/pass/ip/port...)        :1511-1533
 │   ├─ media.setIapTunnelHandler(::startWirelessTunnelControl)   :1539
 │   ├─ onStatus(RunningWireless)                                :1541
 │   └─ Iap2WirelessControlClient.run（把 endpoint 经蓝牙发给 iPhone）:1547-1562
 └─ catch（失败回滚）                                            :1600
```

**关键事实（与上一版文档不同，已据当前代码更正）**：`attachWireless`（:1390）与 `CarPlayBonjour.start()`（:1422）在**蓝牙中部门禁（:1353-1372）之后**，而非之前。也就是说，正常流程里 AirPlay listener 与 Bonjour 广播是在「蓝牙门禁通过后」才建立。因此无蓝牙引导无线必须：

- **绕过预检门禁（:1250-1263）**；
- **绕过中部门禁（:1353-1372）**；
- **跳过 RFCOMM 引导段（:1447-1562，含 iAP2 控制隧道）**；
- **但仍要执行** `attachWireless`（:1390）与 `CarPlayBonjour.start()`（:1422），并手动挂上 `media.setIapTunnelHandler(::startWirelessTunnelControl)`（:1539，否则 iPhone 经 Wi-Fi 发起的 iAP2 隧道无处理者），最后 `onStatus(RunningWireless)`（:1541）。

### 1.2 本车机的现实约束（已用诊断坐实：双蓝牙架构）

GM C140A（API 17）车机存在**双蓝牙架构**：

- iPhone 在系统蓝牙设置里显示「已连接 MyLink Captive」——这是车机**原厂蓝牙模块**的连接状态。
- DiPlay 通过 `BluetoothCompat.getAdapter()`（走 `getDefaultAdapter()`）拿到的是 Android 侧**另一块适配器**；0.2.19 真机诊断（无 `BLUETOOTH` 异常行）显示其 `isEnabled=false`、**已配对设备数=0**、无 ACL 连接。
- 两块射频互不相通，应用层**物理上拿不到**原厂模块那条连接——这是用户原始问题「能否获取已连接的 iPhone」的最终答案：**双蓝牙架构下，应用层获取不到。**

> 权限状态核实（2026-10-14）：`common/AndroidManifest.xml:10-11` 的 `BLUETOOTH` + `BLUETOOTH_ADMIN` 完好，`git log -S BLUETOOTH_ADMIN` 仅命中补加权限的提交 `3c2af54d`，**0.2.19 迁移未删除**。诊断弹窗已加「适配器名称/地址」两行（见 §12），可在真机与「MyLink Captive」比对当场确认双蓝牙。

因此在本机，**方案C（无蓝牙引导无线）从「OEM 屏蔽兜底」升格为首选路径**：常规蓝牙引导（经 Android 适配器）在本机根本走不通（适配器具断连、无配对），只有绕开蓝牙、依赖 iPhone 已记住车机 Wi-Fi 后的自主发起才可行。

### 1.3 无蓝牙引导无线 与「实验性②」的区别（重要）

| 开关 | 作用层 | 是否仍依赖蓝牙引导 | 解决的场景 |
| --- | --- | --- | --- |
| **实验性②**（已落地，`KEY_WIRELESS_SKIP_BT_CONFIG`，`AirPlayPersistence.kt:66`） | UI 层（`DiPlayActivity.choosePhone`） | **是**（仍走 RFCOMM 引导，仍需蓝牙 SPP 可用） | 仅跳过「打开蓝牙」弹框 |
| **方案C 无蓝牙引导无线（本开关）** | **协议层**（`CarPlayController.runWireless`） | **否**（绕过两道门禁 + 整段 RFCOMM 引导） | 蓝牙彻底不可用也能连（本机主路径） |

两者可叠加：实验性②负责「进门不卡弹框」，方案C负责「进门后不强依赖蓝牙」。

### 1.4 无蓝牙引导无线 的本质前提（必须让用户在 UI 上知晓）

Apple 无线 CarPlay 设计：**首次连接靠蓝牙 RFCOMM 引导**（把 Wi-Fi 端点推给 iPhone，建立信任 + 让 iPhone 记住车机 Wi-Fi）；**之后 iPhone 记住车机，自动重连同一 Wi-Fi 并通过 mDNS 自主发现车机 CarPlay 服务并发起会话**。

因此无蓝牙引导无线**只解决「二次连接 / 重连」**，**不解决「首次配对」**：
- 首次配对仍需走一次常规蓝牙引导（或依赖 iPhone 已记住本车机 Wi-Fi）。在双蓝牙架构下，这意味着 iPhone 必须先在系统设置里与**本车机 Android 适配器**完成一次配对并记住其 Wi-Fi（或手动连一次车机 Wi-Fi）。
- 无蓝牙引导无线打开后，若 iPhone 从未与本车机配对过 / 从未记住车机 Wi-Fi，则收不到 endpoint、不会主动发起 → 需 UI 提示用户先走常规流程一次（见 §6.3 / §10）。

---

## 2. 架构定位

```
SettingsCategory.ADVANCED
  └─ SettingsSection.EXPERIMENTAL_DISPLAY           (DiPlayActivity.kt:119；映射于 :154)
       └─ filteredSection(...) 卡片                 (DiPlayActivity.kt:2331-2332)
            ├─ Harman 全屏 / 分屏 / 旋转 / 侧栏
            ├─ ① USB 连接页实时日志
            ├─ ② 无线连接绕过蓝牙开关配置             (toggle：KEY_WIRELESS_SKIP_BT_CONFIG)
            └─ ★ ③ 无线连接（无蓝牙引导 / 方案C）     (新增 toggle，插在 ② 之后)

CarPlayController.runWireless()                     (CarPlayController.kt:1212)
  └─ 入口处：if (config.wirelessNoBluetoothBootstrap) return startWirelessNoBluetoothBootstrap(generation)
       ← 早返回，绕过 :1250-1263 / :1353-1372 / :1447-1562
       startWirelessNoBluetoothBootstrap(): startWirelessHotspot → 选地址 → attachWireless(:1390) → Bonjour(:1422)
                         → setIapTunnelHandler(:1539) → onStatus(RunningWireless :1541)
                         由已就绪的 wirelessSessionListener + FirstTcpWatchdog(:1225) 等待 iPhone 主动 TCP
```

> 开关值经 `CarPlayRuntimeConfig.wirelessNoBluetoothBootstrap` 注入（见 §7.3）：`CarPlayHostActivity.createRuntimeConfig()` 从 `AirPlayPersistence.loadWirelessNoBluetoothBootstrap(this)` 读取并填入 config，`CarPlayController.runWireless` 只读 `config.wirelessNoBluetoothBootstrap`。理由：`shared` 模块**不能反向依赖** `common`（`common → shared` 单向），故 `shared` 内的 `runWireless` 无法直接调用 `common` 的 `AirPlayPersistence`。

---

## 3. 核心机制说明（为什么跳过 RFCOMM 后 iPhone 仍能连）

1. **`attachWireless`（:1390-1410）把 CarPlay AirPlay listener 绑定到 `hostAddress:listenerPort`** 并注册 `wirelessSessionListener(generation, watchdog)`。该 listener 在独立线程接收**所有进来的 CarPlay 会话**，无论 iPhone 经蓝牙引导还是自主发现发起。无蓝牙引导无线跳过 RFCOMM 后，iPhone 自主发起的会话会被**同一个 listener 接住**。
2. **`CarPlayBonjour.start()`（:1422-1440）在 Wi-Fi 上广播 CarPlay 服务**（带 `advertisedAirPlayConfig` 的 IP/端口/公钥）。iPhone 连上同一 Wi-Fi 后，通过 mDNS 发现该服务、读取 TXT 记录中的车机标识，**主动发起 CarPlay StartSession**。
3. **`FirstTcpWatchdog`（:1225）已在 runWireless 开头启动**，职责是「等待首个 AirPlay TCP」——正是 iPhone 主动发起会话的第一个包。无蓝牙引导无线下该 watchdog 自然生效，无需改动。
4. **`media.setIapTunnelHandler(::startWirelessTunnelControl)`（:1539）在无蓝牙引导无线路径内必须手动挂上**（正常流程在 RFCOMM 段里挂；无蓝牙引导无线跳过该段，故需在 `startWirelessNoBluetoothBootstrap` 内、attach 之后显式设置），否则 iPhone 经 Wi-Fi 建立的 iAP2 隧道（type-130）无人处理（`startWirelessTunnelControl` 定义于 :1619）。
5. **iPhone 侧的记忆**：iPhone 首次经 RFCOMM 引导连过本车机后，会记住车机 Wi-Fi 与 CarPlay 信任关系。之后只要车机 Wi-Fi 端点（热点或同一外部 Wi-Fi）在播 CarPlay 服务，iPhone 即可**不依赖蓝牙**自主发起。

---

## 4. 改动文件清单

| 文件 | 改动类型 | 说明 |
| --- | --- | --- |
| `common/.../AirPlayPersistence.kt` | 新增 | `KEY_WIRELESS_NO_BLUETOOTH_BOOTSTRAP` 常量 + `loadWirelessNoBluetoothBootstrap` / `saveWirelessNoBluetoothBootstrap`（默认 `false`），插在 `saveWirelessSkipBluetoothConfig`（:476-477）之后 |
| `shared/.../orchestration/CarPlayController.kt` | 修改 | `runWireless()`（:1212）入口早返回 `startWirelessNoBluetoothBootstrap(generation)`；新增 `startWirelessNoBluetoothBootstrap()` 方法（见 §7.1） |
| `common/.../CarPlayHostActivity.kt` | 修改 | `createRuntimeConfig()`（:160 起）新增 `wirelessNoBluetoothBootstrap = AirPlayPersistence.loadWirelessNoBluetoothBootstrap(this)`，注入 `CarPlayRuntimeConfig` |
| `shared/.../orchestration/CarPlayRuntimeConfig.kt` | 修改 | 构造函数新增 `val wirelessNoBluetoothBootstrap: Boolean = false`（字段默认值区，:60-70 附近） |
| `common/.../DiPlayActivity.kt` | 修改（3 处） | ① `EXPERIMENTAL_DISPLAY` 卡内（:2332 之后）插无蓝牙引导无线 `toggle`；② `connect(wireless)`（:5342-5343）放宽入口；③ `choosePhone`（:5362）在无蓝牙引导无线下跳过蓝牙选设备 |
| `common/src/main/res/values/strings.xml` | 新增 | 2 个 `settings_*` 字符串（标题 + 描述，英文） |
| `common/src/main/res/values-{ar,es,ru,uk,zh-rCN,zh-rTW,ja}` | 新增 | 同上各语言（后缀遵循 AGENTS.md：英文 `(experimental)`、简体 `（实验性）`、繁体 `（實驗性）`、俄/乌 `（экспериментально）`、阿/西 `(experimental)`、日 `（実験的機能）`） |
| `common/.../test/.../ExperimentalTogglesSettingsTest.kt` | 修改 | 新增无蓝牙引导无线开关默认关 + 切换即持久化的用例（仿 `loadWirelessSkipBluetoothConfig` 用例） |

> 注：本开关**修改了 `CarPlayRuntimeConfig`**（新增 `wirelessNoBluetoothBootstrap` 字段），由 `CarPlayHostActivity` 在构建 config 时从 `AirPlayPersistence` 读取注入，`CarPlayController.runWireless` 只读 `config.wirelessNoBluetoothBootstrap`。原因见 §2 末注：`shared` 不能反向依赖 `common`，故 `runWireless` 无法直读 `AirPlayPersistence`；采用与 `wirelessHotspotMode` / `existingWifiSsid` 一致的 config 注入方式。

---

## 5. 持久化层（AirPlayPersistence.kt）

### 5.1 新增常量与方法（放在 :477 之后，`saveWirelessSkipBluetoothConfig` 之后）

```kotlin
// 实验性③（无蓝牙引导无线）：无线连接彻底绕开蓝牙引导，仅用 Wi-Fi + Bonjour 等待 iPhone 主动发起；默认关。
private const val KEY_WIRELESS_NO_BLUETOOTH_BOOTSTRAP = "wireless_no_bluetooth_bootstrap"

fun loadWirelessNoBluetoothBootstrap(context: Context): Boolean =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_WIRELESS_NO_BLUETOOTH_BOOTSTRAP, false)

fun saveWirelessNoBluetoothBootstrap(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putBoolean(KEY_WIRELESS_NO_BLUETOOTH_BOOTSTRAP, enabled)
        .apply()
}
```

`PREFS = "xcertplay_airplay"`（:28），沿用既有定义。

> 持久化 key 由 `wireless_plan_c` 更名为 `wireless_no_bluetooth_bootstrap`（2026-10-14 重命名）。旧 key 在设备上失效，已开启用户需重新打开该开关（实验性自用小开关，影响可忽略）。

---

## 6. UI 开关（DiPlayActivity.kt · EXPERIMENTAL_DISPLAY 卡）

### 6.1 插入位置

在实验性② `toggle` **之后**（卡片 `filteredSection` 内，:2332 之后）插入无蓝牙引导无线 `toggle`，与既有实验性项保持同一卡片、同一 `toggle(...)` 签名（:5929 `toggle(parent, title, description, value, enabled=true, save)`），且**无需重连**（仅影响下一次 `connect(true)` 读取，不调用 `markReconnectNeeded()`）：

```kotlin
// 实验性③（无蓝牙引导无线）：无线连接（无蓝牙引导）。默认关；打开后 runWireless 跳过两道蓝牙门禁 + RFCOMM 引导，
// 仅依赖车机 Wi-Fi + Bonjour 等待 iPhone 主动发起 CarPlay。需先完成过一次常规蓝牙配对/连车机 Wi-Fi。
toggle(card, getString(R.string.settings_wireless_no_bluetooth_bootstrap),
    getString(R.string.settings_wireless_no_bluetooth_bootstrap_description),
    AirPlayPersistence.loadWirelessNoBluetoothBootstrap(this)) {
    AirPlayPersistence.saveWirelessNoBluetoothBootstrap(this, it)
}
```

### 6.2 首次配对约束的 UI 提示（建议）

无蓝牙引导无线不解决首次配对。可加轻量判定：若开关打开但 `DiPlayPreferences.phoneAddress(this) == null`（从未配对），Toast 提示「无蓝牙引导无线需先完成一次常规蓝牙配对/连接车机 Wi-Fi，请关闭本开关走常规流程一次」。该判定不阻断流程，仅提示（细节落地时与用户确认）。

### 6.3 ★ 入口放宽（双蓝牙架构必备，无蓝牙引导无线 的硬依赖）

在双蓝牙架构下，`choosePhone`（:5362）的候选列表永远为空（Android 适配器无配对），且 `connect(wireless)`（:5342-5343）在 `phoneAddress == null` 时**强制走 `choosePhone()`**——这会把无蓝牙引导无线也挡在门外。必须放宽：

```kotlin
// connect(wireless)  :5342 附近
if (wireless && DiPlayPreferences.phoneAddress(this) == null) {
    // 无蓝牙引导无线：无蓝牙配对也不要求选设备，直接进入无线流程，由 runWireless 的 no-bluetooth-bootstrap 分支绕开蓝牙。
    if (AirPlayPersistence.loadWirelessNoBluetoothBootstrap(this)) {
        pendingWireless = true   // 保持原有 pending 语义
    } else {
        pendingWireless = true; choosePhone(); return
    }
}
```

`choosePhone`（:5362）在无蓝牙引导无线打开时也应**短路返回**（`choosePhone` 内开头加：`if (DiPlayPreferences.phoneAddress(this) == null && AirPlayPersistence.loadWirelessNoBluetoothBootstrap(this)) { pendingWireless = false; connect(true); return }`），避免弹「配对 iPhone」诊断框。

> 说明：无蓝牙引导无线的无线流程**不依赖被选中设备**（RFCOMM 段整段跳过），故 `phoneAddress` 是否为 null 不影响 `runWireless` 执行；放宽入口只是为了让用户在双蓝牙下能真正发起连接。

---

## 7. 核心改造（CarPlayController.kt · runWireless）

### 7.1 改造点 A：入口早返回 + 新增 `startWirelessNoBluetoothBootstrap(generation)` 方法

`runWireless`（:1212）在 generation 校验之后、预检门禁（:1250）之前插入早返回；新增 `startWirelessNoBluetoothBootstrap` 复用「Hotspot → 选地址 → attach → Bonjour → 挂隧道 handler → RunningWireless」最小路径，**完全避开**预检门禁、中部门禁、RFCOMM 段。开关值经 `config.wirelessNoBluetoothBootstrap` 读取（见 §7.3）：

```kotlin
private fun runWireless(generation: Int) {
    try {
        debugLog("wireless bring-up generation=$generation starting")
        closeWirelessStack(generation = generation)
        if (closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get()) return

        // 实验性③（无蓝牙引导无线）：彻底绕开蓝牙。早返回，避免预检门禁(:1250)/中部门禁(:1353)/RFCOMM(:1447) 误杀。
        // 双蓝牙架构下 Android 适配器无配对，常规蓝牙引导走不通，无蓝牙引导无线是首选路径。
        // 开关值经 config 注入（shared 不可反向依赖 common 的 AirPlayPersistence）。
        if (config.wirelessNoBluetoothBootstrap) {
            startWirelessNoBluetoothBootstrap(generation); return
        }

        // —— 以下为原有蓝牙引导流程（wirelessNoBluetoothBootstrap == false 时执行，保持不动）——
        val listenerIdentity = AirPlayListenerIdentity(generation)
        ...
    } catch (error: Throwable) { ... }
}

/**
 * 无蓝牙引导无线 分支：仅用 Wi-Fi + Bonjour 让 iPhone 主动发起 CarPlay，不触碰任何蓝牙 API。
 * 复用 runWireless 的启动骨架，但跳过预检门禁、中部门禁与 RFCOMM 引导段。
 */
private fun startWirelessNoBluetoothBootstrap(generation: Int) {
    synchronized(wirelessResourceLock) {
        if (isStaleWirelessRun(generation)) return
    }
    // 1) 建立车机 Wi-Fi 端点（MANUAL 热点 / EXISTING_WIFI / WIFI_P2P / LocalOnly）。
    val hotspotInfo = startWirelessHotspot(generation)
    if (isStaleWirelessRun(generation)) return
    pauseWifiScans(hotspotInfo.backend)
    val startedHotspot = hotspot
    // 2) 选地址（与 runWireless :1273-1305 同逻辑，挑可用 hostAddress）。
    val candidates = hotspotCandidates(hotspotInfo)
    val hostAddress = HotspotAddressPolicy.select(candidates, preferredFamily())
        ?: hotspotInfo.hostAddress
        ?: throw IOException("Wireless hotspot did not provide a usable host address")
    synchronized(wirelessResourceLock) {
        if (isStaleWirelessRun(generation)) return
        wirelessPublishedAddress = hostAddress
    }
    // 3) 构造 AirPlay config：btMac 用 deviceIdentifier 替代（无蓝牙 MAC 可用），供 iPhone 信任校验。
    val deviceIdentifier = hotspotInfo.bssid
        ?.takeUnless { it.equals(ADAPTER_ADDRESS_PLACEHOLDER, ignoreCase = true) }
        ?: airPlayConfig.deviceId
    val wirelessAirPlayConfig = airPlayConfig.copy(deviceId = deviceIdentifier, btMac = deviceIdentifier)
    onStatus(CarPlayStatus.HotspotReady(
        ssid = hotspotInfo.ssid, band = hotspotInfo.bandLabel, channel = hotspotInfo.channel,
        bssid = deviceIdentifier, address = hostAddressText(hostAddress),
        backend = hotspotInfo.backend.label))
    onStatus(CarPlayStatus.WaitingForPairedIphone)
    // 4) AirPlay listener 绑定（与 runWireless :1390 同调用）。
    val service = awaitVpnService() ?: throw IOException("Could not bind the CarPlay AirPlay service")
    synchronized(wirelessResourceLock) {
        if (isStaleWirelessRun(generation)) return
        when (val result = service.attachWireless(
            bindAddress = hostAddress, config = wirelessAirPlayConfig, identity = identity,
            pairings = pairings, mfi = mfi, listener = wirelessSessionListener(generation, firstTcpWatchdog
                ?: throw IOException("FirstTcpWatchdog not started")), listenerIdentity = firstTcpWatchdog!!.listener,
            media = media, additionalBindAddresses = hotspotInfo.hostAddresses.filter { it != hostAddress },
        )) {
            CarPlayVpnService.AttachResult.Started -> Unit
            CarPlayVpnService.AttachResult.AlreadyStarted -> throw IOException("Wireless AirPlay transport is already attached")
            is CarPlayVpnService.AttachResult.Failed -> throw IOException(result.message)
        }
    }
    // 5) Bonjour / mDNS 广播（与 runWireless :1422 同构造）。
    val advertisedAirPlayConfig = wirelessAirPlayConfig.copy(port = service.boundPort() ?: wirelessAirPlayConfig.port)
    val bonjourClient = CarPlayBonjour(
        context = appContext, config = advertisedAirPlayConfig, identity = identity,
        advertisedHost = hostAddress.hostAddress, useInterfaceMdns = true,
        onEvent = { event -> debugLog("wireless no-bluetooth-bootstrap bonjour: ${event.diagnosticSummary()}") },
        additionalAddresses = hotspotInfo.hostAddresses.filter { it != hostAddress })
    synchronized(wirelessResourceLock) {
        if (isStaleWirelessRun(generation)) return
        bonjour = bonjourClient; startedHotspot?.validateReady(); bonjourClient.start()
    }
    debugLog("wireless no-bluetooth-bootstrap: AirPlay listener + Bonjour ready; awaiting iPhone over Wi-Fi (no BT bootstrap)")
    // 6) 手动挂上 iAP2-over-Wi-Fi 隧道处理器（正常流程在 RFCOMM 段 :1539 挂，无蓝牙引导无线跳过该段故此处补）。
    val wirelessIdentification = Iap2WirelessIdentification(deviceIdentifier, hotspotInfo.ssid)
    wirelessRuntimeIdentification = config.identification.forWirelessLink(
        Iap2WirelessLinkRole.RUNTIME_TUNNEL, wirelessIdentification)
    wirelessAirPlayEndpoint = Iap2WirelessCarPlayEndpoint(
        ssid = hotspotInfo.ssid, passphrase = hotspotInfo.passphrase, channel = hotspotInfo.channel,
        security = hotspotInfo.security, ipAddresses = listOf(hostAddressText(hostAddress)),
        airPlayPort = advertisedAirPlayConfig.port, deviceIdentifier = deviceIdentifier,
        publicKey = identity.publicKeyHex, sourceVersion = airPlayConfig.sourceVersion,
        accessPointBssid = hotspotInfo.accessPointBssid)
    media.setIapTunnelHandler(::startWirelessTunnelControl)
    // 7) 进入 RunningWireless，由 wirelessSessionListener + FirstTcpWatchdog 等待 iPhone 主动 TCP。
    onStatus(CarPlayStatus.RunningWireless)
    startedHotspot?.validateReady()
}
```

> 落地决策点：先按「独立 `startWirelessNoBluetoothBootstrap` 方法 + 早返回」实现（主流程零改动，回归风险最低）；地址选择、attach、Bonjour 三段逻辑与 `runWireless` 现有段落**保持一致**，避免行为漂移。

### 7.2 改造点 B：字段级持有（与 `wirelessAirPlayEndpoint` / `wirelessRuntimeIdentification` 同款）

`startWirelessNoBluetoothBootstrap` 内已显式赋值 `wirelessAirPlayEndpoint` / `wirelessRuntimeIdentification`（:1532-1533 同款字段），供 `startWirelessTunnelControl`（:1619）在 iPhone 发起 Wi-Fi 隧道时引用。无需新增其他字段。

### 7.3 开关值注入方式（config 注入，已实现为主方案）

因 `shared` 模块**不能反向依赖** `common`（`common/build.gradle` 有 `api(project(":shared"))`，`shared` 仅 `implementation project(':legacy-mdns')`，即 `common → shared` 单向），`CarPlayController.runWireless` **无法直读** `common` 的 `AirPlayPersistence`。故采用与 `wirelessHotspotMode` / `existingWifiSsid` 一致的 **config 注入**方案（原文档称「备选」，落地后实为唯一可行主方案）：

- `CarPlayRuntimeConfig.kt`（字段默认值区，:60-70 附近）新增 `val wirelessNoBluetoothBootstrap: Boolean = false`；
- `CarPlayHostActivity.createRuntimeConfig()` 内新增 `wirelessNoBluetoothBootstrap = AirPlayPersistence.loadWirelessNoBluetoothBootstrap(this)`（仿 :800-801 `wirelessHotspotMode` / `existingWifiSsid` 写法）；
- `runWireless` 改读 `config.wirelessNoBluetoothBootstrap`（见 §7.1 早返回判断），不再引用 `AirPlayPersistence`。

> 该方案避免了 `shared` 反向依赖 `common` 的编译错误（`Unresolved reference: AirPlayPersistence`），且开关值与既有无线配置同源注入、便于统一来源。

---

## 8. 字符串资源

在 `common/src/main/res/values*/strings.xml` 各语言目录新增（英文标题带 `(experimental)` 后缀，简体 `（实验性）`，其余语言参照 AGENTS.md 与既有 `settings_experimental_display` 后缀）：

```xml
<string name="settings_wireless_no_bluetooth_bootstrap">Wireless connection (no Bluetooth bootstrap) (experimental)</string>
<string name="settings_wireless_no_bluetooth_bootstrap_description">Skip the Bluetooth iAP2 bootstrap and rely on the car Wi-Fi + Bonjour for the iPhone to start CarPlay on its own. Requires a prior pairing / car Wi-Fi join; turn this off and pair once if the iPhone has never connected.</string>
```

```xml
<!-- values-zh-rCN/strings.xml -->
<string name="settings_wireless_no_bluetooth_bootstrap">无线连接（无蓝牙引导）（实验性）</string>
<string name="settings_wireless_no_bluetooth_bootstrap_description">跳过蓝牙 iAP2 引导，仅依靠车机 Wi-Fi 与 Bonjour 让 iPhone 自行发起 CarPlay。需先完成过一次常规蓝牙配对或连过车机 Wi-Fi；若 iPhone 从未连过，请关闭本开关先配对一次。</string>
```

> `SettingsTranslationsTest` 要求所有 `values-*` 语言目录都有该字符串且占位符一致，落地时需同步补齐全部语言（参照 §4 清单），否则该测试失败。`values-v21` 等版本限定目录非语言目录，无需同步。

---

## 9. 风险与边界

| # | 风险 | 影响 | 缓解 / 验证 |
| --- | --- | --- | --- |
| R1 | **首次配对无法用无蓝牙引导无线**：iPhone 未记住车机 Wi-Fi，跳过 RFCOMM 后收不到 endpoint，不会发起 | 无蓝牙引导无线对未配对设备无效 | §6.2 UI 提示；落地前需用户在常规流程（或系统设置手动连车机 Wi-Fi）下配对/连接成功过至少一次 |
| R2 | `btMac` 用 `deviceIdentifier` 替代，iPhone 信任校验对 deviceId 的容忍度未知 | 可能拒绝会话 | 真机验证：无蓝牙引导无线下 iPhone 能否发起；对照首次 RFCOMM 引导建立的信任记录 |
| R3 | 无蓝牙引导无线 `return` 后，若 `closeWirelessStack` 在某些路径被提前触发，会释放 AirPlay listener / bonjour | CarPlay 会话被掐断 | 回归测试确认 `closeWirelessStack` 不在无蓝牙引导无线正常路径被调用；watchdog 超时路径需排除无蓝牙引导无线（超时即「iPhone 未主动连」，应给出 UI 提示而非反复重试） |
| R4 | `FirstTcpWatchdog` 超时后原会 `flipWirelessAddressFamily` + `fail(...)`；无蓝牙引导无线下 iPhone 主动 TCP 必须在超时前到达 | 误判为失败 | 确认无蓝牙引导无线下 watchdog 超时阈值足够（默认 30s+，可配置），并在超时分支加「无蓝牙引导无线 未检测到 iPhone 主动连接」专属诊断 |
| R5 | `EXISTING_WIFI` 下 iPhone 未连该 Wi-Fi，或 `MANUAL` 下 iPhone 未手动加入车机热点 → Bonjour 播在车机侧、iPhone 不在同网 | 无蓝牙引导无线失效 | 文档 + UI 明确：无蓝牙引导无线需 iPhone 已连车机 Wi-Fi（热点或同一外部 Wi-Fi）；MANUAL 模式需用户在 iPhone 上手动连一次车机热点（之后 iPhone 记住） |
| R6 | API 17 上 `CarPlayBonjour` / JmDNS 多播在旧内核可能不稳 | mDNS 发现失败 | 已有 `MulticastLock` 机制（`CarPlayDiscovery`/`CarPlayBonjour` 内部）；真机验证多播可达性 |
| **R7** | **iAP2-over-Wi-Fi 隧道处理者**：无蓝牙引导无线跳过 RFCOMM 段（:1539 `media.setIapTunnelHandler` 原在段内），若不手动挂上 `startWirelessTunnelControl`，iPhone 经 Wi-Fi 建立的 iAP2 隧道无处理者 | 会话建立后控制通道失败 | §7.1 步骤 6 已在 `startWirelessNoBluetoothBootstrap` 内显式 `media.setIapTunnelHandler(::startWirelessTunnelControl)`；真机验证 iPhone 能否完整跑通 CarPlay |
| **R8** | **入口挡路**：双蓝牙下 `connect(wireless)`（:5342-5343）在 `phoneAddress==null` 强制 `choosePhone()`，`choosePhone`（:5362）候选列表恒空 → 无蓝牙引导无线被挡在门外 | 用户打不开无蓝牙引导无线的实际连接 | §6.3 放宽入口；务必与 §7.1 一同落地，否则无蓝牙引导无线形同虚设 |

---

## 10. 首次配对约束的处理策略（落地前与用户确认）

无蓝牙引导无线是「重连/二次连接」开关，**首次配对仍建议走常规蓝牙流程**（或系统设置手动连车机 Wi-Fi）。落地阶段二选一（推荐 A）：

- **A（推荐，UI 提示型）**：开关打开但检测到 `DiPlayPreferences.phoneAddress == null` 时，Toast 提示「需先配对一次」，并仍允许本次走无蓝牙引导无线流程（不强制）。改 `DiPlayActivity.connect(true)` 或分支。
- **B（自动回退型）**：开关打开但从未成功过无线 CarPlay（无缓存 endpoint/会话记录）时，自动临时回退到常规 RFCOMM 引导一次，成功后记住；之后再用无蓝牙引导无线。实现复杂，仅作为后续增强。

> 双蓝牙架构下，首次配对的「常规蓝牙流程」本身也走不通（Android 适配器无配对），所以本机真实的首次接入更可能是：**在 iPhone 系统设置里手动连接一次车机 Wi-Fi（MANUAL 热点）**——即 R5/R1 的真实落地形态。落地时建议把 §6.2 提示文案改为「请在 iPhone 上先手动连接本车机热点一次」。

---

## 11. 测试计划

| 测试 | 类型 | 覆盖 |
| --- | --- | --- |
| `ExperimentalTogglesSettingsTest.wirelessNoBluetoothBootstrapIsOptInAndInAdvanced` | 单元测试（Robolectric） | 无蓝牙引导无线开关默认关、位于高级/实验性卡、切换即持久化（仿 `loadWirelessSkipBluetoothConfig` 用例） |
| `WirelessStartupControllerTest` 扩展 | 集成测试 | 注入 `wirelessNoBluetoothBootstrap=true` 时 `runWireless` 早返回 `startWirelessNoBluetoothBootstrap`；验证预检门禁/RFCOMM/`Iap2WirelessControlClient.run` 未被调用、状态到达 `RunningWireless`、`media.setIapTunnelHandler` 已被调用 |
| `SettingsTranslationsTest` | 单元测试 | 全部 `values-*` 含 `settings_wireless_no_bluetooth_bootstrap` 且占位符一致 |
| 真机（GM C140A） | 手动 | 已连车机 Wi-Fi 的 iPhone + 无蓝牙引导无线开 + MANUAL 热点 → iPhone 应自动连热点并发起 CarPlay；断 Wi-Fi 后重连验证 Bonjour 重发现 |

---

## 12. 真机诊断状态（据 0.2.19 实机结论，供复验对照）

- 当前诊断弹窗（`choosePhone` 空候选分支 + `showTurnOnBluetoothDialog`）已显示：`开关状态(isEnabled)`、`已配对设备数`、`已连接 iPhone`、`适配器名称`、`适配器地址`（0.2.14 第六轮新增）。
- 0.2.19 实机结果：`isEnabled=false`、`已配对设备数=0`、无 `SecurityException` 行 → **Android 侧适配器真关 + 无配对**；结合 iPhone 显示「MyLink Captive 已连接」→ **双蓝牙架构坐实**。
- 权限：`BLUETOOTH` + `BLUETOOTH_ADMIN` 在 `common/AndroidManifest.xml:10-11` 完好，迁移未删。
- 复验时若新诊断弹窗显示「适配器名称 ≠ MyLink Captive」，即双蓝牙最终确认；此时无蓝牙引导无线（§6.3 入口放宽 + §7.1 早返回）是让本机跑通无线 CarPlay 的唯一可行路径。

---

## 13. 验证 Checklist

- [ ] `AirPlayPersistence` 新增 `KEY_WIRELESS_NO_BLUETOOTH_BOOTSTRAP` + load/save，默认 `false`（插在 :477 之后）
- [ ] `CarPlayRuntimeConfig` 新增 `val wirelessNoBluetoothBootstrap: Boolean = false`；`CarPlayHostActivity.createRuntimeConfig()` 注入 `AirPlayPersistence.loadWirelessNoBluetoothBootstrap(this)`
- [ ] `EXPERIMENTAL_DISPLAY` 卡内出现无蓝牙引导无线 `toggle`，标题带 `(experimental)` / `（实验性）` 后缀
- [ ] `runWireless` 在 `config.wirelessNoBluetoothBootstrap == true` 时早返回 `startWirelessNoBluetoothBootstrap`，绕过 :1250-1263 / :1353-1372 / :1447-1562
- [ ] `startWirelessNoBluetoothBootstrap` 仍执行 attachWireless(:1390) + Bonjour(:1422) + `media.setIapTunnelHandler(:1539)` + onStatus(RunningWireless)
- [ ] `connect(wireless)`（:5342）与 `choosePhone`（:5362）在无蓝牙引导无线下放宽入口（§6.3），双蓝牙下可发起连接
- [ ] `wirelessNoBluetoothBootstrap == false` 时行为与原流程完全一致（旧测试不回归）
- [ ] 全部语言 `strings.xml` 补齐 `settings_wireless_no_bluetooth_bootstrap` / `_description`，`SettingsTranslationsTest` 通过
- [ ] 单元测试 `ExperimentalTogglesSettingsTest.wirelessNoBluetoothBootstrapIsOptInAndInAdvanced` 通过
- [ ] 真机：已连车机 Wi-Fi 的 iPhone + 无蓝牙引导无线开 + MANUAL 热点 → 无线 CarPlay 成功拉起（不再依赖蓝牙）
- [ ] 真机：无蓝牙引导无线开但 iPhone 未连车机 Wi-Fi → 给出明确 UI 诊断（而非静默失败）

---

## 14. 落地顺序（建议）

1. **持久化 + 字符串 + UI toggle**（§5、§6.1、§8）——低风险，先合入。
2. **`connect`/`choosePhone` 入口放宽**（§6.3）——双蓝牙下无蓝牙引导无线可被触发的关键，与步骤 1 同步。
3. **`CarPlayRuntimeConfig` 注入 + `startWirelessNoBluetoothBootstrap` 早返回分支**（§7.1、§7.3）——核心改动，先局部方法 + 早返回，过集成测试。
4. **首次配对约束提示**（§10-A，文案按双蓝牙改 R5/R1）——小改动。
5. **真机验证**（§13 真机项）——确认 iPhone 经 Wi-Fi 自主发起 CarPlay（重点验证 R2/R7）。
6. 若 R2/R3 暴露问题，再决定是否升级为字段级持有（§7.2）。

---

## 15. 与既有实验性②的关系总结

| 维度 | 实验性②（已落地） | 无蓝牙引导无线（方案C，本开关） |
| --- | --- | --- |
| 代码位置 | `DiPlayActivity.choosePhone`（UI） | `CarPlayController.runWireless`（协议） |
| 跳过内容 | 「打开蓝牙」弹框 | 两道蓝牙门禁（:1250/:1353）+ RFCOMM 引导段（:1447-1562） |
| 仍走蓝牙？ | 是（RFCOMM 引导） | 否 |
| 适用 | 蓝牙开关误报 / 已连接 iPhone 拿不到 | 蓝牙彻底不可用（双蓝牙架构下 Android 适配器无配对） |
| 依赖 | 已配对 + 蓝牙 SPP 可用 | 已配对/已连车机 Wi-Fi（首次仍需手动连一次热点） |
| 开关值来源 | `AirPlayPersistence` 直读（UI 进程内） | `CarPlayRuntimeConfig.wirelessNoBluetoothBootstrap`（注入，因 `shared` 不可反向依赖 `common`） |

两开关可叠加使用，分别解决「进门卡弹框」与「进门后强依赖坏蓝牙」两个独立问题。在双蓝牙架构的本机上，无蓝牙引导无线 是让无线 CarPlay 跑通的主路径。
