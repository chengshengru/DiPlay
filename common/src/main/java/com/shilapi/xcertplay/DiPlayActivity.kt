// SPDX-License-Identifier: AGPL-3.0-only
// UI copy and visual language adapted from DiAuto. See docs/THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.bluetooth.BluetoothAdapter
import android.view.Window
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.widget.SwitchCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.compat.BluetoothCompat
import com.shilapi.xcertplay.compat.PermissionCompat
// main B4：连接向导逻辑与 DiLink 代际识别（作者：TRAE AI）。
import com.shilapi.xcertplay.setup.DiLinkGeneration
import com.shilapi.xcertplay.setup.SetupGuide
// main B5：应用内更新（作者：TRAE AI）。
import androidx.core.content.FileProvider
import com.shilapi.xcertplay.update.BackgroundUpdateScheduler
import com.shilapi.xcertplay.update.UpdateApkExport
import com.shilapi.xcertplay.update.UpdateAvailability
import com.shilapi.xcertplay.update.UpdateCatalog
import com.shilapi.xcertplay.update.UpdateClient
import com.shilapi.xcertplay.update.UpdateChecksums
import com.shilapi.xcertplay.update.UpdateRelease
import com.shilapi.xcertplay.update.UpdateReleaseLookup
import java.io.IOException
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.ClusterSpeedometerOverlay
import com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay
import com.shilapi.xcertplay.backup.SettingsBackupCodec
import com.shilapi.xcertplay.backup.SettingsBackupEndpoint
import com.shilapi.xcertplay.backup.SettingsBackupServer
import com.shilapi.xcertplay.backup.SettingsQrCode
import com.shilapi.xcertplay.hud.BydAdbAccess
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.hud.BydFieldSource
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.hud.BydVehicleCapabilities
import com.shilapi.xcertplay.hud.BydVehicleField
import com.shilapi.xcertplay.hud.BydVehicleFieldStore
import com.shilapi.xcertplay.hud.BydVehicleProbeOutcome
import com.shilapi.xcertplay.host.R
// main #345：BYD 氛围灯音乐跟随（作者：TRAE AI）
import com.shilapi.xcertplay.media.AmbientColorMode
import com.shilapi.xcertplay.media.AmbientColorSource
import com.shilapi.xcertplay.media.AmbientColorSpeed
import com.shilapi.xcertplay.media.AmbientIdleBehavior
import com.shilapi.xcertplay.media.AmbientMusicController
import com.shilapi.xcertplay.media.AmbientMusicSettings
import com.shilapi.xcertplay.media.MtkDecoderTuning
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.network.CarHotspotTethering
import com.shilapi.xcertplay.network.WifiP2pChannels
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.settings.SettingsTheme
import com.shilapi.xcertplay.settings.SettingsWidgets
import com.shilapi.xcertplay.transport.EvChargingConnectors
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

internal enum class SettingsCategory {
    // main #597：ABOUT 移到最后，归入设置层级（不再走独立 page）
    OVERVIEW, CONNECTION, DISPLAY, AUDIO, NAVIGATION, VEHICLE, LANGUAGE, DIAGNOSTICS, ADVANCED, ABOUT,
}

/** A settings card. Every entry needs one category in [SettingsInformationArchitecture]; see AGENTS.md. */
internal enum class SettingsSection {
    CARPLAY_CONTROLS,
    WHEEL_KEYS,
    // main #345：BYD 氛围灯音乐跟随入口（作者：TRAE AI）
    AMBIENT_LIGHTING,
    // main #344：导航播报方向盘音量入口（作者：TRAE AI）
    NAVIGATION_WHEEL,
    CONNECTION_SETUP,
    DIAGNOSTICS,
    AUTOMATIC_CONNECTION,
    USB_CONNECTION,
    BYD_ADB,
    DISPLAY_AND_PERFORMANCE,
    EXPERIMENTAL_DISPLAY,
    ADVANCED_MEDIA,
    CAR_BUTTON,
    AUDIO_ROUTING,
    LOCATION,
    CLUSTER_MAP,
    BYD_NAVIGATION,
    PERMISSIONS_AND_HELP,
    LANGUAGE,
}

internal object SettingsInformationArchitecture {
    // main #422：LANGUAGE 与 ABOUT 独立分类页；Overview 不再并载两张卡片（作者：TRAE AI）。
    val sectionsByCategory: Map<SettingsCategory, Set<SettingsSection>> = mapOf(
        SettingsCategory.OVERVIEW to emptySet(),
        SettingsCategory.LANGUAGE to setOf(SettingsSection.LANGUAGE),
        SettingsCategory.ABOUT to emptySet(),
        SettingsCategory.CONNECTION to setOf(
            SettingsSection.CONNECTION_SETUP,
            SettingsSection.AUTOMATIC_CONNECTION,
            SettingsSection.USB_CONNECTION,
            SettingsSection.BYD_ADB,
            SettingsSection.PERMISSIONS_AND_HELP,
        ),
        SettingsCategory.DISPLAY to setOf(SettingsSection.DISPLAY_AND_PERFORMANCE),
        SettingsCategory.AUDIO to setOf(SettingsSection.AUDIO_ROUTING),
        SettingsCategory.NAVIGATION to setOf(SettingsSection.LOCATION, SettingsSection.BYD_NAVIGATION),
        SettingsCategory.VEHICLE to setOf(
            SettingsSection.CARPLAY_CONTROLS,
            SettingsSection.WHEEL_KEYS,
            SettingsSection.CAR_BUTTON,
        ),
        SettingsCategory.DIAGNOSTICS to setOf(SettingsSection.DIAGNOSTICS),
        SettingsCategory.ADVANCED to setOf(
            SettingsSection.CLUSTER_MAP,
            SettingsSection.EXPERIMENTAL_DISPLAY,
            SettingsSection.ADVANCED_MEDIA,
            SettingsSection.NAVIGATION_WHEEL,
            SettingsSection.AMBIENT_LIGHTING,
        ),
    )
}

/** DiAuto's visual language, with a connection flow for an independent CarPlay receiver. */
class DiPlayActivity : ComponentActivity(), AppAppearanceOwner {
    private val handler = Handler(Looper.getMainLooper())
    // main #422：设置页日夜外观切换闭环（作者：TRAE AI）。
    // appNight 为解析后的当前日夜态；palette 是对应的语义调色板，全部 UI 取色走它。
    private var appNight = true
    override val currentAppNight: Boolean get() = appNight
    private var palette = DiPlayPalette.DARK
    private var appearanceObserverRemoval: (() -> Unit)? = null
    private var appearanceUpdatesResumed = false
    private var appearanceRenderPending = false
    private var appearanceButtonFocusPending = false
    private var windowLearning: WindowKeyLearning? = null
    private val windowLearningPresses = WheelKeyPresses()
    private val endWindowLearning = Runnable { cancelKeyLearning() }
    private var page = "home"
    // main #345：氛围灯支持性只读检查，可在测试中替换（作者：TRAE AI）
    internal var ambientSupportCheck: (Context) -> java.util.concurrent.CompletableFuture<Boolean> = AmbientMusicController::checkSupport
    private var settingsCategory = SettingsCategory.OVERVIEW
    private var connectionSettingsReturnCategory: SettingsCategory? = null
    private var settingsSectionFilter: Set<SettingsSection>? = null
    private var clusterSafeAreaDialog: Dialog? = null
    private var clusterContentRequestVersion = 0L
    private var pendingCarHotspotSetup = false
    private var hotspotJoinControls: HotspotJoinControls? = null
    private var setupError: String? = null
    private var status: TextView? = null
    // main #742：USB 会话时首页卡片改标题并隐藏 Wi-Fi 提示（作者：TRAE AI）。
    private var homeCardTitle: TextView? = null
    private var homeConnectionHint: TextView? = null
    private var connectButton: Button? = null
    private var disconnectButton: Button? = null
    private var lastRunning: Boolean? = null
    private var pendingWireless = false
    // main #799（波次十三批次E）：驾驶员是否已被送去过蓝牙设置页。再次回来仍是关闭，说明开关留不住。
    private var bluetoothSettingsVisited = false
    private var initialLaunch = true
    private var notificationTransport = true
    private var exportInProgress = false
    private var usbPermissionOperation: UsbPermissionSetup.Operation? = null
    private var usbPermissionDialog: AlertDialog? = null
    internal var usbPermissionOperationFactory: (Context) -> UsbPermissionSetup.Operation = {
        UsbPermissionSetup.Operation(it.applicationContext)
    }
    // main #608：MTK 解码器名查询可注入（测试用）。
    internal var selectedMtkDecoderName: (String) -> String? = MtkDecoderTuning::selectedDecoderName
    private var navigationStreamType = 14
    private var testToneTrack: AudioTrack? = null
    private var toneStop: Runnable? = null
    private var exportButton: Button? = null
    private var rootScroll: ScrollView? = null
    private var settingsRailScroll: ScrollView? = null
    private var reconnectBar: View? = null
    private var readinessCard: LinearLayout? = null
    private var searchIndexSink: MutableList<String>? = null
    // main B4：连接向导当前步骤与来源（设置页入口 or 首启自动打开）（作者：TRAE AI）。
    private var setupStep = SetupGuide.STEP_CAR
    private var setupFromSettings = false
    // main B5：应用内更新状态机（作者：TRAE AI）。
    @Volatile private var updateStage = UpdateStage.IDLE
    @Volatile private var backupServer: SettingsBackupServer? = null
    @Volatile private var backupEndpoint: SettingsBackupEndpoint? = null
    /** The Settings category that opened the phone page, and the one to go back to. */
    private var backupOpenedFrom = SettingsCategory.ABOUT
    @Volatile private var backupSummary: String? = null
    @Volatile private var updateGeneration = 0
    @Volatile private var updateProgress: Int? = null
    private var updateRelease: UpdateRelease? = null
    private var updateFile: File? = null
    private var updateMessage: String? = null
    // main #488：后台更新检查的缓存发布与 APK 导出路径（作者：TRAE AI）。
    private var availableUpdate: UpdateRelease? = null
    private var updateSavedPath: String? = null
    private var updateSavedUri: Uri? = null
    // main #593：安装器选择——校验哈希等工作移出主线程（作者：TRAE AI）。
    internal var updateInstallWorker: (Runnable) -> Unit = { Thread(it, "diplay-update-install").start() }
    // main B3：内嵌搜索状态——输入框、下拉结果弹层、索引与焦点恢复标记（作者：TRAE AI）。
    private var settingsSearchBox: EditText? = null
    private var settingsSearchPopup: PopupWindow? = null
    private var settingsSearchIndex: List<SettingsSearchResult>? = null
    private var settingsSearchQuery = ""
    // 波次十 #645：两态搜索——false 时页头显示搜索按钮，true 时展开为输入框（作者：TRAE AI）。
    private var settingsSearchExpanded = false
    private var settingsSearchIndexing = false
    private var settingsSearchRestoreFocus = false
    private var renderedReadiness: SettingsReadiness? = null
    private var renderedPage: String? = null
    private var renderedSettingsCategory: SettingsCategory? = null
    private var pendingScrollY: Int? = null
    private var pendingRailScrollY: Int? = null
    private var pendingRailFocus = false
    private var bydVehicleAdvancedExpanded = false
    private var adbAccessState: BydAdbAccess.State? = null
    private var adbCheckInProgress = false
    private var adbCheckMayAsk = false
    private var adbCheckFailed = false
    private var vehicleProbeAuthorizationInProgress = false
    private var vehicleProbeInProgress = false
    private var vehicleProbeOutcome: BydVehicleProbeOutcome? = null
    private var adbCheckGeneration = 0
    private var adbStatus: TextView? = null
    private var carButtonCard: LinearLayout? = null
    private var bydAdbControls: LinearLayout? = null
    private var adbSwitchChangePending = false
    private var pausedForAdbSwitchChange = false
    private var updatingAdbSwitches = false
    private val adbSwitches = mutableMapOf<Int, Pair<SwitchCompat, () -> Boolean>>()
    private var hotspotStartupResult: CarHotspotTethering.Result? = null
    @Volatile private var startupHotspotCancelled = false
    @Volatile private var vehicleProbeGeneration = 0
    @Volatile private var vehicleValidationGeneration = 0
    private val vehicleOperationLock = Any()
    private var automaticVehicleValidationStarted = false
    private var automaticVehicleValidationInProgress = false
    private var automaticVehicleValidationPending = false
    private var pendingVehicleReplacement: BydVehicleCapabilities? = null
    // The saved snapshot [pendingVehicleReplacement] was compared with.
    private var pendingVehicleReplacementExpected: BydVehicleCapabilities? = null
    private var pendingVehicleLostFields: Set<BydVehicleField> = emptySet()
    private var defaultVehicleStatus: BydAdbAccess.Status? = null
    private var vehicleDataReconnectPending = false
    private val automaticVehicleValidation = Runnable {
        automaticVehicleValidationPending = false
        validateSavedVehicleConfigurationAutomatically()
    }
    private data class VehicleProbeAttempt(
        val outcome: BydVehicleProbeOutcome,
        val heldCandidate: BydVehicleCapabilities? = null,
        val lostFields: Set<BydVehicleField> = emptySet(),
        val snapshotChanged: Boolean = false,
        val allowedOnlyOnce: Boolean = false,
    )

    private data class VehicleValidationAttempt(
        val status: BydAdbAccess.Status,
        val outcome: BydVehicleProbeOutcome? = null,
        val heldCandidate: BydVehicleCapabilities? = null,
        val lostFields: Set<BydVehicleField> = emptySet(),
        val snapshotChanged: Boolean = false,
        val savedFieldsReadable: Boolean = false,
    )
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        connect(notificationTransport)
    }
    private val tick = object : Runnable {
        override fun run() { refreshStatus(); handler.postDelayed(this, 1000) }
    }
    // 外观轮询：无 runtime 观察者事件时兜底检查（定时计划切换等）（作者：TRAE AI）。
    private val appearancePoll = object : Runnable {
        override fun run() {
            checkForAppearanceChange()
            if (appearanceUpdatesResumed) handler.postDelayed(this, APPEARANCE_POLL_MILLIS)
        }
    }
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) choosePhone() else permissionHelp(getString(R.string.nearby_devices), getString(R.string.allow_nearby_devices_so_diplay_can_connect_to_your_paired))
    }
    // main #439：开启"iPhone 蓝牙重连时自动连接"时请求 BLUETOOTH_CONNECT。
    private val bluetoothAutoConnectPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            if (DiPlayPreferences.phoneAddress(this) == null) choosePhone()
        } else {
            DiPlayPreferences.saveConnectOnPhoneBluetooth(this, false)
            render()
            permissionHelp(getString(R.string.nearby_devices), getString(R.string.allow_nearby_devices_so_diplay_can_connect_to_your_paired))
        }
    }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasPreciseLocation()) {
            applyLocationReporting(true)
        } else {
            render()
            permissionHelp(getString(R.string.location), getString(R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p))
        }
    }
    private val iconPicker = registerForActivityResult(ActivityResultContracts.GetContent(), ::cropIcon)
    private val iconDocumentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument(), ::cropIcon)
    private fun cropIcon(uri: Uri?) {
        if (uri != null) iconCrop.launch(Intent(this, ImageCropActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
    private val iconCrop = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        refreshCarButton()
        carButtonSaved()
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportDiagnostics(uri)
    }
    // main #541：Android 9 上申请存储权限后把报告放进可见的 Downloads/DiPlay 文件夹。
    // 拒绝权限仍然保存报告，只是保存在 DiPlay 自己的存储里（作者：TRAE AI）。
    private val reportStoragePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        exportDiagnostics()
    }

    private var languagePreferenceAtCreate = AppLocale.SYSTEM

    /** Set once per launch so the previous-crash toast shows a single time, not on every onStart. */
    private var crashNoticeShown = false

    private var interfaceOverride: Configuration? = null
    private var interfaceSystemDensityDpi = 0
    private var interfaceRecreateRequested = false

    private fun enforceInterfaceSize(): Boolean {
        val language = AppLocale.enforce(this)
        if (language) android.util.Log.i("DiPlayUi", "app language re-applied")
        val override = interfaceOverride ?: return language
        val found = resources.displayMetrics.densityDpi
        if (!InterfaceSize.enforce(resources, override)) return language
        android.util.Log.i("DiPlayUi", "interface size re-applied: $found -> ${override.densityDpi} dpi")
        return true
    }

    override fun attachBaseContext(newBase: Context) {
        val base = AppLocale.wrap(newBase)
        super.attachBaseContext(base)
        interfaceSystemDensityDpi = base.resources.configuration.densityDpi
        interfaceOverride = InterfaceSize.attach(this, base)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Back on the home page finishes this activity while the session runs on, so the icon lands here.
        if (savedInstanceState == null && isLauncherIntent(intent) && CarPlayBackgroundSession.hasSession()) {
            openProjection(); finish(); return
        }
        // main #488：调度每日后台更新检查并加载缓存的可用更新（作者：TRAE AI）。
        scheduleBackgroundUpdateChecks()
        availableUpdate = UpdateAvailability.available(this, version())
        enforceInterfaceSize()
        languagePreferenceAtCreate = AppLocale.preference(this)
        com.shilapi.xcertplay.hud.BydNavigationOutputs.onAppOpened(applicationContext)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        // 外观接入：解析日夜态并应用到窗口背景/系统栏/全屏模式（作者：TRAE AI）。
        refreshAppearance()
        applyWindowAppearance()
        // main B8：下次启动的 splash 主题跟随当前外观（作者：TRAE AI）。
        rememberLaunchAppearance()
        setupError = runCatching { DiPlayBootstrap.ensure(this, AirPlayPersistence.loadMfiTarget(this)) }.exceptionOrNull()?.let {
            android.util.Log.e("DiPlaySetup", "CarPlay authentication could not be loaded", it)
            getString(R.string.setup_error_auth)
        }
        pendingCarHotspotSetup = savedInstanceState?.getBoolean("pending_car_hotspot") ?: false
        bydVehicleAdvancedExpanded = savedInstanceState?.getBoolean("byd_vehicle_advanced") ?: false
        settingsCategory = savedInstanceState?.getString("settings_category")
            ?.let { runCatching { SettingsCategory.valueOf(it) }.getOrNull() }
            ?: SettingsCategory.OVERVIEW
        connectionSettingsReturnCategory = savedInstanceState?.getString("connection_settings_return_category")
            ?.let { runCatching { SettingsCategory.valueOf(it) }.getOrNull() }
        // main B4：向导步骤随实例状态恢复；仅首启未看过向导且未选 iPhone 时自动打开（作者：TRAE AI）。
        setupStep = savedInstanceState?.getInt("setup_step") ?: SetupGuide.STEP_CAR
        setupFromSettings = savedInstanceState?.getBoolean("setup_from_settings") ?: false
        page = savedInstanceState?.getString("page") ?: intent.getStringExtra("page")
            ?: if (setupError == null && SetupGuide.shouldOpenOnLaunch(SetupGuide.seen(this),
                    DiPlayPreferences.phoneAddress(this) != null)) "setup" else "home"
        render()
        scheduleAutomaticVehicleValidation()
        handleWirelessRecovery()
        // main #439：蓝牙唤醒启动时自动发起连接。
        if (consumeBluetoothAutoConnectIntent(intent)) {
            if (initialLaunch) {
                initialLaunch = false
                startCarHotspotOnLaunch()
            }
            handler.post { connect(true) }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page != "home") navigateBack()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        connectionSettingsReturnCategory = null
        // CarPlay runs in its own task, so the launcher icon resumes this one. Settings opened from
        // CarPlay carry a "page" extra, which isLauncherIntent rejects.
        if (isLauncherIntent(intent) && CarPlayBackgroundSession.hasSession()) {
            page = "home"; render(); openProjection(); return
        }
        page = intent.getStringExtra("page") ?: "home"; render()
        automaticVehicleValidationStarted = false
        scheduleAutomaticVehicleValidation()
        handleWirelessRecovery()
        // main #439：单实例复用时的蓝牙唤醒自动连接。
        if (consumeBluetoothAutoConnectIntent(intent)) {
            if (initialLaunch) {
                initialLaunch = false
                startCarHotspotOnLaunch()
            }
            handler.post { connect(true) }
        }
    }

    /** 消费蓝牙唤醒 Intent 的自动连接标记，避免重复触发。 */
    private fun consumeBluetoothAutoConnectIntent(intent: Intent): Boolean {
        val requested = intent.getBooleanExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT, false)
        intent.removeExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT)
        return requested && DiPlayPreferences.connectOnPhoneBluetooth(this) &&
            DiPlayPreferences.phoneAddress(this) != null && setupError == null &&
            !CarPlayBackgroundSession.hasSession()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page)
        outState.putString("settings_category", settingsCategory.name)
        connectionSettingsReturnCategory?.let { outState.putString("connection_settings_return_category", it.name) }
        outState.putBoolean("pending_car_hotspot", pendingCarHotspotSetup)
        outState.putBoolean("byd_vehicle_advanced", bydVehicleAdvancedExpanded)
        // main B4：保存向导步骤与来源（作者：TRAE AI）。
        outState.putInt("setup_step", setupStep)
        outState.putBoolean("setup_from_settings", setupFromSettings)
        super.onSaveInstanceState(outState)
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (updateInterfaceSize(newConfig)) return
        render()
    }

    /** True while a density change is recreating this activity. */
    private fun updateInterfaceSize(configuration: Configuration): Boolean {
        if (interfaceRecreateRequested) return true
        // The activity handles real system density changes too. Its callback contains our installed
        // density, while application resources retain the current unscaled system density.
        applicationContext.resources.configuration.densityDpi.takeIf { it > 0 }?.let {
            interfaceSystemDensityDpi = it
        }
        val next = InterfaceSize.configurationChange(configuration, interfaceSystemDensityDpi,
            InterfaceSize.preference(this))
        if (InterfaceSize.needsRecreate(interfaceOverride, next)) {
            interfaceRecreateRequested = true
            recreate()
            return true
        }
        interfaceOverride = next
        return false
    }
    private fun openOverlayPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        if (runCatching { startActivity(intent) }.isFailure) {
            android.widget.Toast.makeText(this, R.string.center_map_no_permission_screen, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onStart() {
        super.onStart()
        // Results from the image picker and crop screens arrive before onResume.
        enforceInterfaceSize()
        CenterMapOverlay.onDiPlayScreenShown()
        notifyPreviousCrash()
    }

    /** If the previous run died on an uncaught exception, point the user at the on-disk log. */
    private fun notifyPreviousCrash() {
        if (crashNoticeShown) return
        val public = CrashFileLogger.publicCrashFile()
        val last = CrashFileLogger.lastCrashFile(this)
        // 只提示真实存在的文件路径：公共目录 /sdcard/DiPlay 的写入可能因存储未挂载
        // 或权限失败而缺失，此时应指向应用私有目录的副本，避免 Toast 指向不存在的 sdcard 路径。
        val target = when {
            public.isFile -> public
            last.isFile -> last
            else -> return
        }
        crashNoticeShown = true
        // Reuses the existing "saved to path" string to avoid adding untranslated resources.
        android.widget.Toast.makeText(this, getString(R.string.diagnostic_report_saved_to_path, target.absolutePath), android.widget.Toast.LENGTH_LONG).show()
    }

    override fun onStop() {
        cancelUsbPermissionSetup()
        clusterSafeAreaDialog?.dismiss()
        startupHotspotCancelled = true
        super.onStop()
        if (!isFinishing && !isChangingConfigurations) CenterMapOverlay.scheduleShow()
    }

    override fun onResume() {
        super.onResume()
        WheelKeyService.restoreIfNeeded(this)
        applyFullscreenMode()
        // Returning from another activity can bring the head unit's own density back.
        if (enforceInterfaceSize()) render()
        if (Build.VERSION.SDK_INT < 33 && AppLocale.preference(this) != languagePreferenceAtCreate) {
            recreate()
            return
        }
        handler.removeCallbacks(tick); handler.post(tick)
        // main #748：从系统"修改系统设置"页返回且权限已授予时，重跑此前缺权限的热点启动（作者：TRAE AI）。
        if (hotspotStartupResult == CarHotspotTethering.Result.PERMISSION_REQUIRED && CarHotspotTethering.permitted(this)) {
            hotspotStartupResult = null
            startupHotspotCancelled = false
            startCarHotspotOnLaunch()
        }
        // Back from the car settings: refresh the car hotspot reminder on the home page.
        if (!initialLaunch && !adbSwitchChangePending && !pausedForAdbSwitchChange &&
            (page == "home" || page == "settings" || page == "connection")) render()
        // 恢复外观观察与轮询（作者：TRAE AI）。
        startAppearanceUpdates()
        pausedForAdbSwitchChange = false
        if (initialLaunch) {
            initialLaunch = false
            startCarHotspotOnLaunch()
            if (setupError == null && !CarPlayBackgroundSession.hasSession() &&
                DiPlayPreferences.autoConnect(this) && intent.getStringExtra("page") == null) {
                handler.post { connect(DiPlayPreferences.autoConnectWireless(this)) }
            }
        }
    }
    override fun onPause() {
        // 停止外观观察与轮询（作者：TRAE AI）。
        appearanceUpdatesResumed = false
        appearanceObserverRemoval?.invoke()
        appearanceObserverRemoval = null
        handler.removeCallbacks(appearancePoll)
        cancelKeyLearning()
        pausedForAdbSwitchChange = adbSwitchChangePending
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        // main B3：先收起搜索下拉弹层（作者：TRAE AI）。
        settingsSearchPopup?.dismiss()
        // 清理外观观察（作者：TRAE AI）。
        appearanceObserverRemoval?.invoke()
        appearanceObserverRemoval = null
        handler.removeCallbacks(appearancePoll)
        hotspotJoinControls?.close()
        cancelUsbPermissionSetup()
        cancelKeyLearning()
        stopBackupServer()
        handler.removeCallbacks(automaticVehicleValidation)
        adbCheckGeneration++
        synchronized(vehicleOperationLock) {
            vehicleProbeGeneration++
            vehicleValidationGeneration++
        }
        adbCheckInProgress = false
        vehicleProbeAuthorizationInProgress = false
        vehicleProbeInProgress = false
        automaticVehicleValidationInProgress = false
        super.onDestroy()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        applyFullscreenMode()
        if (updateInterfaceSize(newConfig)) return
        render()
    }

    private val isCompactLayout: Boolean
        // Window size only: a multi-window task can fill the whole screen, and in multi-window the
        // configuration already reports the window's own size.
        get() = resources.configuration.screenWidthDp < 550 ||
            resources.configuration.screenHeightDp < 450

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreenMode()
        // Some head units put their own density back without any other callback.
        if (hasFocus && enforceInterfaceSize()) render()
    }

    // main B6：自然完整布局判定（未勾选强制时的原始阈值结果）（作者：TRAE AI）。
    private val isNaturallyExpandedSettingsLayout: Boolean
        get() = resources.configuration.let {
            SettingsLayoutPolicy.isExpanded(it.screenWidthDp, it.screenHeightDp, it.fontScale)
        }

    // main B6：横屏强制完整布局——勾选后横屏始终用 rail 布局，竖屏自动回退（作者：TRAE AI）。
    private val isExpandedSettingsLayout: Boolean
        get() = SettingsLayoutPreferences.isActive(this) || isNaturallyExpandedSettingsLayout

    private fun render() = render(appearanceOnly = false)

    // 外观重渲染时保留焦点用的快照（main #422，作者：TRAE AI）。
    private data class FocusSnapshot(
        val tag: Any?,
        val contentDescription: String?,
        val label: String?,
    )

    private fun focusLabel(view: View): String? = when (view) {
        is TextView -> view.text?.toString()
        is ViewGroup -> descendants(view).filterIsInstance<TextView>()
            .firstOrNull { it.text.isNotEmpty() }?.text?.toString()
        else -> null
    }

    private fun render(appearanceOnly: Boolean, preferredFocusTag: Any? = null) {
        enforceInterfaceSize()
        refreshAppearance()
        applyWindowAppearance()
        // A pending assignment belongs to the widgets being replaced, never to another page.
        if (!appearanceOnly) cancelKeyLearning()
        // The backup server lives only while its page is shown, however the driver left it.
        if (page != "backup" && backupServer != null) stopBackupServer()
        val focusSnapshot = if (appearanceOnly) {
            currentFocus?.let { focused ->
                FocusSnapshot(
                    tag = preferredFocusTag ?: focused.tag,
                    contentDescription = focused.contentDescription?.toString(),
                    label = focusLabel(focused),
                )
            } ?: preferredFocusTag?.let { FocusSnapshot(it, null, null) }
        } else null
        // A restore still waiting for layout keeps its target: the old page was never laid out.
        val sameDestination = renderedPage == page &&
            (page != "settings" || renderedSettingsCategory == settingsCategory)
        val previousScrollY = (pendingScrollY ?: rootScroll?.scrollY)?.takeIf { sameDestination }
        // The rail is one destination even when its selected category changes.
        val keepRailPosition = renderedPage == "settings" && page == "settings"
        val previousRailScrollY = (pendingRailScrollY ?: settingsRailScroll?.scrollY)
            ?.takeIf { keepRailPosition }
        val restoreRailFocus = keepRailPosition &&
            (pendingRailFocus || settingsRailScroll?.hasFocus() == true)
        settingsRailScroll = null
        // 波次十 #645：结果挂在旧头部上；若搜索继续，由新头部重新打开（作者：TRAE AI）。
        settingsSearchPopup?.dismiss()
        settingsSearchPopup = null
        // 展开态重渲染后焦点由新输入框接管，恢复标记随之延续（作者：TRAE AI）。
        settingsSearchRestoreFocus = settingsSearchRestoreFocus ||
            (settingsSearchExpanded && settingsSearchBox?.hasFocus() == true)
        settingsSearchBox = null
        if (page != "settings") clearSettingsSearchState()
        status = null; connectButton = null; disconnectButton = null; lastRunning = null; carButtonCard = null
        reconnectBar = null
        readinessCard = null
        exportButton = null
        bydAdbControls = null
        adbSwitches.clear()
        adbStatus = null
        // main B6：设置页横屏强制完整布局时不压缩间距（作者：TRAE AI）。
        val compact = isCompactLayout && when (page) {
            "settings" -> !SettingsLayoutPreferences.isActive(this)
            else -> true
        }
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true; clipToPadding = false }
        rootScroll = scroll
        val content = column()
        scroll.addView(content)
        val header = if (page == "settings") settingsHeader(compact) else standardHeader(compact)
        val root: View = if (page == "settings" && isExpandedSettingsLayout) {
            content.setPadding(0, 0, 0, dp(32))
            settingsCategoryContent(content)
            expandedSettingsShell(header, scroll)
        } else {
            if (compact) content.setPadding(dp(12), dp(10), dp(12), dp(12))
            else content.setPadding(dp(32), dp(24), dp(32), dp(32))
            content.addView(header)
            content.addView(space(if (compact) 8 else 24))
            if (page == "settings") content.addView(reconnectBar(), LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(SETTINGS_BLOCK_GAP_DP)
            })
            when (page) {
                "connection" -> connectionSetup(content)
                "settings" -> settingsCategoryContent(content)
                "backup" -> backup(content)
                // main B4：连接向导独立页面（作者：TRAE AI）。
                "setup" -> setupGuide(content)
                else -> home(content)
            }
            scroll
        }
        setContentView(root)
        renderedPage = page
        renderedSettingsCategory = settingsCategory.takeIf { page == "settings" }
        refreshStatus()
        // 外观重渲染后按快照恢复焦点（main #422，作者：TRAE AI）。
        focusSnapshot?.let { snapshot ->
            root.doOnLayout {
                descendants(root).firstOrNull { candidate ->
                    candidate.isFocusable && when {
                        snapshot.tag != null -> candidate.tag == snapshot.tag
                        snapshot.contentDescription != null ->
                            candidate.contentDescription?.toString() == snapshot.contentDescription
                        snapshot.label != null -> focusLabel(candidate) == snapshot.label
                        else -> false
                    }
                }?.requestFocus()
            }
        }
        pendingScrollY = previousScrollY
        // A stopped window still dispatches pre-draw but skips layout, so wait for a real layout;
        // the listener stays on this view and goes away with it.
        previousScrollY?.let { y ->
            scroll.doOnLayout {
                if (rootScroll === scroll) {
                    scroll.scrollTo(0, y)
                    pendingScrollY = null
                }
            }
        }
        val rail = settingsRailScroll
        pendingRailScrollY = previousRailScrollY.takeIf { rail != null }
        pendingRailFocus = restoreRailFocus && rail != null
        rail?.doOnLayout {
            if (settingsRailScroll !== rail) return@doOnLayout
            previousRailScrollY?.let { rail.scrollTo(0, it) }
            val destinations = rail.getChildAt(0) as ViewGroup
            val selected = (0 until destinations.childCount)
                .map(destinations::getChildAt).firstOrNull { it.isSelected }
            if (selected != null) {
                if (restoreRailFocus) selected.requestFocus()
                selected.requestRectangleOnScreen(android.graphics.Rect(0, 0, selected.width, selected.height), true)
            }
            pendingRailScrollY = null
            pendingRailFocus = false
        }
    }

    // ===== 外观切换闭环（main #422，作者：TRAE AI）=====
    // API < 20：WindowInsetsControllerCompat 不实现系统栏可见性，回退到老式窗口标志。
    private fun applyFullscreenMode() {
        val multiWindow = Build.VERSION.SDK_INT >= 24 && isInMultiWindowMode
        val hideTop = AirPlayPersistence.loadHideTopBar(this) && !multiWindow
        val hideBottom = AirPlayPersistence.loadHideBottomBar(this) && !multiWindow
        if (LegacyFullscreen.apply(window, hideTop, hideBottom)) return
        WindowCompat.setDecorFitsSystemWindows(window, !(hideTop && hideBottom))
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = palette.systemBarIconsAreDark
        controller.isAppearanceLightNavigationBars = palette.systemBarIconsAreDark
        if (hideTop) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
        if (hideBottom) {
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        } else {
            controller.show(WindowInsetsCompat.Type.navigationBars())
        }
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ViewCompat.requestApplyInsets(window.decorView)
    }

    // 注册 runtime 观察者并启动兜底轮询；resume 期间挂起的重渲染在此补做。
    private fun startAppearanceUpdates() {
        appearanceUpdatesResumed = true
        appearanceObserverRemoval?.invoke()
        appearanceObserverRemoval = AppAppearanceRuntime.observeHost {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                checkForAppearanceChange()
            } else {
                handler.post { if (appearanceUpdatesResumed) checkForAppearanceChange() }
            }
        }
        handler.removeCallbacks(appearancePoll)
        handler.post(appearancePoll)
        applyPendingAppearanceRender()
    }

    private fun checkForAppearanceChange() {
        if (refreshAppearance()) {
            requestAppearanceRender()
        }
    }

    // 外观变化的重渲染入口；按键学习期间延迟，避免打断学习流程。
    private fun requestAppearanceRender(focusAppearanceButton: Boolean = false) {
        appearanceRenderPending = true
        appearanceButtonFocusPending = appearanceButtonFocusPending || focusAppearanceButton
        if (!appearanceUpdatesResumed || shouldDeferAppearanceRender(
                windowKeyLearning = windowLearning != null,
                serviceKeyLearning = WheelKeyService.isLearning(),
            )
        ) return
        appearanceRenderPending = false
        val restoreAppearanceButton = appearanceButtonFocusPending
        appearanceButtonFocusPending = false
        render(
            appearanceOnly = true,
            preferredFocusTag = APPEARANCE_BUTTON_TAG.takeIf { restoreAppearanceButton },
        )
    }

    private fun applyPendingAppearanceRender() {
        if (!appearanceRenderPending) return
        handler.post {
            if (appearanceRenderPending && appearanceUpdatesResumed &&
                !shouldDeferAppearanceRender(
                    windowKeyLearning = windowLearning != null,
                    serviceKeyLearning = WheelKeyService.isLearning(),
                )
            ) {
                requestAppearanceRender()
            }
        }
    }

    // 重取日夜态与调色板；返回 true 表示发生了变化需要重渲染。
    private fun refreshAppearance(): Boolean {
        val resolved = resolveAppNightNow()
        if (resolved == appNight && palette === DiPlayPalette.of(resolved)) return false
        appNight = resolved
        palette = DiPlayPalette.of(resolved)
        return true
    }

    // 窗口背景与系统栏跟随调色板；系统栏图标明暗随外观切换。
    private fun applyWindowAppearance() {
        window.setBackgroundDrawable(ColorDrawable(palette.background))
        UiCompat.setSystemBarColors(window, palette.systemBar)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = palette.systemBarIconsAreDark
            isAppearanceLightNavigationBars = palette.systemBarIconsAreDark
        }
        applyFullscreenMode()
    }

    // main B8：系统在下一次启动时、任何应用代码运行前绘制启动窗口，API 33 起应用才能选择它的主题；
    // 更旧的车机保持深色边框。AUTO 维持深色：无法预知下次启动的时段，白天显示深色框比夜间显示亮框安全。
    private fun rememberLaunchAppearance() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val light = AirPlayPersistence.loadAppAppearance(this) == AppAppearance.LIGHT
        splashScreen.setSplashScreenTheme(if (light) R.style.Theme_Xcertplay_Light else R.style.Theme_Xcertplay)
    }

    // 外观切换按钮：点击在深色/浅色间切换并触发增量重渲染（main #422，作者：TRAE AI）。
    private fun appearanceButton(): ImageButton = iconButton(
        icon = if (appNight) R.drawable.ic_dp_night else R.drawable.ic_dp_day,
        description = getString(if (appNight) R.string.settings_switch_to_light_appearance
            else R.string.settings_switch_to_dark_appearance),
    ) {
        AirPlayPersistence.saveAppAppearance(
            this,
            if (appNight) AppAppearance.LIGHT else AppAppearance.DARK,
        )
        // main B8：下次启动的 splash 主题跟随新外观（作者：TRAE AI）。
        rememberLaunchAppearance()
        requestAppearanceRender(focusAppearanceButton = true)
    }.apply {
        tag = APPEARANCE_BUTTON_TAG
    }

    // 标题栏圆形图标按钮（焦点环 + 涟漪，遥控器可用）（main #422，作者：TRAE AI）。
    private fun iconButton(icon: Int, description: String, click: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        // API17 适配：imageTintList/stateListAnimator/RippleDrawable/foreground 是 API 21+/23+，全部走 UiCompat（作者：TRAE AI）。
        UiCompat.setImageTint(this, ACCENT)
        contentDescription = description
        scaleType = ImageView.ScaleType.CENTER
        setPadding(dp(12), dp(12), dp(12), dp(12))
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        UiCompat.clearStateListAnimator(this)
        background = UiCompat.ripple(RIPPLE, rounded(BUTTON, BORDER), null)
        UiCompat.setForeground(this, focusRing())
        setOnClickListener { click() }
    }

    private fun standardHeader(compact: Boolean): LinearLayout = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(this@DiPlayActivity).apply {
            setImageResource(R.drawable.ic_carplay)
            contentDescription = getString(R.string.carplay)
        }, LinearLayout.LayoutParams(if (compact) dp(30) else dp(36), if (compact) dp(30) else dp(36)))
        addView(label(getString(R.string.diplay), if (compact) 18 else 26, TEXT, true).apply {
            setPadding(if (compact) dp(8) else dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, if (compact) dp(36) else dp(56), 1f))
        // 标题栏右侧加入外观切换按钮（main #422，作者：TRAE AI）。
        addView(appearanceButton(), LinearLayout.LayoutParams(dp(if (compact) 44 else 52), dp(if (compact) 44 else 52)).apply {
            marginEnd = dp(if (compact) 8 else 12)
        })
        if (page != "home" || !compact) {
            addView(button(if (page == "home") getString(R.string.car_home) else getString(R.string.back), false) {
                if (page == "home") startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
                else navigateBack()
            }, LinearLayout.LayoutParams(if (compact) dp(80) else dp(130), if (compact) dp(36) else dp(56)))
        }
    }

    private fun navigateBack() {
        // main B3：返回键先关闭打开的搜索，与系统搜索栏行为一致（作者：TRAE AI）。
        // 波次十 #645：判定改为两态展开标记，收起态下返回键不再吞掉（作者：TRAE AI）。
        if (page == "settings" && settingsSearchExpanded) {
            closeSettingsSearch()
            return
        }
        val returnCategory = connectionSettingsReturnCategory
        when {
            // main B4：向导第一步之后逐步回退；第一步则关闭向导（作者：TRAE AI）。
            page == "backup" -> {
                stopBackupServer()
                page = "settings"
                settingsCategory = backupOpenedFrom
            }
            page == "setup" && setupStep > SetupGuide.STEP_CAR -> setupStep--
            page == "setup" -> {
                closeSetupGuide()
                return
            }
            page == "connection" && returnCategory != null -> {
                page = "settings"
                settingsCategory = returnCategory
                connectionSettingsReturnCategory = null
            }
            page == "settings" && !isExpandedSettingsLayout && settingsCategory != SettingsCategory.OVERVIEW -> {
                settingsCategory = SettingsCategory.OVERVIEW
            }
            else -> {
                page = "home"
                connectionSettingsReturnCategory = null
            }
        }
        render()
    }

    private fun openConnectionSetupFromSettings() {
        connectionSettingsReturnCategory = settingsCategory
        page = "connection"
        render()
    }

    // ===== main B4：连接向导（SetupGuide）页面组（作者：TRAE AI）=====

    private fun openSetupGuide(fromSettings: Boolean) {
        setupFromSettings = fromSettings
        setupStep = SetupGuide.STEP_CAR
        page = "setup"
        render()
    }

    private fun closeSetupGuide(connectNow: Boolean = false) {
        SetupGuide.markSeen(this)
        page = if (setupFromSettings) "settings" else "home"
        setupFromSettings = false
        setupStep = SetupGuide.STEP_CAR
        render()
        if (connectNow) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun dilinkLabel(generation: DiLinkGeneration) = getString(when (generation) {
        DiLinkGeneration.DILINK_3 -> R.string.dilink_3
        DiLinkGeneration.DILINK_4 -> R.string.dilink_4
        DiLinkGeneration.DILINK_5 -> R.string.dilink_5
        DiLinkGeneration.UNKNOWN -> R.string.dilink_not_sure
    })

    private fun setupGuide(content: LinearLayout) {
        val step = setupStep.coerceIn(SetupGuide.STEP_CAR, SetupGuide.STEP_DONE)
        // API17 适配：TextView.setLetterSpacing 是 API 21+，走 UiCompat（作者：TRAE AI）。
        content.addView(label(getString(R.string.setup_step_of, step + 1, SetupGuide.STEP_COUNT), 13, ACCENT, true)
            .apply { UiCompat.setLetterSpacing(this, .12f) })
        // 顶部步骤进度条：已完成与当前步高亮。
        val progress = row().apply { setPadding(0, dp(10), 0, dp(20)) }
        repeat(SetupGuide.STEP_COUNT) { index ->
            progress.addView(View(this).apply {
                background = GradientDrawable().apply {
                    setColor(if (index <= step) ACCENT else BUTTON); cornerRadius = dp(3).toFloat()
                }
            }, LinearLayout.LayoutParams(0, dp(6), 1f).apply { if (index > 0) marginStart = dp(8) })
        }
        content.addView(progress)
        when (step) {
            SetupGuide.STEP_CAR -> setupCarStep(content)
            SetupGuide.STEP_CONNECTION -> setupConnectionStep(content)
            SetupGuide.STEP_IPHONE -> setupIphoneStep(content)
            SetupGuide.STEP_FEATURES -> setupFeaturesStep(content)
            else -> setupDoneStep(content)
        }
        if (step == SetupGuide.STEP_DONE) return
        val footer = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
        footer.addView(button(getString(R.string.setup_skip), false) { closeSetupGuide() },
            LinearLayout.LayoutParams(0, dp(60), 1f))
        footer.addView(space(16), LinearLayout.LayoutParams(dp(16), 1))
        footer.addView(button(getString(R.string.setup_next), true) { setupStep = step + 1; render() },
            LinearLayout.LayoutParams(0, dp(60), 1f))
        content.addView(footer)
    }

    private fun setupTitle(content: LinearLayout, title: Int, description: String) {
        content.addView(label(getString(title), 34, TEXT, true))
        content.addView(label(description, 17, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
    }

    private fun setupCarStep(content: LinearLayout) {
        val detection = DiLinkGeneration.detect()
        val selected = DiLinkGeneration.confirmed(this) ?: detection.generation
        setupTitle(content, R.string.setup_car_title, when (detection.source) {
            DiLinkGeneration.Source.SYSTEM_VERSION -> getString(R.string.setup_car_detected, dilinkLabel(detection.generation))
            DiLinkGeneration.Source.LIKELY -> getString(R.string.setup_car_likely, dilinkLabel(detection.generation))
            DiLinkGeneration.Source.NONE -> getString(R.string.setup_car_unknown)
        })
        val card = card()
        card.addView(label(getString(R.string.setup_car_choose), 22, TEXT, true))
        val wide = resources.configuration.screenWidthDp >= 850
        val choices = if (wide) row() else column()
        DiLinkGeneration.entries.forEachIndexed { index, generation ->
            val chosen = generation == selected
            choices.addView(button("${if (chosen) "✓  " else ""}${dilinkLabel(generation)}", chosen) {
                DiLinkGeneration.save(this, generation)
                render()
            }, if (wide) LinearLayout.LayoutParams(0, dp(60), 1f).apply {
                topMargin = dp(12); if (index > 0) marginStart = dp(12)
            } else matchButton(12, 60))
        }
        card.addView(choices)
        detection.evidence?.let {
            card.addView(label(getString(R.string.setup_car_evidence, it), 13, MUTED).apply { setPadding(0, dp(16), 0, 0) })
        }
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
    }

    private fun setupConnectionStep(content: LinearLayout) {
        setupTitle(content, R.string.setup_connection_title, getString(R.string.setup_connection_description))
        val card = card()
        wirelessLinkControls(card)
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
    }

    /** main #799 前置（波次十三批次G，c2e1b22e）：选定 iPhone 前按钮只显示"选择 iPhone"，不再带英文占位名。 */
    private fun chooseIphoneTitle(): String =
        if (DiPlayPreferences.phoneAddress(this) == null) getString(R.string.choose_iphone)
        else "${getString(R.string.choose_iphone_prefix)}${DiPlayPreferences.phoneName(this)}"

    private fun setupIphoneStep(content: LinearLayout) {
        setupTitle(content, R.string.setup_iphone_title, getString(R.string.keep_bluetooth_and_wi_fi_on_your_iphone_pair_with_the_car))
        val card = card()
        val chosen = DiPlayPreferences.phoneAddress(this) != null
        card.addView(label(if (chosen) DiPlayPreferences.phoneName(this) else getString(R.string.setup_iphone_none), 22,
            if (chosen) READY else TEXT, true))
        card.addView(button(chooseIphoneTitle(), !chosen) {
            choosePhone()
        }, matchButton(16, 60))
        card.addView(button(getString(R.string.review_app_permissions), false) {
            openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }, matchButton(12, 60))
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
    }

    private fun setupFeaturesStep(content: LinearLayout) {
        val generation = DiLinkGeneration.current(this)
        setupTitle(content, R.string.setup_features_title, getString(R.string.setup_features_description,
            dilinkLabel(generation)))
        // DiLink 3 车开了 DiLink 4 ADB 仪表路线会挡住仪表地图，给出关掉的快捷操作。
        if (SetupGuide.hasConflictingClusterRoute(generation, AirPlayPersistence.loadDiLink4ClusterRouteEnabled(this))) {
            val warning = card()
            clusterRouteConflict(warning)
            content.addView(warning, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
        }
        val card = card()
        SetupGuide.features(generation).forEach { entry ->
            val before = card.childCount
            when (entry.feature) {
                SetupGuide.Feature.AUTO_CONNECT -> toggle(card, getString(R.string.connect_when_diplay_opens),
                    getString(R.string.default_connection_description), DiPlayPreferences.autoConnect(this)) {
                    DiPlayPreferences.saveAutoConnect(this, it)
                }
                SetupGuide.Feature.LOCATION -> toggle(card, getString(R.string.report_location_to_iphone),
                    getString(R.string.sends_precise_android_location_as_carplay_gps_data_when_th),
                    AirPlayPersistence.loadLocationReportingEnabled(this), save = ::onLocationReportingChanged)
                SetupGuide.Feature.CLUSTER_MAP -> reconnectingToggle(card,
                    getString(R.string.carplay_map_on_instrument_cluster_experimental),
                    getString(R.string.shows_the_iphone_s_cluster_map_on_the_instrument_cluster_c),
                    AirPlayPersistence.loadClusterMapEnabled(this)) {
                    AirPlayPersistence.saveClusterMapEnabled(this, it)
                    render()
                }
                SetupGuide.Feature.DILINK4_ADB_CLUSTER -> reconnectingToggle(card,
                    getString(R.string.adb_cluster_activity_mode), getString(R.string.adb_cluster_activity_description),
                    AirPlayPersistence.loadAdbClusterEnabled(this)) {
                    AirPlayPersistence.saveAdbClusterEnabled(this, it)
                    ClusterActivityOutput.stopForSettings()
                    render()
                }
                SetupGuide.Feature.BYD_NAVIGATION -> if (BydOutputSettings.available(this)) {
                    toggle(card, getString(R.string.navigation_on_hud_and_instrument_cluster),
                        getString(R.string.show_phone_navigation_arrows_distance_and_street_names_on),
                        BydOutputSettings.enabled(this)) { BydOutputSettings.setEnabled(this, it) }
                }
                SetupGuide.Feature.CALLS_ON_DASHBOARD -> toggle(card, getString(R.string.carplay_calls_on_dashboard),
                    getString(R.string.carplay_calls_on_dashboard_description),
                    BydOutputSettings.carPlayCalls(this), enabled = !adbSwitchChangePending) { applyCarPlayCalls(it) }
                SetupGuide.Feature.CALL_KEYS -> toggle(card, getString(R.string.carplay_call_controls_experimental),
                    getString(R.string.carplay_call_controls_experimental_description),
                    BydOutputSettings.carPlayCallControls(this)) { applyCallKeys(it) }
            }
            if (card.childCount > before) card.addView(setupBadges(entry))
        }
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
    }

    /** The warning and the button that turn both DiLink 4 cluster routes off (saving the ADB route clears both). */
    private fun clusterRouteConflict(parent: LinearLayout) {
        parent.addView(label(getString(R.string.setup_cluster_route_conflict), 16, WARNING))
        parent.addView(button(getString(R.string.setup_cluster_route_turn_off), true) {
            AirPlayPersistence.saveAdbClusterEnabled(this, false)
            ClusterActivityOutput.stopForSettings()
            markReconnectNeeded()
            render()
        }, matchButton(12, 56))
    }

    // 每个功能行下的徽章：实测（绿）/实验性（黄）+ 需要 ADB。
    private fun setupBadges(entry: SetupGuide.Entry): LinearLayout = row().apply {
        setPadding(0, dp(2), 0, dp(14))
        fun badge(text: String, color: Int) = label(text, 12, color, true).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT); cornerRadius = dp(10).toFloat(); setStroke(dp(1), color)
            }
            setPadding(dp(10), dp(3), dp(10), dp(3))
        }
        val tested = entry.status == SetupGuide.Status.TESTED
        addView(badge(getString(if (tested) R.string.setup_badge_tested else R.string.setup_badge_experimental),
            if (tested) READY else WARNING), LinearLayout.LayoutParams(-2, -2))
        if (entry.needsAdb) addView(badge(getString(R.string.setup_badge_adb), ACCENT),
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
    }

    private fun setupDoneStep(content: LinearLayout) {
        setupTitle(content, R.string.setup_done_title, getString(R.string.setup_done_description))
        val card = card()
        card.addView(button(getString(R.string.connect_phone), true) { closeSetupGuide(connectNow = true) }, matchButton(0, 60))
        card.addView(button(getString(R.string.settings), false) {
            SetupGuide.markSeen(this)
            setupFromSettings = false
            setupStep = SetupGuide.STEP_CAR
            page = "settings"
            render()
        }, matchButton(12, 60))
        card.addView(button(getString(R.string.done), false) { closeSetupGuide() }, matchButton(12, 60))
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
    }

    /**
     * 波次十 #645：设置页头两态搜索（作者：TRAE AI）。
     * 收起态：标题占满剩余宽度，尾部是搜索按钮（窄屏/竖屏为图标、完整横屏为文字按钮）。
     * 展开态：标题收缩为自适应宽度，搜索输入框占满剩余宽度。
     */
    private fun settingsHeader(compact: Boolean): LinearLayout = row().apply {
        val controlHeight = dp(if (compact) 44 else 52)
        val iconOnlySearch = compact || resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        gravity = Gravity.CENTER_VERTICAL
        addView(button(getString(R.string.back), false, ::navigateBack),
            LinearLayout.LayoutParams(if (compact) dp(78) else dp(112), controlHeight))
        val settingsTitle = label(getString(R.string.settings), if (compact) 20 else 26, TEXT, true).apply {
            setPadding(dp(12), 0, dp(12), 0)
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        // 先给操作按钮占位；窄头部里只允许标题收缩（作者：TRAE AI）。
        addView(settingsTitle, LinearLayout.LayoutParams(
            if (settingsSearchExpanded) -2 else 0, controlHeight,
            if (settingsSearchExpanded) 0f else 1f,
        ))
        val searchGap = dp(if (compact) SETTINGS_HEADER_COMPACT_GAP_DP else SETTINGS_HEADER_GAP_DP)
        if (settingsSearchExpanded) {
            val searchField = settingsSearchField(compact)
            addView(searchField, LinearLayout.LayoutParams(0, controlHeight, 1f).apply {
                marginEnd = searchGap
            })
            searchField.doOnLayout {
                // 被挤窄的输入框放不下提示文案时，把标题的空间让给它（按当前语言与字体缩放测量）。
                val hintWidth = searchField.paint.measureText(searchField.hint.toString())
                val requiredWidth = hintWidth + searchField.compoundPaddingLeft + searchField.compoundPaddingRight
                if (searchField.width < requiredWidth) {
                    settingsTitle.visibility = View.GONE
                    searchField.layoutParams = (searchField.layoutParams as LinearLayout.LayoutParams).apply {
                        marginStart = searchGap
                    }
                }
            }
        } else {
            val openSearch = {
                // 索引构建会重渲染设置页；先建索引再聚焦，避免第一个按键替换输入法的连接（作者：TRAE AI）。
                settingsSearchIndex = buildSettingsSearchIndex()
                settingsSearchExpanded = true
                settingsSearchRestoreFocus = true
                render()
            }
            val searchButton = if (iconOnlySearch) {
                iconButton(R.drawable.ic_dp_search, getString(R.string.settings_search), openSearch).apply {
                    UiCompat.setImageTint(this, TEXT)
                }
            } else {
                button(getString(R.string.settings_search_hint), false, openSearch)
            }.apply {
                tag = SETTINGS_SEARCH_BUTTON_TAG
                contentDescription = getString(R.string.settings_search)
            }
            addView(searchButton, LinearLayout.LayoutParams(
                if (iconOnlySearch) controlHeight else -2, controlHeight).apply {
                marginEnd = searchGap
            })
        }
        // 标题栏右侧加入外观切换按钮（main #422，作者：TRAE AI）。
        addView(appearanceButton(), LinearLayout.LayoutParams(controlHeight, controlHeight))
    }

    private fun home(content: LinearLayout) {
        val compact = isCompactLayout
        if (compact) {
            val card = card().apply { setPadding(dp(12), dp(10), dp(12), dp(10)) }
            status = label(getString(R.string.ready_when_you_are), 16, TEXT, true).apply {
                setPadding(0, 0, 0, dp(8))
            }
            card.addView(status)
            connectButton = button(getString(R.string.connect_phone), true) {
                if (CarPlayBackgroundSession.hasSession()) openProjection()
                else connect(true)
            }
            card.addView(connectButton, matchButton(0, 44))

            val buttonRow = row().apply {
                setPadding(0, dp(8), 0, 0)
                gravity = Gravity.CENTER_VERTICAL
            }
            val usbBtn = button(getString(R.string.connect_with_usb), false) { connect(false) }
            val settingsBtn = button(getString(R.string.settings), false) { page = "settings"; render() }
            buttonRow.addView(usbBtn, LinearLayout.LayoutParams(0, dp(38), 1f))
            buttonRow.addView(space(8), LinearLayout.LayoutParams(dp(8), 1))
            buttonRow.addView(settingsBtn, LinearLayout.LayoutParams(0, dp(38), 1f))
            card.addView(buttonRow)

            disconnectButton = button(getString(R.string.disconnect), false) {
                disconnectButton?.isEnabled = false
                CarPlayBackgroundSession.stop { runOnUiThread { refreshStatus() } }
            }.apply { visibility = View.GONE }
            card.addView(disconnectButton, matchButton(8, 38))

            content.addView(card)
            // main #488：compact 首页同样显示后台更新提示，点击跳转 about 页的下载入口（作者：TRAE AI）。
            availableUpdate?.let { release ->
                val message = getString(R.string.update_available_home)
                val tint = { alpha: Int -> (WARNING and 0x00FFFFFF) or (alpha shl 24) }
                val chip = GradientDrawable().apply {
                    setColor(tint(0x26))
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), tint(0x80))
                }
                content.addView(label(message, 14, WARNING, true).apply {
                    gravity = Gravity.CENTER
                    minHeight = dp(60)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    isClickable = true
                    isFocusable = true
                    contentDescription = message
                    // API17 适配：RippleDrawable 是 API 21+、foreground 是 API 23+，走 UiCompat（作者：TRAE AI）。
                    UiCompat.setForeground(this, android.graphics.drawable.LayerDrawable(arrayOf(
                        UiCompat.ripple(RIPPLE, chip, null), focusRing(16))))
                    setOnClickListener { openAvailableUpdate(release) }
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            }
            setupError?.let { content.addView(label(it, 13, WARNING).apply { setPadding(0, dp(6), 0, 0) }) }
            return
        }

        val wide = resources.configuration.screenWidthDp >= 850
        val body = column()
        val left = column()
        left.addView(label(getString(R.string.your_phone_your_drive), 12, ACCENT, true).apply { UiCompat.setLetterSpacing(this, .16f) })
        left.addView(label(getString(R.string.a_familiar_drive), if (wide) 42 else 36, TEXT, true).apply { setPadding(0, dp(12), 0, dp(10)) })
        left.addView(label(getString(R.string.your_maps_music_and_conversations_carplay_right_here_on_yo), 19, MUTED))
        val card = card()
        // main #742：标题保存为字段，USB 会话时改为 "USB 有线 CarPlay"（作者：TRAE AI）。
        homeCardTitle = label(getString(R.string.wireless_carplay), 12, ACCENT, true).apply { UiCompat.setLetterSpacing(this, .12f) }
        card.addView(homeCardTitle)
        status = label(getString(R.string.ready_when_you_are), 24, TEXT, true).apply { setPadding(0, dp(10), 0, dp(16)) }
        card.addView(status)
        connectButton = button(getString(R.string.connect_phone), true) {
            if (CarPlayBackgroundSession.hasSession()) openProjection()
            else connect(true)
        }
        card.addView(connectButton, matchButton())
        val connectionHint = when (AirPlayPersistence.loadWirelessHotspotMode(this)) {
            WirelessHotspotMode.EXISTING_WIFI -> getString(R.string.existing_wifi_hint)
            WirelessHotspotMode.MANUAL -> getString(R.string.hotspot_hint_manual)
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> getString(R.string.hotspot_hint_local)
            else -> getString(R.string.hotspot_hint_p2p)
        }
        // main #742：提示保存为字段，USB 会话时隐藏（作者：TRAE AI）。
        homeConnectionHint = label(connectionHint, 15, MUTED).apply { setPadding(0, dp(14), 0, 0) }
        homeConnectionHint?.let(card::addView)
        val startupProblem = hotspotStartupResult?.takeIf {
            it != CarHotspotTethering.Result.READY && it != CarHotspotTethering.Result.CANCELLED &&
                CarHotspotSettings.shouldEnable(this, true, AirPlayPersistence.loadWirelessHotspotMode(this)) &&
                com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this) != true
        }
        if (startupProblem != null) {
            // main #748：非 BYD 车机在 Android"修改系统设置"页即可放行热点启动权限，无需 ADB（作者：TRAE AI）。
            val allowOnSystemScreen = startupProblem == CarHotspotTethering.Result.PERMISSION_REQUIRED &&
                !CarHotspotSetup.isBydHeadUnit(this) && CarHotspotTethering.appStartSupported()
            card.addView(label(if (allowOnSystemScreen) getString(R.string.settings_write_settings_missing)
                else hotspotResultText(startupProblem), 15, WARNING))
            card.addView(
                if (allowOnSystemScreen) button(getString(R.string.settings_write_settings_allow), false) { openWriteSettingsAccess() }
                else button(getString(R.string.open_car_hotspot_settings), false) { openCarWifiSettings() },
                matchButton(10, 56))
        } else if (carHotspotOff()) {
            val name = AirPlayPersistence.loadManualHotspotSsid(this)
            val off = if (name.isBlank()) getString(R.string.hotspot_details_off) else getString(R.string.msg_car_hotspot_off, name)
            card.addView(label(off, 15, WARNING).apply { setPadding(0, dp(14), 0, 0) })
            card.addView(button(getString(R.string.open_car_hotspot_settings), false) { openCarWifiSettings() }, matchButton(10, 56))
        }
        card.addView(button(getString(R.string.choose_iphone), false) { choosePhone() }, matchButton(16, 56))
        disconnectButton = button(getString(R.string.disconnect), false) {
            disconnectButton?.isEnabled = false
            CarPlayBackgroundSession.stop { runOnUiThread { refreshStatus() } }
        }.apply { visibility = View.GONE }
        card.addView(disconnectButton, matchButton(10, 56))
        val right = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_carplay)
            contentDescription = getString(R.string.carplay_icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val branding = column().apply {
            gravity = Gravity.CENTER
            addView(logo, LinearLayout.LayoutParams(dp(96), dp(96)))
        }
        right.addView(button(getString(R.string.connect_with_usb), false) { connect(false) }, matchButton())
        right.addView(label(getString(R.string.plug_your_iphone_into_a_usb_data_port_allow_carplay_when_y), 14, MUTED).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(24)) })
        right.addView(button(getString(R.string.settings), false) { page = "settings"; render() }, matchButton())
        right.addView(label(getString(R.string.make_diplay_feel_right_for_your_car), 14, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(24)) })
        right.addView(label("${getString(R.string.home_public_preview)}${version()}", 12, MUTED).apply { UiCompat.setLetterSpacing(this, .08f) })
        // main #488：后台检查发现新版本时，首页显示安静的更新提示（作者：TRAE AI）。
        availableUpdate?.let { release ->
            val message = getString(R.string.update_available_home)
            val tint = { alpha: Int -> (WARNING and 0x00FFFFFF) or (alpha shl 24) }
            val chip = GradientDrawable().apply {
                setColor(tint(0x26))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), tint(0x80))
            }
            right.addView(label(message, 14, WARNING, true).apply {
                gravity = Gravity.CENTER
                minHeight = dp(60)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isClickable = true
                isFocusable = true
                contentDescription = message
                // API17 适配：RippleDrawable 是 API 21+、foreground 是 API 23+，走 UiCompat（作者：TRAE AI）。
                UiCompat.setForeground(this, android.graphics.drawable.LayerDrawable(arrayOf(
                    UiCompat.ripple(RIPPLE, chip, null), focusRing(16))))
                setOnClickListener { openAvailableUpdate(release) }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        if (wide) {
            // Both rows share column widths. The USB button starts at the wireless
            // card's top edge, independently of hero wrapping or font scaling.
            fun columns(first: View, second: View, stretchSecond: Boolean = false) = row().apply {
                gravity = Gravity.TOP
                addView(first, LinearLayout.LayoutParams(0, -2, 1.6f))
                addView(space(40), LinearLayout.LayoutParams(dp(40), 1))
                addView(second, LinearLayout.LayoutParams(0, if (stretchSecond) -1 else -2, 1f))
            }
            body.addView(columns(left, branding, true))
            body.addView(space(26))
            body.addView(columns(card, right))
        } else {
            body.addView(left)
            body.addView(space(26))
            body.addView(card)
            body.addView(space(26))
            body.addView(branding)
            body.addView(space(24))
            body.addView(right)
        }
        setupError?.let { body.addView(label(it, 16, WARNING).apply { setPadding(0, dp(16), 0, 0) }) }
        content.addView(body)
    }

    // Only the category scrolls, so the rail keeps showing where the driver is.
    private fun expandedSettingsShell(header: View, categoryScroll: ScrollView): LinearLayout = column().apply {
        setBackgroundColor(BG)
        setPadding(dp(32), dp(24), dp(32), 0)
        addView(header)
        addView(space(24))
        addView(reconnectBar(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
        val split = row().apply { gravity = Gravity.TOP }
        // The rail scrolls on its own only when the window is too short for every destination.
        split.addView(ScrollView(this@DiPlayActivity).apply {
            addView(settingsRail())
            settingsRailScroll = this
        },
            LinearLayout.LayoutParams(dp(SettingsLayoutPolicy.railWidthDp(resources.configuration.fontScale)), -2))
        split.addView(space(24), LinearLayout.LayoutParams(dp(24), 1))
        split.addView(categoryScroll, LinearLayout.LayoutParams(0, -1, 1f))
        addView(split, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun settingsRail(): LinearLayout = column().apply {
        background = rounded(SURFACE, BORDER)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        SettingsCategory.entries.forEach { category ->
            addView(settingsRailDestination(category), matchButton(0, 52).apply {
                bottomMargin = dp(8)
            })
        }
    }

    private fun settingsRailDestination(category: SettingsCategory): View {
        val selected = settingsCategory == category
        val title = settingsCategoryTitle(category)
        return row().apply {
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            isSelected = selected
            UiCompat.setForeground(this, focusRing(12))
            contentDescription = getString(R.string.settings_open_category, title)
            background = UiCompat.ripple(
                RIPPLE,
                GradientDrawable().apply {
                    setColor(if (selected) RAIL_SELECTED else Color.TRANSPARENT)
                    cornerRadius = dp(12).toFloat()
                    if (selected) setStroke(dp(1), RAIL_SELECTED_BORDER)
                },
                null,
            )
            setPadding(dp(8), 0, dp(12), 0)
            addView(View(this@DiPlayActivity).apply {
                background = GradientDrawable().apply {
                    setColor(if (selected) ACCENT else Color.TRANSPARENT)
                    cornerRadius = dp(2).toFloat()
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(4), dp(34)).apply { marginEnd = dp(10) })
            addView(ImageView(this@DiPlayActivity).apply {
                setImageResource(settingsCategoryIcon(category))
                UiCompat.setImageTint(this, if (selected) ACCENT else TEXT)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(12) })
            addView(label(title, 16, if (selected) ACCENT else TEXT, true),
                LinearLayout.LayoutParams(0, -1, 1f))
            setOnClickListener {
                openSettingsCategory(category)
            }
        }
    }

    private fun settingsCategoryIcon(category: SettingsCategory): Int = when (category) {
        SettingsCategory.OVERVIEW -> R.drawable.ic_dp_overview
        SettingsCategory.CONNECTION -> R.drawable.ic_dp_connection
        SettingsCategory.DISPLAY -> R.drawable.ic_dp_display
        SettingsCategory.AUDIO -> R.drawable.ic_dp_audio
        SettingsCategory.NAVIGATION -> R.drawable.ic_dp_navigation
        SettingsCategory.VEHICLE -> R.drawable.ic_dp_car
        SettingsCategory.DIAGNOSTICS -> R.drawable.ic_dp_diagnostics
        SettingsCategory.ADVANCED -> R.drawable.ic_dp_advanced
        SettingsCategory.LANGUAGE -> R.drawable.ic_dp_language
        SettingsCategory.ABOUT -> R.drawable.ic_dp_about
    }

    private fun settingsCategoryTitle(category: SettingsCategory): String = getString(when (category) {
        SettingsCategory.OVERVIEW -> R.string.settings_overview
        SettingsCategory.CONNECTION -> R.string.connection
        SettingsCategory.DISPLAY -> R.string.settings_display
        SettingsCategory.AUDIO -> R.string.audio
        SettingsCategory.NAVIGATION -> R.string.settings_navigation
        SettingsCategory.VEHICLE -> R.string.settings_vehicle
        SettingsCategory.DIAGNOSTICS -> R.string.diagnostics
        SettingsCategory.ADVANCED -> R.string.settings_advanced
        SettingsCategory.LANGUAGE -> R.string.language_section_title
        SettingsCategory.ABOUT -> R.string.about
    })

    private fun settingsCategoryContent(content: LinearLayout) {
        when (settingsCategory) {
            SettingsCategory.OVERVIEW -> settingsOverview(content)
            SettingsCategory.CONNECTION -> connectionSettings(content)
            SettingsCategory.DISPLAY -> displaySettings(content)
            SettingsCategory.AUDIO -> audioSettings(content)
            SettingsCategory.NAVIGATION -> navigationSettings(content)
            SettingsCategory.VEHICLE -> vehicleSettings(content)
            SettingsCategory.DIAGNOSTICS -> diagnosticsSettings(content)
            SettingsCategory.ADVANCED -> advancedSettings(content)
            SettingsCategory.LANGUAGE -> {
                settingsPageTitle(content, getString(R.string.language_section_title), getString(R.string.settings_language_summary))
                renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.LANGUAGE))
            }
            SettingsCategory.ABOUT -> about(content)
        }
    }

    private fun settingsOverview(content: LinearLayout) {
        settingsPageTitle(content, getString(R.string.settings_overview), getString(R.string.settings_overview_subtitle))
        val readiness = card().apply {
            setPadding(dp(20), dp(if (isExpandedSettingsLayout) 16 else 12), dp(20), dp(if (isExpandedSettingsLayout) 16 else 12))
        }
        readinessCard = readiness
        renderedReadiness = null
        refreshReadiness()
        content.addView(readiness, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
        // main B4：Overview 页的连接向导入口（作者：TRAE AI）。
        content.addView(button(getString(R.string.setup_guide), false) { openSetupGuide(fromSettings = true) },
            LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(6) })
        content.addView(label(getString(R.string.setup_guide_description), 14, MUTED).apply {
            setPadding(0, 0, 0, dp(20))
        })

        val destinations = listOf(
            SettingsCategory.CONNECTION to R.string.settings_connection_summary,
            SettingsCategory.DISPLAY to R.string.settings_display_summary,
            SettingsCategory.AUDIO to R.string.settings_audio_summary,
            SettingsCategory.NAVIGATION to R.string.settings_navigation_summary,
            SettingsCategory.VEHICLE to R.string.settings_vehicle_summary,
            SettingsCategory.LANGUAGE to R.string.settings_language_summary,
            SettingsCategory.ABOUT to R.string.about_diplay,
            SettingsCategory.DIAGNOSTICS to R.string.settings_diagnostics_summary,
            SettingsCategory.ADVANCED to R.string.settings_advanced_subtitle,
        )
        val categoryGap = dp(SETTINGS_BLOCK_GAP_DP)
        val twoColumns = isExpandedSettingsLayout && resources.configuration.let {
            SettingsLayoutPolicy.overviewHasTwoColumns(it.screenWidthDp, it.fontScale)
        }
        if (twoColumns) {
            val columns = row().apply { gravity = Gravity.TOP }
            val setup = column()
            setup.addView(settingsSectionHeading(R.string.settings_your_setup))
            destinations.forEach { item ->
                setup.addView(settingsSummaryCard(item.first, getString(item.second)),
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = categoryGap })
            }
            columns.addView(setup, LinearLayout.LayoutParams(0, -2, 1f))
            columns.addView(space(14), LinearLayout.LayoutParams(dp(14), 1))
            val quick = column()
            quick.addView(settingsSectionHeading(R.string.settings_quick_settings))
            quick.addView(quickSettingsCard())
            columns.addView(quick, LinearLayout.LayoutParams(0, -2, 1f))
            content.addView(columns, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
        } else {
            content.addView(settingsSectionHeading(R.string.settings_your_setup))
            destinations.forEach { item ->
                content.addView(settingsSummaryCard(item.first, getString(item.second)),
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = categoryGap })
            }
            content.addView(settingsSectionHeading(R.string.settings_quick_settings).apply { setPadding(0, dp(10), 0, dp(8)) })
            content.addView(quickSettingsCard(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
        }

        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.OVERVIEW))
    }

    private fun currentReadiness() = SettingsReadiness.of(
        setupError = setupError != null,
        active = CarPlayBackgroundSession.active,
        running = CarPlayBackgroundSession.hasSession(),
        wireless = AirPlayPersistence.loadWirelessEnabled(this),
        phoneChosen = DiPlayPreferences.phoneAddress(this) != null,
        hotspotSetupNeeded = AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            carHotspotDetailsMissing(),
    )

    private fun refreshReadiness() {
        val card = readinessCard ?: return
        val state = currentReadiness()
        if (state == renderedReadiness) return
        renderedReadiness = state
        card.removeAllViews()
        val title = getString(when (state) {
            SettingsReadiness.SETUP_ERROR, SettingsReadiness.HOTSPOT_SETUP -> R.string.setup_needs_attention
            SettingsReadiness.CONNECTED -> R.string.carplay_connected
            SettingsReadiness.CONNECTING -> R.string.connecting_to_your_iphone
            SettingsReadiness.CHOOSE_IPHONE -> R.string.settings_choose_iphone_title
            SettingsReadiness.READY_WIRELESS, SettingsReadiness.READY_USB -> R.string.ready
        })
        val detail = when (state) {
            SettingsReadiness.SETUP_ERROR -> setupError.orEmpty()
            SettingsReadiness.HOTSPOT_SETUP -> getString(R.string.save_the_name_and_password_from_the_car_s_hotspot_settings)
            SettingsReadiness.CONNECTED -> getString(R.string.settings_ready_connected)
            SettingsReadiness.CONNECTING -> getString(R.string.settings_connecting_description)
            SettingsReadiness.CHOOSE_IPHONE -> getString(R.string.settings_choose_iphone_description)
            SettingsReadiness.READY_WIRELESS -> getString(R.string.settings_ready_wireless, DiPlayPreferences.phoneName(this))
            SettingsReadiness.READY_USB -> getString(R.string.settings_ready_usb)
        }
        val tint = when {
            state.needsAction -> WARNING
            state == SettingsReadiness.CONNECTED -> READY
            else -> TEXT
        }
        card.addView(label(title, 22, tint, true))
        card.addView(label(detail, 15, MUTED).apply { setPadding(0, dp(6), 0, 0) })
        when (state) {
            SettingsReadiness.CHOOSE_IPHONE -> card.addView(button(getString(R.string.settings_choose_iphone_action), true) {
                choosePhone()
            }, matchButton(12, 56))
            SettingsReadiness.SETUP_ERROR, SettingsReadiness.HOTSPOT_SETUP -> card.addView(button(getString(R.string.open_connection_setup), true) {
                openConnectionSetupFromSettings()
            }, matchButton(12, 56))
            else -> Unit
        }
    }

    private fun quickSettingsCard(): LinearLayout = card().also(::buildQuickSettings).also(::normalizeSpacing)

    private fun buildQuickSettings(card: LinearLayout): Unit = card.run {
        toggle(this, getString(R.string.connect_when_diplay_opens),
            getString(R.string.default_connection_description),
            DiPlayPreferences.autoConnect(this@DiPlayActivity)) { DiPlayPreferences.saveAutoConnect(this@DiPlayActivity, it) }
        appearanceControl(this)
        toggle(this, getString(R.string.full_screen), getString(R.string.settings_full_screen_description),
            AirPlayPersistence.loadHideTopBar(this@DiPlayActivity) && AirPlayPersistence.loadHideBottomBar(this@DiPlayActivity)) { enabled ->
            AirPlayPersistence.saveHideTopBar(this@DiPlayActivity, enabled)
            AirPlayPersistence.saveHideBottomBar(this@DiPlayActivity, enabled)
        }
        toggle(this, getString(R.string.report_location_to_iphone),
            "${getString(R.string.location_reporting_reconnects)} ${getString(R.string.sends_precise_android_location_as_carplay_gps_data_when_th)}",
            AirPlayPersistence.loadLocationReportingEnabled(this@DiPlayActivity), save = ::onLocationReportingChanged)
        Unit
    }

    private fun settingsSectionHeading(title: Int) = label(getString(title), 22, TEXT, true).apply {
        setPadding(0, 0, 0, dp(10))
    }

    // 分类页统一入口：ABOUT 归入设置层级，与其他分类一致（main #597，作者：TRAE AI）。
    private fun openSettingsCategory(category: SettingsCategory) {
        // 波次十 #645：跳转前收起键盘并清理搜索状态（作者：TRAE AI）。
        setSearchKeyboard(settingsSearchBox, false)
        clearSettingsSearchState()
        settingsCategory = category
        render()
    }

    private fun settingsSummaryCard(category: SettingsCategory, summary: String): LinearLayout = card().apply {
        setPadding(dp(16), dp(10), dp(16), dp(10))
        isClickable = true
        isFocusable = true
        UiCompat.setForeground(this, android.graphics.drawable.LayerDrawable(arrayOf(
            UiCompat.ripple(RIPPLE, null, null), focusRing(12))))
        contentDescription = getString(R.string.settings_open_category, settingsCategoryTitle(category))
        addView(label(settingsCategoryTitle(category), 17, TEXT, true))
        addView(label(summary, 13, MUTED).apply { setPadding(0, dp(3), 0, 0) })
        setOnClickListener { openSettingsCategory(category) }
    }

    private fun settingsPageTitle(content: LinearLayout, title: String, subtitle: String) {
        content.addView(label(title, if (isExpandedSettingsLayout) 34 else 26, TEXT, true))
        content.addView(label(subtitle, 16, MUTED).apply { setPadding(0, dp(6), 0, dp(20)) })
    }

    private fun connectionSettings(content: LinearLayout) {
        settingsPageTitle(content, getString(R.string.connection), getString(R.string.settings_connection_summary))
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.CONNECTION))
    }

    private fun displaySettings(content: LinearLayout) {
        settingsPageTitle(content, getString(R.string.settings_display), getString(R.string.settings_display_summary))
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.DISPLAY))
    }

    private fun nightModeLabels() = CarPlayNightMode.entries.map { mode ->
        getString(when (mode) {
            CarPlayNightMode.SYSTEM -> R.string.carplay_night_system
            CarPlayNightMode.AMBIENT -> R.string.carplay_night_ambient
            CarPlayNightMode.DAY -> R.string.carplay_night_day
            CarPlayNightMode.NIGHT -> R.string.carplay_night_night
            CarPlayNightMode.SCHEDULE -> R.string.carplay_night_schedule
        })
    }

    private fun appearanceControl(parent: LinearLayout) {
        val modes = CarPlayNightMode.entries
        choice(parent, getString(R.string.carplay_night_mode), nightModeLabels(),
            modes.indexOf(AirPlayPersistence.loadCarPlayNightMode(this)), reconnects = false) { index ->
            AirPlayPersistence.saveCarPlayNightMode(this, modes[index])
        }
    }

    private fun audioSettings(content: LinearLayout) {
        settingsPageTitle(content, getString(R.string.audio), getString(R.string.settings_audio_summary))
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.AUDIO))
    }

    private fun navigationSettings(content: LinearLayout) {
        settingsPageTitle(content, getString(R.string.settings_navigation), getString(R.string.settings_navigation_summary))
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.NAVIGATION))
        // The BYD card is hidden without its receiver; say so instead of leaving a gap.
        if (!BydOutputSettings.available(this)) {
            content.addView(label(getString(R.string.settings_byd_navigation_unavailable), 15, MUTED).apply {
                setPadding(dp(4), 0, dp(4), dp(SETTINGS_BLOCK_GAP_DP))
            })
        }
    }

    private fun vehicleSettings(content: LinearLayout) {
        settingsPageTitle(content, getString(R.string.settings_vehicle), getString(R.string.settings_vehicle_summary))
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.VEHICLE))
        section(content, getString(R.string.settings_advanced), R.drawable.ic_dp_advanced) { card ->
            card.addView(label(getString(R.string.settings_vehicle_description), 16, MUTED))
            card.addView(button(getString(R.string.settings_open_advanced), false) {
                settingsCategory = SettingsCategory.ADVANCED
                render()
            }, matchButton(14, 56))
        }
    }

    private fun diagnosticsSettings(content: LinearLayout) {
        settingsPageTitle(content, getString(R.string.diagnostics), getString(R.string.settings_diagnostics_summary))
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.DIAGNOSTICS))
    }

    internal data class SettingsSearchResult(val title: String, val category: SettingsCategory)

    // Renders every category into a detached view and records what the builders label.
    // render() afterwards replaces every widget reference those detached builds assigned.
    // ponytail: indexes the default state only; controls behind an expander (vehicle data) are not found.
    private fun buildSettingsSearchIndex(): List<SettingsSearchResult> {
        val selected = settingsCategory
        // Category names first, so a card that only links to a category cannot claim its name.
        val index = SettingsCategory.entries.associateByTo(linkedMapOf(), ::settingsCategoryTitle)
        try {
            for (category in SettingsCategory.entries.filter { it != SettingsCategory.OVERVIEW } + SettingsCategory.OVERVIEW) {
                settingsCategory = category
                val titles = mutableListOf(settingsCategoryTitle(category))
                searchIndexSink = titles
                settingsCategoryContent(column())
                titles.forEach { index.putIfAbsent(it, category) }
            }
        } finally {
            searchIndexSink = null
            settingsCategory = selected
        }
        render()
        return index.map { SettingsSearchResult(it.key, it.value) }
    }

    private fun searchSettings(index: List<SettingsSearchResult>, query: String): List<SettingsSearchResult> {
        val words = query.trim().lowercase(Locale.getDefault()).split(Regex("\\s+")).filter(String::isNotEmpty)
        if (words.isEmpty()) return emptyList()
        return index.filter { result ->
            val haystack = "${result.title} ${settingsCategoryTitle(result.category)}".lowercase(Locale.getDefault())
            words.all(haystack::contains)
        }
    }

    private fun openSearchResult(result: SettingsSearchResult) {
        // main B3：命中后先收起键盘与搜索状态，再进入目标分类（作者：TRAE AI）。
        setSearchKeyboard(settingsSearchBox, false)
        clearSettingsSearchState()
        openSettingsCategory(result.category)
        val scroll = rootScroll ?: return
        val match = descendants(scroll).filterIsInstance<TextView>().firstOrNull {
            it.text.toString() == result.title || it.text.startsWith(result.title + VALUE_SEPARATOR)
        } ?: return
        val target = generateSequence<View>(match) { it.parent as? View }.firstOrNull { it.isClickable } ?: match
        scroll.doOnLayout {
            var top = 0
            var view: View? = target
            while (view != null && view !== scroll) { top += view.top; view = view.parent as? View }
            scroll.smoothScrollTo(0, (top - dp(24)).coerceAtLeast(0))
            target.requestFocus()
            target.isPressed = true
            handler.postDelayed({ target.isPressed = false }, SEARCH_HIGHLIGHT_MILLIS)
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }

    /**
     * 波次十 #645：设置页头部搜索框；输入时在下方下拉结果（作者：TRAE AI）。
     * 索引在展开输入框之前构建，因此输入过程保持同一个编辑器与键盘输入连接。
     */
    private fun settingsSearchField(compact: Boolean): EditText = SettingsSearchEditText(this).apply {
        settingsSearchBox = this
        setSingleLine()
        hint = getString(R.string.settings_search_hint)
        contentDescription = getString(R.string.settings_search)
        textSize = if (compact) 16f else 18f
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        // 编辑框横向滚动长文本时画布随之滚动，但背景不滚动：图标与焦点环画在背景层，
        // 因此它们位置稳定，也无需为文本垂直居中做补偿（作者：TRAE AI）。
        val iconSize = headerIconSize(compact)
        // API17 适配：Context.getDrawable 是 API 21+，低版本走 ContextCompat；setTint 是 API 21+，走 UiCompat（作者：TRAE AI）。
        val icon = androidx.core.content.ContextCompat.getDrawable(this@DiPlayActivity, R.drawable.ic_dp_search)
            ?.mutate()?.let { UiCompat.tint(it, MUTED) }
        // API17 适配：LayerDrawable.setLayerGravity/setLayerSize/setLayerInsetRelative 是 API 23+，
        // 改为 LayerDrawable 子类在 onBoundsChange 里手动摆放图标层（作者：TRAE AI）。
        background = SearchIconLayerDrawable(
            listOfNotNull(rounded(BUTTON, BORDER), icon, focusRing()).toTypedArray(),
            iconSize, dp(16))
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        includeFontPadding = false
        setPaddingRelative(dp(16) + if (icon != null) iconSize + dp(10) else 0, 0, dp(16), 0)
        inputType = android.text.InputType.TYPE_CLASS_TEXT
        // 横屏车机不然会用全屏编辑器盖住结果列表。
        imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        // 触摸焦点等待点击：打开设置页时不主动聚焦搜索框、不弹键盘。
        isFocusableInTouchMode = settingsSearchRestoreFocus
        setText(settingsSearchQuery)
        setSelection(text.length)
        setOnClickListener {
            if (!isFocusableInTouchMode) {
                isFocusableInTouchMode = true
                requestFocus()
                setSearchKeyboard(this, true)
            }
            showSettingsSearchResults()
        }
        setOnFocusChangeListener { _, focused -> if (focused) showSettingsSearchResults() }
        setOnEditorActionListener { _, actionId, event ->
            val search = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER
            if (!search) return@setOnEditorActionListener false
            if (event == null || event.action == KeyEvent.ACTION_DOWN) settingsSearchResults().firstOrNull()?.let(::openSearchResult)
            true
        }
        addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = onSettingsSearchChanged(this@apply)
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        // 键盘改变的是窗口尺寸而不是输入框，因此跟随窗口布局。监听器若直接挂在窗口观察者上
        // 会在每次重建后残留；只在输入框附着期间注册，随视图脱离自动移除（作者：TRAE AI）。
        val followWindow = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            if (settingsSearchBox === this && settingsSearchPopup?.isShowing == true) {
                positionSettingsSearchResults(this, settingsSearchPopup!!)
            }
        }
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = view.viewTreeObserver.addOnGlobalLayoutListener(followWindow)
            override fun onViewDetachedFromWindow(view: View) = view.viewTreeObserver.removeOnGlobalLayoutListener(followWindow)
        })
        if (settingsSearchRestoreFocus) {
            settingsSearchRestoreFocus = false
            post {
                if (settingsSearchBox !== this) return@post
                requestFocus()
                setSearchKeyboard(this, true)
                showSettingsSearchResults()
            }
        }
    }

    /**
     * 波次十 #645：搜索框背景的三层绘制（作者：TRAE AI）。
     * API17 适配：底板、左侧图标与焦点环的摆放不使用 API 23 的 setLayerGravity/setLayerSize/
     * setLayerInsetRelative，改为覆写 onBoundsChange 在 bounds 变化时手动摆放图标层——
     * 左缩进与文本内边距一致，垂直居中，所有层在 API 17 上行为一致。
     */
    private class SearchIconLayerDrawable(
        layers: Array<android.graphics.drawable.Drawable>,
        private val iconSizePx: Int,
        private val iconInsetPx: Int,
    ) : android.graphics.drawable.LayerDrawable(layers) {
        override fun onBoundsChange(bounds: android.graphics.Rect) {
            super.onBoundsChange(bounds)
            if (numberOfLayers < 2) return
            val top = bounds.top + (bounds.height() - iconSizePx).coerceAtLeast(0) / 2
            getDrawable(1).setBounds(
                bounds.left + iconInsetPx, top,
                bounds.left + iconInsetPx + iconSizePx, top + iconSizePx)
        }
    }

    /**
     * 波次十 #645：搜索输入框——文本与提示按内容视觉中心垂直对齐（作者：TRAE AI）。
     * 中文按字形包围盒对齐，其余语言按字体度量对齐（带下伸部或重音时基线仍稳定）。
     */
    private class SettingsSearchEditText(context: android.content.Context) : EditText(context) {
        private val glyphBounds = android.graphics.Rect()
        private var measuredHint: String? = null
        private var measuredHintHeight = -1
        private var measuredHintTextSize = -1f
        private var measuredHintBaseline = 0

        override fun onDraw(canvas: android.graphics.Canvas) {
            val value: CharSequence = if (text.isNullOrEmpty()) hint ?: "" else text
            val drawingBaseline = if (text.isNullOrEmpty() && value.isNotEmpty()) hintBaseline(value) else baseline
            val offset = if (baseline >= 0) {
                val contentCentre = paddingTop + (height - paddingTop - paddingBottom) / 2f
                contentCentre - alignedTextCentreY(this, value, glyphBounds, drawingBaseline)
            } else 0f
            // 文本、选区与光标一起移动。
            val checkpoint = canvas.save()
            canvas.translate(0f, offset)
            super.onDraw(canvas)
            canvas.restoreToCount(checkpoint)
        }

        /**
         * TextView.baseline 描述的是可编辑布局的基线——绘制提示时也在用它，而回退字体里的
         * 提示位置可能不同。每次提示/高度/字号变化只测量一次，而不是光标每帧闪烁都测。
         */
        private fun hintBaseline(value: CharSequence): Int {
            if (value.toString() != measuredHint || height != measuredHintHeight || paint.textSize != measuredHintTextSize) {
                // 编辑框横向滚动不换行：把提示按单行排版。
                val hintWidth = maxOf(
                    (width - compoundPaddingLeft - compoundPaddingRight).coerceAtLeast(1),
                    kotlin.math.ceil(android.text.Layout.getDesiredWidth(value, paint).toDouble()).toInt(),
                )
                // API17 适配：StaticLayout.Builder 是 API 23+，改用 API 1 的废弃构造器（作者：TRAE AI）。
                @Suppress("DEPRECATION")
                val hintLayout = android.text.StaticLayout(
                    value, 0, value.length, paint, hintWidth,
                    android.text.Layout.Alignment.ALIGN_NORMAL, 1f, 0f, includeFontPadding)
                measuredHintBaseline = extendedPaddingTop +
                    ((height - extendedPaddingTop - extendedPaddingBottom - hintLayout.height).coerceAtLeast(0) / 2) +
                    hintLayout.getLineBaseline(0)
                measuredHint = value.toString()
                measuredHintHeight = height
                measuredHintTextSize = paint.textSize
            }
            return measuredHintBaseline
        }
    }

    // 波次十 #645：页头图标统一尺寸（作者：TRAE AI）。
    private fun headerIconSize(compact: Boolean) = dp(if (compact) 20 else 24)

    private fun onSettingsSearchChanged(box: EditText) {
        if (box !== settingsSearchBox) return
        settingsSearchQuery = box.text.toString()
        if (settingsSearchQuery.isEmpty()) {
            // 波次十 #645：清空查询时保留本次搜索的索引；关闭搜索才会使其失效（作者：TRAE AI）。
            settingsSearchPopup?.dismiss()
            return
        }
        if (settingsSearchIndex != null) {
            showSettingsSearchResults()
            return
        }
        if (settingsSearchIndexing) return
        settingsSearchIndexing = true
        // 不能在文本监听器里直接建索引：建索引会替换当前输入框。
        handler.post {
            settingsSearchIndexing = false
            if (page != "settings" || settingsSearchQuery.isEmpty() || settingsSearchIndex != null) return@post
            settingsSearchRestoreFocus = true
            settingsSearchIndex = buildSettingsSearchIndex()
        }
    }

    private fun settingsSearchResults(): List<SettingsSearchResult> =
        settingsSearchIndex?.let { searchSettings(it, settingsSearchQuery) }.orEmpty()

    private fun showSettingsSearchResults() {
        val box = settingsSearchBox ?: return
        if (settingsSearchIndex == null || settingsSearchQuery.isBlank() || !UiCompat.isAttachedToWindow(box)) {
            settingsSearchPopup?.dismiss()
            return
        }
        val results = settingsSearchResults()
        val list = column().apply { setPadding(dp(8), dp(8), dp(8), dp(8)) }
        if (results.isEmpty()) {
            list.addView(label(getString(R.string.settings_search_empty), 15, MUTED).apply {
                setPadding(dp(12), dp(12), dp(12), dp(12))
            })
        }
        results.forEach { list.addView(settingsSearchRow(it)) }
        val existing = settingsSearchPopup?.takeIf { it.isShowing }
        val scroll = (existing?.contentView as? ScrollView) ?: ScrollView(this)
        scroll.removeAllViews()
        scroll.addView(list)
        if (existing != null) {
            positionSettingsSearchResults(box, existing)
            return
        }
        settingsSearchPopup = PopupWindow(scroll, box.width, 0, false).apply {
            setBackgroundDrawable(rounded(SURFACE, BORDER))
            // API17 适配：PopupWindow.setElevation 是 API 21+，走 UiCompat（作者：TRAE AI）。
            UiCompat.setPopupElevation(this, dp(8).toFloat())
            // 点击其他位置关闭列表但保留查询；点回搜索框重新打开。
            isOutsideTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
            // The list is placed in screen coordinates. Android 10 made this call public; before it the
            // same switch is setLayoutInScreenEnabled, and calling the new name stops the app.
            if (Build.VERSION.SDK_INT >= 29) setIsLaidOutInScreen(true)
            else runCatching {
                PopupWindow::class.java.getMethod("setLayoutInScreenEnabled", Boolean::class.javaPrimitiveType)
                    .invoke(this, true)
            }
            positionSettingsSearchResults(box, this)
        }
    }

    /**
     * 波次十 #645：把结果弹层定位在搜索框正下方（作者：TRAE AI）。
     * 宽度不超出可视区，左右钳制在屏幕内，高度不超过键盘顶部；绝不把列表翻转到锚点上方。
     */
    private fun positionSettingsSearchResults(box: EditText, popup: PopupWindow) {
        val visible = android.graphics.Rect()
        box.getWindowVisibleDisplayFrame(visible)
        val location = IntArray(2)
        box.getLocationOnScreen(location)
        val top = location[1] + box.height + dp(SETTINGS_SEARCH_RESULTS_GAP_DP)
        val width = minOf(box.width, visible.width())
        val maxHeight = minOf(
            visible.bottom - top,
            dp(if (resources.configuration.screenHeightDp < 600)
                SETTINGS_SEARCH_RESULTS_SHORT_HEIGHT_DP else SETTINGS_SEARCH_RESULTS_HEIGHT_DP),
        )
        if (width <= 0 || maxHeight <= 0) {
            popup.dismiss()
            return
        }
        val left = location[0].coerceIn(visible.left, visible.right - width)
        val content = popup.contentView
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
        val height = content.measuredHeight.coerceIn(1, maxHeight)
        if (popup.isShowing) {
            popup.update(left, top, width, height)
        } else {
            popup.width = width
            popup.height = height
            popup.showAtLocation(box.rootView, Gravity.TOP or Gravity.LEFT, left, top)
        }
    }

    private fun settingsSearchRow(result: SettingsSearchResult): View = column().apply {
        val category = settingsCategoryTitle(result.category)
        isClickable = true
        isFocusable = true
        contentDescription = "${result.title}, $category"
        // API17 适配：RippleDrawable 是 API 21+、foreground 是 API 23+，走 UiCompat（作者：TRAE AI）。
        UiCompat.setForeground(this, android.graphics.drawable.LayerDrawable(arrayOf(
            UiCompat.ripple(RIPPLE, null, null), focusRing(12))))
        minimumHeight = dp(56)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        addView(label(result.title, 17, TEXT, true))
        addView(label(category, 13, MUTED))
        setOnClickListener { openSearchResult(result) }
    }

    private fun closeSettingsSearch() {
        val box = settingsSearchBox
        setSearchKeyboard(box, false)
        clearSettingsSearchState()
        box?.clearFocus()
        // 波次十 #645：整体重渲染回收起态，焦点回到搜索按钮（作者：TRAE AI）。
        render()
        descendants(window.decorView).firstOrNull { it.tag == SETTINGS_SEARCH_BUTTON_TAG }?.requestFocus()
    }

    private fun clearSettingsSearchState() {
        settingsSearchQuery = ""
        // 波次十 #645：两态复位（作者：TRAE AI）。
        settingsSearchExpanded = false
        settingsSearchIndex = null
        settingsSearchRestoreFocus = false
        settingsSearchPopup?.dismiss()
    }

    private fun setSearchKeyboard(view: View?, show: Boolean) {
        view ?: return
        val keyboard = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        if (show) keyboard.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        else keyboard.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun advancedSettings(content: LinearLayout) {
        settingsPageTitle(content, getString(R.string.settings_advanced), getString(R.string.settings_advanced_subtitle))
        val caution = card().apply {
            addView(label(getString(R.string.settings_advanced_caution_title), 18, WARNING, true))
            addView(label(getString(R.string.settings_advanced_caution_description), 14, MUTED).apply {
                setPadding(0, dp(6), 0, 0)
            })
        }
        content.addView(caution, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.ADVANCED))
        advancedVehicleDataSettings(content)
    }

    private fun advancedVehicleDataSettings(content: LinearLayout) {
        section(content, getString(R.string.settings_vehicle), R.drawable.ic_dp_dashboard) { card ->
            card.addView(button(getString(if (bydVehicleAdvancedExpanded)
                R.string.hide_advanced_vehicle_data else R.string.advanced_vehicle_data), false) {
                bydVehicleAdvancedExpanded = !bydVehicleAdvancedExpanded
                render()
            }, matchButton(0, 56))
            if (bydVehicleAdvancedExpanded) {
                advancedVehicleData(card)
                if (!BydOutputSettings.available(this)) clusterSongSwitch(card)
            }
        }
    }

    private fun renderSections(
        content: LinearLayout,
        sections: Set<SettingsSection>,
    ) {
        val previous = settingsSectionFilter
        settingsSectionFilter = sections
        try {
            allSettingsSections(content)
        } finally {
            settingsSectionFilter = previous
        }
    }

    private fun allSettingsSections(content: LinearLayout) {
        filteredSection(content, SettingsSection.CARPLAY_CONTROLS,
            getString(R.string.carplay_controls), R.drawable.ic_dp_controls) { card ->
            // main #487：新增 Off（0）选项，禁用下滑打开设置的手势（作者：TRAE AI）。
            val gestureFingers = listOf(0, 2, 3, 4)
            choice(card, getString(R.string.settings_gesture_fingers_label),
                gestureFingers.map { if (it == 0) getString(R.string.settings_gesture_disabled) else getString(R.string.settings_gesture_fingers_option, it) },
                gestureFingers.indexOf(AirPlayPersistence.loadSettingsGestureFingers(this)).coerceAtLeast(0),
                reconnects = false) {
                AirPlayPersistence.saveSettingsGestureFingers(this, gestureFingers[it])
            }
            card.addView(label(getString(R.string.settings_gesture_fingers_hint), 14, MUTED).apply {
                setPadding(0, dp(10), 0, 0)
            })
            toggle(card, getString(R.string.right_hand_drive), getString(R.string.place_carplay_s_controls_closer_to_the_driver), AirPlayPersistence.loadRightHandDrive(this)) { AirPlayPersistence.saveRightHandDrive(this, it); markReconnectNeeded() }
        }
        filteredSection(content, SettingsSection.WHEEL_KEYS,
            getString(R.string.settings_wheel_keys), R.drawable.ic_dp_controls, ::wheelKeysSettings)
        filteredSection(content, SettingsSection.CONNECTION_SETUP,
            getString(R.string.connection_setup), R.drawable.ic_dp_connection) { card ->
            card.addView(label(getString(R.string.choose_how_to_connect_follow_the_setup_steps_and_save_your), 16, MUTED))
            card.addView(button(getString(R.string.open_connection_setup), false, ::openConnectionSetupFromSettings), matchButton(12, 60))
            card.addView(space(12))
            // Also here, not only in the CarPlay menu: with the CH341 removed CarPlay cannot connect,
            // so that menu could not be reached to switch back (#696).
            val mfiTargets = listOf(MfiTarget.LOCAL, MfiTarget.USB_CH341)
            val storedMfiTarget = AirPlayPersistence.loadMfiTarget(this)
            if (storedMfiTarget in mfiTargets) {
                choice(card, getString(R.string.mfi_certificate_signing_target), listOf(
                    getString(R.string.local_offline),
                    getString(R.string.usb_ch341),
                ), mfiTargets.indexOf(storedMfiTarget)) { index ->
                    val target = mfiTargets[index]
                    AirPlayPersistence.saveMfiTarget(this, target)
                    setupError = runCatching { DiPlayBootstrap.ensure(this, target) }.exceptionOrNull()?.let {
                        android.util.Log.e("DiPlaySetup", "CarPlay authentication could not be loaded", it)
                        getString(R.string.setup_error_auth)
                    }
                }
                card.addView(label(getString(R.string.settings_mfi_target_description), 14, MUTED))
            }
        }
        filteredSection(content, SettingsSection.DIAGNOSTICS,
            getString(R.string.diagnostics), R.drawable.ic_dp_diagnostics) { card ->
            exportButton = button(if (exportInProgress) getString(R.string.saving_report) else getString(R.string.save_diagnostic_report), false) {
                // main #541：所有版本默认直接保存到 Downloads/DiPlay（作者：TRAE AI）。
                saveReportToDownloads()
            }.apply { isEnabled = !exportInProgress }
            card.addView(exportButton, matchButton(10, 60))
            card.addView(button(getString(R.string.choose_save_location), false) { chooseReportDestination() }, matchButton(10, 60))
            card.addView(button(getString(R.string.settings_report_on_phone), false) { openBackup(SettingsCategory.DIAGNOSTICS) }, matchButton(10, 60))
            val destination = getString(R.string.reports_save_to_downloads_diplay)
            card.addView(label(destination + getString(R.string.nothing_is_sent_automatically_protocol_payloads_and_creden), 14, MUTED).apply { setPadding(0, dp(12), 0, 0) })
            // main #496：FPS 计数器，仅保存设置，回到投屏画面时生效，无需重连。
            toggle(card, getString(R.string.settings_fps_counter), getString(R.string.settings_fps_counter_description),
                AirPlayPersistence.loadFpsCounter(this)) { AirPlayPersistence.saveFpsCounter(this, it) }
        }
        filteredSection(content, SettingsSection.AUTOMATIC_CONNECTION,
            getString(R.string.automatic_connection), R.drawable.ic_dp_automation) { card ->
            toggle(card, getString(R.string.connect_when_diplay_opens), getString(R.string.default_connection_description), DiPlayPreferences.autoConnect(this)) { DiPlayPreferences.saveAutoConnect(this, it) }
            // main #439：已选 iPhone 蓝牙重连时自动唤醒并连接（API 17-30 无需运行时权限）。
            toggle(card, getString(R.string.connect_when_iphone_bluetooth_connects),
                getString(R.string.connect_when_iphone_bluetooth_connects_description),
                DiPlayPreferences.connectOnPhoneBluetooth(this)) { enabled ->
                DiPlayPreferences.saveConnectOnPhoneBluetooth(this, enabled)
                if (enabled && Build.VERSION.SDK_INT >= 31 &&
                    !PermissionCompat.hasPermission(this, Manifest.permission.BLUETOOTH_CONNECT)) {
                    bluetoothAutoConnectPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                } else if (enabled && DiPlayPreferences.phoneAddress(this) == null) choosePhone()
            }
            val connectionModes = DefaultConnectionMode.entries
            choice(card, getString(R.string.default_connection_mode), listOf(
                getString(R.string.default_connection_last_used),
                getString(R.string.default_connection_wireless),
                getString(R.string.default_connection_usb)
            ), connectionModes.indexOf(DiPlayPreferences.defaultConnectionMode(this)), reconnects = false) {
                DiPlayPreferences.saveDefaultConnectionMode(this, connectionModes[it])
            }
            adbToggle(card, R.string.open_after_the_car_starts,
                R.string.availability_depends_on_your_head_unit_s_startup_settings,
                read = { AirPlayPersistence.loadAutoStartOnBoot(this) },
                needsAdb = { CarHotspotSettings.enabled(this) &&
                    AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL },
                permissions = { listOf(CarHotspotSetup.Permission.BOOT_LAUNCH) }) {
                AirPlayPersistence.saveAutoStartOnBoot(this, it)
            }
            val autoConfirmActive = UsbPermissionSetup.Permission.ACCESSIBILITY.granted(this)
            toggle(
                card,
                getString(R.string.usb_auto_confirm_title),
                getString(R.string.usb_auto_confirm_subtitle),
                autoConfirmActive,
            ) { enabled ->
                if (enabled) promptEnableUsbAutoConfirm()
                else {
                    if (!UsbAutoConfirmService.openSettings(this)) toast(getString(R.string.wheel_keys_no_settings))
                    render()
                }
            }
            if (!autoConfirmActive) {
                card.addView(
                    button(
                        getString(R.string.btn_auto_apply_permissions),
                        true,
                    ) {
                        autoApplyPermissions()
                    },
                    matchButton(8, 54),
                )
            } else {
                card.addView(label(getString(R.string.usb_auto_confirm_active_hint), 14, READY).apply {
                    setPadding(0, dp(4), 0, dp(8))
                })
            }
            // 开机自启 ADB 修复入口（#441）：一次性豁免后台限制，让 BOOT_COMPLETED 广播可达。
            card.addView(button(getString(R.string.boot_start_repair), false) { repairBootStart() }, matchButton(6, 56))
            card.addView(label(getString(R.string.boot_start_repair_desc), 14, MUTED).apply { setPadding(0, dp(8), 0, dp(6)) })
            card.addView(button(chooseIphoneTitle(), false) { choosePhone() }, matchButton(12, 60))
        }
        // main #583：USB 连接卡——有线充电电流报价档位（下次 USB 连接生效）。
        filteredSection(content, SettingsSection.USB_CONNECTION,
            getString(R.string.settings_usb_connection), R.drawable.ic_dp_connection) { card ->
            val currents = UsbChargingCurrent.entries
            choice(card, getString(R.string.settings_usb_charging), listOf(
                getString(R.string.settings_usb_charging_normal),
                getString(R.string.settings_usb_charging_reduced),
                getString(R.string.settings_usb_charging_low),
            ), currents.indexOf(AirPlayPersistence.loadUsbChargingCurrent(this)), reconnects = false) {
                AirPlayPersistence.saveUsbChargingCurrent(this, currents[it])
                if (!AirPlayPersistence.loadWirelessEnabled(this)) markReconnectNeeded()
            }
            card.addView(label(getString(R.string.settings_usb_charging_description), 14, MUTED))
        }
        if (settingsSectionFilter?.contains(SettingsSection.BYD_ADB) != false) bydAdbSettings(content)
        filteredSection(content, SettingsSection.DISPLAY_AND_PERFORMANCE,
            getString(R.string.display_and_performance), R.drawable.ic_dp_display) { card ->
            // main B8：应用外观三态（深/浅/跟随 CarPlay 夜间模式）（作者：TRAE AI）。
            val appearances = AppAppearance.entries
            choice(
                card,
                getString(R.string.settings_app_appearance),
                listOf(
                    getString(R.string.settings_app_appearance_dark),
                    getString(R.string.settings_app_appearance_light),
                    getString(R.string.settings_app_appearance_auto),
                ),
                appearances.indexOf(AirPlayPersistence.loadAppAppearance(this)),
                reconnects = false,
            ) { index ->
                AirPlayPersistence.saveAppAppearance(this, appearances[index])
                rememberLaunchAppearance()
                handler.post { checkForAppearanceChange() }
            }
            card.addView(label(getString(R.string.settings_app_appearance_description), 14, MUTED))
            val nightModes = CarPlayNightMode.entries
            val nightMode = AirPlayPersistence.loadCarPlayNightMode(this)
            val ambientControls = column().apply {
                visibility = if (nightMode == CarPlayNightMode.AMBIENT) View.VISIBLE else View.GONE
            }
            val scheduleControls = column().apply {
                visibility = if (nightMode == CarPlayNightMode.SCHEDULE) View.VISIBLE else View.GONE
            }
            choice(
                card,
                getString(R.string.carplay_night_mode),
                nightModeLabels(),
                nightModes.indexOf(nightMode),
                reconnects = false,
            ) { index ->
                AirPlayPersistence.saveCarPlayNightMode(this, nightModes[index])
                ambientControls.visibility = if (nightModes[index] == CarPlayNightMode.AMBIENT) View.VISIBLE else View.GONE
                scheduleControls.visibility = if (nightModes[index] == CarPlayNightMode.SCHEDULE) View.VISIBLE else View.GONE
            }
            // main 9919585e：两句提示合并为一行；中文两句间不加空格（作者：TRAE AI）。
            // API17 适配：Configuration.locales 是 API 24+，低版本走 locale（作者：TRAE AI）。
            val locale = if (Build.VERSION.SDK_INT >= 24) resources.configuration.locales[0]
                else @Suppress("DEPRECATION") resources.configuration.locale
            val sentenceSeparator = if (locale.language == "zh") "" else " "
            card.addView(label(getString(R.string.carplay_night_hint) + sentenceSeparator +
                getString(R.string.carplay_night_time_note), 14, MUTED).apply {
                setPadding(0, 0, 0, dp(18))
            })
            ambientControls.addView(label(getString(R.string.carplay_night_ambient_hint), 14, MUTED).apply {
                setPadding(0, 0, 0, dp(18))
            })
            ambientLightThresholdControl(ambientControls)
            nightDelaySettingControl(ambientControls, R.string.ambient_delay_title, R.string.ambient_delay_hint,
                0..60, 2, R.string.ambient_delay_summary, { AirPlayPersistence.loadAmbientDelaySeconds(this) },
                save = { AirPlayPersistence.saveAmbientDelaySeconds(this, it) })
            card.addView(ambientControls)
            scheduleControls.addView(label(getString(R.string.carplay_night_schedule_hint), 14, MUTED).apply {
                setPadding(0, 0, 0, dp(18))
            })
            scheduleTimeControl(scheduleControls, start = true)
            scheduleTimeControl(scheduleControls, start = false)
            card.addView(scheduleControls)
            card.addView(button(getString(R.string.picture_adjustments), false) {
                OwnScreenStart.start(this, Intent(this, CarPlayHostActivity::class.java)
                    .putExtra("picture_controls", true).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }, matchButton(0, 56).apply { bottomMargin = dp(24) })
            carPlaySizeControl(card)
            resolutionSettingControl(
                card, R.string.resolution, R.string.custom_resolution_hint,
                CarPlayDisplayScale.MIN_PERCENT..CarPlayDisplayScale.MAX_PERCENT, 100,
                R.string.custom_resolution_summary,
                { AirPlayPersistence.loadDisplayScalePercent(this) }, reconnects = true,
                save = { AirPlayPersistence.saveDisplayScalePercent(this, it) },
            )
            choice(card, getString(R.string.frame_rate), listOf(getString(R.string.s_30_fps_lighter_load), getString(R.string.s_60_fps_smoother_motion)), if (AirPlayPersistence.loadFps(this) == 60) 1 else 0) { AirPlayPersistence.saveFps(this, if (it == 1) 60 else 30) }
            carPlayDockControl(card)
            addSystemBarControls(
                hideTopBar = AirPlayPersistence.loadHideTopBar(this),
                hideBottomBar = AirPlayPersistence.loadHideBottomBar(this),
                onHideTopBarChanged = { AirPlayPersistence.saveHideTopBar(this, it) },
                onHideBottomBarChanged = { AirPlayPersistence.saveHideBottomBar(this, it) },
            ) { label, checked, onChanged ->
                toggle(card, getString(label), getString(R.string.hide_the_car_s_system_bars_while_carplay_is_open), checked, save = onChanged)
            }
            toggle(card, getString(R.string.adapt_pip_resolution), getString(R.string.adapt_pip_resolution_description), AirPlayPersistence.loadAdaptPipResolution(this)) {
                AirPlayPersistence.saveAdaptPipResolution(this, it)
            }
            card.addView(button("${getString(R.string.settings_interface_size)} · ${InterfaceSize.displayName(this, InterfaceSize.preference(this))}", false) {
                InterfaceSize.showPicker(this)
            }, matchButton(12, 60))
            card.addView(label(getString(R.string.settings_interface_size_hint), 14, MUTED))
            // main B6：横屏强制完整布局开关（仅自然紧凑布局时展示，作者：TRAE AI）。
            if (!isNaturallyExpandedSettingsLayout) toggle(card, getString(R.string.settings_force_full_settings),
                getString(R.string.settings_force_full_settings_hint), SettingsLayoutPreferences.forceFull(this)) {
                SettingsLayoutPreferences.saveForceFull(this, it)
                render()
            }
        }
        filteredSection(content, SettingsSection.EXPERIMENTAL_DISPLAY,
            getString(R.string.settings_experimental_display), R.drawable.ic_dp_display) { card ->
            // main #613：Harman 固件老式全屏（实验性；写 OEM system-UI 位，需重连生效由用户重连触发）。
            toggle(card, getString(R.string.settings_harman_fullscreen_experimental),
                getString(R.string.settings_harman_fullscreen_experimental_description),
                AirPlayPersistence.loadHarmanFullscreen(this)) { enabled ->
                AirPlayPersistence.saveHarmanFullscreen(this, enabled)
            }
            toggle(card, getString(R.string.split_screen_areas), getString(R.string.split_screen_areas_description),
                SplitScreenSettings.enabled(this)) {
                SplitScreenSettings.setEnabled(this, it)
                markReconnectNeeded()
            }
            toggle(card, getString(R.string.carplay_rotation), getString(R.string.carplay_rotation_description),
                CarPlayRotation.enabled(this)) {
                CarPlayRotation.setEnabled(this, it)
                render()
                markReconnectNeeded()
            }
            toggle(card, getString(R.string.side_panel), getString(R.string.side_panel_description), SidePanelSettings.enabled(this)) {
                SidePanelSettings.setEnabled(this, it)
                markReconnectNeeded()
            }
            // 实验性①：USB 连接页实时日志覆盖层（默认关，打开后 CarPlayHostActivity 才显示诊断行）。无需重连。
            toggle(card, getString(R.string.settings_usb_realtime_log),
                getString(R.string.settings_usb_realtime_log_description),
                AirPlayPersistence.loadUsbRealtimeLog(this)) {
                AirPlayPersistence.saveUsbRealtimeLog(this, it)
            }
            // 实验性②：无线连接绕过蓝牙开关配置（默认关，打开后跳过"打开蓝牙"弹框）。无需重连。
            toggle(card, getString(R.string.settings_wireless_skip_bluetooth_config),
                getString(R.string.settings_wireless_skip_bluetooth_config_description),
                AirPlayPersistence.loadWirelessSkipBluetoothConfig(this)) {
                AirPlayPersistence.saveWirelessSkipBluetoothConfig(this, it)
            }
            // 实验性③（无蓝牙引导无线）：无线连接彻底绕开蓝牙引导（两道蓝牙门禁 + RFCOMM 引导段），
            // 仅依赖车机 Wi-Fi + Bonjour 让 iPhone 主动发起 CarPlay。无需重连；适用双蓝牙架构。
            toggle(card, getString(R.string.settings_wireless_no_bluetooth_bootstrap),
                getString(R.string.settings_wireless_no_bluetooth_bootstrap_description),
                AirPlayPersistence.loadWirelessNoBluetoothBootstrap(this)) {
                AirPlayPersistence.saveWirelessNoBluetoothBootstrap(this, it)
            }
            if (CarPlayRotation.enabled(this)) {
                val pictures = CarPlayRotation.Picture.entries
                choice(card, getString(R.string.carplay_rotation_picture), listOf(
                    getString(R.string.carplay_rotation_smoother),
                    getString(R.string.carplay_rotation_sharper),
                ), pictures.indexOf(CarPlayRotation.picture(this)), reconnects = false) {
                    CarPlayRotation.setPicture(this, pictures[it])
                    markReconnectNeeded()
                }
            }
        }
        // Opt-in controls that can cost sound or video on some head units.
        filteredSection(content, SettingsSection.ADVANCED_MEDIA,
            getString(R.string.settings_advanced_media), R.drawable.ic_dp_advanced) { card ->
            // main #539：前台 CarPlay 期间隐藏 BYD 来电弹窗，仅 Android 12(S) 提供（作者：TRAE AI）。
            if (Build.VERSION.SDK_INT == 32) {
                toggle(card, getString(R.string.settings_hide_byd_call_popup),
                    getString(R.string.settings_hide_byd_call_popup_description),
                    AirPlayPersistence.loadHideBydCallPopup(this)) {
                    AirPlayPersistence.saveHideBydCallPopup(this, it)
                }
            }
            toggle(card, getString(R.string.efficient_video), getString(R.string.use_hevc_leave_off_for_the_widest_head_unit_compatibility), AirPlayPersistence.loadHevcEnabled(this)) {
                AirPlayPersistence.saveHevcEnabled(this, it)
                markReconnectNeeded()
                render()
            }
            // main #608：检测到所选视频格式的 MTK 解码器时才显示该卡(实验性)。
            val hevc = AirPlayPersistence.loadHevcEnabled(this)
            val selectedMime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val mtkDecoder = if (hevc && AirPlayPersistence.loadHevcSoftwareDecoderEnabled(this)) null
                else selectedMtkDecoderName(selectedMime)
            if (mtkDecoder != null) {
                toggle(card, getString(R.string.settings_mtk_decoder_tuning),
                    getString(R.string.settings_mtk_decoder_tuning_description, mtkDecoder),
                    AirPlayPersistence.loadMtkDecoderTuning(this)) {
                    AirPlayPersistence.saveMtkDecoderTuning(this, it)
                    markReconnectNeeded()
                }
            }
            toggle(card, getString(R.string.smooth_video), getString(R.string.smooth_video_description),
                AirPlayPersistence.loadSmoothVideo(this)) {
                AirPlayPersistence.saveSmoothVideo(this, it)
                reconnectIfRunning()
            }
            // main #496：直接视频输出（实验性），立即重连并重建视频视图。
            toggle(card, getString(R.string.settings_direct_video_output),
                getString(R.string.settings_direct_video_output_description),
                AirPlayPersistence.loadDirectVideoOutput(this)) {
                AirPlayPersistence.saveDirectVideoOutput(this, it)
                reconnectIfRunning()
            }
            // main #496：低延迟解码（实验性），下次连接时生效。
            toggle(card, getString(R.string.settings_low_latency_decoder),
                getString(R.string.settings_low_latency_decoder_description),
                AirPlayPersistence.loadLowLatencyDecoder(this)) {
                AirPlayPersistence.saveLowLatencyDecoder(this, it)
                markReconnectNeeded()
            }
            toggle(card, getString(R.string.call_echo_cancellation), getString(R.string.call_echo_cancellation_description),
                AirPlayPersistence.loadCallEchoCancellation(this)) {
                AirPlayPersistence.saveCallEchoCancellation(this, it)
                markReconnectNeeded()
            }
            toggle(card, getString(R.string.call_voice_filter), getString(R.string.call_voice_filter_description),
                AirPlayPersistence.loadCallVoiceFilter(this)) {
                AirPlayPersistence.saveCallVoiceFilter(this, it)
                markReconnectNeeded()
            }
            audioFocusControls(card)
            if (resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)) {
                toggle(card, getString(R.string.advanced_audio_channel_mapping),
                    getString(R.string.use_usage_content_type_routing_instead_of_stream_type),
                    AirPlayPersistence.loadAdvancedAudioChannelMapping(this)) {
                    AirPlayPersistence.saveAdvancedAudioChannelMapping(this, it)
                    markReconnectNeeded()
                }
            }
            toggle(card, getString(R.string.main_buffered_audio), getString(R.string.main_buffered_audio_description),
                AirPlayPersistence.loadMainBufferedAudio(this)) {
                AirPlayPersistence.saveMainBufferedAudio(this, it)
                reconnectIfRunning()
            }
            // main #622：USB 导航语音不提供 8/16 kHz（实验性）。
            toggle(card, getString(R.string.settings_speech_without_low_rates), getString(R.string.settings_speech_without_low_rates_description),
                AirPlayPersistence.loadSpeechWithout8And16Khz(this)) {
                AirPlayPersistence.saveSpeechWithout8And16Khz(this, it)
                reconnectIfRunning()
            }
            // main #432：CarPlay 音频走车机蓝牙（实验性，默认关）。
            toggle(card, getString(R.string.settings_car_bluetooth_audio), getString(R.string.settings_car_bluetooth_audio_description),
                AirPlayPersistence.loadCarBluetoothAudio(this)) {
                AirPlayPersistence.saveCarBluetoothAudio(this, it)
                reconnectIfRunning()
            }
        }
        // main #345：BYD 氛围灯音乐跟随入口（作者：TRAE AI）
        filteredSection(content, SettingsSection.AMBIENT_LIGHTING,
            getString(R.string.settings_ambient_title)) { card ->
            card.addView(button(ambientConfigurationLabel(AmbientMusicSettings.load(this)), false) {
                showAmbientConfiguration()
            }, matchButton(0, 60))
        }
        filteredSection(content, SettingsSection.CAR_BUTTON,
            getString(R.string.car_button_in_carplay), R.drawable.ic_dp_car) { card -> carButtonCard = card; carButtonControls(card) }
        filteredSection(content, SettingsSection.AUDIO_ROUTING,
            getString(R.string.audio_routing), R.drawable.ic_dp_audio) { card ->
            mediaChannelControl(card)
            navigationChannelControl(card)
            // main #719：导航播报专用衰减滑块（-20..0 dB），运行中会话即时生效、无需重连（作者：TRAE AI）
            val gainSlider = overlaySliderRow(
                getString(R.string.settings_navigation_attenuation),
                (com.shilapi.xcertplay.media.NavigationAudioGain.MIN_DB..
                    com.shilapi.xcertplay.media.NavigationAudioGain.MAX_DB).toList(),
                AirPlayPersistence.loadNavigationAudioAttenuationDb(this),
            ) { db -> getString(R.string.settings_navigation_attenuation_value, db) }
            val gainTitle = getString(R.string.settings_navigation_attenuation)
            gainSlider.slider.contentDescription = gainTitle + ": " +
                getString(R.string.settings_navigation_attenuation_value,
                    AirPlayPersistence.loadNavigationAudioAttenuationDb(this))
            gainSlider.onSave = { db ->
                AirPlayPersistence.saveNavigationAudioAttenuationDb(this, db)
                CarPlayBackgroundSession.snapshot()?.sink?.setNavigationAttenuationDb(db)
                gainSlider.slider.contentDescription = gainTitle + ": " +
                    getString(R.string.settings_navigation_attenuation_value, db)
            }
            card.addView(gainSlider)
            card.addView(label(getString(R.string.settings_navigation_attenuation_description), 14, MUTED).apply {
                setPadding(0, dp(SETTINGS_BLOCK_GAP_DP), 0, 0)
            })
            // main #746：无线/有线各自的音乐缓冲区选择（作者：TRAE AI）
            musicBufferChoice(card, wireless = true)
            musicBufferChoice(card, wireless = false)
            toggle(card, getString(R.string.settings_call_speakerphone), getString(R.string.settings_call_speakerphone_description),
                AirPlayPersistence.loadCallSpeakerphone(this)) { AirPlayPersistence.saveCallSpeakerphone(this, it) }
        }
        // main #344：导航播报方向盘音量入口（作者：TRAE AI）
        filteredSection(content, SettingsSection.NAVIGATION_WHEEL,
            getString(R.string.settings_navigation_wheel_volume), R.drawable.ic_dp_audio) { card ->
            navigationWheelControls(card)
        }
        filteredSection(content, SettingsSection.LOCATION,
            getString(R.string.location), R.drawable.ic_dp_navigation) { card ->
            toggle(card, getString(R.string.report_location_to_iphone),
                getString(R.string.sends_precise_android_location_as_carplay_gps_data_when_th),
                AirPlayPersistence.loadLocationReportingEnabled(this), save = ::onLocationReportingChanged)
            card.addView(label(getString(R.string.location_reporting_reconnects), 14, MUTED))
        }
        // Cluster video does not require a BYD navigation broadcast receiver.
        filteredSection(content, SettingsSection.CLUSTER_MAP,
            getString(R.string.carplay_map_on_instrument_cluster_experimental), R.drawable.ic_dp_dashboard) { card ->
            // DiLink 3 makes its own dashboard display; either DiLink 4 route stops that, so offer them only to
            // turn off, as the setup guide does (#722).
            val diLink3 = DiLinkGeneration.current(this) == DiLinkGeneration.DILINK_3
            if (diLink3) {
                if (AirPlayPersistence.loadDiLink4ClusterRouteEnabled(this)) clusterRouteConflict(card)
            } else {
                reconnectingToggle(card, getString(R.string.adb_cluster_activity_mode),
                    getString(R.string.adb_cluster_activity_description), AirPlayPersistence.loadAdbClusterEnabled(this)) {
                    AirPlayPersistence.saveAdbClusterEnabled(this, it)
                    ClusterActivityOutput.stopForSettings()
                    render()
                }
                // main #348：Platform-21 独立仪表任务路由；依赖 API 21，低版本隐藏入口（作者：TRAE AI）
                if (android.os.Build.VERSION.SDK_INT >= 21) {
                    reconnectingToggle(card, getString(R.string.platform21_cluster_mode),
                        getString(R.string.platform21_cluster_description), AirPlayPersistence.loadLegacyClusterEnabled(this)) {
                        AirPlayPersistence.saveLegacyClusterEnabled(this, it)
                        ClusterActivityOutput.stopForSettings()
                        render()
                    }
                }
            }
            val adbCluster = AdbClusterRouter.enabled(this)
            if (adbCluster) {
                card.addView(button(getString(R.string.adb_cluster_authorize), false) { authorizeClusterRouting() }, matchButton(10, 56))
                card.addView(button(getString(R.string.adb_cluster_open), false) { ClusterActivityOutput.retry(force = true) }, matchButton(10, 56))
            }
            if (adbCluster && !AirPlayPersistence.loadLegacyClusterEnabled(this) && com.shilapi.xcertplay.hud.BydOemClusterNavi.applicable(this)) {
                val holds = com.shilapi.xcertplay.hud.BydOemClusterHold.entries
                card.addView(label(getString(R.string.oem_cluster_map_description), 14, MUTED))
                choice(card, getString(R.string.oem_cluster_map), holds.map { it.localizedLabel(this) },
                    holds.indexOf(BydOutputSettings.oemClusterHold(this))) { index ->
                    BydOutputSettings.setOemClusterHold(this, holds[index])
                    ClusterActivityOutput.stopForSettings()
                    markReconnectNeeded()
                }
            }
            val clusterDisplay = ClusterMapPresentation.findDisplay(this)
            val clusterSize = clusterDisplay?.let { ClusterMapPresentation.sizeOf(it) }
            val diLink4 = adbCluster || (clusterDisplay != null && clusterSize != null &&
                DiLink4ClusterDisplay.matches(clusterDisplay.name, clusterSize.x, clusterSize.y))
            val clusterMapEnabled = AirPlayPersistence.loadClusterMapEnabled(this)
            reconnectingToggle(card, getString(R.string.carplay_map_on_instrument_cluster_experimental),
                if (clusterDisplay != null || adbCluster) getString(R.string.shows_the_iphone_s_cluster_map_on_the_instrument_cluster_c)
                else getString(R.string.shows_the_iphone_s_cluster_map_virtual_stream_description),
                clusterMapEnabled) {
                AirPlayPersistence.saveClusterMapEnabled(this, it)
                render()
            }
            if (clusterMapEnabled) {
                clusterSpeedometerControls(card)
                clusterMusicControls(card)
                toggle(card, getString(R.string.center_map_card),
                    if (clusterDisplay != null || adbCluster) getString(R.string.center_map_card_description)
                    else getString(R.string.center_map_card_virtual_description),
                    AirPlayPersistence.loadCenterMapOverlay(this)) {
                    AirPlayPersistence.saveCenterMapOverlay(this, it)
                    if (it && !CenterMapOverlay.permitted(this)) openOverlayPermission()
                    render()
                }
                if (AirPlayPersistence.loadCenterMapOverlay(this)) {
                    toggle(card, getString(R.string.center_map_follows_dashboard), getString(R.string.center_map_follows_dashboard_description),
                        AirPlayPersistence.loadCenterMapFollowsDashboard(this)) {
                        AirPlayPersistence.saveCenterMapFollowsDashboard(this, it)
                    }
                    toggle(card, getString(R.string.center_map_auto_hide), getString(R.string.center_map_auto_hide_description),
                        AirPlayPersistence.loadCenterMapAutoHide(this)) {
                        AirPlayPersistence.saveCenterMapAutoHide(this, it)
                    }
                }
                toggle(card, getString(R.string.launcher_map_sharing), getString(R.string.launcher_map_sharing_description),
                    AirPlayPersistence.loadLauncherMapSharing(this)) {
                    AirPlayPersistence.saveLauncherMapSharing(this, it)
                }
                if (AirPlayPersistence.loadCenterMapOverlay(this)) {
                    val overlay = CenterMapOverlay.permitted(this)
                    card.addView(label(if (overlay) getString(R.string.center_map_overlay_allowed)
                        else getString(R.string.center_map_overlay_missing, packageName), 14, if (overlay) MUTED else WARNING))
                    val usage = HomeScreenMonitor.hasAccess(this)
                    card.addView(label(if (usage) getString(R.string.center_map_auto_hide_active)
                        else getString(R.string.center_map_auto_hide_needed), 14, if (usage) MUTED else WARNING))
                    if (!usage) {
                        card.addView(button(getString(R.string.btn_auto_apply_permissions), false) { autoApplyPermissions() }, matchButton(8, 54))
                    }
                }
                if (clusterDisplay != null || adbCluster) {
                    if (DiLink51ClusterLayout.supported() && !adbCluster) {
                        val automatic = DiLink51ClusterLayout.automatic(this)
                        toggle(card, getString(R.string.follow_instrument_theme_and_map_card),
                            getString(R.string.show_the_side_map_only_when_its_card_is_open_and_switch_to), automatic) {
                            DiLink51ClusterLayout.saveAutomatic(this, it)
                            render()
                            markReconnectNeeded()
                        }
                        val allowed = DiLink51ClusterMonitor.hasAccess(this)
                        card.addView(label(if (allowed) getString(R.string.usage_access_enabled)
                            else getString(R.string.usage_access_setup_needed_for_automatic_mode), 14, if (allowed) MUTED else WARNING))
                        card.addView(actionButton(getString(R.string.automatic_map_setup_adb), false) { showClusterAccessSetup() }, matchButton(10, 56))
                        if (!automatic) {
                            val themes = DiLink51ClusterLayout.Theme.entries
                            choice(card, getString(R.string.instrument_theme), themes.map { it.localizedLabel(this) }, themes.indexOf(DiLink51ClusterLayout.theme(this))) {
                                DiLink51ClusterLayout.saveTheme(this, themes[it])
                                markReconnectNeeded()
                            }
                            card.addView(label(getString(R.string.manual_mode_match_the_cluster_theme_here_the_map_cannot_fo), 14, MUTED))
                        }
                        val contrasts = DiLink51ClusterLayout.Contrast.entries
                        choice(card, getString(R.string.instrument_contrast), contrasts.map { it.localizedLabel(this) }, contrasts.indexOf(DiLink51ClusterLayout.contrast(this))) {
                            DiLink51ClusterLayout.saveContrast(this, contrasts[it])
                            markReconnectNeeded()
                        }
                    } else {
                        if (diLink4) clusterSafeAreaControls(card)
                        val sizes = CarPlayClusterDisplay.scalePresets
                        val contents = CarPlayClusterDisplay.Content.entries
                        val content = AirPlayPersistence.loadClusterContent(this)
                        val customCard = CarPlayClusterDisplay.usesCustomTurnCard(content)
                        val officialCardOnly = content == CarPlayClusterDisplay.Content.TURN_CARD
                        choice(card, getString(R.string.dashboard_shows), listOf(
                            getString(R.string.dashboard_content_map),
                            getString(R.string.dashboard_content_turn_card),
                            getString(R.string.dashboard_content_map_with_turn_card),
                            getString(R.string.dashboard_content_map_with_custom_turn_card),
                        ), contents.indexOf(content).coerceAtLeast(0), reconnects = false) {
                            val next = contents[it]
                            val request = ++clusterContentRequestVersion
                            AirPlayPersistence.saveClusterContent(this, next)
                            render()
                            if (content.url != next.url) {
                                // The iPhone's own contents switch live. DiPlay's card over the map, and the
                                // DiLink 5.1 layout (always the map), are set up at connection, so they reconnect.
                                val controller = CarPlayBackgroundSession.snapshot()?.controller
                                if (customCard || CarPlayClusterDisplay.usesCustomTurnCard(next) ||
                                    DiLink51ClusterLayout.supported() || controller == null) {
                                    markReconnectNeeded()
                                } else controller.showDashboardContent(next.url) { applied ->
                                    runOnUiThread {
                                        if (!applied && request == clusterContentRequestVersion &&
                                            !isFinishing && !isDestroyed &&
                                            AirPlayPersistence.loadClusterContent(this) == next &&
                                            CarPlayBackgroundSession.snapshot()?.controller === controller) {
                                            markReconnectNeeded()
                                        }
                                    }
                                }
                            }
                        }
                        if (customCard) {
                            card.addView(overlaySliderRow(
                                getString(R.string.turn_card_overlay_size),
                                ClusterTurnCardOverlay.sizePercents,
                                AirPlayPersistence.loadClusterTurnCardOverlaySizePercent(this),
                            ) { it -> getString(R.string.turn_card_overlay_size_option, it) }
                                .also { it.onSave = { v -> AirPlayPersistence.saveClusterTurnCardOverlaySizePercent(this, v) } })
                            card.addView(overlaySliderRow(
                                getString(R.string.turn_card_overlay_opacity),
                                ClusterTurnCardOverlay.opacityPercents,
                                AirPlayPersistence.loadClusterTurnCardOpacityPercent(this),
                            ) { it -> getString(R.string.turn_card_overlay_opacity_option, it) }
                                .also { it.onSave = { v -> AirPlayPersistence.saveClusterTurnCardOpacityPercent(this, v) } })
                            card.addView(overlaySliderRow(
                                getString(R.string.turn_card_overlay_horizontal),
                                ClusterTurnCardOverlay.xPercents,
                                AirPlayPersistence.loadClusterTurnCardOverlayXPercent(this),
                            ) { it -> overlayOffsetLabel(it, getString(R.string.marker_left), getString(R.string.marker_right), 50) }
                                .also { it.onSave = { v -> AirPlayPersistence.saveClusterTurnCardOverlayXPercent(this, v) } })
                            card.addView(overlaySliderRow(
                                getString(R.string.turn_card_overlay_vertical),
                                ClusterTurnCardOverlay.yPercents,
                                AirPlayPersistence.loadClusterTurnCardOverlayYPercent(this),
                            ) { it -> overlayOffsetLabel(it, getString(R.string.marker_up), getString(R.string.marker_down), 40) }
                                .also { it.onSave = { v -> AirPlayPersistence.saveClusterTurnCardOverlayYPercent(this, v) } })
                            card.addView(button(getString(R.string.reset_turn_card_overlay), false) {
                                AirPlayPersistence.saveClusterTurnCardOverlayXPercent(this, ClusterTurnCardOverlay.DEFAULT_X_PERCENT)
                                AirPlayPersistence.saveClusterTurnCardOverlayYPercent(this, ClusterTurnCardOverlay.DEFAULT_Y_PERCENT)
                                render()
                            }, matchButton(10, 56))
                            card.addView(label(getString(R.string.turn_card_overlay_note), 14, MUTED))
                        }
                        val turnCard = officialCardOnly
                        if (!diLink4) {
                            choice(card, getString(if (turnCard) R.string.turn_card_size else R.string.cluster_map_size),
                                listOf(getString(R.string.cluster_size_standard), getString(R.string.cluster_size_larger), getString(R.string.cluster_size_largest), getString(R.string.cluster_size_smallest)),
                                sizes.indexOf(AirPlayPersistence.loadClusterMapScalePercent(this)).coerceAtLeast(0)) {
                                AirPlayPersistence.saveClusterMapScalePercent(this, sizes[it])
                            }
                        }
                        if (!diLink4 || AirPlayPersistence.loadClusterSafeAreaRect(this) == null) {
                            // main #494：marker 位置改为 1% 步进滑块；旧 10% 档位值自动迁移到以
                            // 滑块中心（50/45）为基准的 1% 网格上（作者：TRAE AI）。
                            card.addView(overlaySliderRow(
                                getString(if (turnCard) R.string.turn_card_horizontal else R.string.car_marker_horizontal),
                                CarPlayClusterDisplay.markerXPercents,
                                AirPlayPersistence.loadClusterMarkerXPercent(this),
                            ) { it -> overlayOffsetLabel(it, getString(R.string.marker_left), getString(R.string.marker_right), 50) }
                                .also { it.onSave = { v -> AirPlayPersistence.saveClusterMarkerXPercent(this, v) } }
                                .also { it.onCommit = { markReconnectNeeded() } })
                            card.addView(overlaySliderRow(
                                getString(if (turnCard) R.string.turn_card_vertical else R.string.car_marker_vertical),
                                CarPlayClusterDisplay.markerYPercents,
                                AirPlayPersistence.loadClusterMarkerYPercent(this),
                            ) { it -> overlayOffsetLabel(it, getString(R.string.marker_up), getString(R.string.marker_down), 45) }
                                .also { it.onSave = { v -> AirPlayPersistence.saveClusterMarkerYPercent(this, v) } }
                                .also { it.onCommit = { markReconnectNeeded() } })
                            card.addView(button(getString(if (turnCard) R.string.reset_turn_card_to_centre else R.string.reset_car_marker_to_centre), false) {
                                AirPlayPersistence.saveClusterMarkerXPercent(this, 50)
                                AirPlayPersistence.saveClusterMarkerYPercent(this, 45)
                                render()
                                markReconnectNeeded()
                            }, matchButton(10, 56))
                        }
                        if (!diLink4) {
                            toggle(card, getString(R.string.dashboard_map_only_in_small_and_full_navi),
                                getString(R.string.dashboard_map_only_in_small_and_full_navi_description),
                                BydOutputSettings.clusterStreamPause(this)) {
                                BydOutputSettings.setClusterStreamPause(this, it)
                                if (it) checkAdbState(mayAsk = true)
                            }
                        }
                    }
                }
            }
        }
        // FCE head units show this card for their cluster's navigation view, which has its own opt-in.
        val bydNavigation = BydOutputSettings.available(this)
        if (bydNavigation || BydOutputSettings.fceClusterAvailable()) filteredSection(content, SettingsSection.BYD_NAVIGATION,
            getString(R.string.byd_navigation), R.drawable.ic_dp_navigation) { card ->
            toggle(card, getString(R.string.navigation_on_hud_and_instrument_cluster),
                getString(R.string.show_phone_navigation_arrows_distance_and_street_names_on),
                com.shilapi.xcertplay.hud.BydOutputSettings.enabled(this)) { com.shilapi.xcertplay.hud.BydOutputSettings.setEnabled(this, it) }
            if (BydOutputSettings.fceClusterAvailable()) {
                toggle(card, getString(R.string.settings_fce_cluster_guidance),
                    getString(R.string.settings_fce_cluster_guidance_description),
                    BydOutputSettings.fceCluster(this)) { BydOutputSettings.setFceCluster(this, it) }
            }
            if (BydOutputSettings.standaloneHudAvailable(this)) {
                toggle(card, getString(R.string.song_on_hud), getString(R.string.song_on_hud_description),
                    BydOutputSettings.hudSong(this)) { BydOutputSettings.setHudSong(this, it) }
            }
            if (bydNavigation) clusterSongSwitch(card)
        }
        filteredSection(content, SettingsSection.PERMISSIONS_AND_HELP,
            getString(R.string.permissions_and_connection_help), R.drawable.ic_dp_permissions) { card ->
            card.addView(label(getString(R.string.nearby_devices_connects_your_iphone_microphone_enables_sir), 16, MUTED))
            card.addView(button(getString(R.string.app_permissions), false) { openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }, matchButton(16, 60))
            card.addView(button(getString(R.string.bluetooth_settings), false) { openBluetoothSettings() }, matchButton(10, 60))
            card.addView(button(getString(R.string.wireless_connection_help), false) { wirelessHelp() }, matchButton(10, 60))
        }
        // main #422：LANGUAGE 卡片挂到独立分类页，经 renderSections 过滤渲染（作者：TRAE AI）。
        languageSettings(content)
    }

    private fun about(content: LinearLayout) {
        // main #597：与其他分类页共用页头样式
        settingsPageTitle(content, getString(R.string.about_diplay), getString(R.string.carplay_at_home_in_your_car))
        section(content, "${getString(R.string.about_public_preview_prefix)}${version()}") { card ->
            card.addView(label(getString(R.string.an_independent_carplay_receiver_for_android_head_units_wir), 17, TEXT))
            // main B5：about 页应用内更新行（作者：TRAE AI）。
            card.addView(updateRow())
            // main #488：后台更新检查开关（作者：TRAE AI）。
            toggle(card, getString(R.string.settings_background_update_checks),
                getString(R.string.settings_background_update_checks_description),
                UpdateAvailability.backgroundChecksEnabled(this)) { UpdateAvailability.saveBackgroundChecksEnabled(this, it) }
        }
        section(content, getString(R.string.backup_title)) { card ->
            card.addView(button(getString(R.string.backup_open), false) { openBackup() }, matchButton(0, 60))
            card.addView(label(getString(R.string.backup_open_description), 14, MUTED).apply { setPadding(0, dp(10), 0, 0) })
        }
        section(content, getString(R.string.made_possible_by_open_source)) { card ->
            card.addView(label(getString(R.string.receiver_based_on_xcertplay_licensed_under_gpl_3_0_diplay), 16, MUTED))
        }
    }

    // ===== main B5：应用内更新（检查/下载/校验/安装）（作者：TRAE AI）=====

    private enum class UpdateStage { IDLE, CHECKING, AVAILABLE, DOWNLOADING, VERIFYING, READY, FAILED }

    // main #598：设置备份——手机浏览器扫码导入/导出（作者：TRAE AI）。
    // main #686：同一页也承载「在手机上取诊断报告」，来源决定标题、提示与带回车的落点。
    private fun openBackup(from: SettingsCategory = SettingsCategory.ABOUT) {
        backupOpenedFrom = from
        page = "backup"
        render()
    }

    private fun backup(content: LinearLayout) {
        val preferredPrefix = when (AirPlayPersistence.loadWirelessHotspotMode(this)) {
            WirelessHotspotMode.WIFI_P2P -> "p2p"
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> "ap"
            WirelessHotspotMode.MANUAL, WirelessHotspotMode.EXISTING_WIFI -> "wlan"
        }
        val addresses = SettingsBackupServer.addressCandidates(preferredPrefix)
        // The same page serves both; opened from Diagnostics, it says what the driver came for.
        val forReport = backupOpenedFrom == SettingsCategory.DIAGNOSTICS
        content.addView(label(getString(if (forReport) R.string.settings_report_on_phone else R.string.backup_title), 40, TEXT, true))
        var endpoint = backupEndpoint
        if ((backupServer == null || endpoint == null) && addresses.isEmpty()) {
            content.addView(space(SETTINGS_BLOCK_GAP_DP))
            content.addView(label(getString(R.string.backup_no_network), 16, MUTED))
            return
        }
        if (backupServer == null || endpoint == null) {
            val created = SettingsBackupServer(
                page = backupPageHtml(),
                export = { SettingsBackupCodec.export(this, version()) },
                import = { SettingsBackupCodec.import(this, it) },
                report = { diagnosticReportText(applicationContext).toByteArray(Charsets.UTF_8) },
                codePage = assets.open("settings-code.html").use { it.readBytes() },
            )
            created.onCodeEntryClosed = {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && backupServer === created) render()
                }
            }
            created.onIdle = {
                runOnUiThread {
                    if (isFinishing || isDestroyed || backupServer !== created) return@runOnUiThread
                    stopBackupServer()
                    page = "settings"
                    settingsCategory = backupOpenedFrom
                    render()
                }
            }
            created.onImported = { outcome ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    backupSummary = if (outcome.invalid) getString(R.string.backup_import_invalid)
                    else getString(R.string.backup_summary, outcome.applied, outcome.skipped)
                    if (!outcome.invalid && outcome.applied > 0) markReconnectNeeded()
                    render()
                }
            }
            endpoint = created.start()
            if (endpoint == null) {
                created.stop()
                content.addView(space(SETTINGS_BLOCK_GAP_DP))
                content.addView(label(getString(R.string.backup_no_network), 16, MUTED))
                return
            }
            backupServer = created
            backupEndpoint = endpoint
            backupSummary = null
        }
        val active = endpoint
        backupServer?.landing = if (forReport) "#report" else ""
        val qr = ImageView(this).apply {
            val side = dp(240)
            setImageBitmap(SettingsQrCode.encode(active.url(addresses.first()) + if (forReport) "#report" else "", side))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        content.addView(space(SETTINGS_BLOCK_GAP_DP))
        content.addView(card().apply {
            addView(label(getString(if (forReport) R.string.settings_report_on_phone_hint else R.string.backup_page_hint), 16, MUTED))
            addView(qr, LinearLayout.LayoutParams(dp(240) + dp(24), dp(240) + dp(24)).apply {
                topMargin = dp(SETTINGS_BLOCK_GAP_DP); gravity = Gravity.CENTER_HORIZONTAL
            })
            backupSummary?.let {
                addView(label(it, 15, TEXT).apply { setPadding(0, dp(16), 0, 0) })
            }
            // For a computer, or a phone whose camera will not read the code.
            addView(label(
                if (backupServer?.codeEntryOpen == true) {
                    getString(R.string.backup_type_address, active.typedAddress(addresses.first()),
                        active.code.chunked(3).joinToString(" "))
                } else getString(R.string.backup_code_entry_closed),
                16,
                TEXT,
            ).apply { setPadding(0, dp(16), 0, 0) })
            if (addresses.size > 1) {
                addView(label(
                    addresses.drop(1).joinToString("\n") { getString(R.string.backup_other_addresses, it) },
                    14,
                    MUTED,
                ).apply { setPadding(0, dp(16), 0, 0) })
            }
        })
    }

    private fun stopBackupServer() {
        backupServer?.stop()
        backupServer = null
        backupEndpoint = null
        backupSummary = null
    }

    private fun backupPageHtml(): ByteArray = assets.open("settings-backup.html").use { it.readBytes() }

    private fun updateRow(): View {
        val container = column().apply { setPadding(0, dp(12), 0, 0) }
        updateMessage?.let { container.addView(label(it, 14, MUTED)) }
        when (updateStage) {
            UpdateStage.IDLE, UpdateStage.FAILED ->
                container.addView(button(getString(R.string.update_check), false) { checkForUpdates() },
                    matchButton(if (updateMessage == null) 0 else 10, 60))
            UpdateStage.CHECKING -> container.addView(label(getString(R.string.update_checking), 14, MUTED))
            UpdateStage.AVAILABLE -> container.addView(
                button(getString(R.string.update_download), true) { downloadUpdate() },
                matchButton(10, 60))
            UpdateStage.DOWNLOADING -> container.addView(label(getString(R.string.update_downloading, updateProgress ?: 0), 14, MUTED))
            UpdateStage.VERIFYING -> container.addView(label(getString(R.string.update_verifying), 14, MUTED))
            UpdateStage.READY -> {
                container.addView(
                    button(getString(R.string.update_install, updateRelease?.tagName.orEmpty()), true) { installUpdate() },
                    matchButton(10, 60))
                // main #488：展示 APK 导出路径，供无法使用系统安装器的车机从文件管理器访问（作者：TRAE AI）。
                updateSavedPath?.let { container.addView(label(getString(R.string.update_saved_to, it), 13, MUTED).apply { setPadding(0, dp(10), 0, 0) }) }
            }
        }
        return container
    }

    private fun checkForUpdates() {
        updateStage = UpdateStage.CHECKING
        updateMessage = null
        val generation = ++updateGeneration
        render()
        Thread({
            val outcome = runCatching { latestRelease() }
            runOnUiThread {
                if (generation != updateGeneration || isFinishing || isDestroyed) return@runOnUiThread
                outcome.fold(
                    { release ->
                        // main #488：手动检查结果同样进入后台检查缓存（作者：TRAE AI）。
                        UpdateAvailability.recordAttempt(this, System.currentTimeMillis())
                        availableUpdate = release
                        if (release == null) {
                            UpdateAvailability.clear(this)
                            updateMessage = getString(R.string.update_up_to_date)
                            updateStage = UpdateStage.IDLE
                        } else {
                            UpdateAvailability.save(this, release)
                            updateRelease = release
                            updateStage = UpdateStage.AVAILABLE
                        }
                        render()
                    },
                    { failure ->
                        Log.w("DiPlay-Update", "update check failed", failure)
                        updateMessage = failure.message ?: getString(R.string.update_failed)
                        updateStage = UpdateStage.FAILED
                        render()
                    },
                )
            }
        }, "diplay-update-check").start()
    }

    private fun latestRelease(): UpdateRelease? =
        // main #488：发布查询移入 UpdateReleaseLookup（含 openConnection 注入）（作者：TRAE AI）。
        UpdateReleaseLookup.latest(version(), userAgent())

    // main #488：点击首页更新提示，跳到设置层级 ABOUT 分类页的下载流程（main #597，作者：TRAE AI）。
    private fun openAvailableUpdate(release: UpdateRelease) {
        updateRelease = release
        updateMessage = null
        updateStage = UpdateStage.AVAILABLE
        page = "settings"
        settingsCategory = SettingsCategory.ABOUT
        render()
    }

    // main #488：WorkManager 每日后台检查；不可用时仅记录日志（作者：TRAE AI）。
    private fun scheduleBackgroundUpdateChecks() {
        runCatching { BackgroundUpdateScheduler.schedule(applicationContext) }
            .onFailure { Log.i("DiPlay-Update", "background update scheduling unavailable", it) }
    }

    private fun downloadUpdate() {
        // main 1b67267e：缓存的发布可能滞后一天，下载前先查最新发布（作者：TRAE AI）。
        if (updateRelease == null) return
        lateinit var release: UpdateRelease
        updateStage = UpdateStage.DOWNLOADING
        updateSavedPath = null
        updateSavedUri = null
        updateProgress = null
        updateMessage = null
        val generation = ++updateGeneration
        render()
        Thread({
            // 每次尝试独占自己的文件目录，跨 Activity 重建亦然。
            val directory = File(cacheDir, "update/${java.util.UUID.randomUUID()}")
            val outcome = runCatching {
                // The cached release can be a day old. Download the newest one.
                release = latestRelease() ?: throw IOException(getString(R.string.update_up_to_date))
                val checksumsFile = File(directory, UpdateCatalog.CHECKSUMS_FILE)
                UpdateClient.download(release.checksumsUrl, checksumsFile) { _, _ -> }
                val apkFile = File(directory, release.apkName)
                UpdateClient.download(release.apkUrl, apkFile) { written, total ->
                    val percent = total?.takeIf { it > 0 }?.let { (written * 100 / it).toInt() } ?: return@download
                    if (percent != updateProgress) {
                        updateProgress = percent
                        refreshUpdateUi(generation)
                    }
                }
                updateStage = UpdateStage.VERIFYING
                refreshUpdateUi(generation)
                val expected = UpdateChecksums.parse(checksumsFile.readText())
                val actual = UpdateChecksums.sha256Hex(apkFile)
                if (!UpdateChecksums.matches(expected, release.apkName, actual)) {
                    throw IOException("Checksum mismatch for ${release.apkName}")
                }
                apkFile
            }.onFailure { directory.deleteRecursively() }
            // main #488：校验通过的 APK 复制到文件管理器可达的位置（作者：TRAE AI）。
            val saved = outcome.getOrNull()?.let { UpdateApkExport.copy(applicationContext, it) }
            runOnUiThread {
                if (generation != updateGeneration || isFinishing || isDestroyed) {
                    directory.deleteRecursively()
                    return@runOnUiThread
                }
                outcome.fold(
                    { file ->
                        updateRelease = release
                        updateFile = file
                        updateSavedPath = saved?.path
                        updateSavedUri = saved?.uri
                        updateStage = UpdateStage.READY
                        render()
                    },
                    { failure ->
                        Log.w("DiPlay-Update", "update download failed", failure)
                        updateMessage = failure.message ?: getString(R.string.update_failed)
                        updateStage = UpdateStage.FAILED
                        render()
                    },
                )
            }
        }, "diplay-update-download").start()
    }

    private fun installUpdate() {
        // main #593：不做未知来源预检查——部分车机的设置页回答"Not supported yet"，
        // 由所选安装器自行请求该权限（作者：TRAE AI）。
        installApk(updateFile ?: return)
    }

    private fun installApk(file: File) {
        // 公开副本让 MT 管理器等文件管理器可打开 APK；私有 provider URI 是回退，
        // 包括司机删除公开副本的情形。公开副本不受应用控制，必须与已校验文件一致。
        // 两份 APK 的哈希在慢车机上需要数秒，故放到主线程之外（作者：TRAE AI）。
        val savedUri = updateSavedUri
        val generation = updateGeneration
        updateInstallWorker(Runnable {
            val publicUri = savedUri?.takeIf { sameContent(it, file) }
            runOnUiThread {
                if (generation == updateGeneration && !isFinishing && !isDestroyed) startInstaller(file, publicUri)
            }
        })
    }

    private fun startInstaller(file: File, publicUri: Uri?) {
        val uri = publicUri ?: FileProvider.getUriForFile(this, "$packageName.update-apks", file)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // 仅在存在多个安装器时弹选择器；单一系统安装器直接打开，与之前一致。
        val installers = packageManager.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY).size
        val launch = if (installers > 1) Intent.createChooser(view, null) else view
        val outcome = "${if (installers > 1) "chooser" else "direct"} uri=${if (publicUri != null) "public" else "provider"} installers=$installers"
        try {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Log.i("DiPlay-Update", "install started: $outcome")
            UpdateAvailability.recordInstall(applicationContext, System.currentTimeMillis(), outcome)
        } catch (failure: RuntimeException) {
            Log.w("DiPlay-Update", "install failed to start: $outcome", failure)
            UpdateAvailability.recordInstall(applicationContext, System.currentTimeMillis(), "$outcome failed=${failure.javaClass.simpleName}")
            updateMessage = getString(R.string.update_failed)
            render()
        }
    }

    private fun sameContent(uri: Uri, verified: File): Boolean = runCatching {
        val copy = contentResolver.openInputStream(uri)?.use { UpdateChecksums.sha256Hex(it) }
        copy != null && copy == UpdateChecksums.sha256Hex(verified)
    }.getOrDefault(false)

    private fun refreshUpdateUi(generation: Int) {
        runOnUiThread {
            if (generation != updateGeneration || isFinishing || isDestroyed ||
                !(page == "settings" && settingsCategory == SettingsCategory.ABOUT)) return@runOnUiThread
            render()
        }
    }

    private fun userAgent() = "DiPlay/${version()}"

    // An opted-in connection prepares the hotspot in the controller instead of stopping at this reminder.
    private fun carHotspotOff(): Boolean =
        AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            CarHotspotSettings.reportedOff(this, com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this)) &&
            !(CarHotspotSettings.enabled(this) && CarHotspotTethering.permitted(this))

    /**
     * main #748：非 BYD 车机启动热点只需 Android"修改系统设置"权限（WRITE_SETTINGS），
     * 由司机在系统页放行，不涉及 ADB（作者：TRAE AI）。
     */
    private fun systemHotspotSettings(parent: LinearLayout) {
        section(parent, getString(R.string.settings_car_hotspot_control), R.drawable.ic_dp_permissions) { card ->
            toggle(card, getString(R.string.auto_car_hotspot_title),
                getString(R.string.settings_auto_car_hotspot_system_description), CarHotspotSettings.enabled(this)) { enabled ->
                CarHotspotSettings.setEnabled(this, enabled)
                if (!enabled) startupHotspotCancelled = true
                else if (!CarHotspotTethering.permitted(this)) openWriteSettingsAccess()
            }
            val permitted = CarHotspotTethering.permitted(this)
            card.addView(label(getString(if (permitted) R.string.settings_write_settings_granted
                else R.string.settings_write_settings_missing), 14, if (permitted) READY else MUTED).apply {
                setPadding(0, dp(6), 0, dp(4))
            })
            if (!permitted) {
                card.addView(button(getString(R.string.settings_write_settings_allow), false) { openWriteSettingsAccess() },
                    matchButton(8, 54))
            }
        }
    }

    private fun openWriteSettingsAccess() =
        openSystem(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))

    private fun bydAdbSettings(parent: LinearLayout) {
        if (AirPlayPersistence.loadWirelessHotspotMode(this) != WirelessHotspotMode.MANUAL) return
        if (!CarHotspotSetup.isBydHeadUnit(this)) {
            // main #748：非 BYD 且系统允许应用自启热点时，改走"修改系统设置"控制（作者：TRAE AI）。
            if (CarHotspotTethering.appStartSupported()) {
                Log.i("DiPlay-Hotspot", "BYD head unit not detected: hotspot control through system settings access")
                systemHotspotSettings(parent)
            } else {
                Log.i("DiPlay-Hotspot", "settings hidden: BYD head unit not detected; Android ${Build.VERSION.SDK_INT} does not let an app start tethering")
            }
            return
        }
        if (searchIndexSink != null) {
            // Index discoverable names without starting the asynchronous permission probe.
            searchIndexSink?.addAll(listOf(
                getString(R.string.byd_adb_features),
                getString(R.string.auto_car_hotspot_title),
                getString(R.string.btn_auto_apply_permissions),
            ))
            return
        }
        val controls = column().apply { visibility = View.GONE }
        bydAdbControls = controls
        parent.addView(controls)
        Thread({
            val access = runCatching { CarHotspotSetup.check(applicationContext) }
                .onFailure { Log.w("DiPlay-Hotspot", "settings ADB check failed", it) }
                .getOrDefault(LocalAdb.Access.UNREACHABLE)
            Log.i("DiPlay-Hotspot", "settings eligibility: byd=true adb=$access visible=${CarHotspotSettings.visible(true, access)}")
            runOnUiThread {
                if (bydAdbControls !== controls || isFinishing || isDestroyed) return@runOnUiThread
                if (CarHotspotSettings.visible(true, access)) {
                    controls.visibility = View.VISIBLE
                    renderBydAdbControls(controls, access)
                }
            }
        }, "diplay-hotspot-adb-check").start()
    }

    private fun renderBydAdbControls(controls: LinearLayout, access: LocalAdb.Access) {
        controls.removeAllViews()
        adbSwitches.keys.retainAll(setOf(R.string.open_after_the_car_starts))
        adbStatus = null
        controls.visibility = if (CarHotspotSettings.visible(true, access)) View.VISIBLE else View.GONE
        if (controls.visibility == View.GONE) return
        if (AirPlayPersistence.loadWirelessHotspotMode(this) != WirelessHotspotMode.MANUAL) {
            controls.visibility = View.GONE
            return
        }
        section(controls, getString(R.string.byd_adb_features), R.drawable.ic_dp_permissions) { card ->
            if (AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL) {
                adbToggle(card, R.string.auto_car_hotspot_title, R.string.auto_car_hotspot_description,
                    read = { CarHotspotSettings.enabled(this) },
                    permissions = {
                        buildList {
                            add(CarHotspotSetup.Permission.HOTSPOT)
                            if (AirPlayPersistence.loadAutoStartOnBoot(this@DiPlayActivity)) {
                                add(CarHotspotSetup.Permission.BOOT_LAUNCH)
                            }
                        }
                    }) {
                    CarHotspotSettings.setEnabled(this, it)
                    if (!it) startupHotspotCancelled = true
                }
            }
            adbStatus = label(getString(if (access == LocalAdb.Access.READY)
                R.string.adb_access_ready else R.string.adb_not_approved), 14, MUTED).also(card::addView)
            val allReady = UsbPermissionSetup.snapshot(this).values.all { it }
            if (!allReady) {
                card.addView(button(getString(R.string.btn_auto_apply_permissions), false) { autoApplyPermissions() }, matchButton(8, 54))
            } else {
                card.addView(label(getString(R.string.btn_permissions_ready), 14, READY).apply {
                    setPadding(0, dp(6), 0, dp(4))
                })
            }
        }
    }

    private fun adbToggle(parent: LinearLayout, title: Int, description: Int, read: () -> Boolean,
        needsAdb: () -> Boolean = { true },
        permissions: () -> List<CarHotspotSetup.Permission> = { emptyList() }, save: (Boolean) -> Unit) {
        val control = toggle(parent, getString(title), getString(description), read(),
            enabled = !adbSwitchChangePending && !vehicleAdbWorkInProgress()) { enabled ->
            if (updatingAdbSwitches || adbSwitchChangePending) return@toggle
            if (enabled && needsAdb()) requestAdbSwitchChange(permissions()) { save(true) }
            else save(enabled)
        }
        adbSwitches[title] = control to read
    }

    private fun requestAdbSwitchChange(permissions: List<CarHotspotSetup.Permission>, save: () -> Unit) {
        if (adbSwitchChangePending || vehicleAdbWorkInProgress()) {
            updateAdbSwitches()
            return
        }
        cancelAutomaticVehicleValidationForUserOperation(
            resumeAfter = automaticVehicleValidationInProgress || automaticVehicleValidationPending,
        )
        val app = applicationContext
        adbSwitchChangePending = true
        updateAdbSwitches()
        adbStatus?.setText(R.string.adb_checking_may_ask)
        if (page == "settings" && bydVehicleAdvancedExpanded) render()
        Thread({
            var access = LocalAdb.Access.UNREACHABLE
            val ready = runCatching {
                access = CarHotspotSetup.grant(app, permissions)
                access == LocalAdb.Access.READY && permissions.all { it.granted(app) }
            }.getOrDefault(false)
            runOnUiThread {
                adbSwitchChangePending = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                val message = if (ready) {
                    if (permissions.isEmpty()) R.string.adb_access_ready else R.string.hotspot_permission_granted
                } else when (access) {
                    LocalAdb.Access.NOT_APPROVED -> R.string.adb_not_approved
                    LocalAdb.Access.UNREACHABLE -> R.string.adb_off
                    LocalAdb.Access.UNSUPPORTED -> R.string.adb_pairing_only
                    else -> R.string.hotspot_permission_failed
                }
                adbStatus?.setText(if (ready) R.string.adb_access_ready else message)
                if (ready) save()
                updateAdbSwitches()
                toast(getString(message))
                runPendingAutomaticVehicleValidation()
                // Re-enable the vehicle controls disabled while this authorization was outstanding.
                if (page == "settings" && bydVehicleAdvancedExpanded) render()
            }
        }, "diplay-adb-switch").start()
    }

    private fun updateAdbSwitches() {
        updatingAdbSwitches = true
        for ((control, read) in adbSwitches.values) {
            control.isChecked = read()
            control.isEnabled = !adbSwitchChangePending && !vehicleAdbWorkInProgress()
        }
        updatingAdbSwitches = false
    }

    private fun vehicleAdbWorkInProgress(): Boolean =
        adbCheckInProgress || vehicleProbeAuthorizationInProgress || vehicleProbeInProgress

    private fun startCarHotspotOnLaunch() {
        if (!startupHotspotEligible()) return
        val app = applicationContext
        Thread({
            val result = CarHotspotTethering.enable(app,
                isCancelled = { startupHotspotCancelled || !startupHotspotEligible() },
                log = { Log.i("DiPlay-Hotspot", it) },
            )
            runOnUiThread {
                if (isFinishing || isDestroyed || result == CarHotspotTethering.Result.CANCELLED) return@runOnUiThread
                hotspotStartupResult = result
                if (page == "home") render()
            }
        }, "diplay-hotspot-startup").start()
    }

    private fun startupHotspotEligible(): Boolean =
        CarHotspotSetup.shouldStartOnLaunch(applicationContext, CarPlayBackgroundSession.hasSession())

    private fun hotspotResultText(result: CarHotspotTethering.Result): String = getString(when (result) {
        CarHotspotTethering.Result.READY -> R.string.hotspot_control_on
        CarHotspotTethering.Result.PERMISSION_REQUIRED -> R.string.hotspot_control_missing
        CarHotspotTethering.Result.UNSUPPORTED -> R.string.hotspot_control_unsupported
        CarHotspotTethering.Result.TIMED_OUT -> R.string.hotspot_control_timeout
        else -> R.string.hotspot_control_failed
    })

    private fun carHotspotOffDialog() {
        AlertDialog.Builder(this).setTitle(getString(R.string.car_hotspot_is_off))
            .setMessage(getString(R.string.msg_car_hotspot_connect, AirPlayPersistence.loadManualHotspotSsid(this)))
            .setPositiveButton(getString(R.string.open_car_settings)) { _, _ -> openCarWifiSettings() }
            .setNeutralButton(getString(R.string.connect)) { _, _ ->
                // main #789：司机看得见热点已开时，以司机的判断覆盖 Android 的"已关闭"上报，
                // 否则每次点击都会重新弹出本对话框（#604）（作者：TRAE AI）。
                CarHotspotSettings.setOffReportIgnored(this, true)
                connect(true)
            }
            .setNegativeButton(getString(R.string.cancel), null).show()
    }

    // 开机自启 ADB 修复（#441）：后台线程执行阻塞的 ADB shell，结果回到主线程弹窗展示。
    private fun repairBootStart() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.boot_start_repair))
            .setMessage(getString(R.string.boot_start_repair_running))
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
        Thread({
            val result = runCatching {
                com.shilapi.xcertplay.hud.BydBootStartRepair.apply(applicationContext, packageName)
            }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                dialog.dismiss()
                val text = result?.lines?.joinToString("\n")?.let { getString(R.string.boot_start_repair_result, it) }
                    ?: getString(R.string.adb_check_failed)
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.boot_start_repair))
                    .setMessage(text)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }, "diplay-boot-repair").start()
    }

    /**
     * True on the head units this BYD settings work is for, and only those.
     *
     * DiLink 1.0 ships Android 7.1, which is also DiPlay's minimum, so this is the whole of the range
     * anyone has checked. Every BYD path below is behind this so that no other head unit changes
     * behaviour — a stock Android build of any version has no such package to find, and a newer BYD
     * build stays exactly as it is today until it can be measured on a car.
     */
    private val targetedByBydSettingsWork get() = Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1

    /**
     * True when [action] is answered by BYD's decoy rather than a real settings screen.
     *
     * The decoy accepts sixty standard android.settings actions and answers each with "the multimedia
     * system does not support this operation". Because startActivity succeeds when it reaches the
     * decoy, the intent has to be resolved first: a throw is not the only way for a screen to fail to
     * appear.
     */
    private fun answeredByBydDecoy(action: String): Boolean {
        if (!targetedByBydSettingsWork) return false
        val resolved = runCatching { packageManager.resolveActivity(Intent(action), 0) }.getOrNull()
        val info = resolved?.activityInfo ?: return false
        // The decoy is the only occupant of the package's "unsupport" package; BYD's real screens live
        // beside it, so the package name alone would not identify one.
        return info.packageName == BYD_SETTINGS_PACKAGE && info.name.contains("unsupport", ignoreCase = true)
    }

    /** Opens a BYD screen by explicit component, which intent-filter matching cannot redirect. */
    private fun openBydScreen(component: ComponentName): Boolean =
        runCatching { startActivity(Intent().setComponent(component)) }.isSuccess

    /**
     * Opens the head unit's Bluetooth screen.
     *
     * Where BYD's decoy answers android.settings.BLUETOOTH_SETTINGS, BYD's own Bluetooth manager is
     * opened instead. Every other head unit keeps the standard action, unchanged.
     */
    private fun openBluetoothSettings() {
        if (!answeredByBydDecoy(Settings.ACTION_BLUETOOTH_SETTINGS)) {
            openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            return
        }
        if (openBydScreen(BYD_BLUETOOTH_MANAGER)) return
        Log.w(BYD_SETTINGS_TAG, "no Bluetooth screen on this head unit")
        toast(getString(R.string.open_this_setting_from_your_car_s_settings_app))
    }

    // BYD maps the AOSP tether action to its own hotspot screen; other firmware falls back to Wi-Fi settings.
    // BYD shows that screen as a dialog and closes it unless its own settings or the car home screen is on top,
    // so the home screen goes first.
    private fun openCarWifiSettings() {
        val tether = "com.android.settings.WIFI_TETHER_SETTINGS"
        val target = packageManager.resolveActivity(Intent(tether), 0)?.activityInfo?.packageName
        // Nothing opens the AOSP hotspot screen here: either no app answers the action at all, or BYD's
        // decoy does. BYD's own screen answers android.intent.action.HOTSPOT_SETTINGS instead.
        if (target == null || answeredByBydDecoy(tether)) {
            if (targetedByBydSettingsWork && openBydScreen(BYD_HOTSPOT)) return
            // Where the decoy answers the Wi-Fi settings fallback as well, taking it would only repeat
            // the "not supported" dialog that brought the driver here. Say where the screen actually is.
            if (answeredByBydDecoy(Settings.ACTION_WIRELESS_SETTINGS)) {
                Log.w(BYD_SETTINGS_TAG, "no hotspot screen on this head unit")
                toast(getString(R.string.open_this_setting_from_your_car_s_settings_app))
                return
            }
            openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            return
        }
        if (target == "com.byd.carsettings") {
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        }
        if (runCatching { startActivity(Intent(tether)) }.isSuccess) return
        openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }

    private fun openCarClientWifiSettings() {
        val wifi = Intent(Settings.ACTION_WIFI_SETTINGS)
        if (packageManager.resolveActivity(wifi, 0)?.activityInfo?.packageName == "com.byd.carsettings") {
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        }
        openSystem(wifi)
    }

    private fun connectionSetup(content: LinearLayout) {
        content.addView(label(getString(R.string.connection_setup), 34, TEXT, true))
        content.addView(label(getString(R.string.set_up_once_your_details_stay_saved_for_the_next_drive_cha), 17, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, getString(R.string.s_1_choose_your_connection)) { card -> wirelessLinkControls(card) }
        section(content, getString(R.string.s_2_pair_your_iphone)) { card ->
            card.addView(label(getString(R.string.keep_bluetooth_and_wi_fi_on_your_iphone_pair_with_the_car), 16, MUTED))
            card.addView(button(chooseIphoneTitle(), false) { choosePhone() }, matchButton(12, 60))
            card.addView(button(getString(R.string.review_app_permissions), false) {
                openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }, matchButton(12, 60))
        }
        section(content, getString(R.string.s_3_connect)) { card ->
            card.addView(label(getString(R.string.return_from_car_settings_to_diplay_then_connect_accept_the), 16, MUTED))
            card.addView(button(getString(R.string.connect_phone), true) { connect(true) }, matchButton(12, 60))
        }
        section(content, getString(R.string.prefer_a_cable)) { card ->
            card.addView(label(getString(R.string.use_a_usb_data_cable_and_the_car_s_usb_data_port_unlock_yo), 16, MUTED))
            card.addView(button(getString(R.string.connect_with_usb), false) { connect(false) }, matchButton(12, 60))
        }
    }

    private fun wirelessLinkControls(parent: LinearLayout) {
        val stored = if (pendingCarHotspotSetup) WirelessHotspotMode.MANUAL else AirPlayPersistence.loadWirelessHotspotMode(this)
        // android-4.2.2：EXISTING_WIFI 依赖 ConnectivityManager.NetworkCallback（API 21），
        // API 17-20 设备上 ExistingWifiManager 连类加载都无法完成（NoClassDefFoundError）。
        // 选项不呈现；已保存的该模式也不显示为选中，连接时由 CarPlayController 守卫拒绝。
        val existingWifiAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
        val mode = stored.takeIf { it != WirelessHotspotMode.EXISTING_WIFI || existingWifiAvailable }
        val modes = buildList {
            add(WirelessHotspotMode.MANUAL)
            add(WirelessHotspotMode.WIFI_P2P)
            if (existingWifiAvailable) add(WirelessHotspotMode.EXISTING_WIFI)
        }
        val wide = resources.configuration.screenWidthDp >= 850
        val choices = if (wide) row().apply { gravity = Gravity.TOP } else column()
        parent.addView(choices)
        modes.forEach { candidate ->
            val option = column()
            choices.addView(option, if (wide) LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (candidate != modes.first()) marginStart = dp(16)
            } else LinearLayout.LayoutParams(-1, -2))
            option.addView(button("${if (mode == candidate) "✓  " else ""}${hotspotModeTitle(candidate)}", mode == candidate) {
                if (candidate == WirelessHotspotMode.MANUAL) {
                    pendingCarHotspotSetup = true
                    render()
                } else if (candidate == WirelessHotspotMode.EXISTING_WIFI) {
                    askHotspotCredentials(existingWifi = true) { ssid, password, _ ->
                        AirPlayPersistence.saveExistingWifiCredentials(this, ssid, password)
                        pendingCarHotspotSetup = false
                        applyWirelessLink(candidate)
                    }
                } else {
                    pendingCarHotspotSetup = false
                    applyWirelessLink(candidate)
                }
            }, matchButton(12, 60))
            option.addView(label(hotspotModeDescription(candidate), 15, MUTED).apply { setPadding(0, dp(6), 0, dp(12)) })
        }
        if (mode == WirelessHotspotMode.MANUAL) {
            parent.addView(label(getString(R.string.hotspot_setup), 22, TEXT, true))
            parent.addView(label(getString(R.string.s_1_open_car_hotspot_settings_turn_the_hotspot_on_and_sele), 16, MUTED).apply { setPadding(0, dp(8), 0, dp(12)) })
            parent.addView(button(getString(R.string.open_car_hotspot_settings), false) { openCarWifiSettings() }, matchButton(0, 60))
            // Before anything is saved there is no hotspot to edit and no name to show after the dot.
            val detailsMissing = carHotspotDetailsMissing()
            parent.addView(button(if (detailsMissing) getString(R.string.save_hotspot_details_and_use_this_mode) else "${getString(R.string.edit_saved_hotspot_prefix)}${storedSsid()}", false) {
                askHotspotCredentials { ssid, password, security ->
                    saveHotspotCredentials(ssid, password, security)
                    pendingCarHotspotSetup = false
                    applyWirelessLink(WirelessHotspotMode.MANUAL)
                }
            }, matchButton(12, 60))
            parent.addView(label(if (detailsMissing) getString(R.string.finish_setup_save_your_hotspot_details_to_use_this_mode) else if (carHotspotOff()) getString(R.string.hotspot_details_off) else getString(R.string.hotspot_details_saved), 15, if (carHotspotOff()) WARNING else MUTED).apply { setPadding(0, dp(12), 0, 0) })
            val join = hotspotJoinControls ?: HotspotJoinControls(this,
                { CarPlayBackgroundSession.hasSession() }, beforeAction = { startupHotspotCancelled = true },
                labelFactory = { label(it, 15, MUTED) }, buttonFactory = { title, click -> button(title, false, click) })
                .also { hotspotJoinControls = it }
            parent.addView(join.build())
        } else if (mode == WirelessHotspotMode.EXISTING_WIFI) {
            parent.addView(label(getString(R.string.existing_wifi_instructions), 16, MUTED))
            parent.addView(button(getString(R.string.open_car_wi_fi_settings), false) { openCarClientWifiSettings() }, matchButton(12, 60))
            parent.addView(button(getString(R.string.existing_wifi_details), false) {
                askHotspotCredentials(existingWifi = true) { ssid, password, _ ->
                    AirPlayPersistence.saveExistingWifiCredentials(this, ssid, password)
                    toast(getString(R.string.saved_for_your_next_connection))
                }
            }, matchButton(12, 60))
        } else if (mode == WirelessHotspotMode.WIFI_P2P) {
            parent.addView(label(getString(R.string.turn_the_car_s_wi_fi_switch_on_allow_location_nearby_devic), 16, MUTED))
            wifiDirectChannelControl(parent)
            // main #755：扫描暂停开关。暂停也会让车机不自动连已知网络（如手机热点），故可关（作者：TRAE AI）。
            // #755: the pause also stops the car joining a known network, such as the phone's hotspot, by itself.
            toggle(parent, getString(R.string.settings_wifi_direct_scan_pause),
                getString(R.string.settings_wifi_direct_scan_pause_description),
                AirPlayPersistence.loadWifiDirectScanPause(this)) {
                AirPlayPersistence.saveWifiDirectScanPause(this, it)
                markReconnectNeeded()
            }
            parent.addView(button(getString(R.string.open_car_wi_fi_settings), false) { openCarClientWifiSettings() }, matchButton(12, 60))
        }
    }

    private fun hotspotModeTitle(mode: WirelessHotspotMode): String = when (mode) {
        WirelessHotspotMode.MANUAL -> getString(R.string.settings_built_in_car_hotspot_recommended)
        WirelessHotspotMode.WIFI_P2P -> getString(R.string.wifi_direct)
        WirelessHotspotMode.EXISTING_WIFI -> getString(R.string.existing_wifi_title)
        WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> getString(R.string.localonlyhotspot)
    }

    private fun hotspotModeDescription(mode: WirelessHotspotMode): String = when (mode) {
        WirelessHotspotMode.MANUAL -> getString(R.string.hotspot_mode_manual_desc)
        WirelessHotspotMode.WIFI_P2P -> getString(R.string.hotspot_mode_p2p_desc)
        WirelessHotspotMode.EXISTING_WIFI -> getString(R.string.existing_wifi_description)
        WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> getString(R.string.hotspot_hint_local)
    }

    private fun wifiDirectChannelLabel(channel: Int): String = if (channel == WifiP2pChannels.AUTO) {
        getString(R.string.auto)
    } else if (channel == WifiP2pChannels.AUTO_5_GHZ || channel == WifiP2pChannels.AUTO_2_4_GHZ) {
        // main #432：Auto 限定频段的选择项。
        getString(R.string.settings_wifi_direct_auto_band,
            getString(if (channel == WifiP2pChannels.AUTO_5_GHZ) R.string.s_5_ghz else R.string.s_2_4_ghz))
    } else {
        getString(R.string.wifi_direct_channel_choice, channel,
            getString(if (channel < 36) R.string.s_2_4_ghz else R.string.s_5_ghz))
    }

    private fun wifiDirectChannelControl(parent: LinearLayout) {
        val summary: (Int) -> String = {
            getString(R.string.wifi_direct_channel_summary, wifiDirectChannelLabel(it))
        }
        val control = button(summary(AirPlayPersistence.loadWifiP2pPreferredChannel(this)), false) {}
        control.setOnClickListener {
            val choices = listOf(WifiP2pChannels.AUTO) + WifiP2pChannels.bandChoices + WifiP2pChannels.channels
            val current = AirPlayPersistence.loadWifiP2pPreferredChannel(this)
            var selection = current
            AlertDialog.Builder(this).setTitle(R.string.wifi_direct_channel_title)
                .setSingleChoiceItems(choices.map(::wifiDirectChannelLabel).toTypedArray(),
                    choices.indexOf(current)) { _, which -> selection = choices[which] }
                .setPositiveButton(R.string.save) { _, _ ->
                    if (selection != current) {
                        AirPlayPersistence.saveWifiP2pPreferredChannel(this, selection)
                        control.text = summary(selection)
                        toast(getString(R.string.saved_for_your_next_connection))
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        parent.addView(control, matchButton(12, 60))
        parent.addView(label(getString(R.string.wifi_direct_channel_description), 15, MUTED).apply {
            setPadding(0, dp(6), 0, dp(6))
        })
        // main #432：说明频段选择只约束 Auto，手动信道仍按所选信道固定。
        parent.addView(label(getString(R.string.settings_wifi_direct_band_note), 15, MUTED).apply {
            setPadding(0, 0, 0, dp(12))
        })
    }

    private fun mediaChannelControl(parent: LinearLayout) {
        val summary: (Int) -> String = {
            getString(R.string.contrib_audio_home_choice_summary, getString(R.string.contrib_audio_home_media_channel_label), channelLabel(it))
        }
        val control = button(summary(AirPlayPersistence.loadMediaAudioChannel(this)), false) {}
        control.setOnClickListener {
            val current = AirPlayPersistence.loadMediaAudioChannel(this)
            showChannelDialog(
                title = getString(R.string.contrib_audio_home_media_channel_label),
                current = current,
                navigation = false,
                onApply = { value -> applyMediaChannel(value, current, control, summary) },
            )
        }
        parent.addView(control, matchButton(12, 60))
    }

    private fun navigationChannelControl(parent: LinearLayout) {
        val summary: (Int) -> String = {
            getString(R.string.contrib_audio_home_choice_summary, getString(R.string.contrib_audio_home_nav_channel_label), channelLabel(it))
        }
        val control = button(summary(AirPlayPersistence.loadNavigationAudioChannel(this)), false) {}
        control.setOnClickListener {
            val current = AirPlayPersistence.loadNavigationAudioChannel(this)
            showChannelDialog(
                title = getString(R.string.contrib_audio_home_nav_channel_label),
                current = current,
                navigation = true,
                onApply = { value -> applyNavigationChannel(value, current, control, summary) },
            )
        }
        parent.addView(control, matchButton(10, 60))
        parent.addView(label(getString(R.string.contrib_audio_home_nav_channel_note), 14, MUTED).apply {
            setPadding(0, dp(8), 0, dp(18))
        })
    }

    private fun showChannelDialog(title: String, current: Int, navigation: Boolean, onApply: (Int) -> Unit) {
        val preview = AudioChannelPreview { channel ->
            toast(getString(R.string.contrib_audio_home_channel_preview_unavailable, channel))
        }
        val channels = AirPlayPersistence.AUDIO_CHANNELS
        val labels = channels.map(Int::toString).toTypedArray()
        var selection = current.coerceIn(channels.first, channels.last)
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(labels, selection) { _, which ->
                selection = which
                preview.play(which, navigation)
            }
            .setPositiveButton(if (CarPlayBackgroundSession.hasSession()) getString(R.string.apply_and_reconnect) else getString(R.string.save)) { _, _ ->
                onApply(selection)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .setOnDismissListener { preview.close() }
            .show()
    }

    private fun applyMediaChannel(value: Int, previous: Int, control: Button, summary: (Int) -> String) {
        if (value == previous) return
        AirPlayPersistence.saveMediaAudioChannel(this, value)
        control.text = summary(value)
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun applyNavigationChannel(value: Int, previous: Int, control: Button, summary: (Int) -> String) {
        if (value == previous) return
        AirPlayPersistence.saveNavigationAudioChannel(this, value)
        control.text = summary(value)
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun channelLabel(value: Int): String = value.toString()

    private fun storedSsid() = AirPlayPersistence.loadManualHotspotSsid(this)
    private fun storedPassword() = AirPlayPersistence.loadManualHotspotPassphrase(this)

    /** 首次运行前没有可用车机热点名称与密码时为真。 */
    private fun carHotspotDetailsMissing() = pendingCarHotspotSetup || hotspotError(storedSsid(), storedPassword()) != null
    private fun hotspotError(ssid: String, password: String) =
        com.shilapi.xcertplay.orchestration.ManualHotspotValidation.error(ssid, password)?.let { getString(it.messageResource()) }

    // main 4aee3f80：安全模式由用户在对话框中显式选择并持久化（车机热点可能是 WPA3），保存后提示重连。
    private fun saveHotspotCredentials(ssid: String, password: String, security: ManualHotspotSecurity) {
        AirPlayPersistence.saveManualHotspotSsid(this, ssid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, password)
        AirPlayPersistence.saveManualHotspotSecurity(this, security)
        AirPlayPersistence.saveManualHotspotBand(this, com.shilapi.xcertplay.orchestration.ManualHotspotBand.AUTO)
        AirPlayPersistence.saveManualHotspotChannel(this, 0)
        markReconnectNeeded()
    }

    private fun askHotspotCredentials(existingWifi: Boolean = false, done: (String, String, ManualHotspotSecurity) -> Unit) {
        val fields = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        fields.addView(label(getString(if (existingWifi) R.string.existing_wifi_instructions else R.string.copy_these_from_the_car_s_hotspot_settings_use_5_ghz_if_av), 16, MUTED))
        val ssid = EditText(this).apply {
            hint = getString(if (existingWifi) R.string.existing_wifi_ssid else R.string.hotspot_name)
            setText(if (existingWifi) AirPlayPersistence.loadExistingWifiSsid(this@DiPlayActivity) else storedSsid())
            setSingleLine()
        }
        val password = EditText(this).apply {
            hint = getString(if (existingWifi) R.string.existing_wifi_password else R.string.hotspot_password)
            setText(if (existingWifi) AirPlayPersistence.loadExistingWifiPassphrase(this@DiPlayActivity) else storedPassword())
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        ssid.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        password.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        fun hideKeyboard() {
            val token = password.windowToken ?: ssid.windowToken
            (this.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(token, 0)
            ssid.clearFocus(); password.clearFocus()
        }
        ssid.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) { password.requestFocus(); true } else false
        }
        password.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { hideKeyboard(); true } else false
        }
        // main #622：热点名含非英文字符时提示(#303)——部分车机广播的名称 iPhone 找不到
        val nameWarning = label(getString(R.string.settings_hotspot_name_non_english), 14, WARNING)
        fun updateNameWarning() {
            nameWarning.visibility = if (!existingWifi &&
                com.shilapi.xcertplay.orchestration.ManualHotspotValidation.nameMayNotReachIphone(ssid.text.toString())
            ) View.VISIBLE else View.GONE
        }
        updateNameWarning()
        ssid.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = updateNameWarning()
        })
        fields.addView(ssid); fields.addView(nameWarning); fields.addView(password)
        fields.addView(CheckBox(this).apply {
            text = getString(R.string.show_password)
            setOnCheckedChangeListener { _, checked ->
                password.transformationMethod = if (checked) null else android.text.method.PasswordTransformationMethod.getInstance()
                password.setSelection(password.text.length)
            }
        })
        // main 4aee3f80：车机热点可能是 WPA3，安全模式需用户显式选择而非从密码推断。
        val securityModes = listOf(ManualHotspotSecurity.WPA2, ManualHotspotSecurity.WPA3_TRANSITION, ManualHotspotSecurity.WPA3)
        val securityTitles = listOf(getString(R.string.wpa2), getString(R.string.wpa3_transition), getString(R.string.wpa3))
        var protectedSecurity = AirPlayPersistence.loadManualHotspotSecurity(this)
            .takeIf { it in securityModes } ?: ManualHotspotSecurity.WPA2
        if (!existingWifi) {
            lateinit var securityControl: Button
            fun updateSecurityControl() {
                val hasPassword = password.text.isNotEmpty()
                val title = if (hasPassword) securityTitles[securityModes.indexOf(protectedSecurity)] else getString(R.string.open)
                securityControl.text = "${getString(R.string.security)} · $title"
                securityControl.isEnabled = hasPassword
            }
            securityControl = button("", false) {
                var pendingSecurity = protectedSecurity
                AlertDialog.Builder(this).setTitle(getString(R.string.security))
                    .setSingleChoiceItems(securityTitles.toTypedArray(), securityModes.indexOf(protectedSecurity)) { _, index ->
                        pendingSecurity = securityModes[index]
                    }
                    .setPositiveButton(getString(R.string.save)) { _, _ ->
                        protectedSecurity = pendingSecurity
                        updateSecurityControl()
                    }.setNegativeButton(getString(R.string.cancel), null).show()
            }
            updateSecurityControl()
            password.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) = updateSecurityControl()
            })
            fields.addView(securityControl, matchButton(12, 60))
            fields.addView(label(getString(R.string.saved_for_your_next_connection), 15, MUTED))
        }
        val error = label("", 14, WARNING)
        UiCompat.setAccessibilityLiveRegion(error, View.ACCESSIBILITY_LIVE_REGION_POLITE)
        fields.addView(error)
        val dialog = AlertDialog.Builder(this).setTitle(getString(if (existingWifi) R.string.existing_wifi_details else R.string.car_hotspot_details))
            .setView(ScrollView(this).apply { addView(fields) })
            .setPositiveButton(getString(R.string.save_details), null).setNegativeButton(getString(R.string.cancel)) { _, _ -> hideKeyboard() }
            .setNeutralButton(getString(R.string.hide_keyboard), null).create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { hideKeyboard() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = if (existingWifi) ssid.text.toString() else ssid.text.toString().trim()
                val secret = password.text.toString()
                val problem = hotspotError(name, secret)
                if (problem != null) error.text = problem
                else {
                    // 密码为空视为开放网络；否则使用用户显式选择的安全模式。
                    val security = if (secret.isEmpty()) ManualHotspotSecurity.OPEN else protectedSecurity
                    hideKeyboard(); dialog.dismiss(); done(name, secret, security)
                }
            }
        }
        dialog.show()
    }

    // "Left 20 %", "Centre · default", "Down 10 %": a signed step reads as a direction and a distance.
    private fun markerStepLabel(step: Int, negative: String, positive: String): String = when {
        step == 0 -> getString(R.string.marker_centre_default)
        step < 0 -> "$negative ${-step * CarPlayClusterDisplay.MARKER_STEP_PERCENT} %"
        else -> "$positive ${step * CarPlayClusterDisplay.MARKER_STEP_PERCENT} %"
    }

    /** 波次十 #660：集群音乐卡片设置（实验性，默认关闭）（作者：TRAE AI）。 */
    private fun clusterMusicControls(card: LinearLayout) {
        toggle(card, getString(R.string.settings_cluster_music), getString(R.string.settings_cluster_music_description),
            ClusterMusicSettings.enabled(this)) {
            ClusterMusicSettings.saveEnabled(this, it)
            render()
        }
        if (!ClusterMusicSettings.enabled(this)) return
        choice(card, getString(R.string.settings_cluster_music_mode), listOf(
            getString(R.string.settings_cluster_music_temporary), getString(R.string.settings_cluster_music_permanent)),
            if (ClusterMusicSettings.permanent(this)) 1 else 0, reconnects = false) {
            ClusterMusicSettings.savePermanent(this, it == 1)
        }
        listOf(
            Triple("x", R.string.settings_cluster_music_horizontal, ClusterMusicSettings.x(this)),
            Triple("y", R.string.settings_cluster_music_vertical, ClusterMusicSettings.y(this)),
            Triple("size", R.string.settings_cluster_music_size, ClusterMusicSettings.size(this)),
        ).forEach { (key, title, value) ->
            card.addView(overlaySliderRow(getString(title),
                (if (key == "size") 50..150 step 5 else 5..95 step 5).toList(), value) { "$it%" }.also {
                it.onSave = { percent -> ClusterMusicSettings.save(this, key, percent) }
                val head = it.getChildAt(0) as LinearLayout
                val value = head.getChildAt(head.childCount - 1)
                value.layoutParams = (value.layoutParams as LinearLayout.LayoutParams).apply {
                    marginStart = dp(SETTINGS_BLOCK_GAP_DP)
                }
            })
        }
    }

    private fun clusterSpeedometerControls(card: LinearLayout) {
        toggle(card, getString(R.string.settings_cluster_speedometer),
            getString(R.string.settings_cluster_speedometer_description), AirPlayPersistence.loadClusterSpeedometerEnabled(this)) {
            AirPlayPersistence.saveClusterSpeedometerEnabled(this, it)
            render()
        }
        if (AirPlayPersistence.loadClusterSpeedometerEnabled(this)) {
            card.addView(overlaySliderRow(
                getString(R.string.settings_cluster_speedometer_horizontal), ClusterSpeedometerOverlay.xPercents,
                AirPlayPersistence.loadClusterSpeedometerX(this),
            ) { value -> "$value %" }.also { row ->
                row.getChildAt(0).layoutDirection = android.text.TextUtils.getLayoutDirectionFromLocale(resources.configuration.locales[0])
                row.onSave = { value -> AirPlayPersistence.saveClusterSpeedometerX(this, value) }
            })
            card.addView(overlaySliderRow(
                getString(R.string.settings_cluster_speedometer_vertical), ClusterSpeedometerOverlay.yPercents,
                AirPlayPersistence.loadClusterSpeedometerY(this),
            ) { value -> "$value %" }.also { row ->
                row.getChildAt(0).layoutDirection = android.text.TextUtils.getLayoutDirectionFromLocale(resources.configuration.locales[0])
                row.onSave = { value -> AirPlayPersistence.saveClusterSpeedometerY(this, value) }
            })
            card.addView(overlaySliderRow(
                getString(R.string.settings_cluster_speedometer_size), ClusterSpeedometerOverlay.sizePercents,
                AirPlayPersistence.loadClusterSpeedometerSize(this),
            ) { value -> "$value %" }.also { row ->
                row.getChildAt(0).layoutDirection = android.text.TextUtils.getLayoutDirectionFromLocale(resources.configuration.locales[0])
                row.onSave = { value -> AirPlayPersistence.saveClusterSpeedometerSize(this, value) }
            })
            card.addView(overlaySliderRow(
                getString(R.string.settings_cluster_speedometer_opacity), ClusterSpeedometerOverlay.opacityPercents,
                AirPlayPersistence.loadClusterSpeedometerOpacity(this),
            ) { value -> "$value %" }.also { row ->
                row.getChildAt(0).layoutDirection = android.text.TextUtils.getLayoutDirectionFromLocale(resources.configuration.locales[0])
                row.onSave = { value -> AirPlayPersistence.saveClusterSpeedometerOpacity(this, value) }
            })
        }
    }

    /** A 2%-step slider row for overlay placement; every step saves, so the card moves live. */
    private fun overlaySliderRow(title: String, values: List<Int>, current: Int, describe: (Int) -> String): OverlaySliderRow =
        OverlaySliderRow(this, title, values, current, describe)

    private inner class OverlaySliderRow(
        context: android.content.Context,
        title: String,
        private val steps: List<Int>,
        current: Int,
        private val describe: (Int) -> String,
    ) : LinearLayout(context) {
        var onSave: (Int) -> Unit = {}
        // main #494：手指离开滑块时触发一次，用于需要重连的保存（作者：TRAE AI）。
        var onCommit: () -> Unit = {}
        val slider: SeekBar

        init {
            orientation = VERTICAL
            val valueView = label(describe(current), 16, ACCENT, true)
            val head = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, 0) }
            head.addView(label(title, 16, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(valueView)
            addView(head)
            slider = SeekBar(context).apply {
                max = steps.lastIndex
                progress = steps.indexOf(current).coerceIn(steps.indices)
                // main d729cd89：ProgressBar.setMinHeight 需要 API 29；低版本改用 View.minimumHeight 保证触控目标高度。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) minHeight = dp(44) else minimumHeight = dp(44)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val value = steps[progress.coerceIn(steps.indices)]
                        valueView.text = describe(value)
                        if (fromUser) onSave(value)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) { onCommit() }
                })
            }
            addView(slider, LinearLayout.LayoutParams(-1, dp(44)))
        }
    }

    private fun overlayOffsetLabel(percent: Int, negative: String, positive: String, centre: Int): String {
        val delta = percent - centre
        return when {
            delta == 0 -> getString(R.string.marker_centre_default)
            delta < 0 -> "$negative ${-delta} %"
            else -> "$positive $delta %"
        }
    }

    private fun showClusterAccessSetup() {
        val command = "adb shell appops set $packageName GET_USAGE_STATS allow"
        val body = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        body.addView(label(getString(R.string.one_time_setup_on_this_car), 20, TEXT, true))
        body.addView(label(getString(R.string.usage_access_lets_diplay_follow_the_instrument_theme_and_m), 15, MUTED))
        body.addView(label(getString(R.string.s_1_connect_a_computer_with_adb_installed_to_the_car_using), 16, TEXT))
        body.addView(label(command, 16, TEXT).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(16), 0, dp(16))
        })
        body.addView(button(getString(R.string.copy_command), false) {
            (getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(
                android.content.ClipData.newPlainText(getString(R.string.clipboard_usage_access), command))
            toast(getString(R.string.copied_to_the_car_clipboard_run_the_command_on_your_comput))
        }, matchButton(0, 56))
        body.addView(label(getString(R.string.cluster_adb_multi_device, packageName), 14, MUTED))
        body.addView(label(getString(R.string.s_3_tap_check_and_enable_below_this_enables_the_cluster_ma), 16, TEXT))
        val status = label(if (DiLink51ClusterMonitor.hasAccess(this)) getString(R.string.permission_enabled_ready) else getString(R.string.permission_not_enabled), 16, TEXT)
        body.addView(status)
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.automatic_cluster_map_setup))
            .setView(ScrollView(this).apply { addView(body) })
            .setNegativeButton(getString(R.string.close), null)
            .setPositiveButton(getString(R.string.check_and_enable), null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (DiLink51ClusterMonitor.hasAccess(this)) {
                    AirPlayPersistence.saveClusterMapEnabled(this, true)
                    DiLink51ClusterLayout.saveAutomatic(this, true)
                    dialog.dismiss()
                    render()
                    toast(getString(R.string.automatic_map_enabled_open_the_cluster_map_card_or_select))
                    reconnectIfRunning()
                } else {
                    status.text = getString(R.string.still_waiting_for_usage_access_check_that_the_command_ran)
                }
            }
        }
        dialog.show()
    }

    private fun promptEnableUsbAutoConfirm() {
        if (UsbPermissionSetup.Permission.ACCESSIBILITY.granted(this)) {
            toast(getString(R.string.usb_auto_confirm_status_on))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.usb_auto_confirm_title))
            .setMessage(getString(R.string.usb_auto_confirm_dialog_msg))
            .setPositiveButton(getString(R.string.btn_auto_apply_permissions)) { _, _ ->
                autoApplyPermissions()
            }
            .setNeutralButton(getString(R.string.btn_open_accessibility_setting)) { _, _ ->
                if (!UsbAutoConfirmService.openSettings(this)) {
                    toast(getString(R.string.wheel_keys_no_settings))
                }
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                render()
            }
            .setOnCancelListener {
                render()
            }
            .show()
    }

    private fun cancelUsbPermissionSetup() {
        val operation = usbPermissionOperation
        usbPermissionOperation = null
        operation?.cancel()
        val dialog = usbPermissionDialog
        usbPermissionDialog = null
        dialog?.dismiss()
    }

    private fun autoApplyPermissions() {
        if (usbPermissionOperation != null || isFinishing || isDestroyed) return
        val operation = usbPermissionOperationFactory(applicationContext)
        usbPermissionOperation = operation
        val progress = AlertDialog.Builder(this)
            .setTitle(R.string.auto_grant_title)
            .setMessage(R.string.auto_grant_msg)
            .setNegativeButton(R.string.cancel) { _, _ -> cancelUsbPermissionSetup() }
            .create()
        usbPermissionDialog = progress
        progress.setOnCancelListener { cancelUsbPermissionSetup() }
        progress.setOnDismissListener {
            if (usbPermissionDialog === progress) cancelUsbPermissionSetup()
        }
        progress.show()
        Thread({
            val result = operation.run()
            handler.post {
                if (usbPermissionOperation !== operation || operation.isCancelled || isFinishing || isDestroyed) {
                    return@post
                }
                usbPermissionOperation = null
                usbPermissionDialog = null
                progress.dismiss()
                render()
                if (result.complete) {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.auto_grant_success_title)
                        .setMessage(R.string.auto_grant_success_msg)
                        .setPositiveButton(R.string.close, null)
                        .show()
                } else {
                    val reason = when (result.access) {
                        LocalAdb.Access.NOT_APPROVED -> getString(R.string.auto_grant_confirm_msg)
                        LocalAdb.Access.UNSUPPORTED -> getString(R.string.adb_pairing_only)
                        else -> getString(R.string.auto_grant_incomplete)
                    }
                    showManualPermissionDialog(reason)
                }
            }
        }, "diplay-auto-permission").start()
    }

    private fun showManualPermissionDialog(reason: String) {
        val body = column().apply { setPadding(dp(20), dp(10), dp(20), dp(10)) }
        body.addView(label(reason, 15, MUTED).apply { setPadding(0, 0, 0, dp(12)) })

        val autoConfirmOn = UsbPermissionSetup.Permission.ACCESSIBILITY.granted(this)
        body.addView(button(if (autoConfirmOn) getString(R.string.usb_auto_confirm_status_on) else getString(R.string.btn_open_accessibility_setting), false) {
            if (!UsbAutoConfirmService.openSettings(this)) toast(getString(R.string.wheel_keys_no_settings))
        }, matchButton(0, 56))

        val overlayOn = CenterMapOverlay.permitted(this)
        body.addView(button(if (overlayOn) getString(R.string.center_map_overlay_allowed) else getString(R.string.btn_open_overlay_setting), false) {
            openOverlayPermission()
        }, matchButton(10, 56))

        UsbPermissionSetup.snapshot(this).forEach { (permission, granted) ->
            val title = getString(when (permission) {
                UsbPermissionSetup.Permission.ACCESSIBILITY -> R.string.usb_auto_confirm_title
                UsbPermissionSetup.Permission.USAGE -> R.string.center_map_auto_hide
                UsbPermissionSetup.Permission.OVERLAY -> R.string.btn_open_overlay_setting
            })
            body.addView(label("$title: ${getString(if (granted) R.string.permission_enabled_ready else R.string.permission_not_enabled)}", 14, if (granted) MUTED else WARNING))
        }
        val adbCmd = UsbPermissionSetup.manualCommand(packageName)
        body.addView(label(getString(R.string.manual_grant_cmd_hint), 14, MUTED).apply { setPadding(0, dp(12), 0, dp(6)) })
        body.addView(label(adbCmd, 13, TEXT).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(0x22FFFFFF)
        })
        body.addView(button(getString(R.string.copy_command), false) {
            (getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(
                android.content.ClipData.newPlainText("DiPlay ADB Command", adbCmd)
            )
            toast(getString(R.string.copied_to_the_car_clipboard_run_the_command_on_your_comput))
        }, matchButton(8, 50))

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.permissions_and_connection_help))
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton(getString(R.string.close)) { _, _ -> render() }
            .show()
    }

    /** main #344：可选的导航播报音量路由，复用既有方向盘无障碍服务与授权（作者：TRAE AI） */
    private fun navigationWheelControls(card: LinearLayout) {
        toggle(card, getString(R.string.settings_navigation_wheel_volume), getString(R.string.settings_navigation_wheel_volume_description),
            NavigationWheelSettings.enabled(this)) {
            NavigationWheelSettings.setEnabled(this, it)
            render()
        }
        if (!NavigationWheelSettings.enabled(this) || WheelKeyService.connected()) return
        card.addView(label(getString(R.string.settings_navigation_wheel_service_required), 14, WARNING))
        card.addView(button(getString(R.string.wheel_keys_open_settings), false) {
            if (!UsbAutoConfirmService.openSettings(this)) toast(getString(R.string.wheel_keys_no_settings))
        }, matchButton(10, 56))
        card.addView(button(getString(R.string.wheel_keys_enable_adb), false) {
            Thread({
                val access = WheelKeyService.enableOverAdb(this)
                runOnUiThread {
                    if (access != com.shilapi.xcertplay.adb.LocalAdb.Access.READY) {
                        toast(getString(R.string.wheel_keys_adb_failed, access.name))
                    }
                    render()
                }
            }, "diplay-navigation-wheel-enable").start()
        }, matchButton(10, 56))
    }

    /** Steering-wheel keys for the dashboard map zoom and the CarPlay joystick: the switches, the key service and the keys. */
    // One place for the key service setup, above every feature that needs it.
    private fun wheelKeysSettings(card: LinearLayout) {
        // The joystick and map zoom use BYD's media and custom keys.
        val byd = CarHotspotSetup.isBydHeadUnit(this)
        val zoomAvailable = wheelMapZoomAvailable()
        val vehicleKeysOn = WheelZoomSettings.joystick(this) ||
            (zoomAvailable && WheelZoomSettings.enabled(this))
        if (WheelZoomSettings.siriKey(this) || WheelZoomSettings.mediaPlayPause(this) || vehicleKeysOn) {
            wheelKeyServiceControls(card)
        }
        siriKeyControls(card)
        toggle(card, getString(R.string.wheel_media_play_pause),
            getString(R.string.wheel_media_play_pause_description),
            WheelZoomSettings.mediaPlayPause(this)) {
            WheelZoomSettings.setMediaPlayPause(this, it)
            render()
        }
        if (WheelZoomSettings.mediaPlayPause(this)) {
            wheelKeyAssignButton(card, WheelZoomSettings.Role.MEDIA_PLAY_PAUSE,
                getString(R.string.wheel_key_role_media_play_pause))
        }
        // main #750/#752（作者：TRAE AI）：该开关只作用于 BYD 的短语音键（327、304），其他车机从不发送。
        // 与下方控件一致：一旦开启就保持可见，防止包名检测漏判车机后无法关闭。
        if (byd || WheelZoomSettings.siriLongPressOnly(this)) {
            toggle(card, getString(R.string.wheel_siri_long_press_only), getString(R.string.wheel_siri_long_press_only_description),
                WheelZoomSettings.siriLongPressOnly(this)) { WheelZoomSettings.setSiriLongPressOnly(this, it) }
        }
        // main #549：任意车机——配对的键盘/遥控器/旋钮控制器与触屏并行工作（作者：TRAE AI）。
        toggle(card, getString(R.string.settings_external_controller), getString(R.string.settings_external_controller_description),
            AirPlayPersistence.loadExternalController(this)) { AirPlayPersistence.saveExternalController(this, it) }
        // Keep previously configured controls reachable even if package detection misses the car.
        if (byd || zoomAvailable || WheelZoomSettings.joystick(this)) wheelKeyControls(card)
    }

    // Map zoom only works where the dashboard map card used to show these controls.
    private fun wheelMapZoomAvailable(): Boolean {
        if (!AirPlayPersistence.loadClusterMapEnabled(this)) return false
        val adbCluster = AdbClusterRouter.enabled(this)
        if (DiLink51ClusterLayout.supported() && !adbCluster) return false
        return adbCluster || ClusterMapPresentation.findDisplay(this) != null
    }

    private fun wheelKeyControls(card: LinearLayout) {
        val zoomAvailable = wheelMapZoomAvailable()
        if (zoomAvailable) {
            toggle(card, getString(R.string.wheel_map_zoom), getString(R.string.wheel_map_zoom_description),
                WheelZoomSettings.enabled(this)) {
                WheelZoomSettings.setEnabled(this, it)
                render()
            }
        }
        toggle(card, getString(R.string.wheel_joystick), getString(R.string.wheel_joystick_description),
            WheelZoomSettings.joystick(this)) {
            WheelZoomSettings.setJoystick(this, it)
            render()
        }
        val zoom = zoomAvailable && WheelZoomSettings.enabled(this)
        val joystick = WheelZoomSettings.joystick(this)
        if (!zoom && !joystick) return
        if (zoom) {
            val behaviours = WheelZoomSettings.Behaviour.entries
            choice(card, getString(R.string.wheel_zoom_behaviour),
                listOf(getString(R.string.wheel_zoom_behaviour_toggle), getString(R.string.wheel_zoom_behaviour_timed)),
                behaviours.indexOf(WheelZoomSettings.behaviour(this)), reconnects = false) {
                WheelZoomSettings.setBehaviour(this, behaviours[it])
            }
        }
        if (joystick) {
            toggle(card, getString(R.string.wheel_joystick_auto_off), getString(R.string.wheel_joystick_auto_off_description),
                WheelZoomSettings.joystickAutoOff(this)) { WheelZoomSettings.setJoystickAutoOff(this, it) }
        }
        for (role in WheelZoomSettings.Role.entries) {
            // A key can serve both: the mode key goes back in the joystick, the zoom keys move it.
            val zoomName = when (role) {
                WheelZoomSettings.Role.MODE -> R.string.wheel_key_role_mode
                WheelZoomSettings.Role.ZOOM_IN -> R.string.wheel_key_role_zoom_in
                WheelZoomSettings.Role.ZOOM_OUT -> R.string.wheel_key_role_zoom_out
                else -> null
            }.takeIf { zoom }
            val joystickName = when (role) {
                WheelZoomSettings.Role.MODE -> R.string.wheel_key_role_back
                WheelZoomSettings.Role.ZOOM_IN, WheelZoomSettings.Role.PREVIOUS -> R.string.wheel_key_role_previous
                WheelZoomSettings.Role.ZOOM_OUT, WheelZoomSettings.Role.NEXT -> R.string.wheel_key_role_next
                WheelZoomSettings.Role.JOYSTICK -> R.string.wheel_key_role_joystick
                WheelZoomSettings.Role.SELECT -> R.string.wheel_key_role_select
                WheelZoomSettings.Role.SIRI -> null
                WheelZoomSettings.Role.MEDIA_PLAY_PAUSE -> null
            }.takeIf { joystick }
            val names = listOfNotNull(zoomName, joystickName)
            if (names.isEmpty()) continue
            wheelKeyAssignButton(card, role, names.joinToString(" · ") { getString(it) })
        }
    }

    /** A wheel key that opens Siri on any car whose key reaches the key service. */
    private fun siriKeyControls(card: LinearLayout) {
        toggle(card, getString(R.string.wheel_siri_key), getString(R.string.wheel_siri_key_description),
            WheelZoomSettings.siriKey(this)) {
            WheelZoomSettings.setSiriKey(this, it)
            render()
        }
        if (!WheelZoomSettings.siriKey(this)) return
        toggle(card, getString(R.string.wheel_siri_double_press),
            getString(R.string.wheel_siri_double_press_description),
            WheelZoomSettings.siriDoublePress(this)) {
            WheelZoomSettings.setSiriDoublePress(this, it)
            render()
        }
        if (WheelZoomSettings.siriDoublePress(this)) {
            val intervals = WheelZoomSettings.SIRI_DOUBLE_PRESS_INTERVALS
            choice(card, getString(R.string.wheel_siri_double_press_interval),
                intervals.map { getString(R.string.fps_counter_millis, it) },
                intervals.indexOf(WheelZoomSettings.siriDoublePressIntervalMillis(this)), reconnects = false) {
                WheelZoomSettings.setSiriDoublePressIntervalMillis(this, intervals[it])
            }
            val delays = WheelZoomSettings.SIRI_TRIGGER_DELAYS
            choice(card, getString(R.string.wheel_siri_trigger_delay),
                delays.map { getString(R.string.fps_counter_millis, it) },
                delays.indexOf(WheelZoomSettings.siriTriggerDelayMillis(this)), reconnects = false) {
                WheelZoomSettings.setSiriTriggerDelayMillis(this, delays[it])
            }
        }
        wheelKeyAssignButton(card, WheelZoomSettings.Role.SIRI, getString(R.string.wheel_key_role_siri))
    }

    private fun wheelKeyServiceControls(card: LinearLayout) {
        val connected = WheelKeyService.connected()
        card.addView(label(getString(when {
            connected -> R.string.wheel_keys_service_on
            WheelKeyService.enabledInSettings(this) -> R.string.wheel_keys_service_starting
            else -> R.string.wheel_keys_service_off
        }), 14, if (connected) MUTED else WARNING))
        if (!connected) {
            card.addView(actionButton(getString(R.string.wheel_keys_enable_adb), false) {
                Thread({
                    val access = WheelKeyService.enableOverAdb(this)
                    runOnUiThread {
                        if (access != com.shilapi.xcertplay.adb.LocalAdb.Access.READY) {
                            toast(getString(R.string.wheel_keys_adb_failed, access.name))
                        }
                        render()
                    }
                }, "diplay-wheel-keys-enable").start()
            }, matchButton(10, 56))
            card.addView(button(getString(R.string.wheel_keys_open_settings), false) {
                if (!UsbAutoConfirmService.openSettings(this)) toast(getString(R.string.wheel_keys_no_settings))
            }, matchButton(10, 56))
        }
    }

    private fun wheelKeyAssignButton(card: LinearLayout, role: WheelZoomSettings.Role, name: String) {
        fun current(key: WheelKey? = WheelZoomSettings.key(this, role)) =
            getString(R.string.wheel_key_assign, name, key?.toString() ?: getString(R.string.wheel_key_none))
        lateinit var assign: android.widget.Button
        assign = button(current(), false) {
            val cancelled = { runOnUiThread { assign.text = current() } }
            val refused = { taken: WheelZoomSettings.Role ->
                runOnUiThread {
                    assign.text = current()
                    toast(getString(R.string.wheel_key_in_use, getString(wheelKeyRoleName(taken))))
                }
            }
            val started = WheelKeyService.learn(role, cancelled, refused) { _, key -> runOnUiThread {
                assign.text = current(key)
                applyPendingAppearanceRender()
            } } ||
                // Without the service, keys that reach this window can still be learnt.
                ((role == WheelZoomSettings.Role.SIRI ||
                    role == WheelZoomSettings.Role.MEDIA_PLAY_PAUSE) && learnInWindow(role, cancelled, refused) {
                    assign.text = current(it)
                    applyPendingAppearanceRender()
                })
            if (started) assign.text = getString(R.string.wheel_key_press, name)
            else toast(getString(R.string.wheel_keys_service_off))
        }
        card.addView(assign, matchButton(10, 56))
    }

    private fun wheelKeyRoleName(role: WheelZoomSettings.Role): Int = when (role) {
        WheelZoomSettings.Role.MODE -> R.string.wheel_key_role_mode
        WheelZoomSettings.Role.ZOOM_IN -> R.string.wheel_key_role_zoom_in
        WheelZoomSettings.Role.ZOOM_OUT -> R.string.wheel_key_role_zoom_out
        WheelZoomSettings.Role.JOYSTICK -> R.string.wheel_key_role_joystick
        WheelZoomSettings.Role.PREVIOUS -> R.string.wheel_key_role_previous
        WheelZoomSettings.Role.NEXT -> R.string.wheel_key_role_next
        WheelZoomSettings.Role.SELECT -> R.string.wheel_key_role_select
        WheelZoomSettings.Role.SIRI -> R.string.wheel_key_role_siri
        WheelZoomSettings.Role.MEDIA_PLAY_PAUSE -> R.string.wheel_key_role_media_play_pause
    }

    private class WindowKeyLearning(
        val role: WheelZoomSettings.Role,
        val cancelled: () -> Unit,
        val refused: (WheelZoomSettings.Role) -> Unit,
        val done: (WheelKey) -> Unit,
    )

    private fun learnInWindow(role: WheelZoomSettings.Role, cancelled: () -> Unit,
        refused: (WheelZoomSettings.Role) -> Unit, done: (WheelKey) -> Unit): Boolean {
        cancelKeyLearning()
        windowLearning = WindowKeyLearning(role, cancelled, refused, done)
        handler.postDelayed(endWindowLearning, WheelKeyService.LEARNING_TIMEOUT_MILLIS)
        return true
    }

    private fun cancelKeyLearning() {
        WheelKeyService.cancelLearning()
        handler.removeCallbacks(endWindowLearning)
        windowLearning?.cancelled?.invoke()
        windowLearning = null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return super.dispatchKeyEvent(event)
        val action = windowLearningPresses.filter(
            Triple(event.deviceId, event.keyCode, event.scanCode),
            event.action == KeyEvent.ACTION_DOWN, event.repeatCount == 0,
        ) {
            val learning = windowLearning
            if (learning == null || event.keyCode == KeyEvent.KEYCODE_BACK) return@filter WheelZoomKeys.Action.PASS
            if (learning.role == WheelZoomSettings.Role.MEDIA_PLAY_PAUSE &&
                CarPlayRemoteKeys.isProtectedNavigationKey(event.keyCode)) {
                toast(getString(R.string.wheel_media_navigation_reserved))
                return@filter WheelZoomKeys.Action.CONSUME
            }
            val enabled = when (learning.role) {
                WheelZoomSettings.Role.SIRI -> WheelZoomSettings.siriKey(this)
                WheelZoomSettings.Role.MEDIA_PLAY_PAUSE -> WheelZoomSettings.mediaPlayPause(this)
                else -> false
            }
            if (inCall(this) || !enabled) {
                cancelKeyLearning()
                return@filter WheelZoomKeys.Action.PASS
            }
            handler.removeCallbacks(endWindowLearning)
            windowLearning = null
            val key = WheelKey.of(event)
            val taken = WheelZoomSettings.conflict(this, learning.role, key)
            if (taken != null) {
                Log.i(WheelKeyService.TAG, "${learning.role} key $key refused: it is the $taken key (learnt without the service)")
                learning.refused(taken)
            } else {
                WheelZoomSettings.assign(this, learning.role, key)
                Log.i(WheelKeyService.TAG, "${learning.role} key is now $key (learnt without the service)")
                learning.done(key)
            }
            WheelZoomKeys.Action.CONSUME
        }
        return action != WheelZoomKeys.Action.PASS || super.dispatchKeyEvent(event)
    }

    /** The 0.2.9 Dashboard song setting, shown once: in the BYD navigation card, or under Advanced vehicle data. */
    private fun clusterSongSwitch(card: LinearLayout) {
        toggle(card, getString(R.string.cluster_song),
            getString(R.string.cluster_song_description),
            BydOutputSettings.clusterSong(this), enabled = !adbSwitchChangePending) {
            BydOutputSettings.setClusterSong(this, it)
            if (it) checkAdbState(mayAsk = true)
            BydNavigationOutputs.clusterSongChanged(it)
        }
        toggle(card, getString(R.string.cluster_song_on_change), getString(R.string.cluster_song_on_change_description),
            BydOutputSettings.clusterSongOnChange(this), enabled = !adbSwitchChangePending) {
            BydOutputSettings.setClusterSongOnChange(this, it)
            BydNavigationOutputs.clusterSongOnChangeChanged()
        }
        toggle(card, getString(R.string.carplay_call_controls_experimental),
            getString(R.string.carplay_call_controls_experimental_description),
            BydOutputSettings.carPlayCallControls(this)) {
            // main B4：开关逻辑提取为 applyCallKeys，供连接向导复用（作者：TRAE AI）。
            applyCallKeys(it)
        }
        toggle(card, getString(R.string.carplay_calls_on_dashboard),
            getString(R.string.carplay_calls_on_dashboard_description),
            BydOutputSettings.carPlayCalls(this), enabled = !adbSwitchChangePending) {
            // main B4：开关逻辑提取为 applyCarPlayCalls，供连接向导复用（作者：TRAE AI）。
            applyCarPlayCalls(it)
        }
    }

    // main B4：通话按键开关——开启时经 ADB 拉起无障碍服务（作者：TRAE AI）。
    private fun applyCallKeys(enabled: Boolean) {
        BydOutputSettings.setCarPlayCallControls(this, enabled)
        if (enabled && !WheelKeyService.connected()) {
            Thread({
                val access = WheelKeyService.enableOverAdb(this)
                if (access != com.shilapi.xcertplay.adb.LocalAdb.Access.READY) {
                    runOnUiThread { toast(getString(R.string.wheel_keys_adb_failed, access.name)) }
                }
            }, "diplay-call-keys-enable").start()
        }
    }

    // main B4：仪表盘通话开关——开启时请求 ADB 状态并通知导航输出（作者：TRAE AI）。
    private fun applyCarPlayCalls(enabled: Boolean) {
        BydOutputSettings.setCarPlayCalls(this, enabled)
        if (enabled) checkAdbState(mayAsk = true)
        BydNavigationOutputs.carPlayCallsChanged(enabled)
    }

    private fun advancedVehicleData(card: LinearLayout) {
        val legacyMode = BydOutputSettings.legacyVehicleProbe(this)
        card.addView(label(getString(R.string.advanced_vehicle_data_description), 14, MUTED)
            .apply { setPadding(0, dp(12), 0, 0) })
        choice(
            card,
            getString(R.string.vehicle_data_mode),
            listOf(
                getString(R.string.vehicle_data_mode_default),
                getString(R.string.vehicle_data_mode_legacy),
            ),
            if (legacyMode) 1 else 0,
            reconnects = false,
            announcesReconnect = vehicleDataSwitchesOn(),
            // A switch during ADB work would be dropped by the running step.
            enabled = !adbSwitchChangePending && !vehicleAdbWorkInProgress(),
        ) { selectVehicleDataMode(it == 1) }
        if (!legacyMode) {
            defaultVehicleData(card)
            return
        }
        val capabilities = displayedVehicleCapabilities()
        val adbText = when {
            vehicleProbeAuthorizationInProgress -> getString(R.string.adb_checking_may_ask)
            adbCheckInProgress -> getString(if (adbCheckMayAsk)
                R.string.adb_checking_may_ask else R.string.adb_checking)
            vehicleProbeInProgress || automaticVehicleValidationInProgress ->
                getString(R.string.probing_vehicle_data)
            adbCheckFailed -> getString(R.string.adb_check_failed)
            adbAccessState == null && capabilities != null -> getString(R.string.vehicle_probe_saved_automatic)
            adbAccessState == null -> getString(R.string.adb_not_checked)
            else -> adbLinkStatusText(requireNotNull(adbAccessState))
        }
        val healthy = capabilities != null && !adbCheckFailed && vehicleProbeOutcome?.error == null &&
            pendingVehicleLostFields.isEmpty() &&
            (adbAccessState == null || adbAccessState == BydAdbAccess.State.READY)
        card.addView(label(adbText, 14, if (healthy) MUTED else WARNING)
            .apply { setPadding(0, dp(12), 0, 0) })
        if (capabilities != null) {
            showVehicleProbeResults(card, capabilities)
            showVehicleDataSettings(card, capabilities)
        }
        if (vehicleProbeAuthorizationInProgress || adbCheckInProgress ||
            vehicleProbeInProgress || automaticVehicleValidationInProgress) return
        vehicleProbeOutcome?.error?.let {
            card.addView(label(getString(R.string.vehicle_probe_failed, it), 14, WARNING)
                .apply { setPadding(0, dp(10), 0, 0) })
        }
        if (pendingVehicleLostFields.isNotEmpty()) {
            card.addView(label(
                getString(
                    R.string.vehicle_probe_lost_saved_fields,
                    localizedVehicleFields(pendingVehicleLostFields),
                ),
                14,
                WARNING,
            ).apply { setPadding(0, dp(10), 0, 0) })
            card.addView(button(getString(R.string.replace_saved_vehicle_data_anyway), false) {
                replaceSavedVehicleDataAnyway()
            }, matchButton(10, 56))
        }
        val needsUserAction = capabilities == null || adbCheckFailed || vehicleProbeOutcome?.error != null ||
            pendingVehicleLostFields.isNotEmpty() ||
            adbAccessState in setOf(BydAdbAccess.State.NOT_APPROVED, BydAdbAccess.State.ADB_OFF, BydAdbAccess.State.PAIRING_ONLY)
        if (needsUserAction) {
            val title = if (adbAccessState == BydAdbAccess.State.NOT_APPROVED) {
                R.string.request_adb_authorization
            } else if (capabilities == null) {
                R.string.probe_adb_and_vehicle_data
            } else {
                R.string.probe_vehicle_data_again
            }
            card.addView(button(getString(title), false) {
                probeVehicleData(mayAsk = true)
            }.apply { isEnabled = !adbSwitchChangePending && !adbCheckInProgress }, matchButton(10, 56))
        }
    }

    private fun defaultVehicleData(card: LinearLayout) {
        val adbText = when {
            vehicleProbeAuthorizationInProgress -> getString(R.string.adb_checking_may_ask)
            adbCheckInProgress -> getString(if (adbCheckMayAsk)
                R.string.adb_checking_may_ask else R.string.adb_checking)
            vehicleProbeInProgress -> getString(R.string.probing_vehicle_data)
            adbCheckFailed -> getString(R.string.adb_check_failed)
            adbAccessState == null -> getString(R.string.adb_not_checked)
            else -> adbLinkStatusText(requireNotNull(adbAccessState))
        }
        val healthy = !adbCheckFailed && vehicleProbeOutcome?.error == null &&
            (adbAccessState == null || adbAccessState == BydAdbAccess.State.READY)
        card.addView(label(adbText, 14, if (healthy) MUTED else WARNING)
            .apply { setPadding(0, dp(12), 0, 0) })
        val busy = vehicleProbeAuthorizationInProgress || adbCheckInProgress || vehicleProbeInProgress
        if (!busy && adbAccessState == BydAdbAccess.State.READY) {
            defaultVehicleStatus?.let { showDefaultVehicleReadings(card, it) }
        }
        vehicleProbeOutcome?.error?.let {
            card.addView(label(getString(R.string.vehicle_probe_failed, it), 14, WARNING)
                .apply { setPadding(0, dp(10), 0, 0) })
        }
        showVehicleDataSettings(card, capabilities = null)
        if (!busy) {
            card.addView(button(getString(R.string.check_adb_access), false) {
                checkAdbState(mayAsk = true)
            }.apply { isEnabled = !adbSwitchChangePending }, matchButton(10, 56))
        }
    }

    /** The last default-mode reads; a value an enabled switch needs is a warning when missing. */
    private fun showDefaultVehicleReadings(card: LinearLayout, status: BydAdbAccess.Status) {
        fun reading(value: String?, unreadable: Int, needed: Boolean) {
            val text = value ?: getString(unreadable).takeIf { needed } ?: return
            card.addView(label(text, 14, if (value != null) MUTED else WARNING))
        }
        val battery = status.batteryPercent?.let { percent ->
            status.rangeKm?.let { getString(R.string.adb_battery_reading, percent.roundToInt(), it) }
        }
        reading(battery, R.string.adb_battery_unreadable, BydOutputSettings.batteryToIphone(this))
        reading(status.speedKmh?.let { getString(R.string.adb_vehicle_speed_reading, it.roundToInt()) },
            R.string.adb_vehicle_speed_unreadable, BydOutputSettings.wheelSpeedToIphone(this))
        reading(status.gear?.let { getString(R.string.adb_vehicle_gear_reading, it.letter.toString()) },
            R.string.adb_vehicle_gear_unreadable,
            BydOutputSettings.wheelSpeedToIphone(this) || BydOutputSettings.videoWhileParked(this))
    }

    private fun selectVehicleDataMode(legacyMode: Boolean) {
        if (adbSwitchChangePending) return
        // A stale mode dialog must also invalidate a probe when the Boolean stays the same.
        synchronized(vehicleOperationLock) { vehicleProbeGeneration++ }
        vehicleProbeAuthorizationInProgress = false
        vehicleProbeInProgress = false
        if (!legacyMode) {
            cancelAutomaticVehicleValidationForUserOperation(resumeAfter = false)
            synchronized(vehicleOperationLock) {
                BydOutputSettings.setLegacyVehicleProbe(this, false)
            }
            vehicleProbeOutcome = null
            pendingVehicleReplacement = null
            pendingVehicleLostFields = emptySet()
            defaultVehicleStatus = null
            render()
            // The session was built from the legacy probe; apply once the default reads succeed.
            vehicleDataReconnectPending = vehicleDataSwitchesOn()
            if (vehicleDataReconnectPending) checkAdbState(mayAsk = true)
            return
        }
        if (BydVehicleFieldStore.load(this) == null) {
            probeVehicleData(mayAsk = true, activateLegacyModeOnSuccess = true)
            return
        }
        synchronized(vehicleOperationLock) {
            BydOutputSettings.setLegacyVehicleProbe(this, true)
        }
        vehicleProbeOutcome = null
        pendingVehicleReplacement = null
        pendingVehicleLostFields = emptySet()
        scheduleAutomaticVehicleValidation()
        render()
        // With every vehicle-data switch off, the mode changes nothing CarPlay was told.
        if (vehicleDataSwitchesOn()) reconnectForVehicleSetting()
    }

    // Probe results are saved before they are shown, so the saved snapshot is the newest without
    // trusting the clock.
    private fun displayedVehicleCapabilities(): BydVehicleCapabilities? =
        BydVehicleFieldStore.load(this) ?: vehicleProbeOutcome?.capabilities

    private fun localizedVehicleFields(fields: Set<BydVehicleField>): String = fields.map { field ->
        getString(when (field) {
            BydVehicleField.SPEED -> R.string.vehicle_field_speed
            BydVehicleField.GEAR -> R.string.vehicle_field_gear
            BydVehicleField.SOC, BydVehicleField.RANGE, BydVehicleField.REMAINING_KWH ->
                R.string.vehicle_field_battery
            BydVehicleField.BMS_STATE -> R.string.vehicle_field_charging
        })
    }.distinct().joinToString()

    private fun replaceSavedVehicleDataAnyway() {
        val candidate = pendingVehicleReplacement ?: return
        // Never over a snapshot saved since the comparison; a failed write is shown, not thrown.
        val error = runCatching {
            BydVehicleFieldStore.replaceAnyway(applicationContext, pendingVehicleReplacementExpected, candidate)
        }.fold(
            onSuccess = { replaced -> if (replaced) null else getString(R.string.vehicle_probe_snapshot_changed) },
            onFailure = { it.message ?: it.javaClass.simpleName },
        )
        Log.i(BYD_VEHICLE_TAG, "user replaced saved vehicle data error=${error ?: "none"}")
        pendingVehicleReplacement = null
        pendingVehicleLostFields = emptySet()
        vehicleProbeOutcome = error?.let { BydVehicleProbeOutcome(BydAdbAccess.State.READY, error = it) }
        render()
    }

    private fun showVehicleProbeResults(card: LinearLayout, capabilities: BydVehicleCapabilities) {
        val catalogFields = capabilities.fields.values.count { it.address?.source == BydFieldSource.FIRMWARE }
        card.addView(label(getString(if (capabilities.catalogAvailable)
            R.string.vehicle_probe_catalog_ready else R.string.vehicle_probe_catalog_fallback, catalogFields),
            14, if (capabilities.catalogAvailable) MUTED else WARNING).apply { setPadding(0, dp(14), 0, 0) })

        val speed = capabilities.result(BydVehicleField.SPEED)
        val speedValue = speed.value
        val speedText = when {
            speedValue != null -> getString(R.string.adb_vehicle_speed_reading, speedValue.roundToInt())
            speed.supported -> getString(R.string.vehicle_probe_cached_supported, getString(R.string.vehicle_field_speed))
            else -> getString(R.string.adb_vehicle_speed_unreadable)
        }
        card.addView(label(speedText, 14, if (speed.supported) MUTED else WARNING))
        val gear = capabilities.result(BydVehicleField.GEAR)
        val gearValue = gear.value
        val gearText = when {
            gearValue != null -> getString(R.string.adb_vehicle_gear_reading, gearLetter(gearValue.toInt()))
            gear.supported -> getString(R.string.vehicle_probe_cached_supported, getString(R.string.vehicle_field_gear))
            else -> getString(R.string.adb_vehicle_gear_unreadable)
        }
        card.addView(label(gearText, 14, if (gear.supported) MUTED else WARNING))

        if (capabilities.batterySupported) {
            val percentValue = capabilities.result(BydVehicleField.SOC).value
            val rangeValue = capabilities.result(BydVehicleField.RANGE).value
            val energy = capabilities.result(BydVehicleField.REMAINING_KWH).value
            card.addView(label(if (percentValue == null || rangeValue == null) {
                getString(R.string.vehicle_probe_cached_supported, getString(R.string.vehicle_field_battery))
            } else if (energy != null) {
                getString(R.string.vehicle_probe_battery_reading,
                    percentValue.roundToInt(), rangeValue.roundToInt(), energy)
            } else {
                getString(R.string.vehicle_probe_battery_without_energy,
                    percentValue.roundToInt(), rangeValue.roundToInt())
            }, 14, if (energy != null || percentValue == null) MUTED else WARNING))
        } else {
            card.addView(label(getString(R.string.adb_battery_unreadable), 14, WARNING))
        }
        val charging = capabilities.result(BydVehicleField.BMS_STATE)
        val chargingValue = charging.value
        val chargingText = when {
            chargingValue != null -> getString(R.string.vehicle_probe_charging_state, chargingValue.toInt())
            charging.supported -> getString(R.string.vehicle_probe_cached_supported, getString(R.string.vehicle_field_charging))
            else -> getString(R.string.vehicle_probe_charging_unreadable)
        }
        card.addView(label(chargingText, 14, if (charging.supported) MUTED else WARNING))
    }

    private fun showVehicleDataSettings(card: LinearLayout, capabilities: BydVehicleCapabilities?) {
        // main #307：CarPlay 期间延迟暂停车机蓝牙（实验性、需 ADB、默认关闭）。
        toggle(card, getString(R.string.bt_suspend_during_carplay), getString(R.string.bt_suspend_during_carplay_description),
            AirPlayPersistence.loadBtSuspendDuringCarplay(this), enabled = !adbSwitchChangePending) {
            AirPlayPersistence.saveBtSuspendDuringCarplay(this, it)
            markReconnectNeeded()
            if (it) checkAdbState(mayAsk = true)
        }
        if (AirPlayPersistence.loadBtSuspendDuringCarplay(this)) {
            val delays = listOf(5, 10, 15, 30)
            choice(card, getString(R.string.bt_suspend_delay), delays.map { getString(R.string.bt_suspend_delay_option, it) },
                delays.indexOf(AirPlayPersistence.loadBtSuspendDelaySeconds(this)).coerceAtLeast(0), reconnects = false) {
                AirPlayPersistence.saveBtSuspendDelaySeconds(this, delays[it])
                markReconnectNeeded()
            }
        }
        if (capabilities == null || capabilities.batterySupported) {
            toggle(card, getString(R.string.car_battery_for_the_iphone),
                getString(R.string.car_battery_for_the_iphone_description),
                BydOutputSettings.batteryToIphone(this), enabled = !adbSwitchChangePending) {
                BydOutputSettings.setBatteryToIphone(this, it)
                onVehicleDataSettingChanged(it)
            }
            val connectors = EvChargingConnectors.entries
            choice(card, getString(R.string.charging_connectors), connectors.map { it.localizedLabel(this) },
                connectors.indexOf(BydOutputSettings.chargingConnectors(this))) {
                BydOutputSettings.setChargingConnectors(this, connectors[it])
            }
            val lowCharge = BydOutputSettings.lowChargePresets
            choice(card, getString(R.string.low_charge_warning), lowCharge.map {
                    getString(if (it == BydOutputSettings.DEFAULT_LOW_CHARGE_PERCENT)
                        R.string.percent_default else R.string.percent_value, it)
                }, lowCharge.indexOf(BydOutputSettings.lowChargePercent(this)).coerceAtLeast(0), reconnects = false) {
                BydOutputSettings.setLowChargePercent(this, lowCharge[it])
            }
        }
        if (capabilities == null || capabilities.motionSupported) {
            toggle(card, getString(R.string.wheel_speed_for_tunnels),
                getString(R.string.wheel_speed_for_tunnels_description),
                BydOutputSettings.wheelSpeedToIphone(this), enabled = !adbSwitchChangePending) {
                BydOutputSettings.setWheelSpeedToIphone(this, it)
                onVehicleDataSettingChanged(it)
            }
        }
        if (capabilities == null || capabilities.gearSupported) {
            toggle(card, getString(R.string.video_while_parked),
                getString(R.string.video_while_parked_description),
                BydOutputSettings.videoWhileParked(this), enabled = !adbSwitchChangePending) {
                BydOutputSettings.setVideoWhileParked(this, it)
                onVehicleDataSettingChanged(it)
            }
        }
    }

    private fun onVehicleDataSettingChanged(enabled: Boolean) {
        if (!enabled) {
            reconnectForVehicleSetting()
        } else if (BydOutputSettings.legacyVehicleProbe(this)) {
            scheduleAutomaticVehicleValidation()
            reconnectForVehicleSetting()
        } else {
            // Default mode applies the switch once a check reads what the enabled switches need.
            vehicleDataReconnectPending = true
            checkAdbState(mayAsk = true)
        }
    }

    // The approval dialog can open only after an explicit user action.
    private fun checkAdbState(mayAsk: Boolean) {
        if (adbSwitchChangePending || vehicleAdbWorkInProgress()) return
        val legacy = BydOutputSettings.legacyVehicleProbe(this)
        // Only a validation this check interrupts runs again; a Dashboard switch needs no vehicle check.
        cancelAutomaticVehicleValidationForUserOperation(
            resumeAfter = automaticVehicleValidationInProgress || automaticVehicleValidationPending,
        )
        val generation = ++adbCheckGeneration
        adbCheckInProgress = true
        adbCheckMayAsk = mayAsk
        adbCheckFailed = false
        render()
        val backend = BydVehicleSettingsBackendProvider.current
        backend.execute("diplay-adb-state") {
            val result = runCatching {
                if (legacy) {
                    BydAdbAccess.Status(backend.checkState(applicationContext, mayAsk))
                } else {
                    // The default battery path must publish its first sample before CarPlay reconnects.
                    backend.check(applicationContext, mayAsk)
                }
            }
            runOnUiThread {
                val current = generation == adbCheckGeneration
                if (current) {
                    adbCheckInProgress = false
                    adbCheckMayAsk = false
                }
                if (!current || isFinishing || isDestroyed) return@runOnUiThread
                adbCheckFailed = result.isFailure
                val status = result.getOrNull()
                adbAccessState = status?.state
                if (!legacy) defaultVehicleStatus = status
                if (!legacy && adbAccessState == BydAdbAccess.State.READY) {
                    vehicleProbeOutcome = null
                }
                render()
                // main #724：手动点"检查 ADB 权限"后同时 toast 结果，状态行变化太隐蔽（作者：TRAE AI）。
                // A check the driver asked for also says its result where they look: the status line
                // above the button changed so quietly that the button seemed to do nothing (#724).
                if (mayAsk) toast(status?.state?.let(::adbLinkStatusText) ?: getString(R.string.adb_check_failed))
                // Unreadable data keeps the current connection; the page shows what is missing.
                if (!legacy && vehicleDataReconnectPending && status?.state == BydAdbAccess.State.READY &&
                    enabledVehicleDataReadable(null, status)) {
                    vehicleDataReconnectPending = false
                    reconnectForVehicleSetting()
                }
                runPendingAutomaticVehicleValidation()
            }
        }
    }

    /** First probe or user retry: one click handles ADB authorization, probing and persistence. */
    private fun probeVehicleData(mayAsk: Boolean) =
        probeVehicleData(mayAsk, activateLegacyModeOnSuccess = false)

    private fun probeVehicleData(mayAsk: Boolean, activateLegacyModeOnSuccess: Boolean) {
        if (adbSwitchChangePending || vehicleAdbWorkInProgress()) return
        val expectedLegacyMode = BydOutputSettings.legacyVehicleProbe(this)
        val expectedSnapshot = cancelAutomaticVehicleValidationForUserOperation(resumeAfter = false)
        val generation = ++vehicleProbeGeneration
        vehicleProbeAuthorizationInProgress = mayAsk
        vehicleProbeInProgress = !mayAsk
        adbCheckFailed = false
        pendingVehicleReplacement = null
        pendingVehicleLostFields = emptySet()
        Log.i(BYD_VEHICLE_TAG, "user vehicle probe starting mayAsk=$mayAsk")
        render()
        val backend = BydVehicleSettingsBackendProvider.current
        backend.execute("diplay-byd13-probe") {
            val app = applicationContext
            val attempt = runCatching {
                val access = backend.checkState(app, mayAsk)
                if (access != BydAdbAccess.State.READY) {
                    VehicleProbeAttempt(BydVehicleProbeOutcome(access))
                } else {
                    runOnUiThread {
                        if (generation == vehicleProbeGeneration && !isFinishing && !isDestroyed) {
                            vehicleProbeAuthorizationInProgress = false
                            vehicleProbeInProgress = true
                            render()
                        }
                    }
                    var candidate = backend.probe(app, persist = false)
                    if (candidate.access == BydAdbAccess.State.NOT_APPROVED) {
                        // adbd confirms "Always allow" before it saves the key, so this new connection can be early.
                        Log.i(BYD_VEHICLE_TAG, "probe connection not approved yet; retrying once")
                        Thread.sleep(ADB_KEY_SAVE_WAIT_MILLIS)
                        candidate = backend.probe(app, persist = false)
                    }
                    val candidateCapabilities = candidate.capabilities
                    when {
                        candidate.access == BydAdbAccess.State.NOT_APPROVED -> VehicleProbeAttempt(
                            outcome = candidate,
                            allowedOnlyOnce = true,
                        )
                        candidateCapabilities == null -> VehicleProbeAttempt(candidate)
                        else -> {
                            // Invalidation must guard persistence/publication, not only the UI callback.
                            val replacement = synchronized(vehicleOperationLock) {
                                if (generation != vehicleProbeGeneration ||
                                    BydOutputSettings.legacyVehicleProbe(app) != expectedLegacyMode) null
                                else BydVehicleFieldStore.replaceAutomatically(
                                    app,
                                    expectedSnapshot,
                                    candidateCapabilities,
                                )
                            } ?: return@runCatching VehicleProbeAttempt(
                                outcome = BydVehicleProbeOutcome(candidate.access),
                                snapshotChanged = true,
                            )
                            when {
                                replacement.saved -> VehicleProbeAttempt(candidate)
                                replacement.snapshotChanged -> VehicleProbeAttempt(
                                    outcome = candidate,
                                    snapshotChanged = true,
                                )
                                else -> VehicleProbeAttempt(
                                    outcome = BydVehicleProbeOutcome(candidate.access),
                                    heldCandidate = candidateCapabilities,
                                    lostFields = replacement.lostFields,
                                )
                            }
                        }
                    }
                }
            }.getOrElse { error ->
                VehicleProbeAttempt(
                    BydVehicleProbeOutcome(
                        BydAdbAccess.State.READY,
                        error = error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
            runOnUiThread {
                val current = generation == vehicleProbeGeneration
                if (current) {
                    vehicleProbeAuthorizationInProgress = false
                    vehicleProbeInProgress = false
                }
                if (!current || isFinishing || isDestroyed) return@runOnUiThread
                pendingVehicleReplacement = attempt.heldCandidate
                pendingVehicleReplacementExpected = expectedSnapshot
                pendingVehicleLostFields = attempt.lostFields
                vehicleProbeOutcome = when {
                    attempt.allowedOnlyOnce -> attempt.outcome.copy(
                        error = getString(R.string.vehicle_probe_allowed_once),
                    )
                    attempt.snapshotChanged -> attempt.outcome.copy(
                        capabilities = null,
                        error = getString(R.string.vehicle_probe_snapshot_changed),
                    )
                    else -> attempt.outcome
                }
                adbAccessState = attempt.outcome.access
                val activatedLegacyMode = activateLegacyModeOnSuccess &&
                    vehicleProbeOutcome?.capabilities != null
                if (activatedLegacyMode) {
                    BydOutputSettings.setLegacyVehicleProbe(this, true)
                }
                if (vehicleProbeOutcome?.capabilities != null) automaticVehicleValidationStarted = true
                Log.i(
                    BYD_VEHICLE_TAG,
                    "user vehicle probe access=${attempt.outcome.access} " +
                        "saved=${vehicleProbeOutcome?.capabilities != null} " +
                        "lost=${attempt.lostFields.joinToString()} " +
                        "error=${vehicleProbeOutcome?.error ?: "none"}",
                )
                render()
                if (activatedLegacyMode && vehicleDataSwitchesOn()) reconnectForVehicleSetting()
                runPendingAutomaticVehicleValidation()
            }
        }
    }

    /**
     * After the first saved probe, validation is automatic and never asks for authorization. ADB
     * transport failure keeps the saved snapshot; only two complete READY-but-unreadable checks
     * trigger one automatic field re-probe.
     */
    private fun validateSavedVehicleConfigurationAutomatically() {
        if (!BydOutputSettings.legacyVehicleProbe(this)) return
        val saved = BydVehicleFieldStore.load(applicationContext) ?: return
        if (adbSwitchChangePending || vehicleAdbWorkInProgress()) {
            automaticVehicleValidationPending = true
            return
        }
        if (automaticVehicleValidationInProgress) {
            // A request during a run, such as a switch just turned on, runs once this one ends.
            automaticVehicleValidationPending = true
            return
        }
        if (automaticVehicleValidationStarted) return
        automaticVehicleValidationStarted = true
        automaticVehicleValidationInProgress = true
        val generation = ++vehicleValidationGeneration
        Log.i(BYD_VEHICLE_TAG, "automatic vehicle validation starting savedFirmware=${saved.firmwareKey}")
        if (page == "settings" && bydVehicleAdvancedExpanded) render()
        val backend = BydVehicleSettingsBackendProvider.current
        backend.execute("diplay-byd13-auto-validate") {
            val app = applicationContext
            val validation = runCatching {
                var status = backend.check(app, mayAsk = false)
                var outcome: BydVehicleProbeOutcome? = null
                var heldCandidate: BydVehicleCapabilities? = null
                var lostFields: Set<BydVehicleField> = emptySet()
                var snapshotChanged = false
                var readable = status.state == BydAdbAccess.State.READY && enabledVehicleDataReadable(saved, status)
                if (status.state == BydAdbAccess.State.READY && !enabledVehicleDataReadable(saved, status)) {
                    Log.w(BYD_VEHICLE_TAG, "saved vehicle fields unreadable; validating once more")
                    try {
                        Thread.sleep(VEHICLE_VALIDATION_RETRY_MILLIS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    // A cancelled validation reads nothing more alongside the operation that replaced it.
                    if (generation != vehicleValidationGeneration) {
                        return@runCatching VehicleValidationAttempt(
                            status = status,
                            snapshotChanged = true,
                        )
                    }
                    status = backend.check(app, mayAsk = false)
                    readable = status.state == BydAdbAccess.State.READY && enabledVehicleDataReadable(saved, status)
                    if (status.state == BydAdbAccess.State.READY && !readable) {
                        if (generation != vehicleValidationGeneration) {
                            return@runCatching VehicleValidationAttempt(
                                status = status,
                                snapshotChanged = true,
                            )
                        }
                        Log.w(BYD_VEHICLE_TAG, "saved vehicle fields still unreadable; automatic re-probe starting")
                        val candidate = backend.probe(app, persist = false)
                        if (generation != vehicleValidationGeneration) {
                            return@runCatching VehicleValidationAttempt(
                                status = status,
                                snapshotChanged = true,
                            )
                        }
                        outcome = candidate
                        candidate.capabilities?.let { next ->
                            val replacement = synchronized(vehicleOperationLock) {
                                if (generation != vehicleValidationGeneration) null
                                else BydVehicleFieldStore.replaceAutomatically(app, saved, next)
                            } ?: return@runCatching VehicleValidationAttempt(
                                status = status,
                                snapshotChanged = true,
                            )
                            when {
                                replacement.saved -> Unit
                                replacement.snapshotChanged -> {
                                    snapshotChanged = true
                                    outcome = null
                                }
                                else -> {
                                    heldCandidate = next
                                    lostFields = replacement.lostFields
                                    outcome = BydVehicleProbeOutcome(candidate.access)
                                }
                            }
                        }
                    }
                }
                VehicleValidationAttempt(
                    status = status,
                    outcome = outcome,
                    heldCandidate = heldCandidate,
                    lostFields = lostFields,
                    snapshotChanged = snapshotChanged,
                    savedFieldsReadable = readable,
                )
            }
            runOnUiThread {
                val current = generation == vehicleValidationGeneration
                if (current) automaticVehicleValidationInProgress = false
                if (!current || isFinishing || isDestroyed) return@runOnUiThread
                adbCheckFailed = validation.isFailure
                validation.exceptionOrNull()?.let { Log.w(BYD_VEHICLE_TAG, "automatic vehicle validation failed", it) }
                validation.getOrNull()?.let { result ->
                    adbAccessState = result.outcome?.access ?: result.status.state
                    when {
                        result.snapshotChanged -> Unit
                        result.savedFieldsReadable && result.outcome == null -> {
                            // An open offer to replace stays: this check may not have read its lost fields.
                            vehicleProbeOutcome = null
                        }
                        result.lostFields.isNotEmpty() -> {
                            vehicleProbeOutcome = result.outcome
                            pendingVehicleReplacement = result.heldCandidate
                            pendingVehicleReplacementExpected = saved
                            pendingVehicleLostFields = result.lostFields
                        }
                        result.outcome != null -> {
                            vehicleProbeOutcome = result.outcome
                            pendingVehicleReplacement = null
                            pendingVehicleLostFields = emptySet()
                        }
                    }
                    Log.i(
                        BYD_VEHICLE_TAG,
                        "automatic vehicle validation access=${adbAccessState} " +
                            "reprobed=${result.outcome != null} " +
                            "saved=${result.outcome?.capabilities != null && result.lostFields.isEmpty()} " +
                            "lost=${result.lostFields.joinToString()} " +
                            "snapshotChanged=${result.snapshotChanged} " +
                            "error=${result.outcome?.error ?: "none"}",
                    )
                }
                if (page == "settings" && bydVehicleAdvancedExpanded) {
                    render()
                }
                runPendingAutomaticVehicleValidation()
            }
        }
    }

    private fun scheduleAutomaticVehicleValidation() {
        if (!BydOutputSettings.legacyVehicleProbe(this)) {
            automaticVehicleValidationPending = false
            handler.removeCallbacks(automaticVehicleValidation)
            return
        }
        automaticVehicleValidationStarted = false
        automaticVehicleValidationPending = true
        handler.removeCallbacks(automaticVehicleValidation)
        handler.post(automaticVehicleValidation)
    }

    private fun cancelAutomaticVehicleValidationForUserOperation(
        resumeAfter: Boolean,
    ): BydVehicleCapabilities? = synchronized(vehicleOperationLock) {
        handler.removeCallbacks(automaticVehicleValidation)
        automaticVehicleValidationPending = resumeAfter
        if (resumeAfter) automaticVehicleValidationStarted = false
        vehicleValidationGeneration++
        automaticVehicleValidationInProgress = false
        BydVehicleFieldStore.load(applicationContext)
    }

    private fun runPendingAutomaticVehicleValidation() {
        if (!automaticVehicleValidationPending || adbSwitchChangePending ||
            vehicleAdbWorkInProgress() || automaticVehicleValidationInProgress) return
        handler.removeCallbacks(automaticVehicleValidation)
        handler.post(automaticVehicleValidation)
    }

    /** Whether [status] has every reading an enabled switch needs; null [capabilities] is default mode. */
    private fun enabledVehicleDataReadable(
        capabilities: BydVehicleCapabilities?,
        status: BydAdbAccess.Status,
    ): Boolean {
        if (BydOutputSettings.batteryToIphone(this) && capabilities?.batterySupported != false &&
            (status.batteryPercent == null || status.rangeKm == null)) return false
        if (BydOutputSettings.wheelSpeedToIphone(this) && capabilities?.motionSupported != false &&
            (status.speedKmh == null || status.gear == null)) return false
        if (BydOutputSettings.videoWhileParked(this) && capabilities?.gearSupported != false && status.gear == null) return false
        return true
    }

    private fun adbLinkStatusText(state: BydAdbAccess.State): String = when (state) {
        BydAdbAccess.State.READY -> getString(R.string.adb_access_ready)
        BydAdbAccess.State.NOT_APPROVED -> getString(R.string.adb_enabled_not_approved)
        BydAdbAccess.State.ADB_OFF -> getString(R.string.adb_off)
        BydAdbAccess.State.PAIRING_ONLY -> getString(R.string.adb_pairing_only)
    }

    private fun gearLetter(value: Int): String = when (value) {
        1 -> "P"
        2 -> "R"
        3 -> "N"
        else -> "D"
    }

    private fun reconnectForVehicleSetting() {
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun vehicleDataSwitchesOn() = BydOutputSettings.batteryToIphone(this) ||
        BydOutputSettings.wheelSpeedToIphone(this) || BydOutputSettings.videoWhileParked(this)

    private fun hasPreciseLocation() =
        androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // The cluster screen is described at connection time, so a running session reconnects over
    // its current link. The position choices need no call: getString(R.string.apply_and_reconnect) already does it.
    private fun clusterSafeAreaControls(card: LinearLayout) {
        val rect = AirPlayPersistence.loadClusterSafeAreaRect(this)
            ?: DiLink4ClusterDisplay.defaultSafeAreaRect(
                markerXPercent = AirPlayPersistence.loadClusterMarkerXPercent(this),
                markerYPercent = AirPlayPersistence.loadClusterMarkerYPercent(this))
        card.addView(label(getString(R.string.safe_area_mapping_summary,
            rect.width, rect.height, rect.left, rect.top, 1920, 720), 14, MUTED))
        card.addView(actionButton(getString(R.string.cluster_safe_area_edit), false) {
            openClusterSafeAreaEditor()
        }, matchButton(10, 56))
        card.addView(button(getString(R.string.cluster_safe_area_reset), false) {
            AirPlayPersistence.clearClusterSafeAreaRect(this)
            render()
            markReconnectNeeded()
        }, matchButton(10, 56))
        card.addView(label(getString(R.string.cluster_safe_area_hint), 14, MUTED))
    }

    private fun openClusterSafeAreaEditor() {
        clusterSafeAreaDialog?.dismiss()
        val previewOwner = Any()
        val initial = AirPlayPersistence.loadClusterSafeAreaRect(this)
            ?: DiLink4ClusterDisplay.defaultSafeAreaRect(
                markerXPercent = AirPlayPersistence.loadClusterMarkerXPercent(this),
                markerYPercent = AirPlayPersistence.loadClusterMarkerYPercent(this))
        val editor = SafeAreaEditorView(this).apply {
            setBackgroundColor(Color.rgb(35, 39, 45))
            setRect(initial, 1920, 720)
        }
        // A standalone dialog gives the weighted preview an exact available height.
        // AlertDialog's wrap-content custom panel can collapse it to zero.
        val dialog = Dialog(this).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val panel = column().apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(Color.rgb(35, 39, 45))
        }
        panel.addView(label(getString(R.string.cluster_safe_area_edit), 18, Color.WHITE, true))
        panel.addView(label(getString(R.string.cluster_safe_area_live_hint), 14, MUTED))
        panel.addView(ClusterSafeAreaPreviewFrame(this, editor), LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = row()
        actions.addView(button(getString(R.string.cancel), false) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, dp(56), 1f))
        actions.addView(button(getString(if (CarPlayBackgroundSession.hasSession())
            R.string.apply_and_reconnect else R.string.save), true) {
            editor.currentRectForSource()?.let { AirPlayPersistence.saveClusterSafeAreaRect(this, it) }
            dialog.dismiss()
            render()
            reconnectIfRunning()
        }, LinearLayout.LayoutParams(0, dp(56), 1f))
        panel.addView(actions)
        dialog.setContentView(panel, ViewGroup.LayoutParams(-1, -1))
        editor.onRectChanged = { ClusterActivityOutput.updateSafeAreaPreview(previewOwner, it) }
        clusterSafeAreaDialog = dialog
        dialog.setOnDismissListener {
            editor.onRectChanged = null
            ClusterActivityOutput.endSafeAreaPreview(previewOwner)
            if (clusterSafeAreaDialog === dialog) clusterSafeAreaDialog = null
        }
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.9f).toInt(),
            (resources.displayMetrics.heightPixels * 0.85f).toInt())
        ClusterActivityOutput.beginSafeAreaPreview(previewOwner, initial, this)
    }

    private fun reconnectIfRunning() {
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    // Saved without dropping CarPlay; the reconnect bar lets the driver choose when.
    private fun markReconnectNeeded() {
        CarPlayBackgroundSession.snapshot()?.controller?.let(PendingReconnect::mark)
        refreshReconnectBar()
    }

    private fun refreshReconnectBar() {
        reconnectBar?.visibility =
            if (PendingReconnect.isPending(CarPlayBackgroundSession.snapshot()?.controller)) View.VISIBLE else View.GONE
    }

    private fun reconnectBar(): View = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(RAIL_SELECTED, RAIL_SELECTED_BORDER)
        setPadding(dp(20), dp(10), dp(12), dp(10))
        addView(label(getString(R.string.settings_reconnect_pending), 16, TEXT), LinearLayout.LayoutParams(0, -2, 1f))
        addView(button(getString(R.string.settings_reconnect_now), true) {
            // No clear(): if connect() stops early (hotspot off, a permission prompt), the change is still pending.
            reconnectIfRunning()
        }, LinearLayout.LayoutParams(-2, dp(52)).apply { marginStart = dp(12) })
        reconnectBar = this
        refreshReconnectBar()
    }

    private fun onLocationReportingChanged(enabled: Boolean) {
        if (enabled && !hasPreciseLocation()) {
            // Keep the switch off until precise location is actually granted.
            render()
            locationPermission.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ))
            return
        }
        applyLocationReporting(enabled)
    }

    private fun applyLocationReporting(enabled: Boolean) {
        if (AirPlayPersistence.loadLocationReportingEnabled(this) == enabled) return
        AirPlayPersistence.saveLocationReportingEnabled(this, enabled)
        render()
        // Location support is advertised during iAP2 identification, so both enabling and
        // disabling it require a new session. The host also refreshes its location service type.
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
        else toast(getString(R.string.saved_for_your_next_connection))
    }

    private fun applyWirelessLink(mode: WirelessHotspotMode) {
        startupHotspotCancelled = true
        AirPlayPersistence.saveWirelessHotspotMode(this, mode)
        render()
        toast(getString(R.string.saved_for_your_next_connection))
    }

    private fun textInput(title: String, current: String, secret: Boolean, save: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(current)
            setSingleLine()
            inputType = if (secret) {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                android.text.InputType.TYPE_CLASS_TEXT
            }
        }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ -> save(input.text.toString().let { if (secret) it else it.trim() }) }
            .setNegativeButton(getString(R.string.cancel), null).show()
    }

    private fun resolutionSettingControl(
        parent: LinearLayout,
        titleId: Int,
        hintId: Int,
        range: IntRange,
        default: Int,
        summaryId: Int,
        load: () -> Int,
        reconnects: Boolean = false,
        save: (Int) -> Unit,
    ) {
        val title = getString(titleId)
        fun summary() = getString(R.string.contrib_audio_home_choice_summary, title, getString(summaryId, load()))
        val control = button(summary(), false) {}
        control.setOnClickListener {
            val fields = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
            val input = EditText(this).apply {
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(load().toString())
            }
            fields.addView(input)
            fields.addView(label(getString(hintId), 14, MUTED))
            val dialog = AlertDialog.Builder(this).setTitle(title).setView(fields)
                .setPositiveButton(getString(if (reconnects && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save), null)
                .setNegativeButton(getString(R.string.cancel), null)
                .setNeutralButton(getString(R.string.resolution_reset_defaults), null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val value = input.text.toString().trim().toIntOrNull()
                    if (value == null || value !in range) {
                        input.error = getString(R.string.resolution_number_error, range.first, range.last)
                    } else {
                        val changed = value != load()
                        save(value)
                        control.text = summary()
                        dialog.dismiss()
                        if (changed && reconnects && CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    input.setText(default.toString())
                    input.error = null
                }
            }
            showResolutionSettingsDialog(dialog, fields)
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(space(12))
    }

    private fun showResolutionSettingsDialog(dialog: AlertDialog, fields: LinearLayout) {
        dialog.show()
        // AlertDialog replaces the custom view's parameters with MATCH_PARENT. Keep numeric
        // content at its natural height, including on vendor dialog layouts with weighted panels.
        fields.layoutParams = fields.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
        val decor = dialog.window?.decorView ?: return
        var panel = fields.parent as? ViewGroup
        while (panel != null && panel !== decor) {
            val params = panel.layoutParams
            if (params is LinearLayout.LayoutParams && params.weight > 0f) {
                panel.layoutParams = params.apply {
                    weight = 0f
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                break
            }
            panel = panel.parent as? ViewGroup
        }
        dialog.window?.let { window ->
            window.setLayout(window.attributes.width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // Run another traversal after the platform has finished its initial button measurement.
        decor.post { if (dialog.isShowing) decor.requestLayout() }
    }

    private fun nightDelaySettingControl(
        parent: LinearLayout,
        titleId: Int,
        hintId: Int,
        range: IntRange,
        default: Int,
        summaryId: Int,
        load: () -> Int,
        reconnects: Boolean = false,
        save: (Int) -> Unit,
    ) {
        val title = getString(titleId)
        fun summary() = getString(R.string.contrib_audio_home_choice_summary, title, getString(summaryId, load()))
        val control = button(summary(), false) {}
        control.setOnClickListener {
            val fields = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
            val input = EditText(this).apply {
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(load().toString())
            }
            fields.addView(input)
            fields.addView(label(getString(hintId), 14, MUTED))
            val dialog = AlertDialog.Builder(this).setTitle(title).setView(fields)
                .setPositiveButton(getString(if (reconnects && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save), null)
                .setNegativeButton(getString(R.string.cancel), null)
                .setNeutralButton(getString(R.string.ambient_light_reset_defaults), null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val value = input.text.toString().trim().toIntOrNull()
                    if (value == null || value !in range) {
                        input.error = getString(R.string.custom_number_error, range.first, range.last)
                    } else {
                        val changed = value != load()
                        save(value)
                        control.text = summary()
                        dialog.dismiss()
                        if (changed && reconnects && CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    input.setText(default.toString())
                    input.error = null
                }
            }
            showNightModeSettingsDialog(dialog, fields)
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(space(12))
    }

    private fun ambientLightThresholdControl(parent: LinearLayout) {
        val title = getString(R.string.ambient_light_threshold_title)
        fun summary(): String = getString(
            R.string.contrib_audio_home_choice_summary,
            title,
            getString(R.string.ambient_light_threshold_summary, AirPlayPersistence.loadAmbientLightThreshold(this).lux),
        )

        val control = button(summary(), false) {}
        control.setOnClickListener {
            val fields = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
            // main bc1c6001：对话框标题已含字段含义，去掉冗余的"切换门槛 (lux)"行（作者：TRAE AI）。
            val input = EditText(this).apply {
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(AirPlayPersistence.loadAmbientLightThreshold(this@DiPlayActivity).lux.toString())
            }
            fields.addView(input)
            fields.addView(label(getString(R.string.ambient_light_threshold_hint), 14, MUTED))
            val dialog = AlertDialog.Builder(this)
                .setTitle(title)
                .setView(fields)
                .setPositiveButton(getString(R.string.save), null)
                .setNegativeButton(getString(R.string.cancel), null)
                .setNeutralButton(getString(R.string.ambient_light_reset_defaults), null)
                .create()

            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener saveThreshold@{
                    val lux = input.text.toString().trim().toIntOrNull()
                    if (lux == null || !AmbientLightThreshold.isValid(lux)) {
                        input.error = getString(R.string.ambient_light_threshold_error)
                        return@saveThreshold
                    }
                    AirPlayPersistence.saveAmbientLightThreshold(this, AmbientLightThreshold(lux))
                    control.text = summary()
                    dialog.dismiss()
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    input.setText(AmbientLightThreshold.DEFAULT_LUX.toString())
                    input.error = null
                }
            }
            showNightModeSettingsDialog(dialog, fields)
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(space(12))
    }

    private fun showNightModeSettingsDialog(dialog: AlertDialog, fields: LinearLayout) {
        dialog.show()
        // AlertDialog replaces the custom view's parameters with MATCH_PARENT. Keep numeric
        // content at its natural height, including on vendor dialog layouts with weighted panels.
        fields.layoutParams = fields.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
        val decor = dialog.window?.decorView ?: return
        var panel = fields.parent as? ViewGroup
        while (panel != null && panel !== decor) {
            val params = panel.layoutParams
            if (params is LinearLayout.LayoutParams && params.weight > 0f) {
                panel.layoutParams = params.apply {
                    weight = 0f
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                break
            }
            panel = panel.parent as? ViewGroup
        }
        dialog.window?.let { window ->
            window.setLayout(window.attributes.width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // Run another traversal after the platform has finished its initial button measurement.
        decor.post { if (dialog.isShowing) decor.requestLayout() }
    }

    /** Where CarPlay puts its dock; between the two fixed edges it moves at once, otherwise on reconnect. */
    private fun carPlayDockControl(card: LinearLayout) {
        val docks = CarPlayDock.entries
        choice(card, getString(R.string.carplay_dock), listOf(
            getString(R.string.carplay_dock_automatic),
            getString(R.string.carplay_dock_driver_side),
            getString(R.string.carplay_dock_bottom),
        ), docks.indexOf(CarPlayDock.load(this)), reconnects = false) {
            val from = CarPlayDock.load(this)
            val to = docks[it]
            CarPlayDock.save(this, to)
            val session = CarPlayBackgroundSession.snapshot()
            val areas = session?.display?.viewAreas
            val target = to.edge?.let { areas?.withDock(it) }
            if (CarPlayDock.movesLive(from, to) && areas != null && target != null) {
                if (session.controller.showViewArea(target)) areas.use(target)
            } else {
                markReconnectNeeded()
            }
        }
        card.addView(label(getString(R.string.carplay_dock_hint), 14, MUTED).apply { setPadding(0, 0, 0, dp(18)) })
    }

    private fun carButtonControls(parent: LinearLayout) {
        val custom = AirPlayPersistence.loadCustomAirPlayIconFile(this)?.let { BitmapFactory.decodeFile(it.absolutePath) }
        val preview = row().apply { gravity = Gravity.CENTER_VERTICAL }
        preview.addView(ImageView(this).apply {
            setImageBitmap(custom ?: BitmapFactory.decodeResource(resources, R.raw.ic_car_home))
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(SURFACE, BORDER)
            UiCompat.setClipToOutline(this, true)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginEnd = dp(16) })
        val text = column()
        text.addView(label(getString(R.string.car_button_icon), 18, TEXT, true))
        text.addView(label(getString(if (custom != null) R.string.car_button_icon_custom else R.string.default_icon), 14, MUTED).apply { setPadding(0, dp(6), 0, 0) })
        preview.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(preview)
        parent.addView(button(getString(R.string.choose_image), false) {
            launchCarButtonImagePicker(
                openDocument = { iconDocumentPicker.launch(arrayOf("image/*")) },
                getContent = { iconPicker.launch("image/*") },
                documentPickerIsSystem = documentPickerIsSystem(),
            ).onFailure { toast(getString(R.string.this_head_unit_has_no_image_picker)) }
        }, matchButton(16, 60))
        if (custom != null) parent.addView(button(getString(R.string.default_icon), false) {
            AirPlayPersistence.clearCustomAirPlayIcon(this)
            refreshCarButton()
            carButtonSaved()
        }, matchButton(10, 60))
        val name = AirPlayPersistence.loadOemLabel(this)
        parent.addView(button("${getString(R.string.car_button_name)} · $name", false) {
            textInput(getString(R.string.car_button_name), name, secret = false) {
                AirPlayPersistence.saveOemLabel(this, it)
                refreshCarButton()
                carButtonSaved()
            }
        }, matchButton(10, 60))
        parent.addView(label(getString(R.string.car_button_description), 14, MUTED).apply { setPadding(0, dp(12), 0, 0) })
    }

    private fun carButtonSaved() {
        markReconnectNeeded()
        if (CarPlayBackgroundSession.hasSession()) toast(getString(R.string.car_button_saved_next_connection))
    }

    // Rebuilds only this card: render() would scroll the page back to the top.
    private fun refreshCarButton() {
        val card = carButtonCard ?: return
        card.removeViews(1, card.childCount - 1)
        carButtonControls(card)
        normalizeSpacing(card)
    }

    private fun carPlaySizeControl(parent: LinearLayout) {
        val sizes = com.shilapi.xcertplay.airplay.CarPlaySize.entries
        val current = com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(this))
        choice(parent, getString(R.string.carplay_size), sizes.map { it.localizedLabel(this) }, sizes.indexOf(current)) {
            AirPlayPersistence.saveWidthPhysicalMm(this, sizes[it].widthMillimeters)
        }
        parent.addView(label(getString(R.string.changes_the_size_of_carplay_icons_and_text_applying_a_size), 14, MUTED).apply {
            setPadding(0, 0, 0, dp(18))
        })
    }

    private fun scheduleTimeControl(parent: LinearLayout, start: Boolean) {
        val title = getString(if (start) R.string.carplay_night_start else R.string.carplay_night_end)
        fun labelFor(minute: Int) = "$title · ${String.format(Locale.getDefault(), "%02d:%02d", minute / 60, minute % 60)}"
        val initial = AirPlayPersistence.loadCarPlayNightSchedule(this)
        val control = button(labelFor(if (start) initial.startMinute else initial.endMinute), false) {}
        control.setOnClickListener {
            val current = AirPlayPersistence.loadCarPlayNightSchedule(this)
            val selected = if (start) current.startMinute else current.endMinute
            TimePickerDialog(this, { _, hour, minute ->
                val updatedMinute = hour * 60 + minute
                val other = if (start) current.endMinute else current.startMinute
                if (updatedMinute == other) {
                    toast(getString(R.string.carplay_night_schedule_same_time))
                } else {
                    val updated = if (start) current.copy(startMinute = updatedMinute)
                        else current.copy(endMinute = updatedMinute)
                    AirPlayPersistence.saveCarPlayNightSchedule(this, updated)
                    control.text = labelFor(updatedMinute)
                }
            }, selected / 60, selected % 60, true).show()
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(space(12))
    }

    private fun connect(wireless: Boolean) {
        startupHotspotCancelled = true
        if (wireless && pendingCarHotspotSetup) { toast(getString(R.string.save_your_hotspot_details_in_connection_setup_first)); page = "connection"; render(); return }
        if (setupError != null) { toast(setupError!!); return }
        if (wireless && AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            hotspotError(storedSsid(), storedPassword()) != null) {
            pendingCarHotspotSetup = true
            page = "connection"
            render()
            toast(getString(R.string.save_the_name_and_password_from_the_car_s_hotspot_settings))
            return
        }
        if (wireless && carHotspotOff()) { carHotspotOffDialog(); return }
        if (wireless && DiPlayPreferences.phoneAddress(this) == null) {
            // 无蓝牙引导无线（实验性③）：双蓝牙架构下 Android 适配器无配对，蓝牙选设备无意义；
            // 放宽入口，不再强制 choosePhone，直接放行由 runWireless 的无蓝牙引导分支绕开蓝牙。
            if (AirPlayPersistence.loadWirelessNoBluetoothBootstrap(this)) {
                pendingWireless = true
            } else {
                pendingWireless = true; choosePhone(); return
            }
        }
        val preferences = getSharedPreferences("diplay", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !preferences.getBoolean("notification_asked", false)) {
            preferences.edit().putBoolean("notification_asked", true).apply()
            notificationTransport = wireless
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val open = {
            AirPlayPersistence.saveWirelessEnabled(this, wireless)
            openProjection()
        }
        if (CarPlayBackgroundSession.hasSession()) CarPlayBackgroundSession.stop { runOnUiThread { open() } }
        else open()
    }
    private fun openProjection() {
        OwnScreenStart.start(this, Intent(this, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }
    private fun choosePhone() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT); return
        }
        // 无蓝牙引导无线（实验性③）：双蓝牙架构下 Android 适配器无配对，蓝牙选设备无意义。
        // 若开关已开且从未选过设备，直接返回，避免弹出无用的诊断框（无线流程由 connect(wireless) 入口放宽发起）。
        if (AirPlayPersistence.loadWirelessNoBluetoothBootstrap(this) && DiPlayPreferences.phoneAddress(this) == null) {
            return
        }
        // API 17 适配：BluetoothManager 为 API 18+，本车机（Android 4.2.2）上恒为 null 会无条件
        // 误弹"打开蓝牙"；统一走 BluetoothCompat.getAdapter（API 17 回退 getDefaultAdapter）。
        val adapter = BluetoothCompat.getAdapter(this)
        if (adapter == null) {
            // main #799（波次十三批次E）：这台车机的 Android 根本没有向应用开放蓝牙，
            // 没有可引导驾驶员去拨的开关，直接说明原因而不是再问一遍。
            pendingWireless = false
            AlertDialog.Builder(this).setTitle(getString(R.string.wireless_carplay_2))
                .setMessage(getString(R.string.bluetooth_cannot_open_connections))
                .setPositiveButton(getString(R.string.got_it), null).show(); return
        }
        // Plan A 列表优先：先读已配对列表与已连接探针，只要能给出候选设备就放行进入选择；
        // "打开蓝牙"弹窗只留给"无适配器 / 一个候选都拿不到且 isEnabled 关闭"的场景。
        // 旧栈常出现 device.name=null，严格按名称的探针会全部滤空，因此不能让探针结果单独决定拦截。
        // 保留读取异常：logcat 不可用，SecurityException 等失败必须能在 UI 诊断中看到，
        // 否则"权限缺失导致的空列表"与"真空列表"无法区分（本轮 Bluetooth 权限缺失即此症状）。
        val bondedResult = runCatching { adapter.bondedDevices.sortedBy { it.name ?: "" } }
        val devices = bondedResult.getOrDefault(emptyList())
        val connectedIphone = BluetoothCompat.findConnectedIphone(adapter)
        android.util.Log.i(
            "DiPlay-BT",
            "choosePhone: enabled=${adapter.isEnabled} bonded=${devices.size} " +
                "connectedIphone=${connectedIphone?.address ?: "none"}",
        )
        // 实验性②：绕过蓝牙开关配置。若已探测到车机已连接的 iPhone，跳过"打开蓝牙"弹框与设备选择，
        // 直接保存并续接无线流程（应对 API 17 上 adapter.isEnabled 误报关闭的场景）。
        if (AirPlayPersistence.loadWirelessSkipBluetoothConfig(this) && connectedIphone != null) {
            DiPlayPreferences.savePhone(this, connectedIphone.address, connectedIphone.name ?: "iPhone")
            val start = pendingWireless; pendingWireless = false
            render()
            if (start) connect(true)
            return
        }
        // Plan A 兜底：若 bondedDevices 为空但已探测到真实 ACL 连接的 iPhone，仍把它纳入可选列表。
        val candidates = if (devices.isEmpty() && connectedIphone != null) listOf(connectedIphone) else devices
        if (candidates.isEmpty()) {
            // 实验性②：绕过蓝牙开关配置时，即使 adapter.isEnabled 误报关闭也不弹"打开蓝牙"，降级为系统蓝牙配对引导。
            val bluetoothOff = !adapter.isEnabled && !AirPlayPersistence.loadWirelessSkipBluetoothConfig(this)
            if (bluetoothOff) {
                showTurnOnBluetoothDialog(adapter); return
            }
            AlertDialog.Builder(this).setTitle(getString(R.string.pair_your_iphone))
                .setMessage(
                    buildString {
                        append(getString(R.string.on_your_iphone_open_settings_bluetooth_and_pair_with_the_c))
                        append("\n\n———— 诊断信息（请截图反馈）————\n")
                        append("开关状态(isEnabled): ${adapter.isEnabled}\n")
                        append("已配对设备数: ${devices.size}\n")
                        bondedResult.exceptionOrNull()?.let {
                            append("配对列表读取失败: ${it.javaClass.simpleName}\n")
                        }
                        append("已连接 iPhone: ${connectedIphone?.address ?: "未探测到"}\n")
                        // 双蓝牙鉴别：应用层适配器的名称/地址。若与车机系统蓝牙设置里
                        // 显示的名字（如 "MyLink Captive"）不同，说明 iPhone 配对的是
                        // 原厂蓝牙模块，而非 DiPlay 可控的这块适配器。
                        append("适配器名称: ${runCatching { adapter.name }.getOrNull() ?: "读取失败"}\n")
                        append("适配器地址: ${runCatching { adapter.address }.getOrNull() ?: "读取失败"}\n")
                        if (bondedResult.isFailure) {
                            append("→ 读取失败通常是缺少 android.permission.BLUETOOTH 权限")
                        }
                    },
                )
                .setPositiveButton(getString(R.string.open_bluetooth)) { _, _ ->
                    // 先尝试应用层直接开启（enable() 为隐藏 API，反射 + BLUETOOTH_ADMIN），
                    // 成功受理后自动重试设备选择；失败（如系统拒绝）再退回跳转系统蓝牙设置。
                    if (BluetoothCompat.tryEnable(adapter)) {
                        toast("已发送开启蓝牙请求，稍候自动重试")
                        window.decorView.postDelayed({ choosePhone() }, 2000)
                    } else {
                        openBluetoothSettings()
                    }
                }
                .setNegativeButton(getString(R.string.got_it), null).show(); return
        }
        AlertDialog.Builder(this).setTitle(getString(R.string.choose_your_iphone))
            .setItems(candidates.map { device ->
                val name = device.name ?: getString(R.string.paired_device)
                if (candidates.count { it.name == device.name } > 1) "$name · ${device.address.takeLast(5)}" else name
            }.toTypedArray()) { _, index ->
                val device = candidates[index]
                DiPlayPreferences.savePhone(this, device.address, device.name ?: "iPhone")
                val start = pendingWireless; pendingWireless = false
                render()
                if (start) connect(true)
            }.setNeutralButton(getString(R.string.pair_another)) { _, _ -> openBluetoothSettings() }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> pendingWireless = false }.show()
    }

    /**
     * Plan A：蓝牙不可用 / 无候选设备时的提示弹窗。
     * 由于真机无法查看 logcat，这里把关键探测值直接显示在对话框里，
     * 用户截图即可反馈实际卡点，无需 adb。
     */
    private fun showTurnOnBluetoothDialog(adapter: BluetoothAdapter) {
        val diag = buildString {
            append(getString(R.string.enable_the_car_s_bluetooth_and_pair_your_iphone_first))
            // main #799（波次十三批次E）：从蓝牙设置页回来仍是关闭——有些车机的开关留不住，
            // 说明这台车机把蓝牙留给自己的通话和音乐，应用无法使用。
            if (bluetoothSettingsVisited) append("\n\n").append(getString(R.string.bluetooth_will_not_stay_on_hint))
            append("\n\n———— 诊断信息（请截图反馈）————\n")
            append("系统版本: Android ${Build.VERSION.SDK_INT}\n")
            append("蓝牙适配器: 可用\n")
            append("开关状态(isEnabled): ${adapter.isEnabled}\n")
            val bonded = runCatching { adapter.bondedDevices }.getOrNull()
            append("已配对设备数: ${bonded?.size ?: "读取失败"}\n")
            val conn = BluetoothCompat.findConnectedIphone(adapter)
            append("已连接 iPhone: ${conn?.address ?: "未探测到"}\n")
            // 双蓝牙鉴别：与系统蓝牙设置里显示的车机名比对。
            append("适配器名称: ${runCatching { adapter.name }.getOrNull() ?: "读取失败"}\n")
            append("适配器地址: ${runCatching { adapter.address }.getOrNull() ?: "读取失败"}\n")
            if (bonded.isNullOrEmpty() && !adapter.isEnabled) {
                append("→ 应用层既拿不到配对列表、开关又误报关闭，疑似系统未向应用开放蓝牙数据")
            }
        }
        AlertDialog.Builder(this).setTitle(getString(R.string.turn_on_bluetooth))
            .setMessage(diag)
            .setPositiveButton(getString(R.string.open_bluetooth)) { _, _ ->
                // main #799（波次十三批次E）：无论应用层直接开启还是跳转系统设置，驾驶员都已被告知去开开关。
                bluetoothSettingsVisited = true
                if (BluetoothCompat.tryEnable(adapter)) {
                    toast("已发送开启蓝牙请求，稍候自动重试")
                    window.decorView.postDelayed({ choosePhone() }, 2000)
                } else {
                    openBluetoothSettings()
                }
            }
            .setNegativeButton(getString(R.string.later), null).show()
    }

    private fun wirelessHelp() {
        AlertDialog.Builder(this).setTitle(getString(R.string.wireless_connection_help))
            .setMessage(getString(R.string.pair_your_iphone_with_the_car_s_bluetooth_keep_wi_fi_on_an))
            .setPositiveButton(getString(R.string.got_it), null)
            .setNeutralButton(getString(R.string.reset_carplay_wi_fi)) { _, _ ->
                confirmWirelessReset()
            }.show()
    }

    private fun handleWirelessRecovery() {
        if (page != "wireless-recovery") return
        page = "home"; render()
        confirmWirelessReset()
    }

    private fun confirmWirelessReset() {
        AlertDialog.Builder(this).setTitle(getString(R.string.reset_carplay_wi_fi_2))
            .setMessage(getString(R.string.this_ends_the_existing_wi_fi_direct_connection_including_o))
            .setPositiveButton(getString(R.string.reset_and_connect)) { _, _ ->
                CarPlayBackgroundSession.stop { runOnUiThread { resetWirelessGroup() } }
            }.setNegativeButton(getString(R.string.cancel), null).show()
    }

    /** WifiP2pManager.Channel.close() is API 27+; below that the channel dies with the activity. */
    private fun closeChannel(channel: android.net.wifi.p2p.WifiP2pManager.Channel) {
        if (Build.VERSION.SDK_INT >= 27) channel.close()
    }

    private fun resetWirelessGroup() {
        val manager = getSystemService(android.content.Context.WIFI_P2P_SERVICE) as? android.net.wifi.p2p.WifiP2pManager
        if (manager == null) { toast(getString(R.string.this_head_unit_does_not_support_wi_fi_direct)); return }
        val channel = manager.initialize(this, mainLooper, null)
        try {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) { closeChannel(channel); connect(true); return@requestGroupInfo }
                manager.removeGroup(channel, object : android.net.wifi.p2p.WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        val deadline = android.os.SystemClock.elapsedRealtime() + 4000
                        fun waitUntilRemoved() {
                            manager.requestGroupInfo(channel) { remaining ->
                                when {
                                    remaining == null -> { closeChannel(channel); if (!isFinishing && !isDestroyed) connect(true) }
                                    android.os.SystemClock.elapsedRealtime() >= deadline -> {
                                        closeChannel(channel); toast(getString(R.string.wi_fi_direct_is_still_busy_close_the_other_projection_app))
                                    }
                                    else -> handler.postDelayed({ waitUntilRemoved() }, 200)
                                }
                            }
                        }
                        waitUntilRemoved()
                    }
                    override fun onFailure(reason: Int) { closeChannel(channel); toast(getString(R.string.could_not_reset_wi_fi_direct_close_the_other_projection_ap)) }
                })
            }
        } catch (_: SecurityException) {
            closeChannel(channel); permissionHelp(getString(R.string.wireless_permissions), getString(R.string.allow_nearby_devices_and_on_older_android_versions_locatio))
        }
    }

    private fun refreshStatus() {
        val running = CarPlayBackgroundSession.hasSession()
        // main #742：USB 会话共用卡片的状态与断开按钮，但不共用无线标题和 Wi-Fi 提示（作者：TRAE AI）。
        // A USB session shares this card's status and Disconnect, but not its wireless title or Wi-Fi hint (#742).
        val wired = HomeCardTransport.wired(running, AirPlayPersistence.loadWirelessEnabled(this))
        homeCardTitle?.text = getString(if (wired) R.string.home_usb_carplay else R.string.wireless_carplay)
        homeConnectionHint?.visibility = if (wired) View.GONE else View.VISIBLE
        status?.text = when {
            setupError != null -> getString(R.string.setup_needs_attention)
            CarPlayBackgroundSession.active -> getString(R.string.carplay_connected)
            running -> getString(R.string.connecting_to_your_iphone)
            DiPlayPreferences.phoneAddress(this) != null -> "${getString(R.string.status_ready_for_prefix)}${DiPlayPreferences.phoneName(this)}"
            else -> getString(R.string.ready_when_you_are)
        }
        if (lastRunning != running) {
            connectButton?.text = if (running) getString(R.string.open_carplay) else getString(R.string.connect_phone)
            disconnectButton?.visibility = if (running) View.VISIBLE else View.GONE
            disconnectButton?.isEnabled = true
            lastRunning = running
        }
        connectButton?.isEnabled = setupError == null
        refreshReconnectBar()
        refreshReadiness()
    }
    private fun authorizeClusterRouting() {
        val app = applicationContext
        Thread({
            val result = runCatching {
                com.shilapi.xcertplay.adb.LocalAdb(com.shilapi.xcertplay.adb.AdbKeys.load(app)).use {
                    it.connect(mayAsk = true)
                }
            }.getOrNull()
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    toast(if (result == com.shilapi.xcertplay.adb.LocalAdb.Access.READY)
                        getString(R.string.adb_access_ready) else getString(R.string.adb_not_approved))
                    if (result == com.shilapi.xcertplay.adb.LocalAdb.Access.READY) ClusterActivityOutput.retry()
                }
            }
        }, "adb-cluster-authorize").start()
    }

    private fun reportFileName() = "DiPlay-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.txt"

    // main #541：保存按钮的统一入口——需要存储权限（Android 9）时先申请，其余直接导出（作者：TRAE AI）。
    private fun saveReportToDownloads() {
        if (exportInProgress) return
        if (DiagnosticExportStore.needsStoragePermission(this)) {
            runCatching { reportStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
                .onFailure { exportDiagnostics() }
        } else exportDiagnostics()
    }

    private fun chooseReportDestination() {
        // Some head units omit or disable DocumentsUI. Launch itself can throw, before
        // the result callback and the background writer's exception handler ever run.
        if (exportInProgress) return
        runCatching { export.launch(reportFileName()) }.onFailure { exportDiagnostics() }
    }

    private fun exportDiagnostics(uri: Uri? = null) {
        if (exportInProgress) return
        exportInProgress = true
        exportButton?.apply { isEnabled = false; text = getString(R.string.saving_report) }
        val appContext = applicationContext
        val fileName = reportFileName()
        Thread({
            val result = runCatching {
                val report = diagnosticReportText(appContext)
                val savedReport = if (uri != null) {
                    DiagnosticExportStore.write(appContext.contentResolver, uri, report)
                    DiagnosticExportStore.SavedReport(uri)
                } else DiagnosticExportStore.saveWithoutPicker(appContext, fileName, report)
                savedReport to report
            }
            runOnUiThread {
                exportInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                exportButton?.apply { isEnabled = true; text = getString(R.string.save_diagnostic_report) }
                if (result.isSuccess) {
                    val (savedReport, report) = result.getOrThrow()
                    AlertDialog.Builder(this).setTitle(getString(R.string.diagnostic_report_saved))
                        .setMessage(when {
                            savedReport.savedInApp -> getString(R.string.diagnostic_report_saved_in_app)
                            savedReport.savedPath != null -> getString(R.string.diagnostic_report_saved_to_path, savedReport.savedPath)
                            uri == null -> "Downloads/DiPlay/$fileName"
                            else -> getString(R.string.your_report_was_saved_to_the_selected_location)
                        })
                        .setPositiveButton(getString(R.string.view_diagnostic_report)) { _, _ -> showDiagnosticReport(report) }
                        .setNegativeButton(getString(R.string.done), null)
                        .setNeutralButton(getString(R.string.share)) { _, _ ->
                            runCatching {
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"; putExtra(Intent.EXTRA_STREAM, savedReport.uri)
                                    clipData = android.content.ClipData.newRawUri(getString(R.string.report_clip_label), savedReport.uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, getString(R.string.share_diagnostic_report)))
                            }.onFailure { showDiagnosticReport(report) }
                        }.show()
                } else {
                    // API17 适配：导出失败此前被静默吞掉，真机上无从定位根因；
                    // 现在记录日志（含各存储分区可用空间），并把异常类型/消息附到弹窗里（作者：TRAE AI）。
                    val error = result.exceptionOrNull()
                    Log.e("DiPlay-export", "Diagnostic report export failed; ${exportStorageSummary()}", error)
                    val dialog = AlertDialog.Builder(this)
                        .setTitle(getString(R.string.could_not_save_the_report))
                        .setMessage(getString(R.string.check_that_storage_is_available_or_choose_another_save_loc) +
                            "\n\n${error?.javaClass?.name ?: "unknown error"}: ${error?.message ?: "(no message)"}")
                    // API 17 适配：Storage Access Framework（ACTION_CREATE_DOCUMENT）是 API 19+；
                    // 4.2.2 上 launch 会立即抛 ActivityNotFoundException 并回退到同一自动保存逻辑，形成
                    // “点了选择位置还是这个提示”的死循环。低版本直接移除该按钮，只保留关闭（作者：TRAE AI）。
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        dialog.setPositiveButton(getString(R.string.choose_location)) { _, _ -> chooseReportDestination() }
                    }
                    dialog.setNegativeButton(getString(R.string.close), null).show()
                }
            }
        }, "diplay-export").start()
    }

    /** API 17 适配：StatFs.getAvailableBytes 是 API 18+，改用 availableBlocks × blockSize（作者：TRAE AI）。 */
    @Suppress("DEPRECATION")
    private fun exportStorageSummary(): String {
        fun freeMiB(path: File?): String? {
            if (path == null) return null
            return runCatching {
                val stat = StatFs(path.path)
                val freeMiB = stat.availableBlocks.toLong() * stat.blockSize / (1024L * 1024L)
                "${path.path}: ${freeMiB} MiB free"
            }.getOrNull()
        }
        val internal = freeMiB(filesDir) ?: "internal storage unavailable"
        val downloads = freeMiB(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
            ?: "public Downloads unavailable"
        return "internal=$internal; downloads=$downloads"
    }

    /** The text of a diagnostic report. It reads the session logs, so call it off the main thread. */
    private fun diagnosticReportText(appContext: Context): String = buildString {
        appendLine("DiPlay ${version()} · private beta diagnostic report")
        appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        appendLine("Head unit: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Connection: ${if (AirPlayPersistence.loadWirelessEnabled(appContext)) "wireless" else "USB"}")
        // main #583：诊断报告记录当前充电电流报价档位。
        AirPlayPersistence.loadUsbChargingCurrent(appContext).let {
            appendLine("USB charging offer: ${it.key} (${it.milliAmps} mA)")
        }
        appendLine("Authentication: local experimental beta identity; no remote fallback")
        appendLine("CarPlay setup: ${if (setupError == null) "ready" else "authentication unavailable"}")
        appendLine("Saved video preference (may differ from active session): ${if (AirPlayPersistence.loadHevcEnabled(appContext)) "HEVC" else "H.264"}; ${AirPlayPersistence.loadFps(appContext)} fps")
        appendLine("Media-key audio focus: ${if (AirPlayPersistence.loadDisableMediaKeyAudioFocus(appContext)) "disabled" else "enabled"}")
        appendLine("MTK decoder tuning preference: ${if (AirPlayPersistence.loadMtkDecoderTuning(appContext)) "enabled" else "disabled"}")
        appendLine("CarPlay size: ${com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(appContext)).label}")
        appendLine("Saved resolution preference (may differ from active session): ${AirPlayPersistence.loadDisplayScalePercent(appContext)}%")
        appendLine("Session: ${if (CarPlayBackgroundSession.active) "active" else if (CarPlayBackgroundSession.hasSession()) "connecting" else "stopped"}")
        // main #439：蓝牙唤醒诊断行（不含蓝牙地址）。
        appendLine(PhoneWakeDiagnostics.report(appContext))
        appendLine("Head-unit board: ${Build.BOARD}; hardware: ${Build.HARDWARE}; build: ${Build.DISPLAY}")
        appendLine()
        appendLine("--- Current cluster display diagnostics (even when disabled) ---")
        appendLine(ClusterMapPresentation.diagnosticReport(appContext))
        appendLine()
        appendLine("--- ADB cluster activity routing ---")
        appendLine("adbClusterActivityEnabled=${AirPlayPersistence.loadAdbClusterEnabled(appContext)}")
        appendLine("clusterActivityMainTask=${ClusterActivityOutput.mainTaskId} surfaceValid=${ClusterActivityOutput.surface?.isValid}")
        AdbClusterRouter.report(appContext).lineSequence().forEach { line ->
            DiagnosticRedactor.redact(line)?.let { appendLine(it) }
        }
        appendLine()
        appendLine("--- Standalone HUD compatibility ---")
        appendLine(BydOutputSettings.standaloneHudDiagnosticReport(appContext))
        appendLine()
        appendLine("--- BYD vehicle-data probe ---")
        appendLine(
            "mode=${if (BydOutputSettings.legacyVehicleProbe(appContext)) "legacy-probe" else "default"} " +
                "switches location=${AirPlayPersistence.loadLocationReportingEnabled(appContext)} " +
                "battery=${BydOutputSettings.batteryToIphone(appContext)} " +
                "wheelSpeed=${BydOutputSettings.wheelSpeedToIphone(appContext)} " +
                "parkedVideo=${BydOutputSettings.videoWhileParked(appContext)}",
        )
        val bydCapabilities = BydVehicleFieldStore.load(appContext)
        if (bydCapabilities == null) {
            appendLine("no saved successful probe")
        } else {
            appendLine(
                "catalog=${bydCapabilities.catalogAvailable} detectedAt=${bydCapabilities.detectedAtMillis} " +
                    "savedFirmware=${bydCapabilities.firmwareKey} " +
                    "currentFirmware=${BydVehicleFieldStore.firmwareKey()}",
            )
            for (field in BydVehicleField.entries) {
                val probe = bydCapabilities.result(field)
                appendLine("${field.name}: supported=${probe.supported} " +
                    (probe.address?.let { "tx=${it.transaction} dev=${it.device} fid=${it.fid} source=${it.source}" }
                        ?: "address=none"))
            }
        }
        appendLine()
        appendLine("--- Last display negotiation (timestamps distinguish it from current settings) ---")
        appendLine(DisplayDiagnosticSnapshot.report(appContext))
        appendLine()
        appendLine("--- Last received boot and app-launch result ---")
        appendLine(StartupDiagnosticSnapshot.report(appContext))
        appendLine("Startup settings: openAfterBoot=${AirPlayPersistence.loadAutoStartOnBoot(appContext)} " +
            "connectWhenOpened=${DiPlayPreferences.autoConnect(appContext)}")
        // main #748/#749：诊断报告加入热点控制状态（作者：TRAE AI）。
        appendLine("Car hotspot control: autoEnable=${CarHotspotSettings.enabled(appContext)} " +
            "writeSettings=${CarHotspotTethering.permitted(appContext)} " +
            "bydHeadUnit=${runCatching { CarHotspotSetup.isBydHeadUnit(appContext) }.getOrNull()} " +
            "hotspotOn=${runCatching { com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(appContext) }.getOrNull()}")
        appendLine(WheelKeyService.report(appContext))
        WheelKeyLog.report(WheelKeyService.connected()).forEach { appendLine(it) }
        appendLine()
        // main #488：诊断报告加入后台更新状态（作者：TRAE AI）。
        appendLine(UpdateAvailability.report(appContext, System.currentTimeMillis()))
        appendLine()
        appendLine("--- Recent own-app process exits (Android 11+) ---")
        appendLine(ProcessExitDiagnostics.report(appContext))
        appendLine()
        appendLine("--- Last crash recorded by DiPlay ---")
        appendLine(CrashRecord.report(appContext))
        appendLine()
        appendLine("--- Last uncaught Java crash (written to disk without adb) ---")
        val crashFile = CrashFileLogger.lastCrashFile(appContext)
        if (crashFile.isFile) {
            crashFile.useLines { lines -> lines.forEach { line -> DiagnosticRedactor.redact(line)?.let { appendLine(it) } } }
        } else {
            appendLine("none captured")
        }
        appendLine()
        for (name in SessionLogFile.REPORT_NAMES) {
            val file = File(appContext.filesDir, "logs/$name")
            if (file.isFile) {
                appendLine("--- $name ---")
                file.useLines { lines -> lines.forEach { line -> DiagnosticRedactor.redact(line)?.let { appendLine(it) } } }
            }
        }
    }

    private fun showDiagnosticReport(report: String) {
        val body = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        body.addView(label(getString(R.string.diagnostic_report_copy_hint), 14, MUTED))
        body.addView(label(report, 13, TEXT).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        })
        AlertDialog.Builder(this).setTitle(getString(R.string.view_diagnostic_report))
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton(getString(R.string.close), null).show()
    }
    private fun permissionHelp(title: String, body: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton(getString(R.string.app_settings)) { _, _ ->
            openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }.setNegativeButton(getString(R.string.later), null).show()
    }
    private fun openSystem(intent: Intent) { runCatching { startActivity(intent) }.onFailure { toast(getString(R.string.open_this_setting_from_your_car_s_settings_app)) } }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    private fun playTestTone(streamType: Int) {
        toneStop?.let { handler.removeCallbacks(it) }
        toneStop = null
        testToneTrack?.let { runCatching { it.stop(); it.release() } }
        testToneTrack = null
        var candidate: AudioTrack? = null
        val track = try {
            val pcm = assets.open("navigation_test.pcm").use { it.readBytes() }
            AudioTrack(streamType, 44100, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, pcm.size, AudioTrack.MODE_STREAM).also {
                candidate = it
                check(it.state == AudioTrack.STATE_INITIALIZED)
                check(it.write(pcm, 0, pcm.size) == pcm.size)
                it.play()
            }
        } catch (error: Exception) {
            val state = candidate?.state ?: AudioTrack.STATE_UNINITIALIZED
            candidate?.let { runCatching { it.release() } }
            Log.w("DiPlay", "playTestTone streamType=$streamType unavailable", error)
            toast(getString(R.string.audio_stream_unavailable, streamType, state))
            return
        }
        Log.i("DiPlay", "playTestTone streamType=$streamType state=${track.state} playState=${track.playState}")
        testToneTrack = track
        val stop = Runnable {
            track.stop()
            track.release()
            if (testToneTrack === track) testToneTrack = null
            toneStop = null
        }
        toneStop = stop
        handler.postDelayed(stop, 4500)
    }

    private val channelButtons = mutableListOf<Button>()

    private fun paintChannel(index: Int, selected: Boolean) {
        val target = channelButtons.getOrNull(index) ?: return
        target.isSelected = selected
        target.setTextColor(if (selected) BG else TEXT)
        target.background = UiCompat.ripple(
            RIPPLE,
            rounded(if (selected) ACCENT else SURFACE, if (selected) ACCENT else BORDER),
            null
        )
    }

    private fun channelSelector(): ViewGroup {
        channelButtons.clear()
        val grid = GridLayout(this).apply {
            columnCount = 7
            rowCount = 3
            setPadding(0, dp(8), 0, dp(8))
        }
        for (i in 0..20) {
            val btn = Button(this).apply {
                text = i.toString()
                isAllCaps = false
                textSize = 16f
                minHeight = dp(48)
                UiCompat.clearStateListAnimator(this)
                setOnClickListener {
                    val previous = navigationStreamType
                    navigationStreamType = i
                    if (previous != i) {
                        paintChannel(previous, false)
                        paintChannel(i, true)
                    }
                    playTestTone(i)
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(48)
                columnSpec = if (android.os.Build.VERSION.SDK_INT >= 21) GridLayout.spec(GridLayout.UNDEFINED, 1f) else GridLayout.spec(GridLayout.UNDEFINED, 1)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            grid.addView(btn, params)
            channelButtons.add(btn)
            paintChannel(i, i == navigationStreamType)
        }
        return grid
    }
    private fun version() = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0-beta.1"
    private fun languageSettings(content: LinearLayout) {
        filteredSection(content, SettingsSection.LANGUAGE,
            getString(R.string.language_section_title), R.drawable.ic_dp_language) { card ->
            card.addView(label(getString(R.string.language_hint), 14, MUTED))
            val current = AppLocale.preference(this)
            val languageButton = button("${getString(R.string.language_app_language)} · ${AppLocale.displayName(this, current)}", false) { }
            languageButton.setOnClickListener { AppLocale.showPicker(this) }
            card.addView(languageButton, matchButton(12, 60))
        }
    }

    private fun section(parent: LinearLayout, title: String, icon: Int? = null, build: (LinearLayout) -> Unit) {
        searchIndexSink?.add(title)
        val card = card()
        val heading = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(16)) }
        if (icon != null) heading.addView(ImageView(this).apply {
            setImageResource(icon); UiCompat.setImageTint(this, ACCENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) })
        heading.addView(label(title, 22, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(heading)
        build(card)
        if (page == "settings") normalizeSpacing(card)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
    }
    private fun audioFocusControls(parent: LinearLayout) {
        val enabled = AirPlayPersistence.loadAudioFocusEnabled(this)
        val dependent = column().apply { visibility = if (enabled) View.VISIBLE else View.GONE }
        // main #615：关闭媒体键音频焦点（实验性）。
        toggle(parent, getString(R.string.settings_disable_media_key_audio_focus),
            getString(R.string.settings_disable_media_key_audio_focus_description),
            AirPlayPersistence.loadDisableMediaKeyAudioFocus(this)) {
            AirPlayPersistence.saveDisableMediaKeyAudioFocus(this, it)
            markReconnectNeeded()
        }
        toggle(parent, getString(R.string.contrib_audio_home_toggle_audio_focus),
            getString(R.string.contrib_audio_home_toggle_audio_focus_desc), enabled) {
            AirPlayPersistence.saveAudioFocusEnabled(this, it)
            markReconnectNeeded()
            dependent.visibility = if (it) View.VISIBLE else View.GONE
        }
        toggle(dependent, getString(R.string.audio_focus_auto_yield), getString(R.string.audio_focus_auto_yield_desc),
            AirPlayPersistence.loadAudioFocusAutoYield(this)) {
            AirPlayPersistence.saveAudioFocusAutoYield(this, it)
            // main #482：自动让出焦点的变更需要重连才生效，显示"立即重连"条。
            markReconnectNeeded()
        }
        parent.addView(dependent)
    }


    private fun filteredSection(
        parent: LinearLayout,
        key: SettingsSection,
        title: String,
        icon: Int? = null,
        build: (LinearLayout) -> Unit,
    ) {
        if (settingsSectionFilter?.contains(key) == false) return
        section(parent, title, icon, build)
    }
    // Routing changes affect the live cluster surface as soon as the host resumes.
    // Ask before saving them instead of promising to defer only part of the change.
    private fun reconnectingToggle(parent: LinearLayout, title: String, description: String,
        value: Boolean, save: (Boolean) -> Unit) {
        toggle(parent, title, description, value) { selected ->
            if (!CarPlayBackgroundSession.hasSession()) {
                save(selected)
            } else {
                AlertDialog.Builder(this).setTitle(title).setMessage(description)
                    .setPositiveButton(R.string.apply_and_reconnect) { _, _ ->
                        save(selected)
                        reconnectIfRunning()
                    }
                    .setNegativeButton(R.string.cancel) { _, _ -> render() }
                    .setOnCancelListener { render() }
                    .show()
            }
        }
    }

    private fun toggle(parent: LinearLayout, title: String, description: String, value: Boolean, enabled: Boolean = true, save: (Boolean) -> Unit): SwitchCompat {
        searchIndexSink?.add(title)
        val result = SettingsWidgets.createSwitchRow(
            context = this,
            label = title,
            description = description,
            checked = value,
            theme = SettingsTheme.CARD,
            contentDescription = title,
            enabled = enabled,
            // 跟随设备亮/暗 palette：亮色主题下用浅色轨道与深色文字，
            // 否则开关/文字仍停留在暗色样式（SettingsTheme.CARD 是写死的暗色值）。
            palette = palette,
            onChanged = save,
        )
        parent.addView(result.rowView)
        return result.switch
    }
    /** main #746：单一传输方式的音乐缓冲区选择；仅当运行中会话属于该传输时才提示重连（作者：TRAE AI）。 */
    private fun musicBufferChoice(card: LinearLayout, wireless: Boolean) {
        val buffer = com.shilapi.xcertplay.media.MediaAudioBuffer
        val presets = if (wireless) buffer.wirelessPresets else buffer.wiredPresets
        val labels = presets.map { millis ->
            when (millis) {
                buffer.DEFAULT_MILLIS -> getString(R.string.s_300_ms_default)
                buffer.MOST_STABLE_MILLIS -> getString(R.string.s_1000_ms_most_stable)
                else -> getString(R.string.settings_music_buffer_ms, millis)
            }
        }
        val title = getString(if (wireless) R.string.settings_music_buffer_wireless else R.string.settings_music_buffer_wired)
        val sessionTransport = AirPlayPersistence.loadWirelessEnabled(this) == wireless
        choice(card, title, labels,
            presets.indexOf(AirPlayPersistence.loadMediaBufferMillis(this, wireless)).coerceAtLeast(0),
            reconnects = sessionTransport) {
            AirPlayPersistence.saveMediaBufferMillis(this, wireless, presets[it])
        }
    }

    // [announcesReconnect] labels a choice whose [save] reconnects by itself.
    private fun choice(parent: LinearLayout, title: String, options: List<String>, current: Int, reconnects: Boolean = true,
        announcesReconnect: Boolean = reconnects, enabled: Boolean = true, save: (Int) -> Unit) {
        var selection = current
        val button = button("$title · ${options[selection]}", false) {}.apply { isEnabled = enabled }
        button.setOnClickListener {
            var pendingSelection = selection
            AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), selection) { _, index -> pendingSelection = index }
                .setPositiveButton(getString(if (announcesReconnect && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save)) { _, _ ->
                    if (pendingSelection != selection) {
                        selection = pendingSelection
                        save(selection)
                        button.text = "$title · ${options[selection]}"
                        if (reconnects && CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }.setNegativeButton(getString(R.string.cancel), null).show()
        }
        parent.addView(button, matchButton(0, 60)); parent.addView(space(12))
    }
    private fun card() = column().apply { background = rounded(SURFACE, BORDER); setPadding(dp(24), dp(24), dp(24), dp(24)) }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }

    // main #345：氛围灯配置摘要行文案（作者：TRAE AI）
    private fun ambientConfigurationLabel(value: AmbientMusicSettings.Values): String {
        val mode = if (!value.music) getString(R.string.settings_ambient_static) else when (value.colorMode) {
            AmbientColorMode.ENERGY -> getString(R.string.settings_ambient_energy)
            AmbientColorMode.BEAT -> getString(R.string.settings_ambient_beat)
            AmbientColorMode.TEMPO -> getString(R.string.settings_ambient_tempo)
            AmbientColorMode.BASS -> getString(R.string.settings_ambient_bass)
            AmbientColorMode.SMART -> getString(R.string.settings_ambient_smart)
        }
        val speed = when (value.speed) { AmbientColorSpeed.SLOW -> getString(R.string.settings_ambient_slow); AmbientColorSpeed.STANDARD -> getString(R.string.settings_ambient_standard); AmbientColorSpeed.FAST -> getString(R.string.settings_ambient_fast) }
        return getString(R.string.settings_ambient_configuration) + " · " + getString(R.string.settings_ambient_summary, getString(if (value.enabled) R.string.settings_ambient_enabled else R.string.settings_ambient_disabled), mode, speed, value.selectedColors.size, value.brightness)
    }

    // main #749（波次十三批次B，作者：TRAE AI）：每种变色模式的一行白话说明。
    private fun ambientModeDescription(mode: AmbientColorMode): String = getString(when (mode) {
        AmbientColorMode.ENERGY -> R.string.settings_ambient_mode_energy_desc
        AmbientColorMode.BEAT -> R.string.settings_ambient_mode_beat_desc
        AmbientColorMode.TEMPO -> R.string.settings_ambient_mode_tempo_desc
        AmbientColorMode.BASS -> R.string.settings_ambient_mode_bass_desc
        AmbientColorMode.SMART -> R.string.settings_ambient_mode_smart_desc
    })

    // main #749（波次十三批次B，作者：TRAE AI）：亮度 0 显示"最低档"，其余显示档位数字。
    private fun ambientBrightnessText(level: Int): String =
        if (level <= 0) getString(R.string.settings_ambient_minimum) else getString(R.string.settings_ambient_brightness_value, level)

    // main #345：氛围灯配置对话框；保存前做只读支持性检查（作者：TRAE AI）
    // main #749/#772（波次十三批次B，作者：TRAE AI）：色块网格、亮度滑块 0-6、白话行为摘要与空闲行为选择。
    private fun showAmbientConfiguration() {
        var draft = AmbientMusicSettings.load(this)
        val body = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        body.addView(label(getString(R.string.settings_ambient_parked_notice), 14, MUTED))
        body.addView(space(12))
        body.addView(label(getString(R.string.settings_ambient_behavior_title), 16, ACCENT, bold = true))
        val behavior = label("", 14, MUTED)
        body.addView(behavior)
        body.addView(space(12))
        lateinit var colorGrid: AmbientSwatchGrid
        lateinit var colorHint: TextView
        lateinit var musicOptions: LinearLayout
        fun refreshAmbient() {
            behavior.text = AmbientBehaviorText.lines(this, draft).joinToString("\n") { "• $it" }
            musicOptions.visibility = if (draft.music) View.VISIBLE else View.GONE
            val album = draft.colorSource == AmbientColorSource.ALBUM
            colorGrid.single = !draft.music
            colorGrid.setInteractive(!album)
            colorHint.text = getString(when {
                album -> R.string.settings_ambient_colors_hint_album
                draft.music -> R.string.settings_ambient_colors_hint_follow
                else -> R.string.settings_ambient_colors_hint_fixed
            })
        }
        toggle(body, getString(R.string.settings_ambient_enable), getString(R.string.settings_ambient_enable_description), draft.enabled) {
            draft = draft.copy(enabled = it)
            refreshAmbient()
        }
        toggle(body, getString(R.string.settings_ambient_music), getString(R.string.settings_ambient_music_description), draft.music) {
            draft = draft.copy(music = it)
            if (!it) {
                // Fixed mode shows a single color, the first of the selection, as the lamps do.
                val only = draft.selectedColors.first()
                draft = draft.copy(selectedColors = listOf(only), color = only)
                colorGrid.setSelection(setOf(only))
            }
            refreshAmbient()
        }
        musicOptions = column()
        val modeNote = label(ambientModeDescription(draft.colorMode), 13, MUTED)
        choice(musicOptions, getString(R.string.settings_ambient_color_mode), listOf(getString(R.string.settings_ambient_energy), getString(R.string.settings_ambient_beat), getString(R.string.settings_ambient_tempo), getString(R.string.settings_ambient_bass), getString(R.string.settings_ambient_smart)), draft.colorMode.ordinal, reconnects = false) {
            draft = draft.copy(colorMode = AmbientColorMode.entries[it])
            modeNote.text = ambientModeDescription(draft.colorMode)
        }
        musicOptions.addView(modeNote)
        musicOptions.addView(space(12))
        choice(musicOptions, getString(R.string.settings_ambient_speed), listOf(getString(R.string.settings_ambient_slow), getString(R.string.settings_ambient_standard), getString(R.string.settings_ambient_fast)), draft.speed.ordinal, reconnects = false) {
            draft = draft.copy(speed = AmbientColorSpeed.entries[it])
        }
        body.addView(musicOptions)
        choice(body, getString(R.string.settings_ambient_idle), listOf(getString(R.string.settings_ambient_idle_steady), getString(R.string.settings_ambient_idle_car), getString(R.string.settings_ambient_idle_off)), draft.idle.ordinal, reconnects = false) {
            draft = draft.copy(idle = AmbientIdleBehavior.entries[it])
            refreshAmbient()
        }
        // main #537：氛围灯取色来源——跟随专辑封面（灰黑/无封面回退全部颜色）（作者：TRAE AI）。
        toggle(body, getString(R.string.settings_ambient_album), getString(R.string.settings_ambient_album_description), draft.colorSource == AmbientColorSource.ALBUM) {
            draft = draft.copy(colorSource = if (it) AmbientColorSource.ALBUM else AmbientColorSource.SELECTED)
            refreshAmbient()
        }
        body.addView(space(12))
        body.addView(label(getString(R.string.settings_ambient_colors_title), 16, ACCENT, bold = true))
        colorHint = label("", 13, MUTED)
        body.addView(colorHint)
        body.addView(space(8))
        colorGrid = AmbientSwatchGrid(
            this, ACCENT,
            initial = if (draft.music) draft.selectedColors.toSet() else setOf(draft.selectedColors.first()),
            single = !draft.music,
            onChanged = { colors ->
                val sorted = colors.sorted()
                draft = draft.copy(selectedColors = sorted, color = sorted.first())
            },
            onLastDeselected = { toast(getString(R.string.settings_ambient_select_one)) },
        )
        body.addView(colorGrid)
        body.addView(space(12))
        body.addView(label(getString(R.string.settings_ambient_brightness), 16, ACCENT, bold = true))
        val brightnessValue = label(ambientBrightnessText(draft.brightness), 14, MUTED)
        body.addView(brightnessValue)
        body.addView(SeekBar(this).apply {
            max = 6
            progress = draft.brightness
            contentDescription = getString(R.string.settings_ambient_brightness)
            // progressTintList/thumbTintList 为 API 21+，低版本经 UiCompat 守卫（作者：TRAE AI）。
            UiCompat.setSeekBarTints(this, ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, level: Int, fromUser: Boolean) {
                    draft = draft.copy(brightness = level)
                    brightnessValue.text = ambientBrightnessText(level)
                    refreshAmbient()
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }, LinearLayout.LayoutParams(-1, dp(48)))
        body.addView(space(12))
        choice(body, getString(R.string.settings_ambient_area), listOf(getString(R.string.settings_ambient_front), getString(R.string.settings_ambient_rear), getString(R.string.settings_ambient_all)), draft.area - 1, reconnects = false) {
            draft = draft.copy(area = it + 1)
            refreshAmbient()
        }
        refreshAmbient()
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.settings_ambient_configuration))
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton(getString(R.string.save), null)
            .setNegativeButton(getString(R.string.cancel), null).create()
        dialog.setOnShowListener {
            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            save.setOnClickListener {
                val selected = draft.copy(colorCycle = draft.music)
                fun commit() {
                    AmbientMusicSettings.save(this, selected)
                    dialog.dismiss()
                    render()
                }
                if (!selected.enabled) commit()
                else {
                    save.isEnabled = false
                    save.text = getString(R.string.settings_ambient_checking)
                    ambientSupportCheck(applicationContext).whenComplete { supported, _ ->
                        runOnUiThread {
                            if (!dialog.isShowing || isFinishing || isDestroyed) return@runOnUiThread
                            save.isEnabled = true
                            save.text = getString(R.string.save)
                            if (supported == true) commit()
                            else toast(getString(R.string.settings_ambient_unavailable))
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun button(title: String, primary: Boolean, click: () -> Unit) = SettingButton(this, MUTED, ACCENT).apply {
        isAllCaps = false; textSize = 18f; setTextColor(if (primary) BG else TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = UiCompat.ripple(RIPPLE, rounded(if (primary) ACCENT else BUTTON, if (primary) ACCENT else BORDER), null)
        setPadding(dp(16), 0, dp(16), 0); minHeight = dp(56); UiCompat.clearStateListAnimator(this)
        compoundDrawablePadding = dp(12)
        UiCompat.setForeground(this, focusRing())
        text = title
        searchIndexSink?.add(title.substringBefore(VALUE_SEPARATOR))
        setOnClickListener { click() }
    }

    // For an action whose label happens to contain the separator, such as "Turn on … · ADB".
    private fun actionButton(title: String, primary: Boolean, click: () -> Unit) =
        button(title, primary, click).apply { action = true; text = title }

    /**
     * Text in the "Title · Value" form opens a choice, so it reads as a setting row:
     * start-aligned, muted value, chevron. Any other text stays a centred action button.
     * The plain text is unchanged, so callers and tests keep matching "Title · Value".
     */
    // 主/次要色由构造器传入：嵌套类无法访问宿主实例的动态取色属性（作者：TRAE AI）。
    private class SettingButton(
        context: android.content.Context,
        private val muted: Int,
        private val accent: Int,
    ) : Button(context) {
        var action = false

        override fun setText(text: CharSequence?, type: BufferType?) {
            val split = if (action) -1 else text?.indexOf(VALUE_SEPARATOR) ?: -1
            if (text == null || split <= 0) {
                super.setText(text, type)
                gravity = Gravity.CENTER
                setCompoundDrawablesRelative(null, null, null, null)
                return
            }
            val styled = android.text.SpannableString(text).apply {
                setSpan(android.text.style.ForegroundColorSpan(muted), split, text.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            super.setText(styled, BufferType.SPANNABLE)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null,
                androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_dp_chevron)?.mutate()?.let { UiCompat.tint(it, accent) }, null)
        }
    }
    // Remote and D-pad users need to see where they are; touch mode never shows it.
    private fun focusRing(radiusDp: Int = 20) = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            setColor(Color.TRANSPARENT); cornerRadius = dp(radiusDp).toFloat(); setStroke(dp(3), ACCENT)
        })
    }

    private fun rounded(color: Int, stroke: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(20).toFloat(); setStroke(dp(1), stroke) }
    private fun matchButton(top: Int = 0, height: Int = 68) = LinearLayout.LayoutParams(-1, dp(height)).apply { topMargin = dp(top) }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)); tag = SPACER }

    /**
     * One vertical rhythm for every card, whatever margins each control builder chose:
     * buttons are one height with one gap, a note under a button closes its group with a larger gap,
     * and the last item adds nothing to the card's own padding.
     */
    private fun normalizeSpacing(container: LinearLayout, isCard: Boolean = true) {
        if (container.orientation != LinearLayout.VERTICAL) return
        for (i in container.childCount - 1 downTo 0) {
            if (container.getChildAt(i).tag == SPACER) container.removeViewAt(i)
        }
        fun isNote(view: View?) = view is TextView && view !is Button
        fun isToggleRow(view: View?) = view is LinearLayout && view.orientation == LinearLayout.HORIZONTAL &&
            (0 until view.childCount).any { view.getChildAt(it) is SwitchCompat }
        fun isControl(view: View?) = view is Button || isToggleRow(view)
        var previous: View? = null
        var last: View? = null
        var noteFollowsButton = false
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            val next = container.getChildAt(i + 1)
            val lp = child.layoutParams as? LinearLayout.LayoutParams ?: continue
            when {
                child is Button -> {
                    lp.height = dp(CONTROL_HEIGHT_DP); lp.topMargin = 0
                    lp.bottomMargin = dp(if (isNote(next)) NOTE_ATTACH_GAP_DP else CONTROL_GAP_DP)
                }
                // Toggle rows carried their own vertical padding, which doubled the gap after a button.
                isToggleRow(child) -> {
                    child.setPadding(child.paddingLeft, 0, child.paddingRight, 0)
                    lp.topMargin = 0
                    lp.bottomMargin = dp(if (isNote(next)) NOTE_ATTACH_GAP_DP else CONTROL_GAP_DP)
                }
                child is TextView -> {
                    if (!isNote(previous)) noteFollowsButton = isControl(previous)
                    child.setPadding(child.paddingLeft, 0, child.paddingRight, 0)
                    lp.topMargin = 0
                    // Lines of one note stay together; the gap after the last line closes the group.
                    lp.bottomMargin = dp(when {
                        isNote(next) -> NOTE_LINE_GAP_DP
                        noteFollowsButton -> GROUP_GAP_DP
                        else -> CONTROL_GAP_DP
                    })
                }
                child is LinearLayout && child.orientation == LinearLayout.VERTICAL -> {
                    normalizeSpacing(child, isCard = false)
                    lp.topMargin = 0; lp.bottomMargin = 0
                }
                // Any other row (an icon preview, a status line) gets the same gap; the card heading keeps its padding.
                i > 0 || !isCard -> { lp.topMargin = 0; lp.bottomMargin = dp(CONTROL_GAP_DP) }
            }
            child.layoutParams = lp
            if (child.visibility != View.GONE) last = child
            previous = child
        }
        // A nested group keeps its trailing gap so it does not touch the control after it.
        if (isCard && !(last is LinearLayout && last.orientation == LinearLayout.VERTICAL)) {
            (last?.layoutParams as? LinearLayout.LayoutParams)?.let { it.bottomMargin = 0; last.layoutParams = it }
        }
    }
    // Rounded, not truncated: below 160 dpi dp(1) became 0 and every border vanished.
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    // ===== 调色板取色（main #422，作者：TRAE AI）=====
    // 原固定深色常量改为动态委托：113 处调用点无需改动，重渲染后自动跟随日夜外观。
    private val BG get() = palette.background
    private val SURFACE get() = palette.surface
    private val BUTTON get() = palette.button
    private val BORDER get() = palette.outline
    private val ACCENT get() = palette.accent
    private val RAIL_SELECTED get() = palette.railSelected
    private val RAIL_SELECTED_BORDER get() = palette.railSelectedOutline
    private val TEXT get() = palette.primaryText
    private val MUTED get() = palette.secondaryText
    private val WARNING get() = palette.warning
    private val READY get() = palette.success
    private val RIPPLE get() = palette.ripple

    companion object {
        /** 波次十 #645：取资源配置里的首选语言；Configuration.locales 是 API 24+，低版本走废弃 locale（作者：TRAE AI）。 */
        private fun firstLanguage(resources: android.content.res.Resources): String =
            if (Build.VERSION.SDK_INT >= 24) resources.configuration.locales[0].language
            else @Suppress("DEPRECATION") resources.configuration.locale.language

        /**
         * 波次十 #645：文本视觉中心相对绘制原点的纵坐标（作者：TRAE AI）。
         * 中文标签按字形包围盒取中心，其余语言按字体度量取中心（含下伸部时基线仍稳定）。
         */
        private fun alignedTextCentreY(
            view: TextView,
            text: CharSequence,
            bounds: android.graphics.Rect,
            drawingBaseline: Int = view.baseline,
        ): Float {
            if (usesChineseGlyphAlignment(firstLanguage(view.resources), text)) {
                val value = text.toString()
                view.paint.getTextBounds(value, 0, value.length, bounds)
                return drawingBaseline + (bounds.top + bounds.bottom) / 2f
            }
            val metrics = view.paint.fontMetrics
            return drawingBaseline + (metrics.ascent + metrics.descent) / 2f
        }

        /** 波次十 #645：纯中文（无拉丁字母）标签才用字形对齐（作者：TRAE AI）。 */
        private fun usesChineseGlyphAlignment(language: String, text: CharSequence): Boolean {
            return language == "zh" && text.isNotBlank() &&
                text.none { it in 'A'..'Z' || it in 'a'..'z' }
        }

        internal fun isLauncherIntent(intent: Intent): Boolean =
            intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER) &&
                !intent.hasExtra("page")

        private const val BYD_VEHICLE_TAG = "DiPlay-BYD13"
        private const val VEHICLE_VALIDATION_RETRY_MILLIS = 500L

        // BYD's settings app answers sixty standard android.settings actions with an activity in its
        // "unsupport" package instead of a real screen. It does nothing but report string/unsupport_tips
        // ("the multimedia system does not support this operation"), and startActivity reports success
        // when it reaches it, so reaching it cannot be told apart from a real screen by catching a throw.
        private const val BYD_SETTINGS_PACKAGE = "com.byd.vehiclesettings"
        private const val BYD_SETTINGS_TAG = "DiPlay-Settings"

        // BYD's own screens. An explicit component skips intent-filter matching, so the decoy cannot
        // answer one. Source: docs/BYD_SETTINGS_ENTRY_POINTS.md.
        private val BYD_BLUETOOTH_MANAGER =
            ComponentName("com.byd.btsetting", "com.byd.btsetting.BluetoothManagerDialog")
        private val BYD_HOTSPOT =
            ComponentName(BYD_SETTINGS_PACKAGE, "$BYD_SETTINGS_PACKAGE.system.mvp.view.link.hotspot.PerHospotActivity")

        private const val ADB_KEY_SAVE_WAIT_MILLIS = 500L
        // 外观兜底轮询间隔与外观按钮焦点标签（main #422）。
        private const val APPEARANCE_POLL_MILLIS = 2_000L
        private const val APPEARANCE_BUTTON_TAG = "app_appearance_button"
        // 波次十 #645：设置搜索两态与弹层定位常量（作者：TRAE AI）。
        private const val SETTINGS_SEARCH_BUTTON_TAG = "settings_search_button"
        private const val SETTINGS_HEADER_COMPACT_GAP_DP = 8
        private const val SETTINGS_HEADER_GAP_DP = 12
        private const val SETTINGS_SEARCH_RESULTS_GAP_DP = 6
        private const val SETTINGS_SEARCH_RESULTS_SHORT_HEIGHT_DP = 200
        private const val SETTINGS_SEARCH_RESULTS_HEIGHT_DP = 380
        private const val VALUE_SEPARATOR = " · "
        private const val SEARCH_HIGHLIGHT_MILLIS = 900L
        private const val SPACER = "spacer"
        private const val CONTROL_HEIGHT_DP = 60
        private const val CONTROL_GAP_DP = 12
        private const val GROUP_GAP_DP = 20
        private const val NOTE_LINE_GAP_DP = 4
        private const val NOTE_ATTACH_GAP_DP = 6
        // main：设置卡片间距统一为 16dp（作者：TRAE AI）。
        private const val SETTINGS_BLOCK_GAP_DP = 16
    }
}
