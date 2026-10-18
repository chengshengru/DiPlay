# 有线 CarPlay 配对失败修复说明（2026-10-18）

本文记录针对 C140A 车机（Android 4.2.2 / API 17）有线 CarPlay 连接的一次修复：从
`DiPlay-20261018-184756-614.txt` 现场日志定位到两处自身实现缺陷，改动范围限定在
`shared/transport`、`orchestration` 与配对阶段文案，未触碰 iAP2/CarPlay 协议层与 NCM 桥。

版本基线：`0.2.20-api17`；产物：`mobile/build/outputs/apk/release/mobile-release.apk`。

---

## 1. 摘要

| 项 | 内容 |
| --- | --- |
| 现象 | 插 USB 后进入配对阶段，iPhone 上点"信任"无效果，几秒后应用**退回 DiPlay 主页**（非重连、非报错页） |
| 直接原因 1 | USBMUX 读请求超时后 `cancel()` + `close()`，厂商内核拒绝 cancel 时释放了仍在 usbfs 队列里的 URB/ native 缓冲 → 内核完成回调写已释放内存 → 进程被 SIGSEGV 打死 |
| 直接原因 2 | 收到 `PairingDialogResponsePending` 后**断开 TCP 重连**再发 Pair，iPhone 侧这次配对会话随连接作废，用户点的"信任"永远回不到宿主 |
| 放大因素 | 单步超时 5s 同时套在 Pair 请求上；重连退避仅 2s/4s；9.3s 诊断盲区（mux 帧只进 logcat 不进报告） |
| 结论 | 不是证书/信任链问题，是读请求生命周期与配对重试语义两个 bug |

---

## 2. 现场证据回顾

有线段自 18:47:11 起，逐层结论如下（均来自该报告）：

| 层 | 关键证据行 | 结果 |
| --- | --- | --- |
| USB 枚举 | `wired iPhone discovered vid=0x5ac pid=0x12a8`（21.7s，前 3 次轮询 apple=no） | 通过 |
| 权限 | `USB permission granted`（重枚举后 `hasPermission=false` 再申请一次） | 通过，符合预期 |
| 0x52 重枚举 | `CarPlay reenumeration request dir=IN bRequest=0x52 wIndex=0x4 transferred=1` | 通过 |
| 配置切换 | `usb config kernel SET_CONFIGURATION(6) result=0 ... active=6 ok=true` | 通过 |
| USBMUX claim | `usbmux claimed iface=1 ... out=0x4 in=0x85` | 通过 |
| NCM claim | `claim iface=3 class=2/13` + `claim iface=4 class=10` + `setInterface alt=1` | 通过 |
| 双连接探针 | `wired USBMUX session probe after NCM open: ok transferred=2` | 通过（推翻"双连接破坏 A"的旧假设） |
| USBMUX 握手 | `wrote 20 bytes in 0ms` → `version accepted: 2` | 通过，读写双向都活 |
| **lockdown 配对** | `stage=Pairing` → `loadPairRecord()=null` → 之后 **9.3s 无任何输出** | **卡点** |

5 次尝试的错误签名（全部出自旧 `Iap2UsbSession.read` 的超时收尾路径）：

| # | 时刻 | 走到 | 报错文本 |
| --- | --- | --- | --- |
| 1 | 18:47:41.7 | Pairing +9.3s | `could not cancel timed out USBMUX read request` |
| 2 | 19:04:40.0 | Pairing +3.3s | 同上（同签名复现） |
| 3 | 19:05:11.2 | 握手期 | `could not queue USBMUX read request (timeoutMs=41284)`（16K→8K→4K→2K 全被拒，端点已废） |
| 4 | 19:05:38.0 | Pairing +0.3s | `returned no USBMUX read request`（`requestWait()` 返回 null，连接处于错误态） |
| 5 | 19:06:42.4 | Pairing +61s | `could not cancel timed out USBMUX read request` |

日志最后一行停在 `ncm open: claiming control iface=3` 这种 usbfs JNI 调用中间，没有 teardown、没有
`Activity destroyed`、没有 Java 崩溃记录 —— 与"进程被 native 信号打死"一致（车机无 adb，无 tombstone 可取证）。

---

## 3. 根因

| # | 根因 | 旧代码位置 | 判据 |
| --- | --- | --- | --- |
| R1 | 读超时被当成会话死刑：超时 → `request.cancel()` → 若 cancel 失败则 1s drain → `failSession` 直接判死整个 USBMUX 会话。手机"暂时没说话"这种正常静默被升级为会话失败 | `IphoneUsbHost.kt` `read()` 的 `drainTimedOutRead` 分支（旧 675-751） | 4/5 次都死在这条路径，与设备是否回包无关 |
| R2 | **同一处 `finally { request.close() }` 是进程猝死来源**：cancel 被厂商内核拒绝时 URB 仍在 usbfs 队列，`close()` 释放了它正在使用的 native 缓冲，内核完成回调随后写入已释放内存 → SIGSEGV | 同文件 `read()` 的 `finally` 块 | 日志在 usbfs 调用中间截断、无 Java trace、应用退回主页 |
| R3 | 配对对话框期间断开重连：`PairingDialogResponsePending` 落在 `use { }` 花括号之外，先退出连接再 `host.connect()` 重发 Pair。iPhone 侧配对事务绑定在该连接上，重连即作废，用户点"信任"的结果回到一条已关闭的 socket | `LockdownPairingClient.kt` 旧 81-84 | `stage=Pairing` 后 9.3s 静默 + 反复重连；无 `wired created a new Lockdown pair record` |
| R4 | 单步超时 5s 同时约束 Pair 请求；重连退避 2s/4s 让手机反复弹框/复位 | `LockdownPairingClient.kt` 旧 `MAXIMUM_STEP_TIMEOUT_MILLIS=5_000`；`CarPlayHostActivity.kt` 重连退避 | attempt=3 出现 `SET_CONFIGURATION(6) result=-16`（EBUSY）走控制传输兜底 |
| R5 | 9.3s 诊断盲区：mux TX/RX 帧、TCP 控制帧只走 `Log.i`，不进会话报告，无法区分"SYN 没 ACK""lockdown 没回包""plist 被拒" | `Iap2UsbMuxHost.kt` 旧 `sendTcp`/`dispatchTcp` | 报告 32.455→41.755 之间一行都没有 |

---

## 4. 改动总览

| 文件 | 位置 | 改动 |
| --- | --- | --- |
| `shared/.../transport/IphoneUsbHost.kt` | 605-607（新增字段）、686 `read()`、738 `acquireQueuedRead()`、768 `close()`、826 `QueuedUsbRead` | 读请求改为**复用单条 URB**；超时只返回 null，不 cancel / 不 close / 不 failSession；关闭顺序改为 cancel → connection.close() → request.close()；删除 `drainTimedOutRead()` 与 `CANCEL_DRAIN_TIMEOUT_MILLIS` |
| `shared/.../transport/LockdownPairingClient.kt` | 28 `pair()`、82 内层重发循环、238/245 超时拆分、287-291 常量 | pending 期间**同连接**重发 Pair；仅传输层错误才换连接（上限 5 次）；超时拆为传输 20s / Pair 120s / 总 5min；新增逐步耗时诊断 |
| `shared/.../transport/Iap2UsbMuxHost.kt` | 38 `connect()`、92 `sendTcp()`、261 `readerLoop()`、280 `dispatchTcp()`、316-347 诊断辅助、364 常量 | `connect()` 明确记录 SYN-ACK 是否到达；mux 帧摘要（端口/标志/字节数/ack，**不含载荷**）前 64 帧进报告；reader 退出原因落报告 |
| `shared/.../orchestration/CarPlayController.kt` | 2382 | `LockdownPairingClient(mux, onDiagnostic = ::connectionDiagnostic)` 接入会话报告 |
| `common/.../CarPlayHostActivity.kt` | 6653-6659 | 配对阶段文案追加"请在 iPhone 上点信任，并保持数据线连接" |
| `common/src/main/res/values*/strings.xml` | `values`、`values-zh-rCN`、`values-zh-rTW`、`values-ja` | 新增 `pairing_with_iphone_trust_hint`（四语） |
| `shared/src/test/.../UsbmuxTimedOutReadTest.kt` | 全量重写 | 断言新语义：超时保留 URB、连续超时不断会话、请求对象只在关闭时释放一次 |
| `shared/src/test/.../UsbReadQueueCompatibilityTest.kt` | shadow 228-236 / 264-267 | 增加 `requestCloses` 计数，可观测"何时/几次释放请求对象" |

---

## 5. 详解一：USBMUX 读请求生命周期

### 5.1 旧模型（问题所在）

每次 `read()` 都新建一条 `UsbRequest` + 一块 64 KiB direct buffer，超时后试图撤销：

```kotlin
val request = UsbRequest()
try {
    request.initialize(connection, inEndpoint)
    queueRead(buffer)                       // 提交 URB
    val completed = awaitCompletedRead(timeoutMillis, timedOut)
    if (completed == null) {
        if (!timedOut.get()) throw failSession("Android returned no USBMUX read request")
        return drainTimedOutRead(request, buffer)   // cancel + 1s drain，失败即判死会话
    }
    ...
} finally {
    if (initialized) request.cancel()       // 队列里可能还挂着
    request.close()                         // ← 释放仍在队列中的 URB 所用缓冲
}
```

两个独立缺陷叠在同一条路径上：

1. **语义缺陷**：手机在配对阶段本来就会沉默数秒（等待用户在屏幕上操作），这被当作会话失败；
2. **内存安全缺陷**：`cancel()` 在若干厂商内核上返回 false 且请求仍在队列，`finally` 里的 `close()`
   随即释放其 native 缓冲。内核随后完成该 URB 并写入已释放内存 —— 这是"日志戛然而止 + 退回主页"唯一
   与代码对得上的解释（`close()` 发生于任何日志写入之前，所以什么都留不下）。

### 5.2 新模型（与 NCM 桥一致）

`Iap2UsbHost` 早已采用复用模型（`NcmUsbBridge` 的读路径），本次把 USBMUX 侧对齐：

```
pendingRead : UsbRequest?        // 已提交给内核的那条请求
pendingReadBuffer : ByteBuffer?  // 与请求成对存活，直到完成或会话关闭
readQueued : Boolean             // true = 内核里已有一条，下一次调用只等结果

read(timeout):
  acquireQueuedRead() -> 无请求则 initialize+queue 一次；已有则原样复用
  awaitCompletedRead(timeout)
    完成      -> readQueued=false，取出字节；0 字节按"暂无数据"返回 null
    超时      -> 直接返回 null（请求保留在队列里，buffer 不动）
    null 非超时 -> requestWait() 返回 null（连接错误/关闭），按会话失败处理
```

关键不变量（由测试锁定）：

| 不变量 | 含义 |
| --- | --- |
| 一条管道同时最多一条在读 URB | 不会因为反复超时在 usbfs 里堆叠请求 |
| 超时路径不调用 `cancel()` / `close()` | 不会释放仍在队列的缓冲，根除 SIGSEGV 来源 |
| `pendingRead` 与 `pendingReadBuffer` 同生共死 | 不需要"请求-缓冲"配对校验，也无法出现错配 |
| 请求对象只在 `close()` 里释放一次 | 会话生命周期内零泄漏、零重复释放 |
| 超时不 `failSession` | 手机静默不再能杀死会话 |

### 5.3 `close()` 的顺序

```kotlin
override fun close() {
    val queued = synchronized(stateLock) { if (closed) return; closed = true; pendingRead.also { pendingRead = null; ... } }
    queued?.let { runCatching { it.cancel() } }    // 1. 先撤销
    runCatching { connection.close() }             // 2. 再关连接，内核据此丢弃残留请求
    queued?.let { runCatching { it.close() } }     // 3. 最后才释放请求对象
}
```

顺序反了（在 URB 仍在队列时 `close()` 请求）就回到老 bug。三处都包 `runCatching`，因为关闭路径
不应因厂商实现抛异常而留下其他资源未释放。

### 5.4 行为变化对照

| 场景 | 旧行为 | 新行为 |
| --- | --- | --- |
| 手机 5s 无回包 | cancel → drain 失败 → 会话失败，重连风暴 | 返回 null，读线程继续等同一请求 |
| cancel 被内核拒绝 | `close()` 释放在用缓冲 → SIGSEGV | 不再发生（超时路径根本不 cancel） |
| 超时期间数据刚到 | 可能丢字节或判死会话 | 下一次调用立即取回，字节不丢 |
| 完成但 0 字节 | 走失败分支（`completed` 非空但 position=0） | 明确按"暂无数据"返回 null |
| 读越界（transferred > capacity） | 未校验 | `failSession` 报明确错误而非静默截断 |
| 会话关闭中读线程收尾 | `DeviceUnavailable` | 同样是 `DeviceUnavailable`（预期收尾路径，非新失败） |

> 注：`Iap2UsbMuxHost.open()` 的 `readTimeoutMillis` 仍是 1000ms，未改动。旧实现下它意味着"每秒一次赌局"，
> 新实现下只是"每秒交还控制权一次"，因此保留原值以免改变上层 `takeFrame` 的节奏。

---

## 6. 详解二：配对对话框期间保持同一条连接

### 6.1 旧的错误结构

```kotlin
while (true) {
    val connection = host.connect(62078)          // 每次都新建 TCP
    var pending = false
    LockdownPlistChannel(connection).use { ch ->
        ...
        val response = ch.request(pairRequest(...), stepTimeoutMillis(deadline)) // 5s
        when (response.errorCodeOrNull()) {
            "PairingDialogResponsePending" -> pending = true   // ← 连接在此后立刻被 use 关闭
            ...
        }
    }
    if (pending) waitForRetry(...)                 // 再回到 while 顶部重连
}
```

`PairingDialogResponsePending` 是 iPhone 在说"弹框已经显示，等用户点"。配对事务与这条 lockdown 连接
绑定，`use{}` 退出即作废 —— 用户随后点的"信任"落在一条已关闭的 socket 上。真机表现正是
"弹框 → 点信任 → 无下文"，用户反复点、宿主反复重连、手机反复弹框。

### 6.2 新结构：两层循环

```
外层（传输重试，最多 5 次）
  connect 62078  -> 打印 connectMs
  SetValue UntrustedHostBUID
  GetValue DevicePublicKey / RootCertificate（仅首次生成记录）
  内层（配对重发，同一条连接）
     发 Pair -> accepted              : 返回 PairedRecord
             -> PairingDialogPending : 等 1s 后再发，连接不变
             -> UserDeniedPairing    : 直接上抛（用户拒绝，不重试）
             -> PasswordProtected    : 直接上抛
             -> 其他 errorCode       : RemoteError 上抛
  任一步抛 IphoneUsbException（传输层真断） -> 外层重连，transportAttempts+1
```

代码位置：`LockdownPairingClient.kt:45`（外层）、`:82`（内层 `while (true)`）、`:95`（accepted）、
`:102`（pending）。

### 6.3 超时分级

| 常量 | 值 | 适用范围 | 理由 |
| --- | --- | --- | --- |
| `TRANSPORT_STEP_TIMEOUT_MILLIS` | 20s | `host.connect()`、SetValue、GetValue、plist 帧读 | 手机拉起 lockdown 服务后才回第一帧，实测 3.3–9.3s 静默常见；5s 会逼出无意义重连 |
| `PAIR_RESPONSE_TIMEOUT_MILLIS` | 120s | Pair 请求本身 | 这条请求要等人点屏幕，不能用秒级预算衡量 |
| `MAXIMUM_TOTAL_TIMEOUT_MILLIS` | 5min | 整个 `pair()` | 与 `CarPlayController.PAIR_TIMEOUT_MILLIS`（`CarPlayController.kt:3351`）同量级，作为总兜底 |
| `RETRY_INTERVAL_MILLIS` | 1s | pending 后重发间隔 | 同连接重发，1s 足够快且不刷手机 |

所有单步超时仍取 `minOf(step, deadline.remaining())`，总预算不会被单步突破。

### 6.4 为什么"换连接"只在传输层错误时发生

| 错误类型 | 处理 | 原因 |
| --- | --- | --- |
| `IphoneUsbException`（连接失败、plist 帧超时/截断） | 换连接重试，上限 5 次 | 传输层已不可用，只能重建 |
| `PairingDialogResponsePending` | 同连接重发 | 配对事务仍在手机上存活 |
| `UserDeniedPairing` / `PasswordProtected` | 上抛 | 用户/设备给出的结论，重试无意义且会反复弹框 |
| 其他 `errorCode`（如 `InvalidPairRecord`） | `RemoteError` 上抛 | 记录本身有问题，重试同样无效 |

---

## 7. 详解三：把盲区变成证据

旧报告 9.3s 空白，无法区分四种可能。新增输出（全部经 `onDiagnostic` → 会话诊断报告）：

| 位置 | 新日志 | 判读 |
| --- | --- | --- |
| `Iap2UsbMuxHost.connect()` :38 | `usbmux connect port=62078 source=<port> established in <N>ms` | TCP 通 |
| 同上（失败分支） | `usbmux connect port=62078 source=<port> no SYN-ACK within <N>ms` | 手机/固件没回，或连接 A 已废 |
| `sendTcp()` :92 → `traceMux` | `usbmux TX source=.. destination=.. bytes=.. seq=.. ack=.. flags=SYN` | 我们发了什么 |
| `dispatchTcp()` :280 → `traceMux` | `usbmux RX destination=.. source=.. flags=SYN|ACK bytes=0 ack=..` | 手机回了什么（含控制帧） |
| `readerLoop()` :261 | `usbmux reader ended: <Exception>: <message>` | 会话由谁先断 |
| `LockdownPairingClient` | `pair transport attempt=1 connected port=62078 connectMs=..` | 连接耗时 |
| 同上 | `pair UntrustedHostBUID set` | SetValue 通了 |
| 同上 | `pair GetValue DevicePublicKey answered elapsedMs=..` | 设备回包（含耗时） |
| 同上 | `pair trust dialog pending request=.. elapsedMs=..; keep waiting on the same connection ...` | 弹框已显示、仍在等 |
| 同上 | `pair accepted attempt=.. request=.. elapsedMs=..` | 配对成功 |
| 同上 | `pair transport attempt=.. failed <Exception>: <message>` | 传输层断点 |
| 同上 | `pair trust pending; reconnecting before retry`（仅传输失败时） | 确属换连接重试 |

配额约束：mux 帧摘要只记前 **64** 帧（`LOGGED_MUX_TRACE_FRAMES`，`Iap2UsbMuxHost.kt:364`），
第 64 帧处写一行 `usbmux trace limit reached ...`，之后只进 logcat。摘要**只含端口/标志/序号/字节数，
不含载荷**，因此不会把 TLS 或 plist 内容带进报告。64 帧足以覆盖握手 + 一次完整 lockdown 往返。

---

## 8. 详解四：配对阶段文案

| key | en | zh-rCN | zh-rTW | ja |
| --- | --- | --- | --- | --- |
| `pairing_with_iphone_trust_hint` | Tap Trust on the iPhone to allow CarPlay. Keep the cable connected. | 请在 iPhone 上点"信任"，并保持数据线连接。 | 請在 iPhone 上點「信任」，並保持連接線。 | iPhoneで「信頼」をタップしてCarPlayを許可してください。ケーブルは接続したままにしてください。 |

`CarPlayHostActivity.kt:6653-6659` 在 `CarPlayStatus.Pairing` 时把该提示换行追加到主状态文案下方，
另外三种语言目录已同步，避免 `JapaneseTranslationsTest` 之类的翻译完整性用例失败。

---

## 9. 测试与验证

| 项 | 命令 / 范围 | 结果 |
| --- | --- | --- |
| 编译 | `./gradlew :shared:compileDebugKotlin` | 通过，零 warning |
| 编译 | `./gradlew :common:compileDebugKotlin` | 通过，零 warning |
| 单测（定向） | `:shared:testDebugUnitTest --tests UsbmuxTimedOutReadTest --tests UsbReadQueueCompatibilityTest` | 全 PASS |
| 单测（全量） | `:shared:testDebugUnitTest`（1387 项） | 仅 `AirPlayDualListenerTest` 因沙箱 IPv6 报 `SocketTimeoutException`，与本改动无关 |
| 单测 | `:common:testDebugUnitTest` | 新增串的翻译用例恢复 PASS；其余既有失败均为环境性（`WifiManager`/`ConnectivityManager` unavailable、cluster map 分辨率、`CarPlayHostResolution` 解码能力），不涉及本次文件 |
| Lint | `:mobile:lintDebug` | 0 errors / 14 warnings，且 14 条全为既有问题（`StandaloneHudDemoActivity` 的 `SetTextI18n`、`config.xml` 未用资源、`targetSdkVersion` 覆盖、transformed jar 的 `TrustAllX509TrustManager`），无一条落在本次改动文件 |
| 打包 | `:mobile:assembleRelease`（`ANDROID_KEYSTORE_PATH=~/diplay-release.jks`，密码/别名 `diplay`） | BUILD SUCCESSFUL，零 warning |

### 9.1 重写后的三个用例

| 用例 | 断言 | 覆盖的回归点 |
| --- | --- | --- |
| `aReadThatTimesOutKeepsItsBytesForTheNextCall` | 第一次 `read` 返回 null，第二次拿到完整载荷；`cancels==0`、`sizes==[65536]` | 超时不 cancel、不重新排队 |
| `repeatedTimeoutsKeepTheSessionAliveAndReuseTheQueuedRequest` | 连续 3 次超时（且 `cancelResult=false`，即厂商内核拒绝 cancel 的现场）后仍能读到数据 | **根因 R1+R2 的直接回归门禁** |
| `anIdlePipeRepeatedlyTimesOutWithoutFailingOrClosingItsRequest` | 5 次空闲超时无异常，`requestCloses==0`；`close()` 后 `requestCloses==1` | 请求对象只在会话关闭时释放一次 |

`UsbReadQueueCompatibilityTest` 的 Robolectric shadow 新增 `requestCloses` 计数，使"何时释放 URB"
成为可断言行为，而不只是代码注释里的约定。

---

## 10. 装车验证清单

装新 APK 后插线，报告里应按此顺序出现（`ok` 列为期望）：

| # | 期望日志片段 | ok |
| --- | --- | --- |
| 1 | `usbmux connect port=62078 source=... established in Nms` | ☐ |
| 2 | `usbmux TX ... flags=SYN` → `usbmux RX ... flags=SYN|ACK` | ☐ |
| 3 | `pair transport attempt=1 connected port=62078 connectMs=...` | ☐ |
| 4 | `pair UntrustedHostBUID set` | ☐ |
| 5 | `pair GetValue DevicePublicKey answered elapsedMs=...` | ☐ |
| 6 | `pair trust dialog pending request=1 ...` →（点信任）→ `pair accepted attempt=1 request=...` | ☐ |
| 7 | `wired created a new Lockdown pair record hostId=...` | ☐ |
| 8 | `CarPlayStatus.ConnectingControl` 之后不再出现 `could not cancel / could not queue / returned no ... read request` | ☐ |
| 9 | 第二次插线直接 `wired using saved Lockdown pair record`，不再弹信任框 | ☐ |

失败时按第一处断点判读：

| 首个异常 | 判读 | 下一步 |
| --- | --- | --- |
| `no SYN-ACK within ...ms` | 手机没回 SYN-ACK；连接 A 可能已被 NCM 双连接/重枚举破坏 | 把配对前移到 `openNcm()` 之前（见 §11） |
| `pair GetValue ... ` 之后无输出，且 `reader ended` 出现 | plist 帧被截断或 MUX ack 失步 | 查 mux 帧摘要里的 ack 连续性 |
| `pair transport attempt=N failed IphoneUsbException...` | 传输层真断，已按上限重连 | 看是第几次尝试、间隔多长 |
| `RemoteError(InvalidPairRecord)` | 记录不被接受（hostId/证书链问题） | 清 pair record 重配对，检查 hostId 持久化 |
| `UserDeniedPairing` | 真的点了"不允许" | 非缺陷 |
| 仍出现 `could not ... read request` | 说明跑的不是本版本 | 核对 APK 时间戳与 `versionName` |

> 进程是否还会被打死，本轮无法从日志直接证明（无 tombstone）。可观测的间接判据是：
> 失败后应能看到 `pair transport attempt=.. failed` 或 `reader ended` 这类**收尾行**，
> 而不是像上次那样停在 `claiming control iface=3` 中间。

---

## 11. 本次未做，留作后续

| 项 | 现状 | 建议 |
| --- | --- | --- |
| 配对与 NCM 的顺序 | 仍是 `openNcm()`（`CarPlayController.kt:2338`）→ `runStack()`（`:2370`）：`probeControlChannel()`（`:2376`）→ `Iap2UsbMuxHost.open`（`:2378`）→ `Pairing`（`:2381-2382`） | 与上游 usbmuxd 一致的做法是"先配对、再起 NCM"，可缩小双连接窗口；但会改变已验证通过的 NCM 路径，建议在 §10 判读指向 `no SYN-ACK` 时再动 |
| 重连退避 | `CarPlayHostActivity` 仍是 2s/4s，无次数上限 | 建议 5s 起步 + 上限后转显式错误态，避免手机侧状态来不及复位 |
| 硬终止取证 | `CrashFileLogger` 明确不覆盖 native；API 17 取不到 `Process exits` | 每次 phase/stage 变化写一行 `files/last-stage.txt`，下次报告首屏读它 |
| `readTimeoutMillis` | 保留 1000ms | 当前无害；若日后出现"每秒一次空转"的功耗/时序问题，再评估提到 3000ms |

---

## 12. 回滚

改动集中在 6 个源文件 + 4 个 strings + 2 个测试文件，无数据库/schema 变更，可直接按文件回滚：

```bash
git checkout -- \
  shared/src/main/java/com/shilapi/xcertplay/transport/IphoneUsbHost.kt \
  shared/src/main/java/com/shilapi/xcertplay/transport/LockdownPairingClient.kt \
  shared/src/main/java/com/shilapi/xcertplay/transport/Iap2UsbMuxHost.kt \
  shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt \
  common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt
```

回滚后 `pairing_with_iphone_trust_hint` 会变成未使用字符串（lint 可能提示），一并回滚 strings 与测试即可。
注意：回滚即恢复"超时 cancel+close"的旧语义，等于恢复 native 崩溃风险，仅用于对比实验。
