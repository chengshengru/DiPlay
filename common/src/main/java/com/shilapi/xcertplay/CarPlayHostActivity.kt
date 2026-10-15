package com.shilapi.xcertplay

import android.Manifest
import android.annotation.SuppressLint
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.ColorStateList
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.ViewConfiguration
import android.view.SurfaceView
import android.view.TextureView
import android.view.ViewTreeObserver
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import androidx.appcompat.widget.SwitchCompat
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.compat.PermissionCompat
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeMm
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.CarPlayUiScale
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayIcon
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.AirPlaySafeArea
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.airplay.SafeAreaRect
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.location.AndroidCarPlayLocationProvider
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import com.shilapi.xcertplay.media.CarPlayVideoLayout
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.network.WirelessStartupPolicy
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.orchestration.isManualHotspotChannelCompatible
import com.shilapi.xcertplay.settings.ConnectionSettingsSection
import com.shilapi.xcertplay.settings.DisplaySettingsSection
import com.shilapi.xcertplay.settings.SettingsTheme
import com.shilapi.xcertplay.settings.SettingsWidgets
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.BluetoothLinkCheck
import com.shilapi.xcertplay.transport.BluetoothRadioCheck
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import com.shilapi.xcertplay.transport.UsbDeviceId
import com.shilapi.xcertplay.transport.VehicleSpeedLocationProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Full-screen CarPlay host. It renders decoded video through a [TextureView], forwards touch to
 * the active AirPlay session, and drives the complete wired or wireless bring-up through
 * [CarPlayController].
 *
 * Apple devices are discovered by vendor ID; CH341 uses the configured VID/PID below.
 */
class CarPlayHostActivity : ComponentActivity(), AppAppearanceOwner {
    private data class SettingsBaseline(
        val safeAreaRects: MutableMap<DisplaySize, SafeAreaRect?>,
        val customIconBytes: ByteArray?,
    )

    private var connectionPanel: View? = null
    private var wifiRecoveryButton: View? = null
    private var reconnectAttempts = 0
    private val siriKey = WheelSiriKey()
    private val siriKeyPresses = WheelKeyPresses()
    private val legacySiriPresses = mutableSetOf<Triple<Int, Int, Int>>()
    private val startupRetryBudget = WirelessStartupRetryBudget()
    private var startupRetryStopped = false
    private var startupRetryButton: View? = null
    private var startupFailureGeneration = -1
    private lateinit var airPlayIdentity: AirPlayIdentity
    private var languagePreferenceAtCreate = AppLocale.SYSTEM

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    // CH341 USB\VID_1A86&PID_5512&REV_0304 is the deployment-supplied bridge identity.
    private fun createRuntimeConfig(): CarPlayRuntimeConfig = CarPlayRuntimeConfig(
        mfiTarget = mfiTarget,
        ch341Devices = if (mfiTarget == MfiTarget.USB_CH341) {
            listOf(UsbDeviceId(0x1a86, 0x5512))
        } else {
            emptyList()
        },
        // The CP latches its I2C address from the RST level at its own power-up, so the host must
        // not pulse RST before discovery. Driving D0 re-latches the part onto the alternate
        // address (0x10), where the accessory certificate is not readable. Leave RST at its
        // hardware pull (VCC -> 0x11) and let the scanner find the part with its certificate.
        // Set this back to 0 to restore the D0 pulse.
        ch341MfiResetGpio = null,
        linuxI2cPath = if (mfiTarget == MfiTarget.I2C) mfiI2cPath.trim() else null,
        remoteMfiServer = remoteMfiServer.trim().takeIf { it.isNotEmpty() },
        remoteMfiToken = remoteMfiToken.takeIf { it.isNotEmpty() },
        identification = Iap2IdentificationConfig(
            name = "DiPlay",
            modelIdentifier = normalizedModel(),
            manufacturer = normalizedManufacturer(),
            serialNumber = "DIPLAY-" + DiPlayBootstrap.deviceId(airPlayIdentity).replace(":", ""),
            firmwareVersion = "0.1.0",
            hardwareVersion = "1.0",
            carPlayUsbInterfaceNumber = 3,
            locationInformationEnabled = locationReportingEnabled,
            vehicleStatusEnabled = com.shilapi.xcertplay.hud.BydOutputSettings.batteryToIphoneActive(this),
            chargingConnectors = com.shilapi.xcertplay.hud.BydOutputSettings.chargingConnectors(this),
            vehicleSpeedEnabled = locationReportingEnabled && com.shilapi.xcertplay.hud.BydOutputSettings.wheelSpeedToIphoneActive(this),
        ),
        label = "DiPlay",
        hostName = "diplay-" + DiPlayBootstrap.deviceId(airPlayIdentity).replace(":", "").lowercase(),
        hostMac = DiPlayBootstrap.deviceId(airPlayIdentity).split(":").map { it.toInt(16).toByte() }.toByteArray(),
        wirelessBluetoothDeviceAddress = DiPlayPreferences.phoneAddress(this),
        transport = if (wirelessEnabled) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED,
        wirelessHotspotMode = wirelessHotspotMode,
        wifiP2pPreferredChannel = AirPlayPersistence.loadWifiP2pPreferredChannel(this),
        manualHotspotSsid = manualHotspotSsid,
        manualHotspotPassphrase = manualHotspotPassphrase,
        manualHotspotBand = manualHotspotBand,
        manualHotspotChannel = manualHotspotChannel,
        manualHotspotSecurity = manualHotspotSecurity,
        existingWifiSsid = existingWifiSsid,
        existingWifiPassphrase = existingWifiPassphrase,
        wirelessNoBluetoothBootstrap = AirPlayPersistence.loadWirelessNoBluetoothBootstrap(this),
        locationReportingEnabled = locationReportingEnabled,
        availableCurrentMilliAmps = AirPlayPersistence.loadUsbChargingCurrent(this).milliAmps,
    )

    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            // main 4aee3f80：授权页缺失时 launch 已失败并清除 pending 状态，迟到结果不得放行本次尝试。
            onVpnConsentResult(result.resultCode)
        }
    private val wirelessPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            awaitingWirelessPermissions = false
            wirelessPermissionsReady = hasRequiredWirelessPermissions()
            appendLog(
                if (wirelessPermissionsReady) {
                    "Wireless startup permissions granted"
                } else {
                    "Wireless startup permissions denied missing=" +
                        missingWirelessPermissions().joinToString(",") { it.substringAfterLast('.') }
                },
            )
            wirelessPermissionsRefused = !wirelessPermissionsReady
            showMissingWirelessPermission()
            updateHotspotStatusBlock()
            maybeStartCarPlay()
        }
    private val wiredPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            awaitingWiredPermissions = false
            wiredPermissionsResolved = true
            appendLog(
                if (grants.values.all { it }) {
                    "Wired startup permissions granted"
                } else {
                    "Local network access denied: Android 17 may drop the iPhone's AirPlay connection over USB"
                },
            )
            requestStartupPrerequisites()
        }
    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            microphoneAvailable = granted
            microphonePermissionResolved = true
            appendLog(if (granted) "Microphone permission granted" else "Microphone permission denied")
            requestStartupPrerequisites()
        }
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            awaitingLocationPermission = false
            locationPermissionAvailable = hasFineLocationPermission()
            if (locationPermissionAvailable) {
                appendLog("Location permission granted")
            } else if (locationReportingEnabled) {
                locationReportingEnabled = false
                if (!menuOpen) {
                    AirPlayPersistence.saveLocationReportingEnabled(
                        this@CarPlayHostActivity,
                        false,
                    )
                }
                locationReportingSwitch?.isChecked = false
                val approximateOnly =
                    grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                appendLog(
                    if (approximateOnly) {
                        "Precise location permission denied; location reporting disabled"
                    } else {
                        "Location permission denied; location reporting disabled"
                    },
                )
            }
            updateResolutionMenu()
            if (!menuOpen) requestStartupPrerequisites()
        }

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent(), ::cropSelectedImage)
    private val imageDocumentPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument(), ::cropSelectedImage)
    private fun cropSelectedImage(uri: Uri?) {
        if (uri == null) {
            externalActivityInProgress = false
            return
        }
        imageCrop.launch(
            Intent(this, ImageCropActivity::class.java)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }
    private val imageCrop =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            externalActivityInProgress = false
            if (result.resultCode == RESULT_OK) {
                updateAirPlayIconPreview()
                appendLog("Custom AirPlay icon updated")
            }
        }

    // The full-window viewport stays independent of the letterboxed SurfaceView dimensions.
    private var videoView: View? = null
    private var fallbackVideoView: SurfaceView? = null
    private var fallbackVideoBounds: CarPlaySurfaceBounds? = null
    // 波次十一批次D（main #669）：SurfaceView 视频输出的最后一帧。它的 surface 消失时（DiPlay 离开屏幕）
    // 保留该帧，并叠在新 surface 上直到新帧渲染到那里——静止的 CarPlay 画面不发送帧，新 surface 会一直
    // 黑到下一次变化（一次触摸）为止。
    private var lastPicture: Bitmap? = null
    private var pictureHold: ImageView? = null
    private var pictureHoldBaseline = -1L
    private var pictureHoldStartMillis = 0L
    private var pictureCopyThreadStarted = false
    private val pictureCopyThread by lazy {
        pictureCopyThreadStarted = true
        android.os.HandlerThread("diplay-picture-copy").apply { start() }
    }
    private val pictureHoldTick = object : Runnable {
        override fun run() {
            val hold = pictureHold ?: return
            val rendered = sink?.liveVideoCounters()?.rendered ?: -1L
            val elapsed = SystemClock.uptimeMillis() - pictureHoldStartMillis
            if (lastPictureDone(rendered, pictureHoldBaseline, elapsed, PICTURE_HOLD_MAX_MILLIS)) {
                appendLog("Last picture hidden after ${elapsed} ms (${if (elapsed > PICTURE_HOLD_MAX_MILLIS) "timeout" else "new frame"})")
                hold.animate().alpha(0f).setDuration(150L).withEndAction {
                    hold.visibility = View.GONE
                    hold.setImageDrawable(null)
                }.start()
                return
            }
            mainHandler.postDelayed(this, 50L)
        }
    }
    // 新 surface 尚未显示任何帧时，再次向 iPhone 请求一张关键帧。
    private val pictureRetry = Runnable {
        if (pictureHold?.visibility == View.VISIBLE) {
            appendLog("No frame yet after returning; asking for a keyframe again")
            sink?.refreshPicture(SCREEN_TYPE_MAIN)
        }
    }
    // Smooth video (a setting): SurfaceView output with frames released at the iPhone's frame time.
    private var smoothVideo = false
    // 直接视频输出（设置项，main #496）：帧绕过应用绘制，直接交给系统合成器。
    private var directVideoOutput = false
    // 诊断设置项（main #496）：画面右上角的 FPS 计数器。
    private var fpsCounter: FpsCounterOverlay? = null
    // Sinks whose sessions are being torn down; their decoders may still render to the current surface
    // until they have released their codecs, so a destroyed surface is detached from them too. A restart
    // and a shutdown can overlap, so this is a set.
    private val retiringSinks = java.util.concurrent.CopyOnWriteArraySet<AndroidMediaSink>()
    internal var sinkReleaseWaitMillis = SINK_RELEASE_WAIT_MILLIS
    private var videoSurfaceProbe: ViewTreeObserver.OnPreDrawListener? = null
    private var pictureBinding: CarPlayPicture.Binding? = null
    private var picturePanel: View? = null
    private var picturePanelGeneration = 0
    private var gestureOverlay: View? = null
    private var settingsMenu: View? = null
    private var settingsMenuCategoryKey = "connection"
    private var settingsMenuContentScroll: ScrollView? = null
    private var mfiTargetGroup: RadioGroup? = null
    private var mfiI2cFields: View? = null
    private var mfiRemoteFields: View? = null
    private var mfiErrorView: TextView? = null
    private var mfiI2cPathInput: EditText? = null
    private var remoteMfiServerInput: EditText? = null
    private var remoteMfiTokenInput: EditText? = null
    private var settingsBaseline: SettingsBaseline? = null
    // main #500：菜单打开时的暂存设置签名（哈希而非原值，其中含热点口令），离开时判断是否有未保存的修改（作者：TRAE AI）。
    private var menuSettingsSignature: Int? = null
    private var locationReportingSwitch: SwitchCompat? = null
    private var statusView: TextView? = null
    private var statusScrollView: ScrollView? = null
    private var stageStatusView: TextView? = null
    // 波次十一批次B（main #673）：连接失败时用可操作提示替换默认说明文字。
    private var connectionInstructionsView: TextView? = null
    private var connectionHelpView: TextView? = null
    private var usbDiagView: TextView? = null
    private val usbDiagLines = ArrayDeque<String>()
    private val usbDiagLock = Any()
    private var resolutionValueView: TextView? = null
    private var resolutionPreviewView: TextView? = null
    private var hotspotStatusView: TextView? = null
    private var manualHotspotFields: View? = null
    private var existingWifiFields: View? = null
    private var existingWifiErrorView: TextView? = null
    private var manualHotspotErrorView: TextView? = null
    private var iconPreviewView: ImageView? = null
    private var iconStatusView: TextView? = null
    private var safeAreaSummaryView: TextView? = null
    private var safeAreaEditor: View? = null
    private var safeAreaEditorView: SafeAreaEditorView? = null
    private var safeAreaEditSize: DisplaySize? = null
    private var safeAreaEditorActive = false
    private var externalActivityInProgress = false
    private var sink: AndroidMediaSink? = null
    private var controller: CarPlayController? = null
    private val videoSurfaceOwner = CarPlayVideoSurfaceOwner<Surface>(
        detach = { surface ->
            sink?.clearSurface(SCREEN_TYPE_MAIN, surface)
            sink?.clearSurface(SCREEN_TYPE_ALT, surface)
        },
        release = { it.release() },
    )
    private val currentSurface: Surface? get() = videoSurfaceOwner.current
    private var currentSurfaceTexture: SurfaceTexture? = null
    private var clusterPresentation: ClusterMapPresentation? = null
    private var clusterSurface: Surface? = null
    private var clusterMonitor: DiLink51ClusterMonitor? = null
    private var detectedCluster = ClusterActivityState.Snapshot(null, false)
    // Keep one surface per layer alive, including while its map card is hidden.
    private val clusterLayers = mutableMapOf<Boolean, ClusterMapPresentation>()
    private var clusterTurnGuidance: com.shilapi.xcertplay.hud.ClusterTurnGuidance? = null
    private val clusterTurnOverlayListener: (com.shilapi.xcertplay.hud.ClusterTurnGuidance?) -> Unit = { guidance ->
        runOnUiThread {
            clusterTurnGuidance = guidance
            applyClusterTurnOverlay()
        }
    }
    // Copies of stream 111 outside the dashboard (centre card, launcher maps) each get their own decoder.
    private val mirrorSink: (String, Surface?) -> Unit = { key, surface -> sink?.setMirrorSurface(SCREEN_TYPE_ALT, key, surface) }
    private val mirrorsChanged: () -> Unit = {
        updateClusterMapShown()
        if (MapMirrors.launcherShowsMap) CenterMapOverlay.hide()
    }
    // With Usage Access the card shows only over a home screen; null = not known (no monitor).
    private var homeMonitor: HomeScreenMonitor? = null
    private var homeScreenVisible: Boolean? = null
    private var isActivityStarted = false
    private val hideIdleCenterMap = Runnable {
        if (SCREEN_TYPE_ALT !in activeScreenStreamTypes) CenterMapOverlay.hide()
    }
    private var activeDisplaySize: DisplaySize? = null
    private var pendingDisplaySize: DisplaySize? = null
    private var sessionDisplay: CarPlaySessionDisplay? = null
    private var touchOutsideContent = false
    private var displayScalePercent = 100
    private var displayScaleTenths = CarPlayDisplayScale.DEFAULT_TENTHS
    private var uiScalePercent = CarPlayUiScale.DEFAULT
    private var displayDiagnosticAttempt: String? = null
    private var hevcEnabled = true
    private var hevcSoftwareDecoderEnabled = false
    private val hevcDecoders by lazy { HevcSupport.decoders() }
    /** The codec a session asks for: the HEVC setting falls back to H.264 without a decoder for it (#727). */
    private val sessionHevc: Boolean
        get() = HevcSupport.usable(hevcEnabled, hevcSoftwareDecoderEnabled, hevcDecoders)
    private var advancedAudioChannelMappingSupported = false
    private var advancedAudioChannelMapping = false
    private var navigationStreamType = 14
    private var debugLogsEnabled = false
    private var autoStartOnBoot = false
    private var manufacturer = AirPlayPersistence.DEFAULT_MANUFACTURER
    private var model = AirPlayPersistence.DEFAULT_MODEL
    private var oemLabel = AirPlayPersistence.DEFAULT_OEM_LABEL
    private var fps = AirPlayDisplaySettings.DEFAULT_FPS
    private var widthPhysicalMm = AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM
    private var physicalSizeBasis = AirPlayDisplaySettings.DEFAULT_PHYSICAL_SIZE_BASIS
    private var maximumDetectedWidthPixels = 0
    private var maximumDetectedHeightPixels = 0
    private var rightHandDrive = false
    private var carPlayDock = CarPlayDock.AUTOMATIC
    /** The view areas the next session declares (see createAirPlayConfig). */
    private var pendingViewAreas: CarPlayViewAreas? = null
    /** The dock the running session declared; null before the first connection. */
    private var sessionDock: CarPlayDock? = null
    private var hideTopBar = true
    private var hideBottomBar = true
    private var temporarySystemBarsEnabled = false
    private var temporarySystemBarsVisible = false
    private val hideTemporarySystemBars = Runnable {
        temporarySystemBarsVisible = false
        applyFullscreenMode()
    }
    private var harmanFullscreenEnabled = false
    private var harmanFullscreenApplied = false
    private var safeAreaDrawOutside = true
    private var mainDisplayDisableSafeArea = true
    private var locationReportingEnabled = false
    private var locationPermissionAvailable = false
    private var microphoneAvailable = false
    private var microphonePermissionResolved = false
    private var wirelessEnabled = false
    private var mfiTarget = MfiTarget.LOCAL
    private var mfiI2cPath = AirPlayPersistence.DEFAULT_MFI_I2C_PATH
    private var remoteMfiServer = ""
    private var remoteMfiToken = ""
    private var wirelessPermissionsReady = false
    private var wirelessHotspotMode = WirelessHotspotMode.WIFI_P2P
    private var manualHotspotSsid = ""
    private var existingWifiSsid = ""
    private var existingWifiPassphrase = ""
    private var manualHotspotPassphrase = ""
    private var manualHotspotBand = ManualHotspotBand.AUTO
    private var manualHotspotChannel = 0
    private var manualHotspotSecurity = ManualHotspotSecurity.OPEN
    private var awaitingVpnConsent = false
    private var vpnAdbConsentTried = false
    private var awaitingWirelessPermissions = false
    private var wirelessPermissionsRefused = false
    private var awaitingWiredPermissions = false
    private var wiredPermissionsResolved = false
    private var awaitingLocationPermission = false
    private var vpnReady = false
    private var hotspotStatus = HotspotStatus(state = "off")
    private var menuOpen = false
    private var latestStage = "Preparing CarPlay"
    /** English strings for [friendlyStage], whose keywords are English whatever the UI language. */
    private val englishResources: Resources by lazy {
        createConfigurationContext(Configuration(resources.configuration).apply { setLocale(Locale.ENGLISH) })
            .resources
    }
    private var darkMode = false
    // main #594：菜单叠加层配色，跟随日夜态；原固定 MENU_* 常量保留供旧代码使用（作者：TRAE AI）。
    private val overlayPalette: DiPlayPalette get() = DiPlayPalette.of(darkMode)
    // main B7：应用外观联动——主机页持有外观归属权并向运行时发布当前夜间态（作者：TRAE AI）。
    override val currentAppNight: Boolean get() = appNight
    private var appNight = true
    private var hostAppearanceResumed = false
    // main #539：BYD 来电弹窗隐藏的会话就绪标记与开关缓存（作者：TRAE AI）。
    private var callPopupSessionReady = false
    private var hideBydCallPopup = false
    private var paintWaitingScreen: () -> Unit = {}
    private var carPlayNightMode = CarPlayNightMode.SYSTEM
    private var nightSchedule = CarPlayNightSchedule()
    private var ambientLightThreshold = AmbientLightThreshold()
    private var ambientDelaySeconds = 2
    private var nightModeDiagnosticSource = ThemeModeDiagnostics.Source.CARPLAY_MODE
    private val nightModeController by lazy {
        CarPlayNightModeController(
            light = AndroidAmbientLight(this),
            scheduler = MainThreadNightModeScheduler(),
            initialNight = darkMode,
            onNightChanged = { night ->
                darkMode = night
                // main B7：夜控结果发布到外观运行时并刷新应用外观（作者：TRAE AI）。
                if (hostAppearanceResumed) AppAppearanceRuntime.publishHost(this, night)
                refreshAppAppearance()
                paintWaitingScreen()
                paintSidePanelTheme()
                applyClusterTurnOverlay()
                appendLog("CarPlay switched to ${if (night) "night" else "day"} mode")
                logThemeState(nightModeDiagnosticSource, resources.configuration)
                syncAirPlayDarkMode(nightModeDiagnosticSource)
            },
        )
    }
    private val themeDiagnostics = ThemeModeDiagnostics()
    private var lastConfiguration: Configuration? = null
    private var activeAirPlaySession: AirPlaySession? = null
    private val activeScreenStreamTypes = mutableSetOf<Int>()
    private var handshakeResetInProgress = false
    private var startAfterHandshakeReset = false
    private var restartGeneration = 0
    private var reconnectScheduled = false
    private var sessionLog: SessionLogFile? = null
    private var gestureFingerCount = 3
    private var settingsGestureHint: TextView? = null
    private var gestureSequenceActive = false
    private var gestureTracking = false
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var recoveryPendingAfterMenu = false
    private var failurePendingAfterMenu: CarPlayStatus.Failed? = null
    private val shuttingDown = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sidePanel: LinearLayout? = null
    // 侧栏卡片上的全部内容（main #480；分支跳过 resize 模糊，此处仅作内容容器）。
    private var sidePanelContents: LinearLayout? = null
    private var sidePanelBattery: TextView? = null
    // 侧栏的轮胎气压（TPMS）：左前、右前、左后、右后；没有读数时隐藏。
    private var sidePanelTyres: LinearLayout? = null
    private var sidePanelTyreTexts: List<TextView> = emptyList()
    // 侧栏整块屏幕在窗口像素中的位置，以及面板朝向 CarPlay 的那条边
    // （面板在 CarPlay 右侧时为 Gravity.START，左侧时为 END，下方时为 TOP），用于拖拽这条边。
    private var sidePanelScreen: RectF? = null
    private var sidePanelEdge = Gravity.START
    private var sidePanelDrag: FloatArray? = null
    // 面板把手在侧栏打开或其边被触摸时显示几秒，然后淡出。
    private var sidePanelGrip: Drawable? = null
    private var sidePanelGripAlpha = 255
    private var sidePanelGripAnimator: ValueAnimator? = null
    private val sidePanelGripFade = Runnable { animateSidePanelGrip(0, 600L) }
    // 屏幕边缘的把手小条：把它拖出来即打开侧栏（drag 为 null 表示尚未开始拖拽）。
    private var sidePanelTab: View? = null
    private var sidePanelTabDrag: FloatArray? = null
    // 在把手上的触摸被判定为拖拽之前，它的事件副本暂存在这里；若不是拖拽（轻点、沿边缘滑动、长按），
    // 把手下方的 CarPlay 会收到这些事件以及该次触摸的后续部分。
    private val sidePanelTabHeld = mutableListOf<MotionEvent>()
    private var sidePanelTabToCarPlay = false
    private val sidePanelTabLongPress = Runnable {
        val tab = sidePanelTab
        if (tab != null && sidePanelTabDrag?.get(1) == 0f) handSidePanelTabTouchToCarPlay(tab)
    }
    private var sidePanelTabFrom: Int? = null
    // 把手的药丸提示在会话开始或把手被轻点时显示几秒，然后淡出；即使没有药丸，把手仍可拖拽。
    private var sidePanelTabHintFor: CarPlayViewAreas? = null
    private var sidePanelTabHint = false
    private val sidePanelTabFade = Runnable {
        sidePanelTabHint = false
        sidePanelTab?.background?.let { pill ->
            ObjectAnimator.ofInt(pill, "alpha", drawableAlpha(pill), 0).setDuration(600L).start()
        }
    }
    private var sidePanelShown = false
    private val sidePanelTick = object : Runnable {
        override fun run() {
            refreshSidePanel()
            if (sidePanelShown) mainHandler.postDelayed(this, SIDE_PANEL_REFRESH_MILLIS)
        }
    }
    private val teardownExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val airPlayCommandExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val logLines = ArrayDeque<LogEntry>()
    private val expireOldLogLines = Runnable { refreshLogView(System.currentTimeMillis()) }
    private val refreshTurnOverlay = object : Runnable {
        override fun run() {
            com.shilapi.xcertplay.hud.BydNavigationOutputs.refreshTurnOverlay()
            mainHandler.postDelayed(this, 1_000L)
        }
    }
    // Some head units (e.g. BYD DiLink) update resources.configuration for day/night
    // without delivering onConfigurationChanged, so poll while the activity is visible.
    private val pollConfiguration = object : Runnable {
        override fun run() {
            refreshConfiguration(source = ThemeModeDiagnostics.Source.POLL)
            mainHandler.postDelayed(this, CONFIGURATION_POLL_INTERVAL_MILLIS)
        }
    }
    private val applyDisplaySize = Runnable {
        val size = pendingDisplaySize ?: return@Runnable
        pendingDisplaySize = null
        applyDisplaySize(size)
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            val existing = currentSurface
            val surface = if (
                existing != null &&
                currentSurfaceTexture === texture &&
                existing.isValid
            ) {
                existing
            } else {
                Surface(texture).also {
                    videoSurfaceOwner.replace(it, releaseOnDetach = true)
                    currentSurfaceTexture = texture
                }
            }
            appendLog(if (existing === surface) "Texture surface reused" else "Texture surface created")
            attachSurface(surface)
            updateVideoLayout(width, height)
            scheduleDisplaySize(width, height)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            updateVideoLayout(width, height)
            scheduleDisplaySize(width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            if (currentSurfaceTexture !== texture) return true
            currentSurface?.let(videoSurfaceOwner::clear)
            currentSurfaceTexture = null
            appendLog("Texture surface destroyed")
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }

    private val fallbackSurfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            if (isDestroyed || holder !== fallbackVideoView?.holder) return
            val surface = holder.surface
            videoSurfaceOwner.replace(surface, releaseOnDetach = false)
            appendLog("SurfaceView video surface created valid=${surface.isValid}")
            attachSurface(surface)
            videoView?.let { updateVideoLayout(it.width, it.height) }
            // 波次十一批次D（main #669）：先叠上最后一帧，再向 iPhone 请求关键帧（所有 SurfaceView 输出，
            // 不止 smooth video），并在仍无帧时重试。
            showLastPicture()
            sink?.refreshPicture(SCREEN_TYPE_MAIN)
            mainHandler.removeCallbacks(pictureRetry)
            mainHandler.postDelayed(pictureRetry, 1_000L)
            mainHandler.postDelayed(pictureRetry, 2_000L)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (holder !== fallbackVideoView?.holder) return
            // Holder dimensions describe the fitted video, not the host window/CarPlay canvas.
            videoView?.let { updateVideoLayout(it.width, it.height) }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            val surface = holder.surface
            // 波次十一批次D（main #669）：surface 消失前拷下当前画面，供返回时叠显。
            captureLastPicture()
            // The SurfaceHolder contract: nothing may render to the surface once this returns. Every decoder
            // is asked at once, then waited for against one deadline; each confirms once it has moved,
            // parked or released its codec, and closing decoders count once their workers exit. With
            // smooth video the live session's main decoder parks off screen and keeps its state.
            val live = sink
            val detaches = (listOfNotNull(live) + retiringSinks).distinct()
                .map { owner -> owner.beginSurfaceDetach(surface, parkMain = smoothVideo && owner === live) }
            val confirmed = detaches.map { it.await() }.all { it }
            videoSurfaceOwner.clear(surface)
            appendLog("SurfaceView video surface destroyed detachConfirmed=$confirmed")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NavigationWidgetUpdater.attach(applicationContext)
        CarPlayCallKeys.install(applicationContext)
        CenterMapOverlay.requestShow = ::showCenterMap
        MapMirrors.sink = mirrorSink
        MapMirrors.onChanged = mirrorsChanged
        languagePreferenceAtCreate = AppLocale.preference(this)
        if (isIphoneUsbAttachment(intent)) {
            AirPlayPersistence.saveWirelessEnabled(this, false)
        }
        if (runCatching { DiPlayBootstrap.ensure(this, AirPlayPersistence.loadMfiTarget(this)) }.isFailure) {
            OwnScreenStart.start(this, Intent(this, DiPlayActivity::class.java), ::appendLog)
            finish(); return
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        (getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager)
            ?.registerDisplayListener(clusterDisplayListener, mainHandler)
        initializeSessionLog()
        lastConfiguration = Configuration(resources.configuration)
        darkMode = savedInstanceState?.getBoolean("carplay_night_active")
            ?: nightModeOrNull(resources.configuration.uiMode) ?: false
        logThemeState(ThemeModeDiagnostics.Source.CREATE, resources.configuration)
        advancedAudioChannelMappingSupported =
            resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)
        airPlayIdentity = AirPlayPersistence.loadIdentity(this)
        loadPersistedSettings()
        locationPermissionAvailable = hasFineLocationPermission()
        setContentView(buildContentView())
        applyFullscreenMode()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (picturePanel != null) {
                        closePicturePanel()
                    } else if (menuOpen) {
                        if (safeAreaEditorActive) closeSafeAreaEditor() else leaveSettingsMenu()
                    } else {
                        showDiPlayHome()
                    }
                }
            },
        )

        appendLog(
            "Host started; MFI target=${mfiTargetLabel(mfiTarget)}; " +
                "transport=${if (wirelessEnabled) "wireless" else "wired"}",
        )
        val reusedBackgroundSession = adoptBackgroundSession()
        microphoneAvailable =
            PermissionCompat.hasPermission(this, Manifest.permission.RECORD_AUDIO)
        microphonePermissionResolved = microphoneAvailable
        if (reusedBackgroundSession) {
            updateDebugOverlays()
        } else if (microphonePermissionResolved) {
            requestStartupPrerequisites()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun loadPersistedSettings() {
        carPlayDock = CarPlayDock.load(this)
        carPlayNightMode = AirPlayPersistence.loadCarPlayNightMode(this)
        ambientLightThreshold = AirPlayPersistence.loadAmbientLightThreshold(this)
        ambientDelaySeconds = AirPlayPersistence.loadAmbientDelaySeconds(this)
        nightSchedule = AirPlayPersistence.loadCarPlayNightSchedule(this)
        nightModeController.configure(
            carPlayNightMode,
            nightModeOrNull(resources.configuration.uiMode) ?: false,
            ambientLightThreshold,
            ambientDelaySeconds,
            nightSchedule,
        )
        gestureFingerCount = AirPlayPersistence.loadSettingsGestureFingers(this)
        displayScalePercent = AirPlayPersistence.loadDisplayScalePercent(this)
        displayScaleTenths = CarPlayDisplayScale.sanitize((displayScalePercent + 5) / 10)
        // Size is now chosen only through CarPlaySize; ignore the canvas scale older builds stored.
        uiScalePercent = CarPlayUiScale.DEFAULT
        hevcEnabled = AirPlayPersistence.loadHevcEnabled(this)
        hevcSoftwareDecoderEnabled =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                AirPlayPersistence.loadHevcSoftwareDecoderEnabled(this)
        advancedAudioChannelMapping =
            advancedAudioChannelMappingSupported &&
                AirPlayPersistence.loadAdvancedAudioChannelMapping(this)
        navigationStreamType = AirPlayPersistence.loadNavigationStreamType(this)
        debugLogsEnabled = AirPlayPersistence.loadDebugLogsEnabled(this)
        autoStartOnBoot = AirPlayPersistence.loadAutoStartOnBoot(this)
        manufacturer = AirPlayPersistence.loadManufacturer(this)
        model = AirPlayPersistence.loadModel(this)
        oemLabel = AirPlayPersistence.loadOemLabel(this)
        fps = AirPlayPersistence.loadFps(this)
        widthPhysicalMm = AirPlayPersistence.loadWidthPhysicalMm(this)
        physicalSizeBasis = AirPlayPersistence.loadPhysicalSizeBasis(this)
        AirPlayPersistence.loadMaximumDetectedDisplay(this).let { (width, height) ->
            maximumDetectedWidthPixels = width
            maximumDetectedHeightPixels = height
        }
        rightHandDrive = AirPlayPersistence.loadRightHandDrive(this)
        hideTopBar = AirPlayPersistence.loadHideTopBar(this)
        hideBottomBar = AirPlayPersistence.loadHideBottomBar(this)
        temporarySystemBarsEnabled = AirPlayPersistence.loadTemporarySystemBars(this)
        harmanFullscreenEnabled = AirPlayPersistence.loadHarmanFullscreen(this)
        safeAreaDrawOutside = AirPlayPersistence.loadSafeAreaDrawOutside(this)
        mainDisplayDisableSafeArea = AirPlayPersistence.loadMainDisplayDisableSafeArea(this)
        locationPermissionAvailable = hasFineLocationPermission()
        loadConnectionSettings()
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
    }

    /**
     * Connection settings owned by the settings screen ([DiPlayActivity]). It saves them in this
     * process while this screen keeps running, so they are re-read on every resume instead of
     * relying on the snapshot [onCreate] took: otherwise the next handshake would keep using the
     * mode, hotspot and MFI settings that were current when this screen was first opened.
     */
    private fun loadConnectionSettings() {
        hideBydCallPopup = AirPlayPersistence.loadHideBydCallPopup(this)
        wirelessEnabled = AirPlayPersistence.loadWirelessEnabled(this)
        mfiTarget = AirPlayPersistence.loadMfiTarget(this)
        mfiI2cPath = AirPlayPersistence.loadMfiI2cPath(this)
        remoteMfiServer = AirPlayPersistence.loadRemoteMfiServer(this)
        remoteMfiToken = AirPlayPersistence.loadRemoteMfiToken(this)
        locationReportingEnabled = AirPlayPersistence.loadLocationReportingEnabled(this)
        wirelessHotspotMode = AirPlayPersistence.loadWirelessHotspotMode(this)
        existingWifiSsid = AirPlayPersistence.loadExistingWifiSsid(this)
        existingWifiPassphrase = AirPlayPersistence.loadExistingWifiPassphrase(this)
        manualHotspotSsid = AirPlayPersistence.loadManualHotspotSsid(this)
        manualHotspotPassphrase = AirPlayPersistence.loadManualHotspotPassphrase(this)
        manualHotspotBand = AirPlayPersistence.loadManualHotspotBand(this)
        manualHotspotChannel = AirPlayPersistence.loadManualHotspotChannel(this)
        manualHotspotSecurity = AirPlayPersistence.loadManualHotspotSecurity(this)
    }

    private fun requestStartupPrerequisites() {
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
            return
        }
        if (wirelessEnabled) {
            requestWirelessPermissions()
        } else if (!requestWiredPermissions()) {
            requestVpnConsent()
        }
    }

    /**
     * 申请有线启动所需的权限([WiredPermissions]);请求进行中返回 true。被拒绝仅记录日志并继续启动:
     * 部分 ROM 上 USB 网络仍可通行。
     */
    private fun requestWiredPermissions(): Boolean {
        if (wiredPermissionsResolved) return false
        val missing = WiredPermissions.required(Build.VERSION.SDK_INT)
            .filter { !PermissionCompat.hasPermission(this, it) }
        if (missing.isEmpty()) return false
        if (!awaitingWiredPermissions) {
            awaitingWiredPermissions = true
            wiredPermissions.launch(missing.toTypedArray())
        }
        return true
    }

    private fun requestLocationPermission() {
        if (locationPermissionAvailable || awaitingLocationPermission) return
        awaitingLocationPermission = true
        locationPermission.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    private fun hasFineLocationPermission(): Boolean =
        PermissionCompat.hasPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)

    // main 4aee3f80：VPN 授权页缺失/被阻塞（车机 ROM 精简了系统 VPN 设置页）时不崩溃，给出可诊断的降级提示。
    private fun requestVpnConsent() {
        if (awaitingVpnConsent) return
        val consent = try {
            CarPlayVpnService.prepare(this)
        } catch (error: ActivityNotFoundException) {
            onVpnConsentUnavailable("prepare", null, error)
            return
        } catch (error: SecurityException) {
            onVpnConsentUnavailable("prepare", null, error)
            return
        }
        if (consent == null) {
            vpnReady = true
            maybeStartCarPlay()
            return
        }
        vpnReady = false
        awaitingVpnConsent = true
        try {
            vpnConsent.launch(consent)
        } catch (error: ActivityNotFoundException) {
            onVpnConsentUnavailable("launch", consent, error)
        } catch (error: SecurityException) {
            onVpnConsentUnavailable("launch", consent, error)
        }
    }

    private fun onVpnConsentResult(resultCode: Int) {
        // A failed launch clears the pending state; a late result must not authorize that attempt.
        if (!awaitingVpnConsent) return
        awaitingVpnConsent = false
        vpnReady = resultCode == RESULT_OK
        if (vpnReady) {
            maybeStartCarPlay()
        } else {
            setStatus(R.string.vpn_consent_was_denied)
        }
    }

    private fun onVpnConsentUnavailable(operation: String, consent: Intent?, error: RuntimeException) {
        awaitingVpnConsent = false
        vpnReady = false
        val diagnostic = "VPN consent unavailable operation=$operation " +
            "failureClass=${error.javaClass.simpleName} " +
            "component=${consent?.component?.flattenToString() ?: "none"}"
        Log.w(TAG, diagnostic, error)
        appendLog(diagnostic)
        setStatus(R.string.vpn_authorization_unavailable)
        // main #604：无授权弹窗的固件（Geely Galaxy E8）用已批准的 adb 兜底。
        if (!vpnAdbConsentTried) preConsentVpnOverAdb()
    }

    /** Without a consent dialog, the car's approved adb can allow the VPN instead (#604). */
    private fun preConsentVpnOverAdb() {
        vpnAdbConsentTried = true
        val app = applicationContext
        Thread({
            val access = runCatching { VpnPreConsent.grant(app) }.getOrDefault(LocalAdb.Access.UNREACHABLE)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val granted = runCatching { CarPlayVpnService.prepare(this) == null }.getOrDefault(false)
                appendLog("VPN consent over adb access=$access granted=$granted")
                if (granted && !awaitingVpnConsent && !vpnReady) {
                    vpnReady = true
                    maybeStartCarPlay()
                }
            }
        }, "diplay-vpn-adb-consent").start()
    }

    private fun requestWirelessPermissions() {
        val permissions = requiredWirelessPermissions()
        if (permissions.all { PermissionCompat.hasPermission(this, it) }) {
            wirelessPermissionsReady = true
            updateHotspotStatusBlock()
            maybeStartCarPlay()
            return
        }
        wirelessPermissionsReady = false
        updateHotspotStatusBlock()
        awaitingWirelessPermissions = true
        wirelessPermissions.launch(permissions.toTypedArray())
    }

    private fun hasRequiredWirelessPermissions(): Boolean =
        requiredWirelessPermissions().all { PermissionCompat.hasPermission(this, it) }

    // 移植 main #465：权限清单抽取到 WirelessPermissions 以保持版本门控可测试；
    // API 17 上 sdkInt < 21，走 ACCESS_COARSE/FINE_LOCATION 分支，ACCESS_LOCAL_NETWORK 为 no-op。
    private fun missingWirelessPermissions(): List<String> =
        requiredWirelessPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    /**
     * Once the wireless start permissions were refused, by the driver or silently by Android (#290), the
     * screen names the access to allow instead of waiting for the iPhone. Allowing it in the app permissions
     * brings the driver back here, where the start goes on.
     */
    private fun showMissingWirelessPermission() {
        if (wirelessPermissionsReady || !wirelessEnabled) {
            wirelessPermissionsRefused = false
            return
        }
        if (!wirelessPermissionsRefused) return
        setConnectionStage(WirelessPermissions.refusalMessage(missingWirelessPermissions()))
    }

    private fun requiredWirelessPermissions(): List<String> =
        WirelessPermissions.required(wirelessHotspotMode, Build.VERSION.SDK_INT)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isIphoneUsbAttachment(intent)) {
            if (wirelessEnabled) {
                if (menuOpen) cancelSettingsEdits()
                AirPlayPersistence.saveWirelessEnabled(this, false)
                wirelessEnabled = false
                wirelessPermissionsReady = true
                if (controller != null) {
                    restartCarPlay("switching to USB")
                }
                requestStartupPrerequisites()
            } else if (controller == null) {
                requestStartupPrerequisites()
            }
        }
    }

    private fun isIphoneUsbAttachment(intent: Intent): Boolean {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return false
        // API 17 迁移：androidx.core 1.9.0 无 IntentCompat（1.13+ 才有），
        // 使用旧版 getParcelableExtra(String)（API 1 起可用，API 33 才废弃）
        @Suppress("DEPRECATION")
        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        return device?.vendorId == IphoneUsbMatcher.APPLE_VENDOR_ID
    }

    override fun onStart() {
        super.onStart()
        isActivityStarted = true
        logThemeState(ThemeModeDiagnostics.Source.START, resources.configuration)
        mainHandler.removeCallbacks(pollConfiguration)
        mainHandler.post(pollConfiguration)
        CenterMapOverlay.onDiPlayScreenShown()
        homeMonitor?.stop()
        homeScreenVisible = null
        // 在此读取设置，使在设置页中的修改能在回到投屏画面时生效（main #496）。
        if (AirPlayPersistence.loadFpsCounter(this)) fpsCounter?.start() else fpsCounter?.stop()
    }

    override fun onResume() {
        super.onResume()
        WheelKeyService.restoreIfNeeded(this)
        // main B7：恢复期间本页持有外观归属权（作者：TRAE AI）。
        hostAppearanceResumed = true
        updateCallPopupGuard()
        val savedNightMode = AirPlayPersistence.loadCarPlayNightMode(this)
        val savedThreshold = AirPlayPersistence.loadAmbientLightThreshold(this)
        val savedDelay = AirPlayPersistence.loadAmbientDelaySeconds(this)
        val savedSchedule = AirPlayPersistence.loadCarPlayNightSchedule(this)
        val systemNight = nightModeOrNull(resources.configuration.uiMode) ?: false
        if (savedNightMode != carPlayNightMode || savedThreshold != ambientLightThreshold ||
            savedDelay != ambientDelaySeconds || savedSchedule != nightSchedule) {
            carPlayNightMode = savedNightMode
            ambientLightThreshold = savedThreshold
            ambientDelaySeconds = savedDelay
            nightSchedule = savedSchedule
            nightModeController.configure(carPlayNightMode, systemNight, ambientLightThreshold, ambientDelaySeconds,
                nightSchedule)
        }
        nightModeController.resume(systemNight)
        // main B7：发布主机页当前夜间态并按应用外观偏好刷新（作者：TRAE AI）。
        AppAppearanceRuntime.publishHost(this, darkMode)
        refreshAppAppearance()
        if (!menuOpen) {
            displayScalePercent = AirPlayPersistence.loadDisplayScalePercent(this)
            displayScaleTenths = CarPlayDisplayScale.sanitize((displayScalePercent + 5) / 10)
            updateResolutionMenu()
        }
        if (intent.getBooleanExtra("picture_controls", false)) {
            intent.removeExtra("picture_controls")
            openPicturePanel()
        }
        val languagePreference = AppLocale.preference(this)
        if (Build.VERSION.SDK_INT < 33 && languagePreference != languagePreferenceAtCreate) {
            languagePreferenceAtCreate = languagePreference
            recreate()
            return
        }
        // The settings screen returns here with FLAG_ACTIVITY_REORDER_TO_FRONT, so this screen is
        // resumed, not recreated: refresh what that screen can change before it is used again.
        if (!menuOpen) loadConnectionSettings()
        locationPermissionAvailable = hasFineLocationPermission()
        if (locationReportingEnabled && !locationPermissionAvailable && !menuOpen) {
            requestLocationPermission()
        }
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
        showMissingWirelessPermission()
        advancedAudioChannelMapping =
            advancedAudioChannelMappingSupported &&
                AirPlayPersistence.loadAdvancedAudioChannelMapping(this)
        if (DiLink51ClusterLayout.automatic(this) && clusterMonitor == null) {
            clusterMonitor = DiLink51ClusterMonitor(this, ::onClusterActivityState).also { it.start() }
        } else if (!DiLink51ClusterLayout.automatic(this)) {
            clusterMonitor?.stop()
            clusterMonitor = null
        }
        if (!menuOpen) gestureFingerCount = AirPlayPersistence.loadSettingsGestureFingers(this)
        if (!menuOpen) temporarySystemBarsEnabled = AirPlayPersistence.loadTemporarySystemBars(this)
        settingsGestureHint?.text = settingsGestureHintText()
        ensureClusterPresentation()
        AirPlayPersistence.overlaySettingsListener = { runOnUiThread { applyClusterTurnOverlay() } }
        com.shilapi.xcertplay.hud.BydNavigationOutputs.setTurnOverlayListener(clusterTurnOverlayListener)
        mainHandler.removeCallbacks(refreshTurnOverlay)
        mainHandler.post(refreshTurnOverlay)
        var systemBarsChanged = false
        if (!menuOpen) {
            val savedHideTopBar = AirPlayPersistence.loadHideTopBar(this)
            val savedHideBottomBar = AirPlayPersistence.loadHideBottomBar(this)
            val savedHarmanFullscreen = AirPlayPersistence.loadHarmanFullscreen(this)
            systemBarsChanged = hideTopBar != savedHideTopBar ||
                hideBottomBar != savedHideBottomBar ||
                harmanFullscreenEnabled != savedHarmanFullscreen
            hideTopBar = savedHideTopBar
            hideBottomBar = savedHideBottomBar
            harmanFullscreenEnabled = savedHarmanFullscreen
        }
        maybeStartCarPlay()
        applyFullscreenMode()
        if (systemBarsChanged) refreshDisplaySizeAfterLayout()
        videoView?.post {
            val view = videoView ?: return@post
            if (view.width > 0 && view.height > 0) {
                scheduleDisplaySize(view.width, view.height)
            }
        }
    }


    // Experimental: the CarPlay instrument-cluster stream on the BYD cluster projection display.
    private fun effectiveClusterTheme(): DiLink51ClusterLayout.Theme =
        if (DiLink51ClusterLayout.automatic(this)) detectedCluster.theme ?: DiLink51ClusterLayout.theme(this)
        else DiLink51ClusterLayout.theme(this)

    private fun onClusterActivityState(state: ClusterActivityState.Snapshot) {
        if (state != detectedCluster) appendLog("Cluster map: detected theme=${state.theme} mapVisible=${state.mapVisible}")
        detectedCluster = state
        if (!AirPlayPersistence.loadClusterMapEnabled(this)) { dismissClusterPresentation(); return }
        ensureClusterPresentation()
    }

    private fun ensureClusterPresentation() {
        if (!AirPlayPersistence.loadClusterMapEnabled(this)) {
            clusterLaunchGate.reset()
            dismissClusterPresentation()
            return
        }
        if (AdbClusterRouter.enabled(this)) {
            ClusterActivityOutput.bind(this, taskId) { onClusterSurface(it) }
            ClusterActivityOutput.setStreamActive(SCREEN_TYPE_ALT in activeScreenStreamTypes)
            applyClusterTurnOverlay()
            // main #663：等本窗口先取到焦点再拉起集群窗口（见 clusterLaunchGate 注释）。
            if (ClusterActivityOutput.hasConfirmedRoute() ||
                clusterLaunchGate.request(SystemClock.uptimeMillis(), hasWindowFocus())) launchClusterActivity()
            else scheduleClusterLaunchTick()
            return
        }
        clusterLaunchGate.reset()
        ClusterActivityOutput.stop(this)
        val theme = effectiveClusterTheme()
        if (DiLink51ClusterLayout.supported()) {
            ensureDiLink51ClusterPresentation(theme)
            return
        }
        if (clusterPresentation != null) return
        val display = ClusterMapPresentation.findDisplay(this, theme) ?: run {
            appendLog("Cluster map: no cluster projection display among ${ClusterMapPresentation.describeDisplays(this)}")
            return
        }
        val presentation = ClusterMapPresentation(this, display, theme) { surface -> runOnUiThread { onClusterSurface(surface) } }
        // The system dismisses a presentation when its display goes away; allow a new one on resume.
        presentation.setOnDismissListener {
            if (clusterPresentation === presentation) {
                clusterPresentation = null
                com.shilapi.xcertplay.hud.BydNavigationOutputs.setClusterMapShown(false)
                updateClusterMapShown()
            }
        }
        try {
            presentation.show()
            clusterPresentation = presentation
            updateClusterMapShown()
            presentation.setStreamActive(SCREEN_TYPE_ALT in activeScreenStreamTypes)
            applyClusterTurnOverlay()
            Log.i(ClusterMapPresentation.TAG, "cluster presentation shown display=${display.displayId} name=${display.name}")
            appendLog("Cluster map: presentation shown display=${display.displayId} name=${display.name}")
        } catch (error: RuntimeException) {
            Log.w(ClusterMapPresentation.TAG, "cluster presentation failed", error)
            appendLog("Cluster map: presentation failed ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    // DiLink 4 (#536)：在本窗口首次取焦前拉起集群窗口，会让集群窗口带走系统栏——之后把焦点点回来
    // 的那一次触摸会撑大窗口并重启 CarPlay。故集群启动改为等这次焦点（或宿主离开屏幕），CarPlay 也
    // 暂扣到集群就绪，保证首个会话就带上集群流。main #663。
    private val clusterLaunchGate = ClusterLaunchGate()
    private val clusterLaunchTick = Runnable { onClusterLaunchTick() }
    private var carPlayStartHeldForCluster = false

    private fun launchClusterActivity() {
        runCatching { ClusterActivityOutput.ensure(this) }.onFailure {
            appendLog("Cluster activity: launch failed ${it.javaClass.simpleName}: ${it.message}")
        }
        scheduleClusterLaunchTick()
    }

    private fun scheduleClusterLaunchTick() {
        mainHandler.removeCallbacks(clusterLaunchTick)
        mainHandler.postDelayed(clusterLaunchTick, ClusterLaunchGate.TICK_MILLIS)
    }

    private fun onClusterLaunchTick() {
        mainHandler.removeCallbacks(clusterLaunchTick)
        if (isDestroyed) return
        val now = SystemClock.uptimeMillis()
        if (clusterLaunchGate.due(now, hasWindowFocus(), isActivityStarted)) {
            appendLog("Cluster activity: launching after the main window " +
                if (hasWindowFocus()) "took focus" else if (isActivityStarted) "waited for focus" else "left the screen")
            launchClusterActivity()
        } else if (clusterLaunchGate.holdsCarPlayStart(now, ClusterActivityOutput.routeSettling())) {
            scheduleClusterLaunchTick()
        } else if (carPlayStartHeldForCluster) {
            carPlayStartHeldForCluster = false
            maybeStartCarPlay()
        }
    }

    private fun ensureDiLink51ClusterPresentation(theme: DiLink51ClusterLayout.Theme) {
        val fullMap = theme == DiLink51ClusterLayout.Theme.MAP
        val visible = !DiLink51ClusterLayout.automatic(this) || detectedCluster.mapVisible
        clusterLayers.filterKeys { it != fullMap }.values.forEach { it.setMapVisible(false) }
        var target = clusterLayers[fullMap]
        if (target == null) {
            val display = ClusterMapPresentation.findDisplay(this, theme) ?: return
            lateinit var presentation: ClusterMapPresentation
            presentation = ClusterMapPresentation(this, display, theme) { surface ->
                runOnUiThread {
                    if (clusterPresentation === presentation) onClusterSurface(surface)
                }
            }
            presentation.setOnDismissListener {
                if (clusterLayers[fullMap] === presentation) clusterLayers.remove(fullMap)
                if (clusterPresentation === presentation) clusterPresentation = null
                updateClusterMapShown()
            }
            try {
                presentation.setMapVisible(false)
                clusterPresentation = presentation
                clusterLayers[fullMap] = presentation
                presentation.show()
                appendLog("Cluster map: retained layer display=${display.displayId} fullMap=$fullMap")
                target = presentation
            } catch (error: RuntimeException) {
                clusterLayers.remove(fullMap)
                clusterPresentation = null
                appendLog("Cluster map: presentation failed ${error.javaClass.simpleName}: ${error.message}")
                return
            }
        }
        clusterPresentation = target
        target.outputSurface?.let(::onClusterSurface)
        target.setStreamActive(SCREEN_TYPE_ALT in activeScreenStreamTypes)
        target.setMapVisible(visible)
        updateClusterMapShown()
        applyClusterTurnOverlay()
    }

    private fun applyClusterTurnOverlay() {
        val overlay = CarPlayClusterDisplay.usesCustomTurnCard(AirPlayPersistence.loadClusterContent(this))
        ClusterActivityOutput.setTurnCard(if (overlay) clusterTurnGuidance else null,
            AirPlayPersistence.loadClusterTurnCardOverlayXPercent(this),
            AirPlayPersistence.loadClusterTurnCardOverlayYPercent(this),
            AirPlayPersistence.loadClusterTurnCardOverlaySizePercent(this),
            AirPlayPersistence.loadClusterTurnCardOpacityPercent(this), darkMode)
        val presentations = (clusterLayers.values + listOfNotNull(clusterPresentation)).distinct()
        for (presentation in presentations) {
            presentation.setTurnCardOverlay(
                AirPlayPersistence.loadClusterTurnCardOverlayXPercent(this),
                AirPlayPersistence.loadClusterTurnCardOverlayYPercent(this),
                AirPlayPersistence.loadClusterTurnCardOverlaySizePercent(this),
            )
            presentation.setTurnCardOpacity(AirPlayPersistence.loadClusterTurnCardOpacityPercent(this))
            presentation.setTurnCardNightMode(darkMode)
            presentation.setTurnCardGuidance(if (overlay) clusterTurnGuidance else null)
        }
    }

    private fun dismissClusterPresentation() {
        ClusterActivityOutput.stop(this)
        val presentations = (clusterLayers.values + listOfNotNull(clusterPresentation)).distinct()
        clusterLayers.clear()
        clusterPresentation = null
        clusterSurface?.let { sink?.clearSurface(SCREEN_TYPE_ALT, it) }
        clusterSurface = null
        presentations.forEach { runCatching { it.dismiss() } }
        com.shilapi.xcertplay.hud.BydNavigationOutputs.setClusterMapShown(false)
        controller?.setDashboardMapOutputVisible(false)
    }

    private fun onClusterSurface(surface: Surface?) {
        if (clusterSurface === surface) return
        // A direct handoff lets MediaCodec.setOutputSurface preserve its reference frames.
        // Clearing first would destroy the decoder and can leave stream 111 waiting for an IDR.
        if ((!DiLink51ClusterLayout.supported() && !AdbClusterRouter.enabled(this)) || surface == null) {
            clusterSurface?.let { old -> sink?.clearSurface(SCREEN_TYPE_ALT, old) }
        }
        clusterSurface = surface
        // Never fall back to the main surface: two decoders must not draw into one Surface.
        if (surface != null) {
            if (AdbClusterRouter.enabled(this) && ClusterActivityOutput.hasConfirmedRoute() &&
                !adbClusterConfigured && controller != null) {
                ClusterActivityOutput.setStreamActive(false)
                reconnectAfterLoss("DiLink 4 cluster confirmed; requesting its native stream")
            } else sink?.setSurface(SCREEN_TYPE_ALT, surface)
        }
        updateClusterMapShown()
    }

    private var adbClusterConfigured = false
    // Whether the running session's cluster stream was sized for a physical cluster display.
    private var clusterStreamOnDisplay = false

    // DiLink 3 creates its cluster display only after DiPlay first projects to the cluster, which can
    // finish after the session has already asked for the virtual fallback stream.
    private val clusterDisplayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            if (isDestroyed || !AirPlayPersistence.loadClusterMapEnabled(this@CarPlayHostActivity)) return
            val display = ClusterMapPresentation.findDisplay(this@CarPlayHostActivity, effectiveClusterTheme())
            if (display?.displayId != displayId) return
            appendLog("Cluster map: display ${display.name} appeared")
            ensureClusterPresentation()
            if (controller != null && !clusterStreamOnDisplay) {
                reconnectAfterLoss("Cluster display appeared; requesting its stream")
            }
        }
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = Unit
    }

    private fun clusterDisplayConfig(): AirPlayDisplayConfig? {
        adbClusterConfigured = false
        clusterStreamOnDisplay = false
        if (!AirPlayPersistence.loadClusterMapEnabled(this)) return null
        if (AdbClusterRouter.enabled(this) && ClusterActivityOutput.hasConfirmedRoute()) {
            adbClusterConfigured = true
            clusterStreamOnDisplay = true
            return DiLink4ClusterDisplay.streamConfig(AirPlayPersistence.loadClusterContent(this),
                safeAreaRect = AirPlayPersistence.loadClusterSafeAreaRect(this),
                markerXPercent = AirPlayPersistence.loadClusterMarkerXPercent(this),
                markerYPercent = AirPlayPersistence.loadClusterMarkerYPercent(this)).also {
                MapMirrors.streamAspect = it.widthPixels.toDouble() / it.heightPixels
                appendLog("Cluster activity: requesting stream 111 at ${it.widthPixels}x${it.heightPixels}; safeArea=${it.safeArea} drawOutside=${it.safeAreaDrawOutside}; ADB task routing")
            }
        }
        val theme = effectiveClusterTheme()
        val display = ClusterMapPresentation.findDisplay(this, theme)
        if (display != null) {
            val size = ClusterMapPresentation.sizeOf(display)
            if (size.x > 0 && size.y > 0) {
                clusterStreamOnDisplay = true
                if (DiLink51ClusterLayout.supported()) {
                    val plan = DiLink51ClusterLayout.plan(size.x, size.y, theme) ?: return null
                    return DiLink51ClusterLayout.streamConfig().also {
                        MapMirrors.streamAspect = it.widthPixels.toDouble() / it.heightPixels
                        appendLog("Cluster map: fixed 1920x720 stream; layout=$theme viewport=$plan")
                    }
                }
                if (DiLink4ClusterDisplay.matches(display.name, size.x, size.y)) {
                    return DiLink4ClusterDisplay.streamConfig(
                        AirPlayPersistence.loadClusterContent(this),
                        safeAreaRect = AirPlayPersistence.loadClusterSafeAreaRect(this),
                        markerXPercent = AirPlayPersistence.loadClusterMarkerXPercent(this),
                        markerYPercent = AirPlayPersistence.loadClusterMarkerYPercent(this),
                    ).also { MapMirrors.streamAspect = it.widthPixels.toDouble() / it.heightPixels }
                }
                val requestedScale = AirPlayPersistence.loadClusterMapScalePercent(this)
                fun streamAt(scale: Int) = CarPlayClusterDisplay.config(
                    size.x,
                    size.y,
                    scale,
                    0,
                    0,
                    AirPlayPersistence.loadClusterContent(this),
                    markerXPercent = AirPlayPersistence.loadClusterMarkerXPercent(this),
                    markerYPercent = AirPlayPersistence.loadClusterMarkerYPercent(this),
                )
                val requested = streamAt(requestedScale)
                // The smaller-map preset enlarges the encoded canvas beyond this panel. Probe
                // the same selected hardware decoder as the main-screen enlargement guard.
                val effective = if (requestedScale > 100) {
                    val support = largerCanvasSupport(requested)
                    appendLog("Cluster map: ${support.details}")
                    if (support.supported) requested else {
                        val native = streamAt(100)
                        val fallback = if (largerCanvasSupport(native).supported) 100
                            else CarPlayClusterDisplay.STREAM_SCALE_PERCENT
                        AirPlayPersistence.saveClusterMapScalePercent(this, fallback)
                        appendLog("Cluster map: scale $requestedScale% refused (${support.reason}); using $fallback%")
                        streamAt(fallback)
                    }
                } else requested
                return effective.also {
                    MapMirrors.streamAspect = it.widthPixels.toDouble() / it.heightPixels
                    appendLog("Cluster map: requesting ${it.widthPixels}x${it.heightPixels} on ${size.x}x${size.y} safeArea=${it.safeArea} url=${it.initialUrl}")
                }
            }
        }
        // Fallback for head units without physical cluster projection (e.g. DiLink 3.0/3.5 without digital cluster):
        // Provide standard virtual cluster stream (1280x720, 16:9 aspect) for CenterMapOverlay and MapEmbedService.
        MapMirrors.streamAspect = MapMirrors.VIRTUAL_STREAM_ASPECT
        return CarPlayClusterDisplay.config(
            widthPixels = 1280,
            heightPixels = 720,
            scalePercent = 100,
            content = AirPlayPersistence.loadClusterContent(this),
            baseSafeArea = CarPlayClusterDisplay.VIRTUAL_SAFE_AREA_PERCENT,
        ).also {
            appendLog("Cluster map: requesting virtual ${it.widthPixels}x${it.heightPixels} (16:9) stream for launcher/center card")
        }
    }

    // Guards the one-shot remap of a learned OEM signal to its CarPlay logical key (see dispatchKeyEvent).
    private var remappingLearnedKey = false

    // Hardware navigation belongs to the iPhone-rendered CarPlay UI, not Android View focus.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Owner-learned OEM keys: translate the vendor signal to its logical key once, then let the
        // existing media/voice/phone paths below handle it. A no-op when nothing has been learned.
        if (!remappingLearnedKey) {
            val learned = HardwareKeyBindings.logicalKey(this, event)
            if (learned != null && learned != event.keyCode) {
                remappingLearnedKey = true
                try {
                    return dispatchKeyEvent(
                        KeyEvent(event.downTime, event.eventTime, event.action, learned,
                            event.repeatCount, event.metaState, event.deviceId, event.scanCode),
                    )
                } finally {
                    remappingLearnedKey = false
                }
            }
        }
        val physicalKey = Triple(event.deviceId, event.keyCode, event.scanCode)
        val downOrUp = event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP
        if (downOrUp && physicalKey in legacySiriPresses) {
            if (event.action == KeyEvent.ACTION_UP) {
                legacySiriPresses.remove(physicalKey)
                requestLegacySiri(event.keyCode)
            }
            return true
        }
        // A release/repeat belongs to its original press even if a call, session or setting changed.
        if (downOrUp && siriKeyPresses.hasConsumedPress(physicalKey)) {
            siriKeyPresses.filter(physicalKey, event.action == KeyEvent.ACTION_DOWN, event.repeatCount == 0) {
                WheelZoomKeys.Action.PASS
            }
            return true
        }
        if (!menuOpen &&
            // main #549：外接控制器开启时，仅实体输入设备的按键驱动 CarPlay（虚拟导航键 Back 仍返回）。
            (AndroidTvInputMode.shouldUseKnobAsPrimaryInput(this) ||
                CarPlayRemoteKeys.fromExternalController(event, AirPlayPersistence.loadExternalController(this))) &&
            CarPlayRemoteKeys.dispatch(event, controller)) {
            if (event.repeatCount == 0) {
                Log.d(
                    TAG,
                    "remote key ${KeyEvent.keyCodeToString(event.keyCode)} action=${event.action}",
                )
            }
            return true
        }

        // During a CarPlay call the wheel's call key answers on the iPhone instead of opening BYD's phone app.
        if (CarPlayCallKeys.onKey(this, event.keyCode, event.action == KeyEvent.ACTION_DOWN, controller)) return true

        // The wheel key service, when it runs, takes an assigned Siri key before this window sees it.
        val assignedKey = WheelZoomSettings.isSiriKey(this, WheelKey.of(event))
        val assignedAction = if (downOrUp) siriKeyPresses.filter(
            physicalKey, event.action == KeyEvent.ACTION_DOWN, event.repeatCount == 0,
        ) {
            if (activeAirPlaySession == null || !assignedKey || inCall(this)) {
                return@filter WheelZoomKeys.Action.PASS
            }
            if (siriKey.opens(event.eventTime)) {
                val message = "Siri: assigned key ${event.keyCode} sent=${controller?.requestSiri() == true}"
                Log.i(WheelKeyService.TAG, message)
                appendLog(message)
            }
            WheelZoomKeys.Action.CONSUME
        } else WheelZoomKeys.Action.PASS
        if (assignedAction != WheelZoomKeys.Action.PASS) return true
        if (downOrUp && assignedKey) return super.dispatchKeyEvent(event)

        // Steering-wheel next/previous, claimed by the CarPlay screen itself. BYD gives these keys to
        // whichever window is in front, and this is the only route that reaches CarPlay without the
        // wheel key service — which a head unit with no ADB cannot turn on at all.
        val mediaPress = CarPlayMediaButton.forCarPlayWindow(
            event.keyCode,
            sessionActive = activeAirPlaySession != null,
            inCall = inCall(this),
        )
        if (downOrUp && mediaPress != null) {
            // A media button is a tap, and BYD sends each tap as an instant down/up pair: send once,
            // on the press. The release is swallowed rather than passed on, or BYD's own player would
            // receive an unpaired key.
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val sent = controller?.sendMediaButton(mediaPress) == true
                appendLog("Media: wheel key ${event.keyCode} -> CarPlay $mediaPress sent=$sent")
            }
            return true
        }

        // Keep DiPlay's existing steering-wheel/voice-key Siri handling intact.
        // main #750：开启"仅长按"后，短按语音键在 DiPlay 内被消费、不请求 Siri（作者：TRAE AI）。
        if (CarPlayMediaButton.isShortVoiceKey(event.keyCode) && WheelZoomSettings.siriLongPressOnly(this)) return true
        if (!CarPlayMediaButton.opensSiri(event.keyCode)) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) legacySiriPresses.add(physicalKey)
        if (event.action == KeyEvent.ACTION_UP) {
            requestLegacySiri(event.keyCode)
        }
        return true
    }

    private fun requestLegacySiri(keyCode: Int) {
        // main #751：一次按下的 CarPlay 键与通用键只放行第一个，避免第二次请求结束 Siri 会话（作者：TRAE AI）。
        val sent = VoiceKeyPair.shared.allowGenericKey(android.os.SystemClock.uptimeMillis()) && controller?.requestSiri() == true
        appendLog("Siri: voice key $keyCode sent=$sent")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            refreshConfiguration(source = ThemeModeDiagnostics.Source.WINDOW_FOCUS)
            applyFullscreenMode()
            onClusterLaunchTick()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("carplay_night_active", darkMode)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        cancelTemporarySystemBars()
        // main B7：暂停即释放外观归属权（作者：TRAE AI）。
        hostAppearanceResumed = false
        updateCallPopupGuard()
        AppAppearanceRuntime.clearHost(this)
        nightModeController.pause()
        super.onPause()
    }

    override fun onStop() {
        fpsCounter?.stop()
        closePicturePanel()
        // The controller, USB/iAP2 link, and VPN attachment intentionally outlive the UI.
        isActivityStarted = false
        logThemeState(ThemeModeDiagnostics.Source.STOP, resources.configuration)
        mainHandler.removeCallbacks(pollConfiguration)
        super.onStop()
        // Off screen there are no system bars to hide, so a launch waiting for focus can go now.
        onClusterLaunchTick()
        if (!isFinishing && !isChangingConfigurations) CenterMapOverlay.scheduleShow()
    }

    /** Shows the dashboard map as a card on the centre screen while DiPlay is in the background. */
    private fun showCenterMap() {
        if (isDestroyed || shuttingDown.get() || sink == null || isActivityStarted) return
        if (!AirPlayPersistence.loadCenterMapOverlay(this) || !AirPlayPersistence.loadClusterMapEnabled(this)) return
        if (!AirPlayPersistence.loadCenterMapFollowsDashboard(this)) return
        if (MapMirrors.launcherShowsMap) return // the launcher has the map on its own screen
        // Without the stream the card would stay black; it follows once the stream starts.
        if (SCREEN_TYPE_ALT !in activeScreenStreamTypes) return
        if (!CenterMapOverlay.permitted(this)) {
            appendLog("Centre map: no permission to draw over other apps")
            return
        }
        // Without Usage Access or when auto-hide is disabled, the card shows over any app, as before.
        if (AirPlayPersistence.loadCenterMapAutoHide(this) && HomeScreenMonitor.hasAccess(this)) {
            val monitor = homeMonitor ?: HomeScreenMonitor(this, ::onHomeScreenVisible).also { homeMonitor = it }
            if (!monitor.running) {
                monitor.start() // its first answer shows the card
                return
            }
            if (homeScreenVisible != true) {
                CenterMapOverlay.hide()
                return
            }
        }
        if (CenterMapOverlay.shown) return
        val shown = CenterMapOverlay.show(applicationContext, MapMirrors.streamAspect, ::onCenterMapSurface) {
            OwnScreenStart.start(this, Intent(this, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), ::appendLog)
        }
        appendLog("Centre map: card ${if (shown) "shown" else "failed"} streamActive=${SCREEN_TYPE_ALT in activeScreenStreamTypes}")
    }

    private fun onHomeScreenVisible(visible: Boolean) {
        homeScreenVisible = visible
        appendLog("Centre map: home screen ${if (visible) "in front" else "not in front"}")
        if (!AirPlayPersistence.loadCenterMapAutoHide(this)) {
            homeMonitor?.stop()
            if (!isActivityStarted && !CenterMapOverlay.diPlayInFront()) showCenterMap()
        } else if (!visible) CenterMapOverlay.hide()
        else if (!isActivityStarted && !CenterMapOverlay.diPlayInFront()) showCenterMap()
    }

    private fun onCenterMapSurface(surface: Surface?) {
        MapMirrors.set(MapMirrors.CARD, surface)
        appendLog(if (surface != null) "Centre map: mirroring the dashboard stream" else "Centre map: mirror stopped")
    }

    // The dashboard map pause must not stop the stream while a copy of the map is on screen.
    private fun updateClusterMapShown() {
        com.shilapi.xcertplay.hud.BydNavigationOutputs.setClusterMapShown((clusterPresentation != null ||
            (ClusterActivityOutput.hasConfirmedRoute() && clusterSurface != null)) && !MapMirrors.any)
        // Wheel zoom needs a physical map; centre-screen copies must not suppress that eligibility.
        val surface = clusterSurface?.takeIf { it.isValid }
        val presentation = clusterPresentation
        val presented = surface != null && presentation?.isShowing == true && presentation.mapVisible &&
            presentation.outputSurface === surface
        val direct = surface != null && adbClusterConfigured && ClusterActivityOutput.hasConfirmedRoute()
        controller?.setDashboardMapOutputVisible(presented || direct)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshConfiguration(newConfig, ThemeModeDiagnostics.Source.CALLBACK)
        applyFullscreenMode()
        stageStatusView?.maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        connectionHelpView?.maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        scrollLogsToBottom()
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        if (isInMultiWindowMode) cancelTemporarySystemBars()
        applyFullscreenMode()
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(hideTemporarySystemBars)
        discardLastPicture()
        // 波次十一批次D（main #669）：销毁时丢弃最后一帧并关闭其拷贝线程。
        // API17 适配：quitSafely 是 API 18+，dalvik 在 API 17 上把它替换成抛 NoSuchMethodError 的桩。
        if (pictureCopyThreadStarted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) pictureCopyThread.quitSafely()
            else pictureCopyThread.quit()
        }
        resetSidePanel()
        releaseSidePanelEffects()
        // main B7：销毁时释放外观归属权（作者：TRAE AI）。
        hostAppearanceResumed = false
        updateCallPopupGuard()
        AppAppearanceRuntime.clearHost(this)
        nightModeController.pause()
        pictureBinding?.close()
        pictureBinding = null
        removeVideoSurfaceProbe()
        fallbackVideoView?.holder?.removeCallback(fallbackSurfaceCallback)
        mainHandler.removeCallbacks(refreshTurnOverlay)
        mainHandler.removeCallbacks(clusterLaunchTick)
        AirPlayPersistence.overlaySettingsListener = null
        com.shilapi.xcertplay.hud.BydNavigationOutputs.setTurnOverlayListener(null)
        clusterMonitor?.stop()
        (getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager)
            ?.unregisterDisplayListener(clusterDisplayListener)
        mainHandler.removeCallbacks(hideIdleCenterMap)
        homeMonitor?.stop()
        CenterMapOverlay.hide()
        if (CenterMapOverlay.requestShow == (::showCenterMap)) CenterMapOverlay.requestShow = null
        if (MapMirrors.sink === mirrorSink) {
            MapMirrors.sink = null
            MapMirrors.setStreamActive(false)
        }
        if (MapMirrors.onChanged === mirrorsChanged) MapMirrors.onChanged = null
        dismissClusterPresentation()
        mainHandler.removeCallbacks(applyDisplaySize)
        mainHandler.removeCallbacks(expireOldLogLines)
        mainHandler.removeCallbacks(pollConfiguration)
        videoSurfaceOwner.clear()
        currentSurfaceTexture = null
        fallbackVideoView = null
        fallbackVideoBounds = null
        sessionLog?.append("Activity destroyed")
        sessionLog?.close()
        sessionLog = null
        super.onDestroy()
    }

    private fun buildContentView(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val video = TextureView(this).apply {
            isOpaque = false
            surfaceTextureListener = textureListener
        }
        pictureBinding = CarPlayPicture.Binding(video)
        val gestureLayer = View(this).apply {
            isClickable = true
            setOnTouchListener { view, event -> onHostTouch(view, event) }
        }
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        root.addView(gestureLayer, FrameLayout.LayoutParams(-1, -1))
        // Measure the preparation content naturally, then fit it inside the safe viewport.
        val viewport = object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                getChildAt(0)?.measure(
                    View.MeasureSpec.makeMeasureSpec((measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
            }
        }.apply {
            isClickable = true
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_carplay)
            contentDescription = getString(R.string.carplay)
        }
        panel.addView(icon, LinearLayout.LayoutParams(dp(88), dp(88)))
        val title = TextView(this).apply {
            text = getString(R.string.diplay)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        panel.addView(title)
        val stage = TextView(this).apply {
            text = getString(R.string.getting_carplay_ready)
            gravity = Gravity.CENTER
        }
        panel.addView(stage)
        val instructions = TextView(this).apply {
            text = if (wirelessEnabled) getString(R.string.keep_your_iphone_nearby_with_bluetooth_and_wi_fi_on_allow)
                else getString(R.string.use_a_usb_data_cable_and_unlock_your_iphone_allow_trust_an)
            gravity = Gravity.CENTER
        }
        panel.addView(instructions)
        // 波次十一批次B（main #673）：连接失败时的可操作提示；无失败时隐藏，由 instructions 承担说明。
        val help = TextView(this).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        panel.addView(help)
        val recovery = DiPlayButton(this).apply {
            text = getString(R.string.reset_carplay_wi_fi)
            visibility = View.GONE
            setOnClickListener { showDiPlayHome("wireless-recovery") }
            wifiRecoveryButton = this
        }
        panel.addView(recovery, LinearLayout.LayoutParams(dp(300), dp(64)).apply { bottomMargin = dp(12) })
        val retry = DiPlayButton(this).apply {
            text = getString(R.string.retry_carplay_connection)
            visibility = View.GONE
            setOnClickListener {
                if (!CarPlayBackgroundSession.isOwner(this@CarPlayHostActivity) ||
                    shuttingDown.get() || menuOpen || handshakeResetInProgress) return@setOnClickListener
                startupRetryBudget.manualRetry()
                startupRetryStopped = false
                reconnectAttempts = 0
                visibility = View.GONE
                restartCarPlay(getString(R.string.connecting_to_your_iphone))
            }
            startupRetryButton = this
        }
        panel.addView(retry, LinearLayout.LayoutParams(dp(300), dp(64)).apply { bottomMargin = dp(12) })
        val back = DiPlayButton(this).apply {
            text = getString(R.string.back_to_diplay)
            setOnClickListener { showDiPlayHome() }
        }
        panel.addView(back, LinearLayout.LayoutParams(dp(300), dp(64)))
        val gestureHint = TextView(this).apply {
            text = settingsGestureHintText()
            gravity = Gravity.CENTER
        }
        panel.addView(gestureHint)
        paintWaitingScreen = {
            // main #536（波次十三批次G）：连接屏是 DiPlay 自身界面，跟随应用主题而非发给 CarPlay 的日夜模式。
            val colors = WaitingScreenColors.of(appNight)
            viewport.setBackgroundColor(colors.background)
            title.setTextColor(colors.text)
            stage.setTextColor(colors.text)
            instructions.setTextColor(colors.secondary)
            help.setTextColor(colors.secondary)
            gestureHint.setTextColor(colors.secondary)
            // main #594：等待屏按钮跟随日夜配色统一由 DiPlayButton 着色（作者：TRAE AI）。
            val buttonPalette = DiPlayPalette.of(appNight)
            back.style(DiPlayButtonKind.PRIMARY, buttonPalette, overlay = false)
            retry.style(DiPlayButtonKind.SECONDARY, buttonPalette, overlay = false)
            recovery.style(DiPlayButtonKind.SECONDARY, buttonPalette, overlay = false)
        }
        paintWaitingScreen()
        viewport.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        root.addView(viewport, FrameLayout.LayoutParams(-1, -1))
        // USB 连接实时诊断覆盖层：把最近若干行 xcertplay-usb 日志直接打在车机屏幕上，
        // 便于 API 17 等环境下不接电脑也能看到卡在哪一步（作者：TRAE AI）。
        val usbDiag = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(150, 230, 160))
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            gravity = Gravity.BOTTOM or Gravity.START
            maxLines = 12
        }
        root.addView(
            usbDiag,
            FrameLayout.LayoutParams(-1, -2).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = dp(8); leftMargin = dp(8); rightMargin = dp(8)
            },
        )
        usbDiagView = usbDiag
        // Above the video and gesture layer, below the menus.
        sidePanel = buildSidePanel().also { root.addView(it, FrameLayout.LayoutParams(0, 0)) }
        // 侧栏把手小条（main #480），位于视频与手势层之上、菜单之下。
        sidePanelTab = buildSidePanelTab().also { root.addView(it, FrameLayout.LayoutParams(0, 0)) }
        var preparationHeight = -1
        fun updatePreparationLayout() {
            val height = viewport.height - viewport.paddingTop - viewport.paddingBottom
            if (height <= 0) return
            // Interpolate within the short viewport range; keep regular screens at their existing size.
            val fraction = ((height.toFloat() / resources.displayMetrics.density - 240f) / 240f).coerceIn(0f, 1f)
            fun size(short: Float, regular: Float) = short + (regular - short) * fraction
            fun spacing(short: Float, regular: Float) = dp(size(short, regular).toInt())
            val availableWidth = viewport.width - viewport.paddingLeft - viewport.paddingRight - dp(48)
            val buttonWidth = minOf(dp(300), availableWidth.coerceAtLeast(dp(48)))
            for (button in listOf(back, recovery, retry)) {
                if (button.layoutParams.width != buttonWidth) {
                    button.layoutParams = button.layoutParams.apply { width = buttonWidth }
                }
            }
            if (preparationHeight == height) return
            preparationHeight = height
            panel.setPadding(dp(24), spacing(16f, 32f), dp(24), spacing(16f, 32f))
            val iconSize = spacing(54f, 88f)
            icon.layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            title.textSize = size(26f, 34f)
            title.setPadding(0, spacing(8f, 18f), 0, spacing(6f, 14f))
            stage.textSize = size(19f, 22f)
            instructions.textSize = size(15f, 17f)
            instructions.setPadding(0, spacing(8f, 14f), 0, spacing(12f, 24f))
            for (button in listOf(back, recovery, retry)) {
                button.textSize = size(17f, 18f)
                button.layoutParams = button.layoutParams.apply { this.height = spacing(50f, 64f) }
            }
            gestureHint.textSize = size(12.5f, 13f)
            gestureHint.setPadding(0, spacing(10f, 20f), 0, 0)
        }
        fun fitPreparationContent() {
            val availableHeight = viewport.height - viewport.paddingTop - viewport.paddingBottom
            if (panel.height <= 0 || availableHeight <= 0) return
            val landscape = viewport.width - viewport.paddingLeft - viewport.paddingRight > availableHeight
            val scale = if (landscape) minOf(1f, availableHeight.toFloat() / panel.height) else 1f
            panel.scaleX = scale
            panel.scaleY = scale
        }
        panel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitPreparationContent() }
        viewport.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updatePreparationLayout()
            fitPreparationContent()
        }
        ViewCompat.setOnApplyWindowInsetsListener(viewport) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            viewport.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            viewport.post { updatePreparationLayout() }
            insets
        }
        ViewCompat.requestApplyInsets(viewport)
        // 覆盖画面与连接面板，位于设置菜单之下（main #496 FPS 计数器）。
        fpsCounter = FpsCounterOverlay(this, mainHandler) { sink?.liveVideoCounters() }.also { counter ->
            // CarPlay 画面永不镜像，因此在从右到左的语言里计数器仍保持在右上角。
            root.addView(counter.view, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.RIGHT).apply {
                setMargins(0, dp(12), dp(12), 0)
            })
        }
        settingsMenu = buildSettingsMenu().apply { visibility = View.GONE }
        root.addView(settingsMenu, FrameLayout.LayoutParams(-1, -1))
        safeAreaEditor = buildSafeAreaEditor().apply { visibility = View.GONE }
        root.addView(safeAreaEditor, FrameLayout.LayoutParams(-1, -1))
        videoView = video
        smoothVideo = AirPlayPersistence.loadSmoothVideo(this)
        directVideoOutput = AirPlayPersistence.loadDirectVideoOutput(this)
        observeVideoWindow(video)
        gestureOverlay = gestureLayer
        settingsGestureHint = gestureHint
        stageStatusView = stage
        connectionInstructionsView = instructions
        connectionHelpView = help
        connectionPanel = viewport
        updateDebugOverlays()
        return root
    }

    // 侧栏卡片跟随应用主题：深色模式为黑、浅色为白（main #480 移植，分支以 darkMode 代替 appNight）。
    private val SIDE_PANEL_CARD: Int get() = if (darkMode) Color.BLACK else Color.WHITE
    private val SIDE_PANEL_PRIMARY: Int get() = if (darkMode) Color.WHITE else Color.rgb(28, 28, 30)
    private val SIDE_PANEL_SECONDARY: Int get() = if (darkMode) MENU_SECONDARY else Color.rgb(96, 102, 108)

    // DiPlay's side panel: placed over the part of the stream CarPlay leaves black (placeSidePanel), so it
    // follows the video when it is letterboxed; touches elsewhere still reach the gesture layer and CarPlay.
    // main #480：卡片背景带把手、可拖拽边缘；分支按迁移指南跳过 resize 模糊（Android 12 RenderEffect）。
    private fun buildSidePanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            visibility = View.GONE
            background = sidePanelBackground()
        }
        panel.setOnTouchListener { view, event -> onSidePanelTouch(view, event) }
        val contents = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        sidePanelContents = contents
        panel.addView(contents, LinearLayout.LayoutParams(-1, -1))
        contents.addView(android.widget.TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "h:mm"
            textSize = 72f
            setTextColor(SIDE_PANEL_PRIMARY)
            gravity = Gravity.CENTER
        })
        sidePanelBattery = TextView(this).apply {
            textSize = 30f
            setTextColor(SIDE_PANEL_SECONDARY)
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(32))
        }
        contents.addView(sidePanelBattery)
        contents.addView(buildSidePanelTyres())
        return panel
    }

    // 类似 CarPlay 的圆角卡片，靠 CarPlay 的一边带把手。
    private fun sidePanelBackground(): Drawable {
        val card = GradientDrawable().apply {
            setColor(SIDE_PANEL_CARD)
            cornerRadius = dp(28).toFloat()
        }
        val across = sidePanelEdge != Gravity.TOP
        val grip = GradientDrawable().apply {
            // 把手位于 CarPlay 旁的黑色空隙里，因此两种主题下都保持浅色。
            setColor(Color.rgb(170, 180, 190))
            cornerRadius = dp(3).toFloat()
            setSize(if (across) dp(6) else dp(72), if (across) dp(72) else dp(6))
        }
        grip.alpha = sidePanelGripAlpha
        sidePanelGrip = grip
        // 只在朝向 CarPlay 的一侧留出放把手的缺口；贴着 CarPlay 的底边与其 Dock 齐平。
        val bottom = if (across) dp(6) else 0
        // setLayerGravity/setLayerInsetBottom 是 API 23 方法，低版本走自绘等效实现，避免 NoSuchMethodError。
        if (Build.VERSION.SDK_INT >= 23) {
            return LayerDrawable(arrayOf(card, grip)).apply {
                when (sidePanelEdge) {
                    Gravity.TOP -> setLayerInset(0, 0, dp(10), 0, 0)
                    Gravity.END -> setLayerInset(0, 0, 0, dp(10), bottom)
                    else -> setLayerInset(0, dp(10), 0, 0, bottom)
                }
                setLayerInset(1, 0, 0, 0, bottom)
                setLayerGravity(1, when (sidePanelEdge) {
                    Gravity.TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    Gravity.END -> Gravity.RIGHT or Gravity.CENTER_VERTICAL
                    else -> Gravity.LEFT or Gravity.CENTER_VERTICAL
                })
            }
        }
        val cardInsets = when (sidePanelEdge) {
            Gravity.TOP -> intArrayOf(0, dp(10), 0, 0)
            Gravity.END -> intArrayOf(0, 0, dp(10), bottom)
            else -> intArrayOf(dp(10), 0, 0, bottom)
        }
        val gripGravity = when (sidePanelEdge) {
            Gravity.TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
            Gravity.END -> Gravity.RIGHT or Gravity.CENTER_VERTICAL
            else -> Gravity.LEFT or Gravity.CENTER_VERTICAL
        }
        return GravityLayerDrawable(
            arrayOf(card, grip),
            arrayOf(cardInsets, intArrayOf(0, 0, 0, bottom)),
            intArrayOf(Gravity.FILL, gripGravity),
        )
    }

    /**
     * 拖拽面板朝向 CarPlay 的那条边：面板跟随手指；松手时 CarPlay 移动到离松手位置最近的
     * 声明份额（屏幕的三分之二、一半或三分之一），若在离对侧更近处松手则 CarPlay 占全屏并关闭面板。
     */
    private fun onSidePanelTouch(panel: View, event: MotionEvent): Boolean {
        val screen = sidePanelScreen ?: return false
        val params = panel.layoutParams as? FrameLayout.LayoutParams ?: return false
        val vertical = sidePanelEdge == Gravity.TOP
        val raw = if (vertical) event.rawY else event.rawX
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 只有靠近那条边的触摸才会拖拽；面板其余部分保留点击。
                val distance = when (sidePanelEdge) {
                    Gravity.TOP -> event.y
                    Gravity.END -> panel.width - event.x
                    else -> event.x
                }
                if (distance > dp(56)) return false
                showSidePanelGripHint()
                val edge = when (sidePanelEdge) {
                    Gravity.TOP -> params.topMargin.toFloat()
                    Gravity.END -> (params.leftMargin + params.width).toFloat()
                    else -> params.leftMargin.toFloat()
                }
                sidePanelDrag = floatArrayOf(raw, edge)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val drag = sidePanelDrag ?: return false
                // CarPlay 至少保留四分之一屏幕，也可以占满全屏。
                val quarter = sidePanelEdgeAt(screen, 0.25f)
                val whole = sidePanelEdgeAt(screen, 1f)
                val edge = (drag[1] + raw - drag[0]).coerceIn(minOf(quarter, whole), maxOf(quarter, whole))
                panel.alpha = if (sidePanelCarPlayShare(screen, edge) > SIDE_PANEL_CLOSE_SHARE) 0.5f else 1f
                when (sidePanelEdge) {
                    Gravity.TOP -> { params.topMargin = Math.round(edge); params.height = Math.round(screen.bottom - edge) }
                    Gravity.END -> { params.leftMargin = Math.round(screen.left); params.width = Math.round(edge - screen.left) }
                    else -> { params.leftMargin = Math.round(edge); params.width = Math.round(screen.right - edge) }
                }
                panel.layoutParams = params
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val drag = sidePanelDrag ?: return false
                sidePanelDrag = null
                panel.alpha = 1f
                val carPlay = sidePanelCarPlayShare(screen, drag[1] + raw - drag[0])
                if (event.actionMasked == MotionEvent.ACTION_UP && carPlay > SIDE_PANEL_CLOSE_SHARE) {
                    appendLog("Side panel closed by dragging its edge")
                    showSidePanel(false)
                    if (sidePanelShown) videoView?.let { updateVideoLayout(it.width, it.height) }
                    return true
                }
                val sixths = CarPlayViewAreas.nearestSidePanelSixths(carPlay.toDouble())
                if (event.actionMasked == MotionEvent.ACTION_UP && sixths != SidePanelSettings.sixths(this)) {
                    SidePanelSettings.setSixths(this, sixths)
                    appendLog("Side panel resized: CarPlay $sixths/6 of the screen")
                    showSidePanel(true)
                } else {
                    videoView?.let { updateVideoLayout(it.width, it.height) }
                }
                return true
            }
        }
        return false
    }

    private fun showSidePanelGripHint() {
        if (sidePanelGripAlpha < 255) animateSidePanelGrip(255, 200L)
        mainHandler.removeCallbacks(sidePanelGripFade)
        mainHandler.postDelayed(sidePanelGripFade, SIDE_PANEL_TAB_HINT_MILLIS)
    }

    private fun animateSidePanelGrip(to: Int, millis: Long) {
        sidePanelGripAnimator?.cancel()
        sidePanelGripAnimator = ValueAnimator.ofInt(sidePanelGripAlpha, to).setDuration(millis).apply {
            addUpdateListener {
                sidePanelGripAlpha = it.animatedValue as Int
                sidePanelGrip?.alpha = sidePanelGripAlpha
            }
            start()
        }
    }

    // CarPlay 占 [share] 屏幕时面板边所在的位置，以及边在 [edge] 时 CarPlay 的份额。
    private fun sidePanelEdgeAt(screen: RectF, share: Float): Float = when (sidePanelEdge) {
        Gravity.TOP -> screen.top + screen.height() * share
        Gravity.END -> screen.right - screen.width() * share
        else -> screen.left + screen.width() * share
    }

    private fun sidePanelCarPlayShare(screen: RectF, edge: Float): Float = when (sidePanelEdge) {
        Gravity.TOP -> (edge - screen.top) / screen.height()
        Gravity.END -> (screen.right - edge) / screen.width()
        else -> (edge - screen.left) / screen.width()
    }

    // 与仪表盘一致的胎压显示：前轮在上排、后轮在下排，左右两列。
    private fun buildSidePanelTyres(): LinearLayout {
        val texts = List(4) { position ->
            TextView(this).apply {
                textSize = 24f
                setTextColor(SIDE_PANEL_PRIMARY)
                gravity = if (position % 2 == 0) Gravity.START else Gravity.END
            }
        }
        sidePanelTyreTexts = texts
        fun row(left: TextView, right: TextView) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            setPadding(0, dp(8), 0, dp(8))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(8), 0, dp(8), dp(28))
            addView(TextView(this@CarPlayHostActivity).apply {
                text = "TPMS"
                textSize = 18f
                setTextColor(SIDE_PANEL_SECONDARY)
                gravity = Gravity.CENTER
            })
            addView(row(texts[0], texts[1]))
            addView(row(texts[2], texts[3]))
        }.also { sidePanelTyres = it }
    }

    // The panel's strip of the stream, mapped through the video's layout in the window.
    private fun placeSidePanel(viewWidth: Int, viewHeight: Int) {
        val panel = sidePanel ?: return
        val display = sessionDisplay ?: return
        val strip = display.viewAreas?.let { it.panelRect(it.current) } ?: return
        val content = contentRect(viewWidth, viewHeight)
        val scaleX = content.width / display.width
        val scaleY = content.height / display.height
        val left = Math.round(content.left + strip.originX * scaleX)
        val top = Math.round(content.top + strip.originY * scaleY)
        panel.layoutParams = FrameLayout.LayoutParams(
            Math.round(content.left + (strip.originX + strip.width) * scaleX) - left,
            Math.round(content.top + (strip.originY + strip.height) * scaleY) - top,
            // Stream coordinates are physical pixels, independent of the UI's reading direction.
            Gravity.TOP or Gravity.LEFT,
        ).apply { leftMargin = left; topMargin = top }
        val screen = display.viewAreas?.let { it.layoutArea(it.current) } ?: return
        sidePanelScreen = sidePanelScreenRect(viewWidth, viewHeight)
        sidePanelEdge = when {
            strip.height < screen.height -> Gravity.TOP
            strip.originX > screen.originX -> Gravity.START
            else -> Gravity.END
        }
        panel.background = sidePanelBackground()
    }

    // CarPlay 布局所依据的整块屏幕（含面板条带）在窗口中的位置。
    private fun sidePanelScreenRect(viewWidth: Int, viewHeight: Int): RectF? {
        val display = sessionDisplay ?: return null
        val screen = display.viewAreas?.let { it.layoutArea(it.current) } ?: return null
        val content = contentRect(viewWidth, viewHeight)
        val scaleX = content.width / display.width
        val scaleY = content.height / display.height
        return RectF(content.left + screen.originX * scaleX, content.top + screen.originY * scaleY,
            content.left + (screen.originX + screen.width) * scaleX, content.top + (screen.originY + screen.height) * scaleY)
    }

    // CarPlay 边缘上的小药丸条，离屏幕边缘有一点距离；车机保留它自身的返回滑动。
    private fun buildSidePanelTab(): View = View(this).apply {
        visibility = View.GONE
        setOnTouchListener { view, event -> onSidePanelTabTouch(view, event) }
    }

    private fun placeSidePanelTab(viewWidth: Int, viewHeight: Int) {
        val tab = sidePanelTab ?: return
        val from = sidePanelOpenEdge()
        val screen = sidePanelScreenRect(viewWidth, viewHeight)
        if (from == null || screen == null || sidePanelTabDrag != null) {
            if (sidePanelTabDrag == null) tab.visibility = View.GONE
            return
        }
        val across = from != Gravity.BOTTOM
        val reach = dp(64)
        val length = dp(160)
        if (from != sidePanelTabFrom) tab.background = sidePanelTabPill(from).apply { alpha = if (sidePanelTabHint) 255 else 0 }
        sidePanelTabFrom = from
        val areas = sessionDisplay?.viewAreas
        if (areas !== sidePanelTabHintFor) {
            sidePanelTabHintFor = areas
            showSidePanelTabHint()
        }
        val params = FrameLayout.LayoutParams(if (across) reach else length, if (across) length else reach,
            Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = Math.round(when (from) {
                Gravity.BOTTOM -> screen.centerX() - length / 2f
                Gravity.LEFT -> screen.left
                else -> screen.right - reach
            })
            topMargin = Math.round(if (across) screen.centerY() - length / 2f else screen.bottom - reach)
        }
        val old = tab.layoutParams as? FrameLayout.LayoutParams
        if (old == null || old.width != params.width || old.height != params.height ||
            old.leftMargin != params.leftMargin || old.topMargin != params.topMargin) tab.layoutParams = params
        tab.visibility = View.VISIBLE
    }

    private fun showSidePanelTabHint() {
        sidePanelTabHint = true
        sidePanelTab?.background?.let { pill ->
            val current = drawableAlpha(pill)
            if (current < 255) ObjectAnimator.ofInt(pill, "alpha", current, 255).setDuration(200L).start()
        }
        mainHandler.removeCallbacks(sidePanelTabFade)
        mainHandler.postDelayed(sidePanelTabFade, SIDE_PANEL_TAB_HINT_MILLIS)
    }

    private fun sidePanelTabPill(from: Int): Drawable {
        val across = from != Gravity.BOTTOM
        val pill = GradientDrawable().apply {
            setColor(0xDDFFFFFF.toInt())
            setStroke(dp(1), 0x66000000)
            cornerRadius = dp(4).toFloat()
            setSize(if (across) dp(8) else dp(88), if (across) dp(88) else dp(8))
        }
        // setLayerGravity/setLayerInsetLeft 等均是 API 23 方法，低版本走自绘等效实现。
        val gravity = when (from) {
            Gravity.BOTTOM -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            Gravity.LEFT -> Gravity.LEFT or Gravity.CENTER_VERTICAL
            else -> Gravity.RIGHT or Gravity.CENTER_VERTICAL
        }
        val inset = dp(20)
        val insets = when (from) {
            Gravity.BOTTOM -> intArrayOf(0, 0, 0, inset)
            Gravity.LEFT -> intArrayOf(inset, 0, 0, 0)
            else -> intArrayOf(0, 0, inset, 0)
        }
        if (Build.VERSION.SDK_INT >= 23) {
            return LayerDrawable(arrayOf(pill)).apply {
                setLayerGravity(0, gravity)
                setLayerInset(0, insets[0], insets[1], insets[2], insets[3])
            }
        }
        return GravityLayerDrawable(arrayOf(pill), arrayOf(insets), intArrayOf(gravity))
    }

    /**
     * 把手跟随时面板本身跟随手指；松手时 CarPlay 移动到离松手位置最近的声明份额，
     * 若在离屏幕边缘更近处松手则什么都不发生。轻点会重新显示药丸。
     */
    private fun onSidePanelTabTouch(tab: View, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            sidePanelTabToCarPlay = false
            dropHeldSidePanelTabTouch()
        }
        if (sidePanelTabToCarPlay) {
            forwardSidePanelTabTouch(tab, event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                sidePanelTabToCarPlay = false
            }
            return true
        }
        val panel = sidePanel ?: return false
        val vertical = sidePanelOpenEdge() == Gravity.BOTTOM
        val position = if (vertical) tab.y + event.y else tab.x + event.x
        val across = if (vertical) tab.x + event.x else tab.y + event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val from = sidePanelOpenEdge() ?: return false
                val view = videoView ?: return false
                sidePanelScreen = sidePanelScreenRect(view.width, view.height) ?: return false
                sidePanelEdge = when (from) {
                    Gravity.BOTTOM -> Gravity.TOP
                    Gravity.LEFT -> Gravity.END
                    else -> Gravity.START
                }
                // 面板从左往右拉出时方向为正，其余朝 x 或 y 变小的方向。
                sidePanelTabDrag = floatArrayOf(position, 0f, across, if (from == Gravity.LEFT) 1f else -1f)
                holdSidePanelTabTouch(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val drag = sidePanelTabDrag ?: return false
                val screen = sidePanelScreen ?: return false
                if (drag[1] == 0f) {
                    when (sidePanelTabIntent((position - drag[0]) * drag[3], Math.abs(across - drag[2]), dp(8).toFloat())) {
                        SidePanelTabIntent.UNDECIDED -> {
                            holdSidePanelTabTouch(event)
                            return true
                        }
                        SidePanelTabIntent.CARPLAY -> {
                            handSidePanelTabTouchToCarPlay(tab, event)
                            return true
                        }
                        SidePanelTabIntent.OPEN_PANEL -> dropHeldSidePanelTabTouch()
                    }
                }
                drag[1] = 1f
                tab.alpha = 0f
                val quarter = sidePanelEdgeAt(screen, 0.25f)
                val whole = sidePanelEdgeAt(screen, 1f)
                val edge = position.coerceIn(minOf(quarter, whole), maxOf(quarter, whole))
                panel.alpha = if (sidePanelCarPlayShare(screen, edge) > SIDE_PANEL_CLOSE_SHARE) 0.5f else 1f
                panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).apply {
                    gravity = Gravity.TOP or Gravity.LEFT
                    when (sidePanelEdge) {
                        Gravity.TOP -> { leftMargin = Math.round(screen.left); width = Math.round(screen.width()); topMargin = Math.round(edge); height = Math.round(screen.bottom - edge) }
                        Gravity.END -> { leftMargin = Math.round(screen.left); width = Math.round(edge - screen.left); topMargin = Math.round(screen.top); height = Math.round(screen.height()) }
                        else -> { leftMargin = Math.round(edge); width = Math.round(screen.right - edge); topMargin = Math.round(screen.top); height = Math.round(screen.height()) }
                    }
                }
                if (panel.visibility != View.VISIBLE) {
                    panel.background = sidePanelBackground()
                    refreshSidePanel()
                    panel.visibility = View.VISIBLE
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                // 多指从不拖拽面板（设置滑动、捏合）：它们交给 CarPlay。
                if (sidePanelTabDrag?.get(1) == 0f) handSidePanelTabTouchToCarPlay(tab, event)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val drag = sidePanelTabDrag ?: return false
                if (drag[1] == 0f) {
                    // 不是拖拽：轻点交给把手下方的 CarPlay 并重新显示药丸。
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        handSidePanelTabTouchToCarPlay(tab, event)
                        sidePanelTabToCarPlay = false
                        showSidePanelTabHint()
                    } else {
                        dropHeldSidePanelTabTouch()
                        sidePanelTabDrag = null
                    }
                    return true
                }
                sidePanelTabDrag = null
                panel.alpha = 1f
                tab.alpha = 1f
                val screen = sidePanelScreen
                val carPlay = if (screen != null) sidePanelCarPlayShare(screen, position) else null
                if (event.actionMasked == MotionEvent.ACTION_UP && carPlay != null && carPlay <= SIDE_PANEL_CLOSE_SHARE) {
                    SidePanelSettings.setSixths(this, CarPlayViewAreas.nearestSidePanelSixths(carPlay.toDouble()))
                    appendLog("Side panel opened from its tab: CarPlay ${SidePanelSettings.sixths(this)}/6 of the screen")
                    showSidePanel(true)
                }
                if (!sidePanelShown) panel.visibility = View.GONE
                videoView?.let { updateVideoLayout(it.width, it.height) }
                return true
            }
        }
        return false
    }

    private fun holdSidePanelTabTouch(event: MotionEvent) {
        if (sidePanelTabHeld.isEmpty()) {
            mainHandler.postDelayed(sidePanelTabLongPress, ViewConfiguration.getLongPressTimeout().toLong())
        }
        sidePanelTabHeld += MotionEvent.obtain(event)
    }

    private fun dropHeldSidePanelTabTouch() {
        mainHandler.removeCallbacks(sidePanelTabLongPress)
        sidePanelTabHeld.forEach(MotionEvent::recycle)
        sidePanelTabHeld.clear()
    }

    /** 把手上的触摸不是拖拽：CarPlay 收到暂存的事件、[event] 以及该次触摸的其余部分。 */
    private fun handSidePanelTabTouchToCarPlay(tab: View, event: MotionEvent? = null) {
        mainHandler.removeCallbacks(sidePanelTabLongPress)
        sidePanelTabHeld.forEach { held ->
            forwardSidePanelTabTouch(tab, held)
            held.recycle()
        }
        sidePanelTabHeld.clear()
        event?.let { forwardSidePanelTabTouch(tab, it) }
        sidePanelTabDrag = null
        sidePanelTabToCarPlay = true
    }

    /** 把把手上的触摸按手势层的坐标交给其下方的 CarPlay 触摸层。 */
    private fun forwardSidePanelTabTouch(tab: View, event: MotionEvent) {
        val layer = gestureOverlay ?: return
        val forwarded = MotionEvent.obtain(event)
        forwarded.offsetLocation(tab.x - layer.x, tab.y - layer.y)
        try {
            onHostTouch(layer, forwarded)
        } finally {
            forwarded.recycle()
        }
    }

    private fun showSidePanel(show: Boolean) {
        val areas = sessionDisplay?.viewAreas ?: return
        val view = videoView ?: return
        val split = isMultiWindowActive()
        val portrait = if (split) screenPortrait() else view.height > view.width
        val target = if (show) areas.sidePanel(portrait, SidePanelSettings.sixths(this)) else areas.indexFor(view.width, view.height, split, portrait)
        val sent = target != null && controller?.showViewArea(target) == true
        appendLog("Side panel ${if (show) "shown" else "hidden"}: view area $target sent=$sent")
        if (!sent || target == null) return
        areas.use(target)
        sidePanelShown = show
        sidePanel?.visibility = if (show) View.VISIBLE else View.GONE
        mainHandler.removeCallbacks(sidePanelTick)
        if (show) startSidePanelTyres() else com.shilapi.xcertplay.hud.BydTyreStatus.stop()
        if (show) sidePanelTick.run()
        if (show) showSidePanelGripHint()
        updateVideoLayout(view.width, view.height)
    }

    private fun resetSidePanel() {
        sidePanelShown = false
        sidePanel?.visibility = View.GONE
        mainHandler.removeCallbacks(sidePanelTick)
        com.shilapi.xcertplay.hud.BydTyreStatus.stop()
        videoView?.let { placeSidePanelTab(it.width, it.height) }
    }

    // 轮胎数据通过车机的 adb shell 读取；没有 BYD autoservice 时读取器会自行停止。
    private fun startSidePanelTyres() {
        com.shilapi.xcertplay.hud.BydTyreStatus.start(this)
    }

    // 停止即将销毁的宿主的把手提示（分支无 resize 模糊，只清理提示与暂存事件）。
    private fun releaseSidePanelEffects() {
        dropHeldSidePanelTabTouch()
        sidePanelTabToCarPlay = false
        mainHandler.removeCallbacks(sidePanelGripFade)
        mainHandler.removeCallbacks(sidePanelTabFade)
        sidePanelGripAnimator?.cancel()
    }

    // 侧栏现在会占据的屏幕边（即把手所在处）；已显示或不可用时为 null。
    private fun sidePanelOpenEdge(): Int? {
        if (sidePanelShown) return null
        val areas = sessionDisplay?.viewAreas ?: return null
        val view = videoView ?: return null
        val portrait = if (isMultiWindowActive()) screenPortrait() else view.height > view.width
        val index = areas.sidePanel(portrait, SidePanelSettings.sixths(this)) ?: return null
        return when {
            portrait -> Gravity.BOTTOM
            areas.areas[index].originX > 0 -> Gravity.LEFT
            else -> Gravity.RIGHT
        }
    }

    // The battery shows only where DiPlay already reads it for the iPhone.
    private fun refreshSidePanel() {
        val battery = if (com.shilapi.xcertplay.hud.BydOutputSettings.batteryToIphoneActive(this)) {
            com.shilapi.xcertplay.hud.BydNavigationOutputs.batteryStatus(applicationContext).snapshot()
        } else null
        sidePanelBattery?.text = battery?.let { "🔋 ${Math.round(it.batteryPercent)} %  ·  ${it.rangeKm} km" }.orEmpty()
        val tyres = com.shilapi.xcertplay.hud.BydTyreStatus.latest()
        sidePanelTyres?.visibility = if (tyres == null) View.GONE else View.VISIBLE
        if (tyres != null) sidePanelTyreTexts.forEachIndexed { tyre, text ->
            val pressure = tyres.pressureKpa.getOrNull(tyre)?.let { "$it kPa" } ?: "—"
            val temperature = tyres.temperatureC.getOrNull(tyre)?.let { "\n$it °C" }.orEmpty()
            text.text = pressure + temperature
        }
    }

    // 日夜切换时刷新侧栏卡片的配色（分支无 overlayPalette，按 darkMode 直接重涂）。
    private fun paintSidePanelTheme() {
        sidePanel?.let { panel ->
            panel.background = sidePanelBackground()
            val contents = sidePanelContents ?: return@let
            (contents.getChildAt(0) as? TextView)?.setTextColor(SIDE_PANEL_PRIMARY)
            sidePanelBattery?.setTextColor(SIDE_PANEL_SECONDARY)
            sidePanelTyreTexts.forEach { it.setTextColor(SIDE_PANEL_PRIMARY) }
            (sidePanelTyres?.getChildAt(0) as? TextView)?.setTextColor(SIDE_PANEL_SECONDARY)
        }
    }

    // main B7：应用外观（DARK/LIGHT/AUTO）解析出的夜间态与主机页不同时，重建设置菜单
    // 并重涂侧栏与画面调节面板（作者：TRAE AI）。
    private fun refreshAppAppearance() {
        val nextNight = resolveAppNightNow(darkMode)
        if (nextNight == appNight) return
        appNight = nextNight
        // main #536（波次十三批次G）：主题变化时连接屏立即重绘。
        paintWaitingScreen()

        settingsMenu?.let { previous ->
            val parent = previous.parent as? ViewGroup ?: return@let
            val index = parent.indexOfChild(previous)
            val visible = previous.visibility
            val scrollY = settingsMenuContentScroll?.scrollY ?: 0
            val focusDescription = previous.findFocus()?.contentDescription?.toString()
            parent.removeView(previous)
            val replacement = buildSettingsMenu().apply { visibility = visible }
            settingsMenu = replacement
            parent.addView(replacement, index, FrameLayout.LayoutParams(-1, -1))
            val replacementContentScroll = settingsMenuContentScroll
            replacementContentScroll?.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(view: View, left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                    view.removeOnLayoutChangeListener(this)
                    replacementContentScroll.scrollTo(0, scrollY)
                }
            })
            replacement.post {
                if (focusDescription != null) {
                    findDescendants(replacement).firstOrNull {
                        it.contentDescription?.toString() == focusDescription
                    }?.requestFocus()
                }
            }
        }
        paintSidePanelTheme()
        // main #594：画面调节面板随夜间态整树重涂（作者：TRAE AI）。
        (picturePanel as? CarPlayPicturePanel)?.applyPalette(overlayPalette)
    }

    private inline fun <reified T : View> findDescendant(root: View): T? =
        findDescendants(root).filterIsInstance<T>().firstOrNull()

    private fun findDescendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) yieldAll(findDescendants(root.getChildAt(index)))
        }
    }

    private fun settingsMenuWidth(availableWidth: Int): Int =
        minOf(MAX_SETTINGS_MENU_WIDTH_PX, (availableWidth - dp(SWIPE_PANEL_MARGIN_DP * 2)).coerceAtLeast(1))

    /** 波次十一批次F（main #642）：车机内设置抽屉的"玻璃壳"背景（上/下圆角可控 + 描边）。 */
    private fun swipeMenuChrome(
        topCornersOnly: Boolean = false,
        bottomCornersOnly: Boolean = false,
    ): GradientDrawable {
        val base = ColorUtils.blendARGB(MENU_BACKGROUND, overlayPalette.surface, if (darkMode) 0.48f else 0.38f)
        val highlight = ColorUtils.blendARGB(base, Color.WHITE, if (darkMode) 0.10f else 0.28f)
        val radius = dp(SWIPE_PANEL_RADIUS_DP).toFloat()
        val top = if (bottomCornersOnly) 0f else radius
        val bottom = if (topCornersOnly) 0f else radius
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(highlight, base)).apply {
            cornerRadii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
            setStroke(
                dp(SWIPE_BORDER_WIDTH_DP),
                ColorUtils.blendARGB(overlayPalette.outline, Color.WHITE, if (darkMode) 0.12f else 0.30f),
            )
        }
    }

    private fun buildSettingsMenu(): View {
        val overlay = FrameLayout(this).apply {
            // 波次十一批次F（main #642）：抽屉背后的遮罩（半透明黑），而非全黑。
            setBackgroundColor(SWIPE_SCRIM_COLOR)
            isClickable = true
            layoutDirection = resources.configuration.layoutDirection
        }
        val panel = FrameLayout(this).apply {
            background = swipeMenuChrome()
            // clipToOutline / elevation 均为 API 21+；API 17 经 UiCompat/守卫降级为无裁剪、无阴影。
            UiCompat.setClipToOutline(this, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = dp(SWIPE_PANEL_ELEVATION_DP).toFloat()
            }
        }
        val categories = listOf(
            "connection" to R.string.connection,
            "display" to R.string.settings_display,
            "navigation" to R.string.settings_navigation,
            "vehicle" to R.string.settings_vehicle,
            "diagnostics" to R.string.diagnostics,
            "advanced" to R.string.settings_advanced,
            "more" to R.string.settings_more,
        )
        val pages = LinkedHashMap<String, LinearLayout>()
        categories.forEach { (key, title) ->
            pages[key] = LinearLayout(this).apply {
                tag = key
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(
                    dp(SWIPE_CHROME_PADDING_DP), dp(SWIPE_CONTENT_TOP_PADDING_DP),
                    dp(SWIPE_CHROME_PADDING_DP), dp(SWIPE_CONTENT_BOTTOM_PADDING_DP),
                )
                // The category name heads every page, so the sections below never add their own.
                addView(
                    settingsCategoryHeader(getString(if (key == "advanced") R.string.settings_swipe_advanced else title)),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        }
        fun pageFor(key: String) = pages.getValue(key)
        var content = pageFor("connection")
        if (sessionDisplay?.viewAreas?.sidePanel() != null) {
            pageFor("advanced").addView(DiPlayButton(this).apply {
                text = getString(if (sidePanelShown) R.string.side_panel_full_screen else R.string.side_panel_show)
                textSize = 17f
                minHeight = dp(52)
                style(DiPlayButtonKind.SECONDARY, overlayPalette, overlay = true)
                setOnClickListener {
                    cancelSettingsEdits()
                    showSidePanel(!sidePanelShown)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        content.addView(
            buildMfiTargetSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) },
        )

        val wirelessRowResult = ConnectionSettingsSection.createWirelessCarPlayRow(
            context = this,
            checked = wirelessEnabled,
            theme = SettingsTheme.OVERLAY,
        ) { checked ->
            if (wirelessEnabled == checked) return@createWirelessCarPlayRow
            wirelessEnabled = checked
            hotspotStatus = HotspotStatus(state = if (wirelessEnabled) getString(R.string.hotspot_state_stopped) else getString(R.string.hotspot_state_off))
            updateHotspotStatusBlock()
            appendLog(
                "Wireless CarPlay ${if (wirelessEnabled) "enabled" else "disabled"}; " +
                    "applies when settings close",
            )
            requestStartupPrerequisites()
        }
        content.addView(
            wirelessRowResult.rowView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        content.addView(
            buildHotspotModeSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        content.addView(
            menuText(getString(R.string.hotspot_status), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(18) },
        )
        val hotspotStatusView = menuText("", 16f, MENU_ACCENT)
        content.addView(
            hotspotStatusView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )

        content.addView(
            settingsCategoryHeader(getString(R.string.automatic_connection)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(36) },
        )
        content.addView(
            settingsSwitchRow(
                label = getString(R.string.auto_start_on_boot),
                checked = autoStartOnBoot,
                description = getString(R.string.start_carplay_automatically_after_device_boot),
            ) { checked ->
                autoStartOnBoot = checked
                appendLog("Boot auto-start ${if (checked) "enabled" else "disabled"}")
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        content = pageFor("navigation")
        content.addView(
            buildLocationReportingSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        if (advancedAudioChannelMappingSupported) {
            content = pageFor("advanced")
            content.addView(
                settingsSwitchRow(
                    label = getString(R.string.advanced_audio_channel_mapping),
                    checked = advancedAudioChannelMapping,
                    description = getString(R.string.use_usage_content_type_routing_instead_of_stream_type),
                ) { checked ->
                    advancedAudioChannelMapping = checked
                    appendLog(
                        "Advanced audio channel mapping ${if (checked) "enabled" else "disabled"}; " +
                            "applies when settings close",
                    )
                    updateResolutionMenu()
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12) },
            )
        }

        content = pageFor("vehicle")
        content.addView(
            buildIdentitySettingsSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        // main #539：会话内菜单里的 BYD 来电弹窗隐藏开关，仅 Android 12(S)（作者：TRAE AI）。
        if (Build.VERSION.SDK_INT == 32) {
            content.addView(settingsSwitchRow(
                label = getString(R.string.settings_hide_byd_call_popup),
                checked = hideBydCallPopup,
                description = getString(R.string.settings_hide_byd_call_popup_description),
            ) { hideBydCallPopup = it })
        }
        pageFor("display").addView(
            buildAirPlayIconSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(26) },
        )
        content.addView(
            buildDrivingSideSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(26) },
        )
        pageFor("display").addView(
            buildDockSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(26) },
        )
        content = pageFor("display")

        val resolutionControl = DisplaySettingsSection.createResolutionSlider(
            context = this,
            initialPercent = displayScalePercent,
            theme = SettingsTheme.OVERLAY,
        ) { percent ->
            displayScalePercent = percent
            displayScaleTenths = CarPlayDisplayScale.sanitize((percent + 5) / 10)
            updateResolutionMenu()
        }
        val resolutionValue = resolutionControl.valueTextView
        content.addView(
            resolutionControl.container,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        content.addView(
            buildStepSliderSection(
                title = getString(R.string.frame_rate),
                values = (
                    AirPlayDisplaySettings.MIN_FPS..AirPlayDisplaySettings.MAX_FPS
                    step AirPlayDisplaySettings.FPS_STEP
                    ).toList(),
                selectedValue = fps,
                label = { "$it fps" },
                onValueChanged = { value ->
                    fps = value
                    updateResolutionMenu()
                },
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(24) },
        )

        content.addView(
            settingsChoiceRow(
                label = getString(R.string.physical_size_basis),
                options = listOf(
                    AirPlayPhysicalSizeBasis.WIDTH to getString(R.string.widest_width),
                    AirPlayPhysicalSizeBasis.HEIGHT to getString(R.string.longest_height),
                ),
                selected = physicalSizeBasis,
            ) { value ->
                physicalSizeBasis = value
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(24) },
        )

        content.addView(
            buildStepSliderSection(
                title = getString(R.string.physical_length),
                values = (
                    AirPlayDisplaySettings.MIN_WIDTH_PHYSICAL_MM..
                        AirPlayDisplaySettings.MAX_WIDTH_PHYSICAL_MM
                    step AirPlayDisplaySettings.WIDTH_PHYSICAL_MM_STEP
                    ).toList(),
                selectedValue = widthPhysicalMm,
                label = { "$it mm" },
                onValueChanged = { value ->
                    widthPhysicalMm = value
                    updateResolutionMenu()
                },
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) },
        )

        val hevcRow = DisplaySettingsSection.createHevcRow(
            context = this,
            checked = hevcEnabled,
            theme = SettingsTheme.OVERLAY,
        ) { checked ->
            if (hevcEnabled == checked) return@createHevcRow
            hevcEnabled = checked
            appendLog(
                "HEVC (H.265) ${if (hevcEnabled) "enabled" else "disabled"}; " +
                    "applies when settings close",
            )
            updateResolutionMenu()
        }
        pageFor("advanced").addView(
            hevcRow.rowView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        val softwareHevcRow = DisplaySettingsSection.createSoftwareHevcRow(
            context = this,
            checked = hevcSoftwareDecoderEnabled,
            theme = SettingsTheme.OVERLAY,
        ) { checked ->
            if (hevcSoftwareDecoderEnabled == checked) return@createSoftwareHevcRow
            hevcSoftwareDecoderEnabled = checked
            appendLog(
                "HEVC software decoder ${if (hevcSoftwareDecoderEnabled) "enabled" else "disabled"}; " +
                    "applies when settings close",
            )
            updateResolutionMenu()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            pageFor("advanced").addView(
                softwareHevcRow.rowView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(16) },
            )
        }

        content.addView(
            buildSafeAreaSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        content.addView(
            buildFullscreenSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        val preview = menuText("", 17f, MENU_SECONDARY)
        content.addView(
            preview,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        content = pageFor("diagnostics")
        content.addView(
            buildDebugLogsSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            content = pageFor("advanced")
            content.addView(
                settingsCategoryHeader(getString(R.string.android_9_compatibility)),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(40) },
            )
            content.addView(
                menuText(
                    getString(R.string.settings_android9_compat),
                    16f,
                    MENU_SECONDARY,
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12) },
            )
        }

        content = pageFor("more")
        val save = DiPlayButton(this).apply {
            text = getString(R.string.save_and_reconnect)
            textSize = 17f
            minHeight = dp(SWIPE_ACTION_HEIGHT_DP)
            style(DiPlayButtonKind.PRIMARY, overlayPalette, overlay = true)
            setOnClickListener { saveSettingsAndReconnect() }
        }

        val exitApplicationButton = DiPlayButton(this).apply {
            text = getString(R.string.exit_application)
            textSize = 17f
            minHeight = dp(SWIPE_ACTION_HEIGHT_DP)
            // 波次十一批次F（main #642）：退出改为柔和危险色调（TONAL_DANGER），并移出"紧邻保存"的位置。
            style(DiPlayButtonKind.TONAL_DANGER, overlayPalette, overlay = true)
            setOnClickListener { exitApplication() }
        }

        content.addView(DiPlayButton(this).apply {
            text = getString(R.string.language_app_language)
            textSize = 17f
            minHeight = dp(52)
            style(DiPlayButtonKind.SECONDARY, overlayPalette, overlay = true)
            setOnClickListener { AppLocale.showPicker(this@CarPlayHostActivity) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val gestureButton = DiPlayButton(this).apply {
            textSize = 17f
            minHeight = dp(52)
            style(DiPlayButtonKind.SECONDARY, overlayPalette, overlay = true)
            setOnClickListener {
                // main #487：0 = 禁用手势；循环为 0→2→3→4→0（作者：TRAE AI）。
                gestureFingerCount = when (gestureFingerCount) { 0 -> 2; 4 -> 0; else -> gestureFingerCount + 1 }
                text = settingsGestureButtonText()
            }
        }
        gestureButton.text = settingsGestureButtonText()
        pageFor("vehicle").addView(gestureButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val openDiPlaySettingsButton = DiPlayButton(this).apply {
            text = "${getString(R.string.app_name)} ${getString(R.string.settings)}"
            textSize = 17f
            minHeight = dp(52)
            style(DiPlayButtonKind.SECONDARY, overlayPalette, overlay = true)
            setOnClickListener {
                // main #500：跳转完整设置页前确认丢弃（作者：TRAE AI）。
                leaveSettingsMenu { showDiPlayHome("settings") }
            }
        }
        content.addView(
            openDiPlaySettingsButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        // 退出会结束 CarPlay，故留在列表末尾而非紧邻"保存"——避免驾驶员伸手点"保存"时误触。
        content.addView(
            exitApplicationButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        val pageHost = FrameLayout(this)
        val selectedPage = settingsMenuCategoryKey.takeIf(pages::containsKey) ?: "connection"
        settingsMenuCategoryKey = selectedPage
        pages.forEach { (key, page) ->
            page.visibility = if (key == selectedPage) View.VISIBLE else View.GONE
            pageHost.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(
                pageHost,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        settingsMenuContentScroll = scroll
        val categoryButtons = mutableListOf<Pair<String, DiPlayButton>>()
        lateinit var chips: HorizontalScrollView
        var fitNavigation: ((Int) -> Unit)? = null
        fun selectCategory(key: String) {
            settingsMenuCategoryKey = key
            pages.forEach { (candidate, page) ->
                page.visibility = if (candidate == key) View.VISIBLE else View.GONE
            }
            categoryButtons.forEach { (candidate, button) ->
                button.style(if (candidate == key) DiPlayButtonKind.PRIMARY else DiPlayButtonKind.SECONDARY,
                    overlayPalette, overlay = true)
            }
            scroll.scrollTo(0, 0)
            chips.requestLayout()
        }
        fun categoryButton(key: String, title: Int) = DiPlayButton(this).apply {
            text = getString(title)
            textSize = 16f
            minHeight = dp(SWIPE_CATEGORY_ACTION_HEIGHT_DP)
            categoryButtons += key to this
            style(if (key == selectedPage) DiPlayButtonKind.PRIMARY else DiPlayButtonKind.SECONDARY,
                overlayPalette, overlay = true)
            setOnClickListener { selectCategory(key) }
        }
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(SWIPE_CHROME_PADDING_DP), dp(SWIPE_CHROME_PADDING_DP),
                dp(SWIPE_CHROME_PADDING_DP), dp(SWIPE_CHROME_PADDING_DP))
            categories.forEach { (key, title) ->
                addView(categoryButton(key, title), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(SWIPE_ACTION_GAP_DP)
                })
            }
        }
        val railScroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(ColorUtils.blendARGB(MENU_BACKGROUND, overlayPalette.surface, 0.55f))
            addView(rail)
        }
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(SWIPE_CHROME_PADDING_DP), dp(SWIPE_ACTION_GAP_DP),
                dp(SWIPE_CHROME_PADDING_DP), dp(SWIPE_ACTION_GAP_DP))
            categories.forEach { (key, title) ->
                addView(categoryButton(key, title), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(SWIPE_ACTION_GAP_DP)
                })
            }
        }
        chips = object : HorizontalScrollView(this) {
            override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                super.onLayout(changed, left, top, right, bottom)
                val selectedIndex = categories.indexOfFirst { it.first == settingsMenuCategoryKey }
                if (selectedIndex >= 0) {
                    scrollTo((chipRow.getChildAt(selectedIndex).left - dp(SWIPE_CHROME_PADDING_DP))
                        .coerceAtLeast(0), 0)
                }
            }
        }.apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(ColorUtils.blendARGB(MENU_BACKGROUND, overlayPalette.surface, 0.55f))
            addView(chipRow)
        }
        val body = LinearLayout(this)
        var railShown: Boolean? = null
        fitNavigation = { panelWidth ->
            val showRail = panelWidth >= dp(SWIPE_CATEGORY_RAIL_MIN_WIDTH_DP)
            if (railShown != showRail) {
                railShown = showRail
                body.removeAllViews()
                (scroll.parent as? ViewGroup)?.removeView(scroll)
                body.orientation = if (showRail) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                if (showRail) {
                    body.addView(railScroll, LinearLayout.LayoutParams(dp(SWIPE_CATEGORY_RAIL_WIDTH_DP),
                        ViewGroup.LayoutParams.MATCH_PARENT))
                    body.addView(scroll, LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                } else {
                    body.addView(chips, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT))
                    body.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        0, 1f))
                }
            }
        }
        // 波次十一批次F（main #642）：标题栏（标题 + 关闭）与底栏（保存）各带"玻璃壳"的上/下圆角。
        val headerTitle = menuText(getString(R.string.carplay_settings), 20f, Color.WHITE, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutDirection = resources.configuration.layoutDirection
        }
        val closeButton = DiPlayButton(this).apply {
            text = getString(R.string.close)
            textSize = 16f
            style(DiPlayButtonKind.SECONDARY, overlayPalette, overlay = true)
            contentDescription = getString(R.string.discard_changes_and_exit_settings)
            minHeight = dp(SWIPE_ACTION_HEIGHT_DP)
            setOnClickListener { cancelSettingsEdits() }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            background = swipeMenuChrome(topCornersOnly = true)
            setPadding(
                dp(SWIPE_CHROME_PADDING_DP), dp(SWIPE_CHROME_PADDING_DP),
                dp(SWIPE_CHROME_PADDING_DP), dp(SWIPE_CHROME_PADDING_DP),
            )
            val titleParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val buttonParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                buttonParams.marginEnd = dp(SWIPE_ACTION_GAP_DP)
                addView(closeButton, buttonParams)
                addView(headerTitle, titleParams)
            } else {
                buttonParams.marginStart = dp(SWIPE_ACTION_GAP_DP)
                addView(headerTitle, titleParams)
                addView(closeButton, buttonParams)
            }
        }
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = swipeMenuChrome(bottomCornersOnly = true)
            setPadding(
                dp(SWIPE_CHROME_PADDING_DP), dp(SWIPE_CHROME_PADDING_DP),
                dp(SWIPE_CHROME_PADDING_DP), dp(SWIPE_CHROME_PADDING_DP),
            )
            addView(
                save,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        panel.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    header,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                addView(
                    body,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
                )
                addView(
                    footer,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        overlay.addView(
            panel,
            FrameLayout.LayoutParams(
                settingsMenuWidth(resources.displayMetrics.widthPixels),
                (resources.displayMetrics.heightPixels * SWIPE_PANEL_HEIGHT_PERCENT) / 100,
                Gravity.CENTER,
            ),
        )
        fitNavigation?.invoke(settingsMenuWidth(resources.displayMetrics.widthPixels))

        resolutionValueView = resolutionValue
        resolutionPreviewView = preview
        this.hotspotStatusView = hotspotStatusView
        updateHotspotStatusBlock()
        updateResolutionMenu()
        return overlay
    }

    // main #487：手势数 0（禁用）时提示与按钮显示专用文案（作者：TRAE AI）。
    private fun settingsGestureHintText(): String =
        if (gestureFingerCount == 0) getString(R.string.settings_gesture_disabled_hint)
        else getString(R.string.open_diplay_settings_hint, gestureFingerCount)

    private fun settingsGestureButtonText(): String =
        if (gestureFingerCount == 0) getString(R.string.settings_gesture_disabled_action)
        else getString(R.string.settings_gesture_fingers, gestureFingerCount)

    private fun persistMenuSettings() {
        AirPlayPersistence.saveHideBydCallPopup(this, hideBydCallPopup)
        AirPlayPersistence.saveSettingsGestureFingers(this, gestureFingerCount)
        AirPlayPersistence.saveWirelessEnabled(this, wirelessEnabled)
        AirPlayPersistence.saveMfiTarget(this, mfiTarget)
        AirPlayPersistence.saveMfiI2cPath(this, mfiI2cPath)
        AirPlayPersistence.saveRemoteMfiServer(this, remoteMfiServer)
        AirPlayPersistence.saveRemoteMfiToken(this, remoteMfiToken)
        AirPlayPersistence.saveWirelessHotspotMode(this, wirelessHotspotMode)
        AirPlayPersistence.saveExistingWifiCredentials(this, existingWifiSsid, existingWifiPassphrase)
        AirPlayPersistence.saveManualHotspotSsid(this, manualHotspotSsid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, manualHotspotPassphrase)
        AirPlayPersistence.saveManualHotspotBand(this, manualHotspotBand)
        AirPlayPersistence.saveManualHotspotChannel(this, manualHotspotChannel)
        AirPlayPersistence.saveManualHotspotSecurity(this, manualHotspotSecurity)
        AirPlayPersistence.saveLocationReportingEnabled(this, locationReportingEnabled)
        AirPlayPersistence.saveAutoStartOnBoot(this, autoStartOnBoot)
        AirPlayPersistence.saveAdvancedAudioChannelMapping(this, advancedAudioChannelMapping)
        AirPlayPersistence.saveDisplayScaleTenths(this, displayScaleTenths)
        AirPlayPersistence.saveDisplayScalePercent(this, displayScalePercent)
        AirPlayPersistence.saveFps(this, fps)
        AirPlayPersistence.saveWidthPhysicalMm(this, widthPhysicalMm)
        AirPlayPersistence.savePhysicalSizeBasis(this, physicalSizeBasis)
        AirPlayPersistence.saveHevcEnabled(this, hevcEnabled)
        AirPlayPersistence.saveHevcSoftwareDecoderEnabled(this, hevcSoftwareDecoderEnabled)
        AirPlayPersistence.saveManufacturer(this, manufacturer)
        AirPlayPersistence.saveModel(this, model)
        AirPlayPersistence.saveOemLabel(this, oemLabel)
        AirPlayPersistence.saveDebugLogsEnabled(this, debugLogsEnabled)
        AirPlayPersistence.saveRightHandDrive(this, rightHandDrive)
        CarPlayDock.save(this, carPlayDock)
        AirPlayPersistence.saveHideTopBar(this, hideTopBar)
        AirPlayPersistence.saveHideBottomBar(this, hideBottomBar)
        AirPlayPersistence.saveTemporarySystemBars(this, temporarySystemBarsEnabled)
        AirPlayPersistence.saveSafeAreaDrawOutside(this, safeAreaDrawOutside)
        AirPlayPersistence.saveMainDisplayDisableSafeArea(this, mainDisplayDisableSafeArea)
    }

    /** main #500：镜像 [persistMenuSettings]——凡在那里暂存的字段都必须参与签名（作者：TRAE AI）。 */
    private fun menuSettingsSignature(): Int = listOf(
        gestureFingerCount, wirelessEnabled, mfiTarget, mfiI2cPath, remoteMfiServer, remoteMfiToken,
        wirelessHotspotMode, existingWifiSsid, existingWifiPassphrase, manualHotspotSsid,
        manualHotspotPassphrase, manualHotspotBand, manualHotspotChannel, manualHotspotSecurity,
        locationReportingEnabled, autoStartOnBoot, advancedAudioChannelMapping, displayScaleTenths,
        displayScalePercent, fps, widthPhysicalMm, physicalSizeBasis, hevcEnabled,
        hevcSoftwareDecoderEnabled, manufacturer, model, oemLabel, debugLogsEnabled, rightHandDrive,
        carPlayDock, hideTopBar, hideBottomBar, temporarySystemBarsEnabled,
        safeAreaDrawOutside, mainDisplayDisableSafeArea,
    ).joinToString("|").hashCode()

    private fun captureSettingsBaseline(): SettingsBaseline {
        val safeAreaSize = currentActivitySize()
        val customIconBytes = try {
            AirPlayPersistence.loadCustomAirPlayIconFile(this)?.readBytes()
        } catch (error: Exception) {
            Log.w(TAG, "Could not read the current AirPlay icon for settings rollback", error)
            null
        }
        return SettingsBaseline(
            safeAreaRects = mutableMapOf<DisplaySize, SafeAreaRect?>().apply {
                safeAreaSize?.let { put(it, AirPlayPersistence.loadSafeAreaRect(this@CarPlayHostActivity, it.width, it.height)) }
            },
            customIconBytes = customIconBytes,
        )
    }

    private fun restoreSettingsBaseline() {
        val baseline = settingsBaseline ?: return
        // main #501：记录预览期间系统栏是否真的变化，未变化时取消菜单不应触发重新协商。
        val previewChangedSystemBars = hideTopBar != AirPlayPersistence.loadHideTopBar(this) ||
            hideBottomBar != AirPlayPersistence.loadHideBottomBar(this)
        loadPersistedSettings()
        baseline.safeAreaRects.forEach { (size, savedRect) ->
            savedRect?.let { rect ->
                AirPlayPersistence.saveSafeAreaRect(
                    this,
                    size.width,
                    size.height,
                    rect,
                    commit = true,
                )
            } ?: AirPlayPersistence.clearSafeAreaRect(
                this,
                size.width,
                size.height,
                commit = true,
            )
        }
        try {
            baseline.customIconBytes?.let { bytes ->
                AirPlayPersistence.saveCustomAirPlayIcon(this, bytes)
            } ?: AirPlayPersistence.clearCustomAirPlayIcon(this)
        } catch (error: Exception) {
            Log.w(TAG, "Could not restore the previous AirPlay icon", error)
        }
        settingsBaseline = null
        locationPermissionAvailable = hasFineLocationPermission()
        hotspotStatus = HotspotStatus(state = if (wirelessEnabled) getString(R.string.hotspot_state_stopped) else getString(R.string.hotspot_state_off))
        syncMfiSettingsControls()
        updateManualHotspotFields()
        updateAirPlayIconPreview()
        updateSafeAreaSummary()
        updateHotspotStatusBlock()
        updateResolutionMenu()
        updateDebugOverlays()
        // main #501：关闭未变化的菜单不得调度显示重新协商；真实的状态栏预览
        // 取消后仍需恢复窗口并重新测量。
        if (previewChangedSystemBars) {
            applyFullscreenMode()
            refreshDisplaySizeAfterLayout()
        }
    }

    private fun buildMfiTargetSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val targetChoice = ConnectionSettingsSection.createMfiTargetChoice(
            context = this,
            selected = mfiTarget,
            theme = SettingsTheme.OVERLAY,
        ) { target ->
            if (mfiTarget == target) return@createMfiTargetChoice
            mfiTarget = target
            updateMfiTargetFields()
            appendLog("MFI target: ${mfiTargetLabel(target)}; applies when settings close")
        }
        mfiTargetGroup = targetChoice.radioGroup
        section.addView(
            targetChoice.container,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val i2cFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                settingsInputRow(
                    getString(R.string.i2c_device),
                    mfiI2cPath,
                    onInputCreated = { mfiI2cPathInput = it },
                ) { value ->
                    mfiI2cPath = value
                    mfiErrorView?.visibility = View.GONE
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                menuText(getString(R.string.linux_device_path_for_example_dev_i2c_1), 14f, MENU_SECONDARY),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) },
            )
        }
        section.addView(
            i2cFields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        mfiI2cFields = i2cFields

        val remoteFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                settingsInputRow(
                    getString(R.string.server_address),
                    remoteMfiServer,
                    onInputCreated = { remoteMfiServerInput = it },
                ) { value ->
                    remoteMfiServer = value
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                settingsInputRow(
                    getString(R.string.token_optional),
                    remoteMfiToken,
                    password = true,
                    onInputCreated = { remoteMfiTokenInput = it },
                ) { value ->
                    remoteMfiToken = value
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) },
            )
            addView(
                menuText(
                    getString(R.string.settings_mfi_address_hint),
                    14f,
                    MENU_SECONDARY,
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) },
            )
        }
        section.addView(
            remoteFields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        mfiRemoteFields = remoteFields
        val error = menuText("", 14f, MENU_DANGER).apply {
            visibility = View.GONE
        }
        section.addView(
            error,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )
        mfiErrorView = error
        updateMfiTargetFields()
        return section
    }

    private fun updateMfiTargetFields() {
        mfiI2cFields?.visibility = if (mfiTarget == MfiTarget.I2C) View.VISIBLE else View.GONE
        mfiRemoteFields?.visibility = if (mfiTarget == MfiTarget.REMOTE) View.VISIBLE else View.GONE
        mfiErrorView?.visibility = View.GONE
    }

    private fun syncMfiSettingsControls() {
        mfiTargetGroup?.let { group ->
            val button = (0 until group.childCount)
                .map { group.getChildAt(it) }
                .filterIsInstance<RadioButton>()
                .firstOrNull { it.tag == mfiTarget }
            button?.let { group.check(it.id) }
        }
        if (mfiI2cPathInput?.text?.toString() != mfiI2cPath) {
            mfiI2cPathInput?.setText(mfiI2cPath)
        }
        if (remoteMfiServerInput?.text?.toString() != remoteMfiServer) {
            remoteMfiServerInput?.setText(remoteMfiServer)
        }
        if (remoteMfiTokenInput?.text?.toString() != remoteMfiToken) {
            remoteMfiTokenInput?.setText(remoteMfiToken)
        }
        updateMfiTargetFields()
    }

    private fun mfiTargetLabel(target: MfiTarget): String = when (target) {
        MfiTarget.LOCAL -> getString(R.string.local_offline)
        MfiTarget.USB_CH341 -> getString(R.string.usb_ch341)
        MfiTarget.I2C -> getString(R.string.i2c)
        MfiTarget.REMOTE -> getString(R.string.remote)
    }

    private fun buildIdentitySettingsSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            settingsInputRow(getString(R.string.manufacturer), manufacturer) { value ->
                manufacturer = value
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        section.addView(
            settingsInputRow(getString(R.string.model), model) { value ->
                model = value
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        section.addView(
            settingsInputRow(getString(R.string.oem_label), oemLabel) { value ->
                oemLabel = value
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        return section
    }

    private fun settingsCategoryHeader(title: String): TextView =
        SettingsWidgets.createCategoryHeader(this, title, SettingsTheme.OVERLAY)

    private fun buildLocationReportingSection(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val row = LinearLayout(this@CarPlayHostActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(
                menuText(getString(R.string.report_location_to_iphone), 20f, MENU_SECONDARY),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            val switch = SwitchCompat(this@CarPlayHostActivity).apply {
                isChecked = locationReportingEnabled
                contentDescription = getString(R.string.report_android_location_to_the_iphone)
                // main 9c7fda97：覆盖层开关统一用大开关样式（内部已做 API 17 适配）（作者：TRAE AI）。
                SettingsWidgets.applyLargeSwitchStyle(this, Color.WHITE, Color.WHITE, MENU_ACCENT_TRACK, MENU_TRACK_OFF)
                setOnCheckedChangeListener { _, checked ->
                    onLocationReportingChanged(checked)
                }
            }
            locationReportingSwitch = switch
            row.addView(
                switch,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                menuText(
                    getString(R.string.sends_precise_android_location_as_carplay_gps_data_when_th),
                    14f,
                    MENU_SECONDARY,
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(6) },
            )
        }

    private fun onLocationReportingChanged(checked: Boolean) {
        if (locationReportingEnabled == checked) return
        locationReportingEnabled = checked
        appendLog(
            "Location reporting ${if (locationReportingEnabled) "enabled" else "disabled"}; " +
                "applies when settings close",
        )
        updateResolutionMenu()
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
        }
    }

    private fun buildDebugLogsSection(): View =
        settingsSwitchRow(
            label = getString(R.string.debug_logs),
            checked = debugLogsEnabled,
            description = getString(R.string.show_on_screen_debug_logs),
        ) { checked ->
            debugLogsEnabled = checked
            appendLog("Debug logs ${if (debugLogsEnabled) "enabled" else "disabled"}")
            updateDebugOverlays()
        }

    private fun buildStepSliderSection(
        title: String,
        values: List<Int>,
        selectedValue: Int,
        label: (Int) -> String,
        onValueChanged: (Int) -> Unit,
    ): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            menuText(title, 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val selectedIndex = values.indexOf(selectedValue)
            .takeIf { it >= 0 }
            ?: 0
        val valueView = menuText(label(values[selectedIndex]), 22f, MENU_ACCENT, bold = true)
        header.addView(
            valueView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        section.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val seekBar = SeekBar(this).apply {
            max = (values.size - 1).coerceAtLeast(0)
            progress = selectedIndex
            // splitTrack/progressTintList/thumbTintList 均为 API 21+，走 UiCompat 兼容 API 17
            UiCompat.setSeekBarTints(this, MENU_ACCENT)
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        val value = values.getOrNull(progress) ?: return
                        valueView.text = label(value)
                        if (fromUser) onValueChanged(value)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                },
            )
        }
        section.addView(
            seekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        return section
    }

    private fun buildAirPlayIconSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.airplay_icon), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(MENU_TRACK_OFF)
            }
        }
        row.addView(
            preview,
            LinearLayout.LayoutParams(dp(72), dp(72)),
        )
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        actions.addView(
            DiPlayButton(this).apply {
                text = getString(R.string.choose_image)
                style(DiPlayButtonKind.SECONDARY, overlayPalette, overlay = true)
                setOnClickListener {
                    externalActivityInProgress = true
                    launchCarButtonImagePicker(
                        openDocument = { imageDocumentPicker.launch(arrayOf("image/*")) },
                        getContent = { imagePicker.launch("image/*") },
                        documentPickerIsSystem = documentPickerIsSystem(),
                    ).onFailure {
                        externalActivityInProgress = false
                        appendLog("No image picker: ${it.javaClass.simpleName}")
                        android.widget.Toast.makeText(this@CarPlayHostActivity,
                            getString(R.string.this_head_unit_has_no_image_picker),
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        actions.addView(
            DiPlayButton(this).apply {
                text = getString(R.string.default_icon)
                style(DiPlayButtonKind.SECONDARY, overlayPalette, overlay = true)
                setOnClickListener {
                    AirPlayPersistence.clearCustomAirPlayIcon(this@CarPlayHostActivity)
                    updateAirPlayIconPreview()
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        row.addView(
            actions,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { marginStart = dp(16) },
        )
        section.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        val status = menuText("", 14f, MENU_SECONDARY)
        section.addView(
            status,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        iconPreviewView = preview
        iconStatusView = status
        updateAirPlayIconPreview()
        return section
    }

    // Between the driver's side and the bottom the dock moves at once (and is saved at once) when the
    // running session declared both; to or from automatic waits for Save, which reconnects.
    private fun buildDockSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.carplay_dock), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val labels = mapOf(
            CarPlayDock.AUTOMATIC to R.string.carplay_dock_automatic,
            CarPlayDock.DRIVER_SIDE to R.string.carplay_dock_driver_side,
            CarPlayDock.BOTTOM to R.string.carplay_dock_bottom,
        )
        val buttons = CarPlayDock.entries.associateWith { dock ->
            RadioButton(this).apply {
                id = View.generateViewId()
                text = getString(labels.getValue(dock))
                setTextColor(Color.WHITE)
                isChecked = carPlayDock == dock
            }.also { group.addView(it) }
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val next = buttons.entries.firstOrNull { it.value.id == checkedId }?.key ?: return@setOnCheckedChangeListener
            carPlayDock = next
            val from = sessionDock
            val areas = sessionDisplay?.viewAreas
            val target = next.edge?.let { areas?.withDock(it) }
            if (from != null && areas != null && target != null && CarPlayDock.movesLive(from, next) &&
                controller?.showViewArea(target) == true
            ) {
                areas.use(target)
                CarPlayDock.save(this, next)
            }
        }
        section.addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        section.addView(menuText(getString(R.string.carplay_dock_hint), 16f, MENU_SECONDARY))
        return section
    }

    private fun buildDrivingSideSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.driving_side), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val left = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.left_hand_drive)
            setTextColor(Color.WHITE)
            isChecked = !rightHandDrive
        }
        val right = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.right_hand_drive)
            setTextColor(Color.WHITE)
            isChecked = rightHandDrive
        }
        group.addView(left)
        group.addView(right)
        group.setOnCheckedChangeListener { _, checkedId ->
            rightHandDrive = checkedId == right.id
            updateResolutionMenu()
        }
        section.addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        return section
    }

    private fun buildFullscreenSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.fullscreen), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        addSystemBarControls(
            hideTopBar = hideTopBar,
            hideBottomBar = hideBottomBar,
            onHideTopBarChanged = { checked ->
                hideTopBar = checked
                applyFullscreenMode()
                refreshDisplaySizeAfterLayout()
            },
            onHideBottomBarChanged = { checked ->
                hideBottomBar = checked
                applyFullscreenMode()
                refreshDisplaySizeAfterLayout()
            },
        ) { label, checked, onChanged ->
            section.addView(
                settingsSwitchRow(getString(label), checked, getString(label), onChanged),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(10) },
            )
        }
        section.addView(
            settingsSwitchRow(
                getString(R.string.temporary_system_bars),
                temporarySystemBarsEnabled,
                getString(R.string.temporary_system_bars_description),
            ) { checked ->
                temporarySystemBarsEnabled = checked
                if (!checked) cancelTemporarySystemBars()
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12) },
        )
        return section
    }

    private fun buildSafeAreaSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.safe_area), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val summary = menuText("", 15f, MENU_ACCENT)
        section.addView(
            summary,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        buttons.addView(
            DiPlayButton(this).apply {
                text = getString(R.string.set)
                style(DiPlayButtonKind.PRIMARY, overlayPalette, overlay = true)
                setOnClickListener { openSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        buttons.addView(
            DiPlayButton(this).apply {
                text = getString(R.string.reset)
                style(DiPlayButtonKind.SECONDARY, overlayPalette, overlay = true)
                setOnClickListener { resetSafeAreaForCurrentSize() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            },
        )
        section.addView(
            buttons,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        section.addView(
            settingsSwitchRow(
                label = getString(R.string.draw_outside_safe_area),
                checked = safeAreaDrawOutside,
                description = getString(R.string.allow_carplay_ui_outside_the_safe_area),
            ) { checked ->
                safeAreaDrawOutside = checked
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        // main #596：中控直角铺满——声明 cornerMasks 并省略中控 safeArea。
        section.addView(
            settingsSwitchRow(
                label = getString(R.string.rect_fill_disable_safe_area),
                checked = mainDisplayDisableSafeArea,
                description = getString(R.string.rect_fill_disable_safe_area_description),
            ) { checked ->
                mainDisplayDisableSafeArea = checked
                updateSafeAreaSummary()
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        safeAreaSummaryView = summary
        updateSafeAreaSummary()
        return section
    }

    private fun buildSafeAreaEditor(): View {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
        }
        val editor = SafeAreaEditorView(this)
        overlay.addView(
            editor,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        overlay.addView(
            menuText(getString(R.string.safe_area), 24f, Color.WHITE, bold = true).apply {
                setPadding(dp(16), dp(12), dp(16), dp(8))
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ),
        )
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(16))
        }
        controls.addView(
            DiPlayButton(this).apply {
                text = getString(R.string.cancel)
                style(DiPlayButtonKind.SECONDARY, overlayPalette, overlay = true)
                setOnClickListener { closeSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        controls.addView(
            DiPlayButton(this).apply {
                text = getString(R.string.save)
                style(DiPlayButtonKind.PRIMARY, overlayPalette, overlay = true)
                setOnClickListener { saveSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            },
        )
        overlay.addView(
            controls,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        safeAreaEditorView = editor
        return overlay
    }

    private fun settingsInputRow(
        label: String,
        value: String,
        password: Boolean = false,
        numeric: Boolean = false,
        onInputCreated: ((EditText) -> Unit)? = null,
        onChanged: (String) -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            menuText(label, 18f, MENU_SECONDARY).apply {
                gravity = Gravity.CENTER_VERTICAL
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(
            EditText(this@CarPlayHostActivity).apply {
                setText(value)
                textSize = 18f
                setTextColor(Color.WHITE)
                setHintTextColor(MENU_SECONDARY)
                UiCompat.setBackgroundTint(this, ColorStateList.valueOf(MENU_ACCENT))
                minHeight = dp(48)
                isSingleLine = true
                inputType = when {
                    numeric -> InputType.TYPE_CLASS_NUMBER
                    password -> InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_PASSWORD or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                addTextChangedListener(afterTextChanged(onChanged))
                onInputCreated?.invoke(this)
            },
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { marginStart = dp(12) },
        )
    }

    private fun settingsSwitchRow(
        label: String,
        checked: Boolean,
        description: String,
        onChanged: (Boolean) -> Unit,
    ): View = SettingsWidgets.createSwitchRow(
        context = this,
        label = label,
        description = description,
        checked = checked,
        theme = SettingsTheme.OVERLAY,
        contentDescription = description,
        onChanged = onChanged,
    ).rowView

    private fun afterTextChanged(onChanged: (String) -> Unit): TextWatcher =
        object : TextWatcher {
            override fun beforeTextChanged(
                text: CharSequence?,
                start: Int,
                count: Int,
                after: Int,
            ) = Unit

            override fun onTextChanged(
                text: CharSequence?,
                start: Int,
                before: Int,
                count: Int,
            ) = Unit

            override fun afterTextChanged(text: Editable?) {
                onChanged(text?.toString().orEmpty())
            }
        }

    private fun buildHotspotModeSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.wi_fi_session), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val group = RadioGroup(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val modes = buildList {
            add(WirelessHotspotMode.MANUAL to getString(R.string.settings_built_in_car_hotspot_recommended))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(WirelessHotspotMode.WIFI_P2P to getString(R.string.wi_fi_p2p_5_ghz))
            }
            // EXISTING_WIFI 依赖 ConnectivityManager.NetworkCallback（API 21），API 17-20 设备不可用
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                add(WirelessHotspotMode.EXISTING_WIFI to getString(R.string.existing_wifi_title))
            }
        }
        var selectedId = View.NO_ID
        for ((mode, label) in modes) {
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 18f
                setTextColor(MENU_SECONDARY)
                // setButtonTintList 是 API 21+，API 17 设备直接调用会抛 NoSuchMethodError，走 UiCompat 兜底
                UiCompat.setButtonTint(
                    this,
                    ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(MENU_ACCENT, MENU_SECONDARY),
                    ),
                )
                tag = mode
                isChecked = wirelessHotspotMode == mode
            }
            if (wirelessHotspotMode == mode) selectedId = button.id
            group.addView(
                button,
                RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        if (selectedId != View.NO_ID) group.check(selectedId)
        group.setOnCheckedChangeListener { radioGroup, checkedId ->
            val selected = radioGroup.findViewById<RadioButton>(checkedId)
                ?.tag as? WirelessHotspotMode
                ?: return@setOnCheckedChangeListener
            if (wirelessHotspotMode == selected) return@setOnCheckedChangeListener
            wirelessHotspotMode = selected
            hotspotStatus = HotspotStatus(state = if (wirelessEnabled) getString(R.string.hotspot_state_stopped) else getString(R.string.hotspot_state_off))
            updateHotspotStatusBlock()
            updateManualHotspotFields()
            appendLog(
                "Wi-Fi session mode: ${hotspotModeLabel(wirelessHotspotMode)}; " +
                    "applies when settings close",
            )
        }
        section.addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val manualFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        manualFields.addView(
            settingsInputRow(getString(R.string.hotspot_ssid), manualHotspotSsid) { value ->
                manualHotspotSsid = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        manualFields.addView(
            settingsChoiceRow(
                label = getString(R.string.band),
                options = listOf(
                    ManualHotspotBand.AUTO to getString(R.string.auto),
                    ManualHotspotBand.GHZ_2_4 to getString(R.string.s_2_4_ghz),
                    ManualHotspotBand.GHZ_5 to getString(R.string.s_5_ghz),
                ),
                selected = manualHotspotBand,
            ) { value ->
                manualHotspotBand = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        manualFields.addView(
            settingsInputRow(
                label = getString(R.string.channel_0_auto),
                value = manualHotspotChannel.toString(),
                numeric = true,
            ) { value ->
                manualHotspotChannel = value.toIntOrNull() ?: -1
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        manualFields.addView(
            settingsInputRow(
                label = getString(R.string.hotspot_password),
                value = manualHotspotPassphrase,
                password = true,
            ) { value ->
                manualHotspotPassphrase = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        manualFields.addView(
            settingsChoiceRow(
                label = getString(R.string.security),
                options = listOf(
                    ManualHotspotSecurity.OPEN to getString(R.string.open),
                    ManualHotspotSecurity.WPA2 to getString(R.string.wpa2),
                    ManualHotspotSecurity.WPA3_TRANSITION to getString(R.string.wpa3_transition),
                    ManualHotspotSecurity.WPA3 to getString(R.string.wpa3),
                ),
                selected = manualHotspotSecurity,
            ) { value ->
                manualHotspotSecurity = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        val error = menuText("", 14f, Color.rgb(0xff, 0x7a, 0x7a)).apply {
            visibility = View.GONE
        }
        manualFields.addView(
            error,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )

        section.addView(
            manualFields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        manualHotspotFields = manualFields
        manualHotspotErrorView = error
        val existingFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(menuText(getString(R.string.existing_wifi_instructions), 14f, MENU_SECONDARY))
            addView(settingsInputRow(getString(R.string.existing_wifi_ssid), existingWifiSsid) {
                existingWifiSsid = it
                existingWifiErrorView?.visibility = View.GONE
            })
            addView(settingsInputRow(getString(R.string.existing_wifi_password), existingWifiPassphrase, password = true) {
                existingWifiPassphrase = it
                existingWifiErrorView?.visibility = View.GONE
            })
        }
        existingWifiErrorView = menuText("", 14f, MENU_DANGER).apply { visibility = View.GONE }
        existingFields.addView(existingWifiErrorView)
        section.addView(existingFields)
        existingWifiFields = existingFields
        updateManualHotspotFields()
        return section
    }

    private fun updateManualHotspotFields() {
        existingWifiFields?.visibility = if (wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI) View.VISIBLE else View.GONE
        existingWifiErrorView?.visibility = View.GONE
        val visible = wirelessHotspotMode == WirelessHotspotMode.MANUAL
        manualHotspotFields?.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) manualHotspotErrorView?.visibility = View.GONE
    }

    private fun validateMfiSettings(): Boolean {
        val error = when {
            mfiTarget == MfiTarget.LOCAL && runCatching { DiPlayBootstrap.ensure(this, mfiTarget) }.isFailure ->
                getString(R.string.setup_error_auth)
            mfiTarget == MfiTarget.I2C && mfiI2cPath.isBlank() ->
                getString(R.string.i2c_device_path_is_required)
            mfiTarget == MfiTarget.REMOTE && remoteMfiServer.isBlank() ->
                getString(R.string.remote_server_address_is_required)
            mfiTarget == MfiTarget.REMOTE &&
                !remoteMfiServer.trim().startsWith("http://") &&
                !remoteMfiServer.trim().startsWith("https://") ->
                getString(R.string.remote_server_address_must_start_with_http_or_https)
            '\u0000' in mfiI2cPath -> getString(R.string.i2c_device_path_contains_u_0000)
            '\u0000' in remoteMfiServer -> getString(R.string.remote_server_address_contains_u_0000)
            '\u0000' in remoteMfiToken -> getString(R.string.remote_token_contains_u_0000)
            else -> null
        }
        mfiErrorView?.text = error.orEmpty()
        mfiErrorView?.visibility = if (error == null) View.GONE else View.VISIBLE
        return error == null
    }

    private fun validateManualHotspotSettings(): Boolean {
        if (wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI) {
            val error = com.shilapi.xcertplay.orchestration.ManualHotspotValidation.error(existingWifiSsid, existingWifiPassphrase)
            existingWifiErrorView?.text = error?.let { getString(it.messageResource()) }.orEmpty()
            existingWifiErrorView?.visibility = if (error == null) View.GONE else View.VISIBLE
            return error == null
        }
        if (wirelessHotspotMode != WirelessHotspotMode.MANUAL) return true
        val error = when {
            manualHotspotSsid.isBlank() -> getString(R.string.hotspot_ssid_is_required)
            manualHotspotSsid.encodeToByteArray().size > 32 ->
                getString(R.string.hotspot_ssid_must_be_at_most_32_utf_8_bytes)
            '\u0000' in manualHotspotSsid -> getString(R.string.hotspot_ssid_contains_u_0000)
            manualHotspotChannel !in 0..196 -> getString(R.string.channel_must_be_0_or_1_196)
            manualHotspotChannel != 0 &&
                !isManualHotspotChannelCompatible(manualHotspotBand, manualHotspotChannel) ->
                getString(R.string.channel_is_not_valid_for_the_selected_band)
            '\u0000' in manualHotspotPassphrase -> getString(R.string.hotspot_password_contains_u_0000)
            manualHotspotSecurity == ManualHotspotSecurity.OPEN &&
                manualHotspotPassphrase.isNotEmpty() ->
                getString(R.string.password_must_be_empty_when_security_is_open)
            manualHotspotSecurity != ManualHotspotSecurity.OPEN &&
                manualHotspotPassphrase.length !in 8..63 ->
                getString(R.string.wpa2_wpa3_password_must_be_8_63_characters)
            else -> null
        }
        manualHotspotErrorView?.text = error.orEmpty()
        manualHotspotErrorView?.visibility = if (error == null) View.GONE else View.VISIBLE
        return error == null
    }

    private fun hotspotModeLabel(mode: WirelessHotspotMode): String = when (mode) {
        WirelessHotspotMode.WIFI_P2P -> getString(R.string.wi_fi_p2p_5_ghz)
        WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> getString(R.string.localonlyhotspot)
        WirelessHotspotMode.MANUAL -> getString(R.string.manual_hotspot)
        WirelessHotspotMode.EXISTING_WIFI -> getString(R.string.existing_wifi_title)
    }

    private fun menuText(
        text: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        includeFontPadding = false
    }

    private fun updateHotspotStatus(status: CarPlayStatus) {
        if (!wirelessEnabled) return
        hotspotStatus = when (status) {
            CarPlayStatus.StartingHotspot -> HotspotStatus(state = getString(R.string.starting))
            is CarPlayStatus.HotspotReady -> HotspotStatus(
                state = getString(R.string.ready),
                ssid = status.ssid,
                band = status.band,
                channel = status.channel,
                backend = status.backend,
            )
            CarPlayStatus.WaitingForPairedIphone ->
                hotspotStatus.copy(state = getString(R.string.waiting_for_paired_iphone))
            CarPlayStatus.ConnectingBluetooth ->
                hotspotStatus.copy(state = getString(R.string.connecting_bluetooth))
            CarPlayStatus.RunningWireless ->
                hotspotStatus.copy(state = getString(R.string.running))
            CarPlayStatus.WirelessActive, CarPlayStatus.WirelessActiveFallback ->
                hotspotStatus.copy(state = getString(R.string.active))
            CarPlayStatus.AttachingNetwork ->
                hotspotStatus.copy(state = getString(R.string.starting_airplay_service))
            is CarPlayStatus.Failed -> hotspotStatus.copy(state = getString(R.string.error))
            else -> return
        }
        updateHotspotStatusBlock()
    }

    private fun updateHotspotStatusBlock() {
        if (!wirelessEnabled) {
            hotspotStatusView?.text = getString(R.string.wireless_hotspot_off)
            return
        }
        val status = hotspotStatus
        hotspotStatusView?.text = buildString {
            append(getString(R.string.hotspot_wireless_prefix)).append(status.state)
            status.ssid?.let { append(getString(R.string.hotspot_ssid_prefix)).append(it) }
            status.backend?.let { append(getString(R.string.hotspot_backend_prefix)).append(it) }
            status.band?.let { append(getString(R.string.hotspot_band_prefix)).append(it) }
            status.channel?.let {
                append(getString(R.string.hotspot_channel_prefix)).append(if (it == 0) getString(R.string.auto_label) else it.toString())
            }
        }
    }

    private fun <T> settingsChoiceRow(
        label: String,
        options: List<Pair<T, String>>,
        selected: T,
        onSelected: (T) -> Unit,
    ): View = SettingsWidgets.createChoiceRow(
        context = this,
        label = label,
        options = options,
        selected = selected,
        theme = SettingsTheme.OVERLAY,
        onSelected = onSelected,
    ).container

    private fun updateResolutionMenu() {
        resolutionValueView?.text = "${displayScalePercent}%"
        val native = activeDisplaySize ?: currentActivitySize()
        val resolution = if (native == null) {
            getString(R.string.handshake_resolution_waiting_for_display)
        } else {
            val negotiated = CarPlayDisplayScale.applyPercent(
                AirPlayDisplayConfig(
                    widthPixels = native.width,
                    heightPixels = native.height,
                    widthPhysicalMm = widthPhysicalMm,
                    fps = fps,
                ),
                displayScalePercent,
            )
            "${getString(R.string.resolution_handshake_prefix)}${native.width} x ${native.height} -> " +
                "${negotiated.widthPixels} x ${negotiated.heightPixels}"
        }
        val transport = if (!sessionHevc) {
            "H.264"
        } else {
            "HEVC (H.265, ${if (hevcSoftwareDecoderEnabled) "software" else "hardware"})"
        }
        val fullscreen = buildString {
            append(if (hideTopBar) getString(R.string.fullscreen_top_hidden) else getString(R.string.fullscreen_top_shown))
            append(", ")
            append(if (hideBottomBar) getString(R.string.fullscreen_bottom_hidden) else getString(R.string.fullscreen_bottom_shown))
        }
        resolutionPreviewView?.text = buildString {
            append(resolution).append('\n')
            append(getString(R.string.preview_identity)).append(normalizedManufacturer()).append(" / ")
                .append(normalizedModel()).append('\n')
            append(getString(R.string.preview_oem_label)).append(oemLabel.ifBlank { getString(R.string.preview_empty) }).append('\n')
            append(getString(R.string.preview_frame_rate)).append(fps).append(" fps\n")
            append(getString(R.string.preview_detected_maximum))
                .append(maximumDetectedWidthPixels).append(" x ")
                .append(maximumDetectedHeightPixels).append(" px\n")
            append(getString(R.string.preview_physical_reference))
                .append(
                    when (physicalSizeBasis) {
                        AirPlayPhysicalSizeBasis.WIDTH -> getString(R.string.basis_widest_width)
                        AirPlayPhysicalSizeBasis.HEIGHT -> getString(R.string.basis_longest_height)
                    },
                )
                .append(" = ").append(widthPhysicalMm).append(" mm\n")
            native?.let { size ->
                val physical = resolvePhysicalSize(size)
                append(getString(R.string.preview_carplay_physical_size))
                    .append(physical.widthMm).append(" x ")
                    .append(physical.heightMm).append(" mm\n")
            }
            append(getString(R.string.preview_driving_side)).append(if (rightHandDrive) getString(R.string.driving_side_right) else getString(R.string.driving_side_left)).append('\n')
            append(getString(R.string.preview_fullscreen)).append(fullscreen).append('\n')
            append(getString(R.string.preview_video_transport)).append(transport).append('\n')
            append(getString(R.string.preview_location_reporting))
                .append(if (locationReportingEnabled) getString(R.string.enabled_value) else getString(R.string.disabled_value))
                .append('\n')
            if (advancedAudioChannelMappingSupported) {
                append(getString(R.string.preview_audio_channel_mapping))
                    .append(if (advancedAudioChannelMapping) getString(R.string.mapping_aaos_buses) else getString(R.string.mapping_mobile_compatible))
                    .append('\n')
            }
            append(safeAreaSummary())
        }
    }

    private data class CanvasSupport(val supported: Boolean, val reason: String, val details: String)

    private fun largerCanvasSupport(display: AirPlayDisplayConfig): CanvasSupport =
        if (maxOf(display.widthPixels, display.heightPixels) > 3840 || minOf(display.widthPixels, display.heightPixels) > 2160) {
            CanvasSupport(false, "canvas_4k_limit", "Decoder capability check skipped: canvas exceeds enlargement limit")
        } else decoderCanvasSupport(display)

    private fun decoderCanvasSupport(display: AirPlayDisplayConfig): CanvasSupport = try {
        val mime = if (sessionHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        // Match MediaCodec.createDecoderByType's first suitable decoder; do not silently force
        // an enlarged stream through a software decoder on a slower head unit.
        val decoder = CodecCompat.all().firstOrNull {
            !it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) }
        }
        if (decoder == null) {
            CanvasSupport(false, "no_decoder", "Decoder capability mime=$mime result=no_decoder")
        } else {
            val hardware = if (Build.VERSION.SDK_INT >= 29) decoder.isHardwareAccelerated
                else !decoder.name.startsWith("OMX.google.") && !decoder.name.startsWith("c2.android.")
            val video = decoder.getCapabilitiesForType(mime).videoCapabilities
            val sizeSupported = video?.isSizeSupported(display.widthPixels, display.heightPixels) == true
            val rateSupported = sizeSupported && video?.areSizeAndRateSupported(
                display.widthPixels, display.heightPixels, display.fps.toDouble()) == true
            val reason = when {
                !hardware -> "software_decoder"
                sessionHevc && hevcSoftwareDecoderEnabled -> "software_hevc_selected"
                video == null -> "no_video_capabilities"
                !sizeSupported -> "canvas_dimensions_unsupported"
                !rateSupported -> "frame_rate_unsupported"
                else -> "supported"
            }
            CanvasSupport(reason == "supported", reason,
                "Decoder capability codec=${decoder.name} mime=$mime hardware=$hardware " +
                    "sizeSupported=$sizeSupported rateSupported=$rateSupported " +
                    "widths=${video?.supportedWidths} heights=${video?.supportedHeights} " +
                    "alignment=${video?.widthAlignment}x${video?.heightAlignment} " +
                    "fpsRange=${video?.supportedFrameRates} result=$reason")
        }
    } catch (error: Throwable) {
        // 同上：NoSuchMethodError 属 Error，catch (Exception) 拦不住，会让整个 host 崩掉。
        CanvasSupport(false, "capability_query_${error.javaClass.simpleName}",
            "Decoder capability query failed error=${error.javaClass.simpleName}")
    }

    private fun createAirPlayConfig(size: DisplaySize): AirPlayConfig {
        // The home settings page can change the name while this host stays alive.
        if (!menuOpen) oemLabel = AirPlayPersistence.loadOemLabel(this)
        val safeWidth = (size.width / 2 * 2).coerceAtLeast(2)
        val safeHeight = (size.height / 2 * 2).coerceAtLeast(2)
        val alignedSize = DisplaySize(safeWidth, safeHeight)
        val physical = resolvePhysicalSize(alignedSize)
        val knobPrimary = AndroidTvInputMode.shouldUseKnobAsPrimaryInput(this)
        val baseDisplay = AirPlayDisplayConfig(
            widthPixels = alignedSize.width,
            heightPixels = alignedSize.height,
            widthPhysicalMm = physical.widthMm,
            heightPhysicalMm = physical.heightMm,
            fps = fps,
            // Tell CarPlay to use its native knob/focus model on Android TV and other non-touch
            // hosts. Touch-capable head units remain touchscreen-primary.
            primaryInputDevice = if (knobPrimary) 3 else 1,
        )
        appendLog(
            "CarPlay primary input=${if (knobPrimary) "knob" else "touch"} " +
                "tv=${AndroidTvInputMode.isTelevision(this)} " +
                "touchscreen=${resources.configuration.touchscreen}",
        )
        val requestedResolutionPercent = displayScalePercent
        val requestedResolutionDisplay = CarPlayDisplayScale.applyPercent(baseDisplay, requestedResolutionPercent)
        var resolutionDisplay = requestedResolutionDisplay
        val requestedPercent = uiScalePercent
        var scaledDisplay = CarPlayUiScale.apply(resolutionDisplay, uiScalePercent)
        val candidate = scaledDisplay
        var support = when {
            uiScalePercent < CarPlayUiScale.DEFAULT && scaledDisplay === resolutionDisplay ->
                CanvasSupport(false, "canvas_4k_limit", "Decoder capability check skipped: canvas exceeds enlargement limit")
            uiScalePercent < CarPlayUiScale.DEFAULT || scaledDisplay.widthPixels > baseDisplay.widthPixels ||
                scaledDisplay.heightPixels > baseDisplay.heightPixels -> largerCanvasSupport(scaledDisplay)
            else -> CanvasSupport(true, "not_enlarging", "Decoder capability enlargement check not required")
        }
        var smallerUiFallback = false
        var resolutionFallback = false
        if (!support.supported && uiScalePercent < CarPlayUiScale.DEFAULT) {
            val failedCanvas = support
            smallerUiFallback = true
            uiScalePercent = CarPlayUiScale.DEFAULT
            AirPlayPersistence.saveUiScalePercent(this, uiScalePercent)
            scaledDisplay = resolutionDisplay
            appendLog("Larger CarPlay canvas unavailable reason=${failedCanvas.reason}; using Default icon and text size")
            // Removing the smaller-controls enlargement may leave a separately enlarged resolution.
            if (displayScalePercent > 100) {
                val resolutionSupport = largerCanvasSupport(scaledDisplay)
                support = resolutionSupport.copy(
                    reason = if (resolutionSupport.supported) failedCanvas.reason else resolutionSupport.reason,
                    details = "${failedCanvas.details}\n${resolutionSupport.details}",
                )
            }
        }
        if (!support.supported && displayScalePercent > 100) {
            resolutionFallback = true
            displayScalePercent = 100
            displayScaleTenths = CarPlayDisplayScale.DEFAULT_TENTHS
            AirPlayPersistence.saveDisplayScalePercent(this, displayScalePercent)
            AirPlayPersistence.saveDisplayScaleTenths(this, displayScaleTenths)
            resolutionDisplay = baseDisplay
            scaledDisplay = CarPlayUiScale.apply(resolutionDisplay, uiScalePercent)
            appendLog("Enlarged CarPlay resolution unavailable reason=${support.reason}; using 100%")
        }
        if (smallerUiFallback || resolutionFallback) {
            runOnUiThread {
                android.widget.Toast.makeText(this,
                    getString(if (resolutionFallback) R.string.resolution_canvas_unsupported
                        else R.string.this_head_unit_cannot_use_the_smaller_size_at_this_resolut),
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
        appendLog("CarPlay size=${CarPlayUiScale.label(uiScalePercent)} canvas=${scaledDisplay.widthPixels}x${scaledDisplay.heightPixels}")
        val display = scaledDisplay.copy(
            safeArea = if (mainDisplayDisableSafeArea) null else AirPlaySafeArea.toInsets(
                mapping = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height),
                activityWidthPixels = size.width,
                activityHeightPixels = size.height,
                displayWidthPixels = scaledDisplay.widthPixels,
                displayHeightPixels = scaledDisplay.heightPixels,
            ),
            safeAreaDrawOutside = safeAreaDrawOutside,
            mainDisplayCornerMasks = if (mainDisplayDisableSafeArea) true else null,
            mainDisplayDisableSafeArea = mainDisplayDisableSafeArea,
        )
        // A fixed dock, split-screen support or a turning screen declares several areas the car switches
        // between live. A session that starts in split screen sizes its canvas to that window, so it keeps
        // the plain canvas and one area for it.
        val inSplitScreen = isMultiWindowActive()
        val dock = CarPlayDock.load(this).also { sessionDock = it }
        // The whole screen and CarPlay's full window in each orientation: the system bars take their
        // place in both, so a remembered split window is sized against the window of its orientation.
        val screen = android.util.DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
        val screenLong = maxOf(screen.widthPixels, screen.heightPixels)
        val screenShort = minOf(screen.widthPixels, screen.heightPixels)
        val (landscapeWindow, portraitWindow) = CarPlayRotation.turnedWindows(size.width, size.height,
            screen.widthPixels, screen.heightPixels)
        // Before DiPlay has seen a split window, expect one from the screen, its bars and Android's divider.
        val statusBar = systemDimension("status_bar_height")
        val navigationBar = systemDimension("navigation_bar_height")
        val divider = (systemDimension("docked_stack_divider_thickness") -
            2 * systemDimension("docked_stack_divider_insets")).coerceAtLeast(0)
        val splitWindow: (Boolean) -> Pair<Float, Float>? = { portrait ->
            if (SplitScreenSettings.enabled(this) && !inSplitScreen) {
                val window = if (portrait) portraitWindow else landscapeWindow
                val expected = SplitScreenSettings.expectedWindow(portrait, screenLong, screenShort,
                    statusBar, navigationBar, divider)
                SplitScreenSettings.ofWindow(SplitScreenSettings.window(this, portrait, expected),
                    if (portrait) screenShort else screenLong, if (portrait) screenLong else screenShort,
                    window.first, window.second)
            } else null
        }
        val longPixels = maxOf(display.widthPixels, display.heightPixels)
        val square = if (CarPlayRotation.enabled(this) && !inSplitScreen) {
            CarPlayRotation.squareSide(longPixels, CarPlayRotation.picture(this), sessionHevc, hevcSoftwareDecoderEnabled)
        } else null
        val canvas = if (square == null) display else {
            // The square keeps the plain canvas's pixel density, so CarPlay's scale (and "CarPlay size")
            // stays the same in both orientations; it has no room for the custom safe area.
            val longMm = if (display.widthPixels >= display.heightPixels) display.widthPhysicalMm else display.heightPhysicalMm
            val squareMm = longMm?.let { (it.toLong() * square / longPixels).toInt() }
            display.copy(widthPixels = square, heightPixels = square, widthPhysicalMm = squareMm,
                heightPhysicalMm = squareMm, viewArea = null, safeArea = null)
        }
        val viewAreas = if (square == null) {
            CarPlayViewAreas.build(display.widthPixels, display.heightPixels, listOf(
                CarPlayViewAreas.Screen(display.widthPixels, display.heightPixels, portrait = display.heightPixels > display.widthPixels),
            ), dock, splitWindow, startPortrait = display.heightPixels > display.widthPixels,
                sidePanel = SidePanelSettings.enabled(this), rightHandDrive = rightHandDrive)
        } else {
            val (landscape, portrait) = CarPlayRotation.turningAreas(square, size.width, size.height,
                screen.widthPixels, screen.heightPixels)
            CarPlayViewAreas.build(square, square, listOf(
                CarPlayViewAreas.Screen(landscape.first, landscape.second, portrait = false),
                CarPlayViewAreas.Screen(portrait.first, portrait.second, portrait = true),
            ), dock, splitWindow, startPortrait = size.height > size.width, sidePanel = SidePanelSettings.enabled(this),
                rightHandDrive = rightHandDrive)
        }
        pendingViewAreas = viewAreas
        val declared = if (viewAreas == null) canvas else canvas.copy(viewAreas = viewAreas.areas, initialViewArea = viewAreas.current)
        if (square != null) appendLog("Turning screen: square canvas ${square}x$square, areas ${viewAreas?.areas}")
        if (SplitScreenSettings.enabled(this) && !inSplitScreen) {
            appendLog("Split screen expected from status bar=$statusBar navigation bar=$navigationBar divider=$divider px")
        }
        val requestSummary = "Display request selected=${CarPlayUiScale.label(requestedPercent)} percent=$requestedPercent " +
            "surface=${size.width}x${size.height} resolution=${requestedResolutionPercent}% " +
            "base=${requestedResolutionDisplay.widthPixels}x${requestedResolutionDisplay.heightPixels} " +
            "candidate=${candidate.widthPixels}x${candidate.heightPixels} fps=$fps " +
            "codec=${if (sessionHevc) "HEVC" else "H.264"} softwareHevc=$hevcSoftwareDecoderEnabled"
        val effectiveSummary = "Display effective percent=$uiScalePercent resolution=${displayScalePercent}% " +
            "canvas=${display.widthPixels}x${display.heightPixels} decision=${support.reason} " +
            "physical=${physical.widthMm}x${physical.heightMm}mm safeArea=${display.safeArea} " +
            "drawOutside=${display.safeAreaDrawOutside}"
        displayDiagnosticAttempt = DisplayDiagnosticSnapshot.begin(this, requestSummary, support.details, effectiveSummary)
        appendLog(requestSummary)
        if (hevcEnabled && !sessionHevc) {
            appendLog("HEVC is on, but no hardware decoder plays it (${hevcDecoders.joinToString { it.name }.ifEmpty { "none" }}); " +
                "this session uses H.264")
        }
        appendLog(support.details)
        appendLog(effectiveSummary)
        // main #432：CarPlay 音频走车机蓝牙（实验性）。
        val carBluetoothAudio = AirPlayPersistence.loadCarBluetoothAudio(this)
        if (carBluetoothAudio) logCarBluetoothAudio()
        return AirPlayConfig(
            deviceName = "DiPlay",
            deviceId = DiPlayBootstrap.deviceId(airPlayIdentity),
            btMac = DiPlayBluetooth.localAddress(this) ?: DiPlayBootstrap.deviceId(airPlayIdentity),
            sourceVersion = "950.7.1",
            main = declared,
            cluster = clusterDisplayConfig(),
            rightHandDrive = rightHandDrive,
            hevc = sessionHevc,
            microphone = microphoneAvailable,
            manufacturer = normalizedManufacturer(),
            model = normalizedModel(),
            oemLabel = oemLabel,
            icons = listOf(loadAirPlayIcon()),
            videoInCar = com.shilapi.xcertplay.hud.BydOutputSettings.videoWhileParkedActive(this),
            mainBufferedAudio = AirPlayPersistence.loadMainBufferedAudio(this),
            speechWithout8And16Khz = AirPlayPersistence.loadSpeechWithout8And16Khz(this),
            disableAudioOutput = carBluetoothAudio,
        )
    }

    /** 车机蓝牙音频：记录 iPhone 当前及 CarPlay 运行后是否与车机保持 A2DP/HFP（main #432）。 */
    private fun logCarBluetoothAudio() {
        appendLog("Car Bluetooth audio: on; CarPlay audio not offered")
        // API 17 适配：getSystemService(Class) 为 API 23+，统一走 BluetoothCompat.getAdapter。
        val adapter = runCatching { com.shilapi.xcertplay.compat.BluetoothCompat.getAdapter(this) }.getOrNull()
            ?: return appendLog("Car Bluetooth audio: no Bluetooth adapter")
        fun probe(moment: String) {
            for ((name, profile) in listOf("A2DP" to android.bluetooth.BluetoothProfile.A2DP,
                    "HFP" to android.bluetooth.BluetoothProfile.HEADSET)) {
                runCatching {
                    @SuppressLint("MissingPermission")
                    val started = adapter.getProfileProxy(applicationContext, object : android.bluetooth.BluetoothProfile.ServiceListener {
                        @SuppressLint("MissingPermission")
                        override fun onServiceConnected(id: Int, proxy: android.bluetooth.BluetoothProfile) {
                            val devices = runCatching { proxy.connectedDevices.map { it.name ?: "?" } }
                                .getOrElse { listOf("unreadable:${it.javaClass.simpleName}") }
                            appendLog("Car Bluetooth audio: $moment $name connected=$devices")
                            adapter.closeProfileProxy(id, proxy)
                        }
                        override fun onServiceDisconnected(id: Int) = Unit
                    }, profile)
                    if (!started) appendLog("Car Bluetooth audio: $moment $name unavailable")
                }.onFailure { appendLog("Car Bluetooth audio: $moment $name unavailable ${it.javaClass.simpleName}") }
            }
        }
        probe("start")
        mainHandler.postDelayed({ if (!isDestroyed) probe("after30s") }, 30_000L)
    }

    private fun loadAirPlayIcon(): AirPlayIcon {
        val customBytes = try {
            AirPlayPersistence.loadCustomAirPlayIconFile(this)?.readBytes()
        } catch (_: Exception) {
            null
        }
        if (customBytes != null) {
            decodeAirPlayIcon(customBytes)?.let { return it }
            AirPlayPersistence.clearCustomAirPlayIcon(this)
        }
        return decodeAirPlayIcon(defaultAirPlayIconBytes())
            ?: throw IllegalStateException("Packaged AirPlay icon is invalid")
    }

    private fun decodeAirPlayIcon(encoded: ByteArray): AirPlayIcon? {
        val (size, bytes) = AirPlayIconSizing.fit(encoded) ?: return null
        if (bytes !== encoded) appendLog("CarPlay car-button icon resized bytes=${encoded.size}->${bytes.size} pixels=$size")
        return AirPlayIcon(size, size, bytes)
    }

    private fun defaultAirPlayIconBytes(): ByteArray =
        // Shown in CarPlay's app list as the "back to the car" button.
        resources.openRawResource(R.raw.ic_car_home).use { it.readBytes() }

    private fun updateAirPlayIconPreview() {
        val preview = iconPreviewView ?: return
        val custom = AirPlayPersistence.loadCustomAirPlayIconFile(this)
        var customBitmap: Bitmap? = null
        if (custom != null) {
            customBitmap = BitmapFactory.decodeFile(custom.absolutePath)
            if (customBitmap == null) {
                AirPlayPersistence.clearCustomAirPlayIcon(this)
            }
        }
        val bitmap = customBitmap ?: BitmapFactory.decodeResource(resources, R.raw.placeholder_icon)
        preview.setImageBitmap(bitmap)
        iconStatusView?.text =
            if (customBitmap != null) getString(R.string.custom_1_1_icon) else getString(R.string.default_placeholder_icon)
    }

    private fun currentActivitySize(): DisplaySize? {
        val view = videoView
        if (view != null && view.width > 0 && view.height > 0) {
            return DisplaySize(view.width, view.height)
        }
        return activeDisplaySize
    }

    private fun resolvePhysicalSize(size: DisplaySize): AirPlayPhysicalSizeMm =
        AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = size.width,
            currentHeightPixels = size.height,
            maximumWidthPixels = maxOf(maximumDetectedWidthPixels, size.width),
            maximumHeightPixels = maxOf(maximumDetectedHeightPixels, size.height),
            referenceMillimeters = widthPhysicalMm,
            basis = physicalSizeBasis,
        )

    private fun safeAreaSummary(): String {
        if (mainDisplayDisableSafeArea) {
            return getString(R.string.safe_area_disabled_rect_fill_summary)
        }
        val size = currentActivitySize() ?: return getString(R.string.safe_area_waiting_for_activity_size)
        val mapping = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height)
        return if (mapping == null) {
            "${getString(R.string.safe_area_full_screen_at)}${size.width} x ${size.height}"
        } else {
            getString(
                R.string.safe_area_mapping_summary,
                mapping.width, mapping.height, mapping.left, mapping.top, size.width, size.height,
            )
        }
    }

    private fun updateSafeAreaSummary() {
        safeAreaSummaryView?.text = safeAreaSummary()
    }

    private fun openSafeAreaEditor() {
        val size = currentActivitySize()
        if (size == null) {
            appendLog("Safe area editor is unavailable before display layout")
            return
        }
        val editorView = safeAreaEditorView ?: return
        val initial = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height)
            ?: AirPlaySafeArea.default(size.width, size.height)
        safeAreaEditSize = size
        safeAreaEditorActive = true
        // Keep the current activity size; changing system bars here would remap the safe area.
        settingsMenu?.visibility = View.GONE
        safeAreaEditor?.visibility = View.VISIBLE
        editorView.setRect(initial, size.width, size.height)
        appendLog(
            "Safe area editor opened for ${size.width}x${size.height}; " +
                "drag the four boundaries",
        )
    }

    private fun closeSafeAreaEditor() {
        if (!safeAreaEditorActive) return
        safeAreaEditorActive = false
        safeAreaEditSize = null
        safeAreaEditor?.visibility = View.GONE
        settingsMenu?.visibility = View.VISIBLE
        updateSafeAreaSummary()
        updateResolutionMenu()
        appendLog("Safe area editor closed")
    }

    private fun saveSafeAreaEditor() {
        val size = safeAreaEditSize ?: currentActivitySize() ?: return
        val rect = safeAreaEditorView?.currentRectForSource() ?: return
        rememberSafeAreaBeforeEdit(size)
        AirPlayPersistence.saveSafeAreaRect(this, size.width, size.height, rect)
        appendLog(
            "Safe area saved for ${size.width}x${size.height}: " +
                "${rect.width}x${rect.height} at (${rect.left}, ${rect.top})",
        )
        closeSafeAreaEditor()
    }

    private fun resetSafeAreaForCurrentSize() {
        val size = currentActivitySize()
        if (size == null) {
            appendLog("Safe area reset is unavailable before display layout")
            return
        }
        rememberSafeAreaBeforeEdit(size)
        AirPlayPersistence.clearSafeAreaRect(this, size.width, size.height)
        updateSafeAreaSummary()
        updateResolutionMenu()
        appendLog("Safe area reset to full screen for ${size.width}x${size.height}")
    }

    private fun rememberSafeAreaBeforeEdit(size: DisplaySize) {
        val baseline = settingsBaseline ?: return
        if (!baseline.safeAreaRects.containsKey(size)) {
            baseline.safeAreaRects[size] = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height)
        }
    }

    private fun refreshDisplaySizeAfterLayout() {
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    private fun normalizedManufacturer(): String =
        manufacturer.trim().ifBlank { AirPlayPersistence.DEFAULT_MANUFACTURER }

    private fun normalizedModel(): String =
        model.trim().ifBlank { AirPlayPersistence.DEFAULT_MODEL }

    private fun createMediaSink(
        videoWidth: Int,
        videoHeight: Int,
        controllerGeneration: Int,
    ): AndroidMediaSink {
        // Capture this session's log: late decoder shutdown must not write into a new session.
        val diagnosticLog = sessionLog
        return AndroidMediaSink(
            surface = null,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            preferSoftwareHevcDecoder = hevcSoftwareDecoderEnabled,
            advancedAudioChannelMapping = advancedAudioChannelMapping,
            audioFocusEnabled = AirPlayPersistence.loadAudioFocusEnabled(this),
            audioFocusAutoYield = AirPlayPersistence.loadAudioFocusAutoYield(this),
            mediaChannel = AirPlayPersistence.loadMediaAudioChannel(this),
            navigationChannel = AirPlayPersistence.loadNavigationAudioChannel(this),
            // main #719：导航播报衰减（dB），会话创建时读取设置（作者：TRAE AI）
            navigationAttenuationDb = AirPlayPersistence.loadNavigationAudioAttenuationDb(this),
            context = this,
            navigationStreamType = navigationStreamType,
            onScreenStreamActiveChanged = { type, active ->
                onScreenStreamStateChanged(controllerGeneration, type, active)
            },
            // main #746：无线/有线分别维护音乐缓冲区（作者：TRAE AI）
            mediaBufferMillis = AirPlayPersistence.loadMediaBufferMillis(this, wireless = wirelessEnabled),
            onAudioDiagnostic = { message ->
                if (message.startsWith("Microphone: ")) {
                    AsyncDiagnosticLog.append(diagnosticLog, message)
                } else {
                    diagnosticLog?.append(formattedLogLine(message, System.currentTimeMillis()))
                }
            },
            onMediaAudioChanged = CarPlayMediaKeys::onMediaAudioChanged,
            callEchoCancellation = AirPlayPersistence.loadCallEchoCancellation(this),
            callVoiceFilter = AirPlayPersistence.loadCallVoiceFilter(this),
            callSpeakerphone = { AirPlayPersistence.loadCallSpeakerphone(this) },
            // Only a SurfaceView honours release timestamps; smooth video always selects one.
            videoPacingDelayMillis = if (smoothVideo) smoothVideoDelayMillis(fps) else 0,
            // main #417：主屏解码器按帧率设置申请工作速率。
            mainVideoFrameRate = fps,
            // main #496：低延迟解码（实验性设置），下次连接时生效。
            vendorLowLatencyDecoder = AirPlayPersistence.loadLowLatencyDecoder(this),
            // main #608：MTK 解码器调优（实验性设置），下次连接时生效。
            mtkDecoderTuningEnabled = AirPlayPersistence.loadMtkDecoderTuning(this),
        )
    }

    private fun createMediaEngine(sink: AndroidMediaSink): CarPlayMediaEngine =
        CarPlayMediaEngine(
            sink = sink,
            microphoneEnabled = microphoneAvailable,
            audioCaptureDirectory = audioCaptureDirectory(),
        )

    private fun createSessionListener(controllerGeneration: Int): AirPlaySessionListener =
        object : AirPlaySessionListener {
            private val diagnosticLog = sessionLog

            override fun onSessionActive(session: AirPlaySession) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    activeAirPlaySession = session
                    CarPlayBackgroundSession.active = true
                    // 波次十一批次B（main #673）：会话已建立，撤下连接失败的可操作提示。
                    showConnectionHelp(null)
                    callPopupSessionReady = true
                    updateCallPopupGuard()
                    reconnectAttempts = 0
                    logThemeState(ThemeModeDiagnostics.Source.SESSION_ACTIVE, resources.configuration)
                    syncAirPlayDarkMode(ThemeModeDiagnostics.Source.SESSION_ACTIVE)
                    if (menuOpen) return@runOnUiThread
                    appendLog("AirPlay session active")
                }
            }

            override fun onVideoFrameRendered(session: AirPlaySession) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration || activeAirPlaySession !== session || shuttingDown.get()) return@runOnUiThread
                    if (!startupRetryBudget.firstFrame(session, android.os.SystemClock.elapsedRealtime())) return@runOnUiThread
                    mainHandler.postDelayed({
                        if (controllerGeneration == restartGeneration && activeAirPlaySession === session &&
                            !shuttingDown.get() && CarPlayBackgroundSession.isOwner(this@CarPlayHostActivity) &&
                            startupRetryBudget.resetIfStable(session, android.os.SystemClock.elapsedRealtime())) {
                            appendLog("wireless startup retry budget reset after stable video session")
                        }
                    }, WirelessStartupPolicy.STABLE_SESSION_MILLIS)
                }
            }

            override fun onSessionEnded(session: AirPlaySession) {
                runOnUiThread {
                    val active = activeAirPlaySession
                    if (active != null && active !== session) {
                        // 探针或非活动握手结束时，保留当前播放会话。
                        appendLog("Non-active AirPlay connection ended; keeping the live session")
                        return@runOnUiThread
                    }
                    if (controllerGeneration != restartGeneration || startupRetryStopped) return@runOnUiThread
                    startupRetryBudget.disconnected()
                    activeAirPlaySession = null
                    CarPlayBackgroundSession.active = false
                    callPopupSessionReady = false
                    updateCallPopupGuard()
                    if (menuOpen) {
                        recoveryPendingAfterMenu = true
                        return@runOnUiThread
                    }
                    activeScreenStreamTypes.clear()
                    ClusterActivityOutput.setStreamActive(false)
                    setConnectionStage(R.string.carplay_session_ended_reconnecting)
                    appendLog("AirPlay session ended; reconnecting from scratch")
                    reconnectAfterLoss("AirPlay session ended")
                }
            }

            override fun onTransportError(message: String) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration || startupRetryStopped) return@runOnUiThread
                    callPopupSessionReady = false
                    updateCallPopupGuard()
                    startupRetryBudget.disconnected()
                    if (menuOpen) {
                        recoveryPendingAfterMenu = true
                        return@runOnUiThread
                    }
                    activeScreenStreamTypes.clear()
                    ClusterActivityOutput.setStreamActive(false)
                    setConnectionStage(R.string.transport_error_reconnecting)
                    appendLog("CarPlay transport error: $message; reconnecting from scratch")
                    reconnectAfterLoss("CarPlay transport error: $message")
                }
            }

            override fun onDebugLog(message: String) {
                val redacted = DiagnosticRedactor.redact(message) ?: return
                synchronized(usbDiagLock) {
                    usbDiagLines.addLast(redacted)
                    while (usbDiagLines.size > 12) usbDiagLines.removeFirst()
                    val snapshot = usbDiagLines.joinToString("\n")
                    usbDiagView?.post { usbDiagView?.text = snapshot }
                }
                if (message.startsWith(CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX + " ")) {
                    // Retain old-controller teardown evidence without accepting its UI/session state.
                    AsyncDiagnosticLog.append(diagnosticLog, message)
                    return
                }
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    DisplayDiagnosticSnapshot.record(this@CarPlayHostActivity, displayDiagnosticAttempt, message)
                    if (menuOpen) return@runOnUiThread
                    if (message.startsWith(PROTOCOL_TRACE_PREFIX)) {
                        appendFileLog(message)
                    } else {
                        appendLog(message)
                    }
                }
            }
        }

    private fun createStatusReporter(
        controllerGeneration: Int,
    ): (CarPlayStatus) -> Unit = report@{ status ->
        if (controllerGeneration != restartGeneration) return@report
        // main #539：失败/控制通道结束时立刻撤回 BYD 来电弹窗隐藏（作者：TRAE AI）。
        if (status is CarPlayStatus.Failed || status is CarPlayStatus.ControlEnded) {
            callPopupSessionReady = false
            updateCallPopupGuard()
        }
        if (menuOpen) {
            if (status is CarPlayStatus.Failed) failurePendingAfterMenu = status
            return@report
        }
        updateHotspotStatus(status)
        val description = status.describe()
        val englishDescription = status.describe(englishResources)
        // Once the retries stopped, a later failure of the same attempt, such as its cleanup, keeps the line saying so.
        if (!startupRetryStopped || status !is CarPlayStatus.Failed) setConnectionStage(description, englishDescription)
        // Keep the actionable tip readable while automatic retries are in progress.
        // Only a working AirPlay session, or a new failure, replaces it.
        if (status is CarPlayStatus.Failed) showConnectionHelp(status)
        when (status) {
            is CarPlayStatus.Failed -> if (status.wifiResetRequired) {
                wifiRecoveryButton?.visibility = View.VISIBLE
            } else {
                wifiRecoveryButton?.visibility = View.GONE
                if (status.startupFailure != null) {
                    if (startupFailureGeneration == controllerGeneration) return@report
                    startupFailureGeneration = controllerGeneration
                }
                reconnectAfterLoss(description, status.startupFailure, englishDescription)
            }
            else -> Unit
        }
    }

    private fun adoptBackgroundSession(): Boolean {
        val snapshot = CarPlayBackgroundSession.snapshot() ?: return false
        if (snapshot.controller.isClosed()) {
            CarPlayBackgroundSession.clear(snapshot.controller)
            return false
        }
        // The sink's pacing is fixed when it is built; a session from before a Smooth video change (for
        // example one whose reconnect stopped at a prerequisite) is stopped instead of adopted. Callers
        // retry, and the next start matches this view.
        if (!backgroundSessionMatchesView(snapshot.sink.videoPacingEnabled, smoothVideo)) {
            appendLog("Background session smooth video=${snapshot.sink.videoPacingEnabled} differs from this view; " +
                "stopping it instead of adopting it")
            CarPlayBackgroundSession.stop { mainHandler.post { if (!isDestroyed) maybeStartCarPlay() } }
            return false
        }
        displayDiagnosticAttempt = DisplayDiagnosticSnapshot.currentAttempt(this)
        adbClusterConfigured = AdbClusterRouter.enabled(this) && snapshot.controller.configuredClusterSize() ==
            (DiLink4ClusterDisplay.STREAM_WIDTH to DiLink4ClusterDisplay.STREAM_HEIGHT)
        controller = snapshot.controller
        updateClusterMapShown()
        sink = snapshot.sink
        sessionDisplay = snapshot.display
        resetSidePanel()
        if (snapshot.display.viewAreas?.let { it.kindOf(it.current) == CarPlayViewAreas.Kind.SIDE_PANEL } == true) {
            // The live stream can outlast its Activity. Restore the Android view covering its
            // unused strip instead of leaving a blank third when a new host adopts the session.
            sidePanelShown = true
            sidePanel?.visibility = View.VISIBLE
            startSidePanelTyres()
            sidePanelTick.run()
            showSidePanelGripHint()
        }
        MapMirrors.reapply()
        CarPlayBackgroundSession.store(snapshot.controller, snapshot.sink, snapshot.width, snapshot.height,
            this, snapshot.display) { completion ->
            runOnUiThread {
                shutdown(false, "DiPlay disconnect", completion)
                finish()
            }
        }
        callPopupSessionReady = CarPlayBackgroundSession.callPopupReady
        updateCallPopupGuard()
        if (snapshot.width > 0 && snapshot.height > 0) {
            activeDisplaySize = DisplaySize(snapshot.width, snapshot.height)
        }
        videoView?.let { updateVideoLayout(it.width, it.height) }
        videoView?.post {
            val view = videoView ?: return@post
            if (view.width > 0 && view.height > 0) {
                scheduleDisplaySize(view.width, view.height)
            }
        }

        val generation = restartGeneration
        snapshot.controller.attachUi(
            createSessionListener(generation),
            createStatusReporter(generation),
        )
        snapshot.sink.setScreenStreamActiveChangedListener { type, active ->
            onScreenStreamStateChanged(restartGeneration, type, active)
        }
        currentSurface?.let(::attachSurface)
        val serviceReused = snapshot.controller.hasActiveAirPlayAttachment()
        appendLog(
            if (serviceReused) {
                "Reusing existing background CarPlay service"
            } else {
                "Reusing existing background CarPlay session"
            },
        )
        setConnectionStage(
            if (serviceReused) {
                R.string.carplay_service_already_running
            } else {
                R.string.carplay_session_already_running
            },
        )
        updateDebugOverlays()
        return true
    }

    private fun startCarPlay(size: DisplaySize) {
        if (CarPlayBackgroundSession.hasSession() && !CarPlayBackgroundSession.isOwner(this)) return
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress || controller != null) return
        val controllerGeneration = restartGeneration
        // Settings → Connection can change the target while this screen stays open (#696).
        mfiTarget = AirPlayPersistence.loadMfiTarget(this)
        runCatching { DiPlayBootstrap.ensure(this, mfiTarget) }
        val config = createRuntimeConfig()
        val effectiveSize = if (isMultiWindowActive() && !AirPlayPersistence.loadAdaptPipResolution(this) &&
            maximumDetectedWidthPixels >= size.width && maximumDetectedHeightPixels >= size.height &&
            (maximumDetectedWidthPixels > size.width || maximumDetectedHeightPixels > size.height)) {
            DisplaySize(maximumDetectedWidthPixels, maximumDetectedHeightPixels)
        } else {
            size
        }
        val airPlayConfig = createAirPlayConfig(effectiveSize)
        val locationProvider: Iap2LocationProvider? =
            when {
                !config.locationReportingEnabled -> null
                config.identification.vehicleSpeedEnabled -> VehicleSpeedLocationProvider(
                    AndroidCarPlayLocationProvider(this),
                    com.shilapi.xcertplay.hud.BydNavigationOutputs.wheelSpeed(applicationContext),
                )
                else -> AndroidCarPlayLocationProvider(this)
            }
        appendLog(
            "Starting CarPlay controller at ${size.width}x${size.height} -> " +
                "${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "(${displayScalePercent}%) " +
                "physical=${airPlayConfig.main.widthPhysicalMm}x" +
                "${airPlayConfig.main.heightPhysicalMm}mm " +
                "video=${if (airPlayConfig.hevc) "HEVC" else "H.264"} " +
                "decoder=${if (airPlayConfig.hevc && hevcSoftwareDecoderEnabled) "software" else "hardware"} " +
                "microphone=${airPlayConfig.microphone} " +
                "location=${if (config.locationReportingEnabled) "enabled" else "disabled"}" +
                "${if (config.identification.vehicleSpeedEnabled) "+wheel-speed" else ""} " +
                "mfi=${mfiTargetLabel(config.mfiTarget)}" +
                if (config.transport == CarPlayTransport.WIRED) " usbCharging=${config.availableCurrentMilliAmps}mA" else "",
        )
        Log.i(
            TAG,
            "starting controller display=${size.width}x${size.height} " +
                "negotiated=${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "scale=${displayScalePercent}% " +
                "hevc=${airPlayConfig.hevc} " +
                "softwareHevc=${airPlayConfig.hevc && hevcSoftwareDecoderEnabled} " +
                "microphone=${airPlayConfig.microphone} " +
                "location=${config.locationReportingEnabled} " +
                "mfi=${config.mfiTarget}",
        )
        val renderer = createMediaSink(
            videoWidth = airPlayConfig.main.widthPixels,
            videoHeight = airPlayConfig.main.heightPixels,
            controllerGeneration = controllerGeneration,
        )
        sink = renderer
        currentSurface?.let(::attachSurface)
        clusterSurface?.let { renderer.setSurface(SCREEN_TYPE_ALT, it) }
        MapMirrors.reapply()
        val media = createMediaEngine(renderer)
        val pairings = AirPlayPersistence.loadPairings(this) { id, key ->
            AirPlayPersistence.savePairing(this, id, key)
        }
        val next = CarPlayController(
            context = this,
            config = config,
            airPlayConfig = airPlayConfig,
            identity = airPlayIdentity,
            pairings = pairings,
            listener = createSessionListener(controllerGeneration),
            media = media,
            reportStatus = createStatusReporter(controllerGeneration),
            loadPairRecord = { AirPlayPersistence.loadLockdownRecord(this) },
            savePairRecord = { record -> AirPlayPersistence.saveLockdownRecord(this, record) },
            clearPairRecord = { AirPlayPersistence.clearLockdownRecord(this) },
            // 注入跨启动稳定的主机身份：与完整配对记录解耦，即使记录丢失，iPhone 也不会把本机
            // 识别为「新电脑」而反复要求信任（C140A 真机曾出现每次连接都需重新信任）。
            provideHostId = { AirPlayPersistence.getStableHostId(this) },
            provideSystemBuid = { AirPlayPersistence.getStableSystemBuid(this) },
            locationProvider = locationProvider,
            vehicleStatusProvider = if (com.shilapi.xcertplay.hud.BydOutputSettings.batteryToIphoneActive(this)) {
                com.shilapi.xcertplay.hud.BydNavigationOutputs.batteryStatus(applicationContext)
            } else {
                null
            },
        )
        controller = next
        resetSidePanel() // a new session starts without the side panel
        updateClusterMapShown()
        CarPlayMediaKeys.attach(this, next)
        if (airPlayConfig.videoInCar) CarPlayVideo.attach(this, next)
        val display = CarPlaySessionDisplay(
            airPlayConfig.main.widthPixels, airPlayConfig.main.heightPixels,
            displayRotation(), hideTopBar, hideBottomBar, effectiveSize.width, effectiveSize.height,
            viewAreas = pendingViewAreas,
            statusBarShown = statusBarShown(),
        )
        sessionDisplay = display
        videoView?.let { updateVideoLayout(it.width, it.height) }
        CarPlayBackgroundSession.store(next, renderer, size.width, size.height, this, display) { completion ->
            runOnUiThread {
                shutdown(terminateProcess = false, reason = "DiPlay disconnect", completion = completion)
                finish()
            }
        }
        try {
            // Context.startForegroundService is API 26+; Jelly Bean starts the same service plainly.
            val serviceIntent = Intent(this, DiPlaySessionService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
            next.start()
        } catch (error: RuntimeException) {
            appendLog("Connection could not start: ${error.javaClass.simpleName}")
            shutdown(false, "foreground service could not start")
            setConnectionStage(R.string.could_not_start_carplay_return_to_diplay_and_check_app_per)
        }
    }

    private fun refreshConfiguration(
        newConfig: Configuration = resources.configuration,
        source: ThemeModeDiagnostics.Source,
    ) {
        if (lastConfiguration == newConfig) {
            logThemeState(source, newConfig)
            return
        }
        // resources.configuration is mutated in place, so keep a copy to compare against.
        lastConfiguration = Configuration(newConfig)
        nightModeDiagnosticSource = source
        try {
            nightModeOrNull(newConfig.uiMode)?.let { nightModeController.systemChanged(it) }
        } finally {
            nightModeDiagnosticSource = ThemeModeDiagnostics.Source.CARPLAY_MODE
        }
        logThemeState(source, newConfig)
    }

    private fun logThemeState(source: ThemeModeDiagnostics.Source, configuration: Configuration) {
        themeDiagnostics.observe(
            source, configuration.uiMode, darkMode, activeAirPlaySession != null, SystemClock.elapsedRealtime(),
        )?.let(::appendLog)
    }

    private fun syncAirPlayDarkMode(source: ThemeModeDiagnostics.Source) {
        val session = activeAirPlaySession
        val night = darkMode
        if (session == null) {
            appendLog("THEME_DIAGNOSTIC request source=${source.label} applied=${if (night) "dark" else "light"} sessionActive=false")
            return
        }
        airPlayCommandExecutor.execute {
            try {
                val sent = session.setNightMode(night)
                // A successful write does not prove the iPhone changed its appearance.
                appendLog(
                    "THEME_DIAGNOSTIC send source=${source.label} applied=${if (night) "dark" else "light"} commandWritten=$sent",
                )
                Log.i(
                    TAG,
                    "AirPlay dark mode=${if (night) "dark" else "light"} eventChannelReady=$sent",
                )
            } catch (error: Throwable) {
                val failureClass = error.javaClass.simpleName.take(64)
                    .filter { it.isLetterOrDigit() || it == '_' || it == '$' }
                    .ifEmpty { "unknown" }
                appendLog("THEME_DIAGNOSTIC send source=${source.label} failureClass=$failureClass")
                Log.w(TAG, "Could not send AirPlay dark mode update", error)
            }
        }
    }

    private fun audioCaptureDirectory(): File? {
        if (!File(filesDir, AUDIO_CAPTURE_MARKER).isFile) return null
        return File(filesDir, AUDIO_CAPTURE_DIRECTORY)
    }

    private fun scheduleDisplaySize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || shuttingDown.get()) return
        val size = DisplaySize(width, height)
        if (size == pendingDisplaySize) return
        mainHandler.removeCallbacks(applyDisplaySize)
        if (size == activeDisplaySize && !displayLayoutChanged()) {
            pendingDisplaySize = null
            // A rotation back can cancel the last pending resize after teardown has finished.
            maybeStartCarPlay()
            return
        }
        pendingDisplaySize = size
        // With a turning screen's square canvas nothing reconnects, so the turn need not wait for the size to settle.
        mainHandler.postDelayed(applyDisplaySize,
            if (sessionDisplay?.viewAreas?.turnsWithScreen == true) 0L else DISPLAY_CHANGE_DEBOUNCE_MILLIS)
    }

    private fun isMultiWindowActive(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode
    }


    /** 波次十一批次C（main #643）：当前状态栏是否可见，未知返回 null。 */
    private fun statusBarShown(): Boolean? =
        ViewCompat.getRootWindowInsets(window.decorView)?.isVisible(WindowInsetsCompat.Type.statusBars())

    private fun applyDisplaySize(size: DisplaySize) {
        val display = sessionDisplay
        if (display != null && applyViewArea(size, display)) return
        val layoutChanged = displayLayoutChanged(size)
        if (shuttingDown.get()) return
        if (size == activeDisplaySize && !layoutChanged) {
            // Teardown may have removed the session that made this same-size rotation pending.
            maybeStartCarPlay()
            return
        }
        val previous = activeDisplaySize
        activeDisplaySize = size
        recordDetectedMaximum(size)
        updateResolutionMenu()
        if (previous == null) {
            appendLog("Display detected: ${size.width}x${size.height}")
            maybeStartCarPlay()
        } else if (menuOpen || handshakeResetInProgress) {
            appendLog(
                "Display updated while handshake is reset: " +
                    "${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        } else if (controller == null && display == null) {
            appendLog(
                "Display updated before CarPlay startup: " +
                    "${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
            maybeStartCarPlay()
        } else if (display != null && !layoutChanged &&
            statusBarHiddenByAnotherWindow(display, size.width, size.height, statusBarShown())
        ) {
            // 波次十一批次C（main #643）：另一个窗口（倒车摄像头）隐藏了状态栏，窗口只是临时增高了一个
            // 状态栏的高度；保持原会话画布，避免返回状态栏后新建会话一直缩小显示。
            val message = "Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height} " +
                "while another window hides the status bar; keeping CarPlay session canvas=${display.width}x${display.height}"
            appendLog(message)
            Log.i(TAG, message)
            videoView?.let { updateVideoLayout(it.width, it.height) }
        } else if (display != null && !layoutChanged &&
            size.width <= display.windowWidth && size.height <= display.windowHeight) {
            // Keep camera shrink/restore cycles within the original window connected. If the
            // session started in a camera window, growth beyond it needs a full-size canvas.
            val message = "Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height}; " +
                "keeping CarPlay session canvas=${display.width}x${display.height}"
            appendLog(message)
            Log.i(TAG, message)
            videoView?.let { updateVideoLayout(it.width, it.height) }
        } else {
            restartCarPlay(
                "Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        }
    }

    /**
     * Entering or leaving the head unit's split screen, or turning the screen with a square canvas, moves
     * CarPlay to the matching view area instead of reconnecting. The split window is remembered per screen
     * orientation for the next session's area. False when the change is something else (a camera window,
     * a turn without a square canvas), which the usual path handles.
     */
    private fun applyViewArea(size: DisplaySize, display: CarPlaySessionDisplay): Boolean {
        val areas = display.viewAreas ?: return false
        if (controller == null || menuOpen || handshakeResetInProgress || shuttingDown.get()) return false
        val split = isMultiWindowActive()
        // A full window's own shape tells the screen's orientation; during a turn the display metrics and
        // rotation can lag behind the window. A split-screen window can have either shape, so it asks the
        // display, which is settled then.
        val portrait = if (split) screenPortrait() else size.height > size.width
        val previous = activeDisplaySize
        if (areas.turnsWithScreen && display.rotation != displayRotation() && size == previous) {
            // The turn arrived before the window's new size: note it and wait for the size, which picks
            // the area, instead of letting the old rotation look like a changed layout.
            sessionDisplay = display.copy(rotation = displayRotation())
            Log.i(TAG, "Screen turned before the window resized (${size.width}x${size.height}); waiting for its size")
            return true
        }
        val turned = previous != null && !split && (previous.height > previous.width) != portrait
        if (turned && !areas.turnsWithScreen) return false
        if (split) {
            // Against the whole screen, which the system bars (shown in BYD's split screen) do not change.
            val screen = android.util.DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
            val screenLong = maxOf(screen.widthPixels, screen.heightPixels).toFloat()
            val screenShort = minOf(screen.widthPixels, screen.heightPixels).toFloat()
            if (screenLong > 0 && screenShort > 0) {
                if (portrait) SplitScreenSettings.saveWindow(this, true, size.width / screenShort, size.height / screenLong)
                else SplitScreenSettings.saveWindow(this, false, size.width / screenLong, size.height / screenShort)
            }
        }
        val target = areas.indexFor(size.width, size.height, split, portrait) ?: return false
        if (sidePanelShown) resetSidePanel() // a turn or the split screen ends the side panel
        // The same whole-screen area without a turn (a camera window, say) keeps the usual handling.
        if (!turned && target == areas.current && areas.kindOf(target) == CarPlayViewAreas.Kind.FULL_SCREEN) return false
        activeDisplaySize = size
        sessionDisplay = display.copy(rotation = displayRotation())
        val sent = if (target == areas.current) true else controller?.showViewArea(target) == true
        if (sent) areas.use(target)
        videoView?.let { updateVideoLayout(it.width, it.height) }
        val message = "Window ${previous?.width}x${previous?.height} -> ${size.width}x${size.height} " +
            "split=$split portrait=$portrait turned=$turned: view area $target sent=$sent"
        appendLog(message)
        Log.i(TAG, message)
        return true
    }

    /** Whether the screen itself is portrait now (DiPlay's own window may be a split-screen part of it). */
    @Suppress("DEPRECATION")
    private fun screenPortrait(): Boolean {
        val metrics = android.util.DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics.heightPixels > metrics.widthPixels
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = videoView?.display?.rotation ?: windowManager.defaultDisplay.rotation

    private fun displayLayoutChanged(newSize: DisplaySize? = activeDisplaySize): Boolean {
        val display = sessionDisplay ?: return false
        if (display.rotation != displayRotation()) return true
        if (display.hideTopBar != hideTopBar || display.hideBottomBar != hideBottomBar) return true
        if (newSize != null && newSize.width > 0 && newSize.height > 0 &&
            display.windowWidth > 0 && display.windowHeight > 0) {
            // main #501：协商画布可能是正方形以支持屏幕旋转；应比较实际启动窗口，
            // 否则每次回到横屏窗口都会被误判为画中画。
            val baseAspect = display.windowWidth.toDouble() / display.windowHeight
            val currentAspect = newSize.width.toDouble() / newSize.height
            val aspectDiff = kotlin.math.abs(currentAspect / baseAspect - 1.0)
            if (aspectDiff > 0.08 && AirPlayPersistence.loadAdaptPipResolution(this)) {
                return true
            }
        }
        return false
    }



    private fun contentRect(viewWidth: Int, viewHeight: Int): CarPlayVideoLayout {
        val display = sessionDisplay ?: return CarPlayVideoLayout(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        // In the head unit's split screen, or on a turning screen's square canvas, CarPlay draws in part of
        // the canvas; that area fills the window and the rest falls outside it. Video and touches both
        // follow this. An area of the whole canvas keeps the usual fit.
        display.viewAreas?.let { areas ->
            val area = areas.layoutArea(areas.current)
            if (area.width != display.width || area.height != display.height) {
                val scaleX = viewWidth.toFloat() / area.width
                val scaleY = viewHeight.toFloat() / area.height
                return CarPlayVideoLayout(-area.originX * scaleX, -area.originY * scaleY,
                    display.width * scaleX, display.height * scaleY)
            }
        }
        return CarPlayVideoLayout.fit(display.width, display.height, viewWidth, viewHeight)
    }

    private fun updateVideoLayout(viewWidth: Int, viewHeight: Int) {
        val view = videoView ?: return
        if (viewWidth <= 0 || viewHeight <= 0) return
        val content = contentRect(viewWidth, viewHeight)
        val fallback = fallbackVideoView
        if (fallback != null) {
            val bounds = CarPlaySurfaceBounds.from(content)
            if (fallbackVideoBounds != bounds) {
                fallbackVideoBounds = bounds
                fallback.layoutParams = FrameLayout.LayoutParams(bounds.width, bounds.height,
                    Gravity.TOP or Gravity.LEFT).apply {
                    leftMargin = bounds.left
                    topMargin = bounds.top
                }
            }
        } else {
            (view as? TextureView)?.setTransform(Matrix().apply {
                setScale(content.width / viewWidth, content.height / viewHeight)
                postTranslate(content.left, content.top)
            })
        }
        if (sidePanelShown) placeSidePanel(viewWidth, viewHeight)
        placeSidePanelTab(viewWidth, viewHeight)
    }

    private fun recordDetectedMaximum(size: DisplaySize) {
        val width = maxOf(maximumDetectedWidthPixels, size.width)
        val height = maxOf(maximumDetectedHeightPixels, size.height)
        if (width == maximumDetectedWidthPixels && height == maximumDetectedHeightPixels) return
        maximumDetectedWidthPixels = width
        maximumDetectedHeightPixels = height
        AirPlayPersistence.saveMaximumDetectedDisplay(this, width, height)
    }

    private fun maybeStartCarPlay() {
        if (shuttingDown.get()) return
        if (CarPlayBackgroundSession.hasSession() && !CarPlayBackgroundSession.isOwner(this)) {
            if (!adoptBackgroundSession()) mainHandler.postDelayed({ maybeStartCarPlay() }, 500)
            return
        }
        if (controller == null && adoptBackgroundSession()) return
        // The video view is chosen once per activity; a changed Smooth video or Direct video output
        // setting needs a new one.（平滑视频或直接视频输出变更时需要重建视频视图）
        if (controller == null && (AirPlayPersistence.loadSmoothVideo(this) != smoothVideo ||
                AirPlayPersistence.loadDirectVideoOutput(this) != directVideoOutput)) {
            appendLog("Video output setting changed; rebuilding the video view")
            // No session runs here, but a restart keeps this host as the session owner; the new instance
            // must be able to start its own.
            if (CarPlayBackgroundSession.isOwner(this)) CarPlayBackgroundSession.clear()
            recreate()
            return
        }
        val size = activeDisplaySize ?: return
        val transportReady = if (wirelessEnabled) wirelessPermissionsReady else vpnReady
        val locationReady = !locationReportingEnabled || locationPermissionAvailable
        if (
            !transportReady ||
            !locationReady ||
            !microphonePermissionResolved ||
            shuttingDown.get() ||
            menuOpen ||
            handshakeResetInProgress ||
            pendingDisplaySize != null ||
            controller != null
        ) {
            return
        }
        // main #663：首个会话就要带上集群流——晚到会让集群路由把 CarPlay 重连一次，故暂扣等待。
        if (clusterLaunchGate.holdsCarPlayStart(SystemClock.uptimeMillis(), ClusterActivityOutput.routeSettling())) {
            carPlayStartHeldForCluster = true
            scheduleClusterLaunchTick()
            return
        }
        startCarPlay(size)
    }

    private fun reconnectAfterLoss(
        reason: String,
        startupFailure: WirelessStartupFailure? = null,
        englishReason: String = reason,
    ) {
        if (!CarPlayBackgroundSession.isOwner(this)) return
        if (menuOpen) recoveryPendingAfterMenu = true
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress || startupRetryStopped) return
        if (reconnectScheduled) return
        // main #590：不可重试的失败（HOTSPOT_CONFIGURATION / BLUETOOTH_UNUSABLE）不再进入重试预算
        val startupDelay = if (startupFailure != null && startupFailure.retryable)
            startupRetryBudget.nextDelayMillis() else null
        if (startupFailure != null && startupDelay == null) {
            startupRetryStopped = true
            startupRetryButton?.visibility = View.VISIBLE
            if (startupFailure == WirelessStartupFailure.HOTSPOT_CONFIGURATION) {
                // Its cause is in English, for the report; the help line below says what to check.
                setConnectionStage("${getString(R.string.hotspot_network_not_ready)}\n" +
                    getString(R.string.wireless_startup_retries_exhausted))
            } else if (!startupFailure.retryable) {
                setConnectionStage(reason, englishReason)
            } else {
                setConnectionStage("$reason\n${getString(R.string.wireless_startup_retries_exhausted)}",
                    "$englishReason\n${englishResources.getString(R.string.wireless_startup_retries_exhausted)}")
            }
            appendLog("wireless startup recovery stopped generation=$restartGeneration reason=$startupFailure retries=${startupRetryBudget.retries}")
            return
        }
        reconnectScheduled = true
        callPopupSessionReady = false
        updateCallPopupGuard()
        val generation = restartGeneration
        val delayMillis = if (startupDelay != null) {
            startupDelay
        } else if (reason.contains("AirPlay iAP tunnel", ignoreCase = true)) {
            IAP_TUNNEL_RECONNECT_DELAY_MILLIS
        } else {
            (RECONNECT_DELAY_MILLIS * (1L shl reconnectAttempts.coerceAtMost(4))).coerceAtMost(30_000L)
        }
        reconnectAttempts += 1
        appendLog("$reason; retrying in ${delayMillis}ms generation=$generation startupFailure=$startupFailure startupRetries=${startupRetryBudget.retries}")
        mainHandler.postDelayed(
            {
                reconnectScheduled = false
                if (menuOpen && generation == restartGeneration) recoveryPendingAfterMenu = true
                if (
                    shuttingDown.get() ||
                    menuOpen ||
                    handshakeResetInProgress || startupRetryStopped ||
                    generation != restartGeneration
                ) {
                    return@postDelayed
                }
                restartCarPlay("Reconnecting after $reason")
            },
            delayMillis,
        )
    }

    /** Full-stack fallback when an AirPlay-only reconnect is unavailable. */
    private fun restartCarPlay(reason: String) {
        if (!CarPlayBackgroundSession.isOwner(this)) return
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress) return
        val size = activeDisplaySize ?: return
        startupRetryBudget.disconnected()
        startupRetryButton?.visibility = View.GONE
        appendLog(reason)
        activeScreenStreamTypes.clear()
        ClusterActivityOutput.setStreamActive(false)
        setConnectionStage(reason)
        Log.i(TAG, "$reason; rebuilding stack at ${size.width}x${size.height}")
        val generation = ++restartGeneration
        handshakeResetInProgress = true
        callPopupSessionReady = false
        updateCallPopupGuard()
        val oldController = controller
        val oldSink = sink
        CarPlayMediaKeys.detach(oldController)
        CarPlayBackgroundSession.clear(oldController, keepOwner = true)
        controller = null
        oldSink?.let(retiringSinks::add)
        sink = null
        sessionDisplay = null
        discardLastPicture()
        val diagnosticLog = sessionLog
        teardownExecutor.execute {
            val started = System.nanoTime()
            oldController?.close()
            val completed = oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS) ?: true
            AsyncDiagnosticLog.append(
                diagnosticLog,
                "${CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX} generation=$generation " +
                    "restart teardownWaitCompleted=$completed " +
                    "elapsedMs=${((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0)}",
            )
            oldSink?.let(::closeRetiringSink)
            runOnUiThread {
                if (!shuttingDown.get() && generation == restartGeneration) {
                    handshakeResetInProgress = false
                    // The display can rotate again while the old stack is closing. Use the
                    // latest accepted size, and wait for any pending resize to settle first.
                    maybeStartCarPlay()
                }
            }
        }
    }

    private fun showDiPlayHome(page: String = "home") {
        controller?.sendTouch(emptyList())
        OwnScreenStart.start(this, Intent(this, DiPlayActivity::class.java)
            .putExtra("page", page).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), ::appendLog)
    }

    /** Keep a timed-out sink visible to surface teardown until its codecs have actually been released. */
    private fun closeRetiringSink(owner: AndroidMediaSink) {
        owner.close()
        val owners = retiringSinks
        owner.whenVideoReleased { owners.remove(owner) }
        // Preserve bounded teardown sequencing without blocking the UI or forgetting a live worker.
        owner.awaitVideoReleased(sinkReleaseWaitMillis)
    }

    private fun openSettingsMenu() {
        cancelTemporarySystemBars()
        if (menuOpen) return
        controller?.sendTouch(emptyList())
        loadPersistedSettings()
        settingsBaseline = captureSettingsBaseline()
        menuSettingsSignature = menuSettingsSignature()
        // Rebuild controls from saved values so a cancelled edit cannot reappear on reopening.
        settingsMenu?.let { previous ->
            val parent = previous.parent as ViewGroup
            val index = parent.indexOfChild(previous)
            parent.removeView(previous)
            settingsMenu = buildSettingsMenu()
            parent.addView(settingsMenu, index, FrameLayout.LayoutParams(-1, -1))
        }
        menuOpen = true
        updateCallPopupGuard()
        gestureOverlay?.visibility = View.GONE
        settingsMenu?.visibility = View.VISIBLE
        safeAreaEditor?.visibility = View.GONE
        updateSafeAreaSummary()
        updateResolutionMenu()
        updateDebugOverlays()
    }

    private fun openPicturePanel() {
        closePicturePanel()
        val generation = picturePanelGeneration
        val root = videoView?.parent as? FrameLayout ?: return
        controller?.sendTouch(emptyList())
        root.post {
            if (generation != picturePanelGeneration || isFinishing || isDestroyed) return@post
            val panel = CarPlayPicturePanel(
                this,
                adjustmentsAvailable = fallbackVideoView == null,
                initialPalette = overlayPalette,
                close = ::closePicturePanel,
            )
            val availableWidth = (root.width - dp(24)).coerceAtLeast(1)
            val width = minOf(dp(420), if (root.width < dp(800)) availableWidth else (root.width * 0.42f).toInt())
            val height = minOf(dp(540), root.height - dp(24)).coerceAtLeast(1)
            root.addView(panel, FrameLayout.LayoutParams(width, height, Gravity.END or Gravity.TOP).apply {
                topMargin = dp(12); marginEnd = dp(12)
            })
            picturePanel = panel
        }
    }

    private fun closePicturePanel() {
        ++picturePanelGeneration
        picturePanel?.let { (it.parent as? ViewGroup)?.removeView(it) }
        picturePanel = null
        CarPlayPicture.showOriginal(false)
    }

    private fun saveSettingsAndReconnect() {
        if (!menuOpen) return
        if (!validateMfiSettings()) return
        if (!validateManualHotspotSettings()) return
        persistMenuSettings()
        settingsBaseline = null
        finishSettingsMenu("Settings saved", reconnect = true)
    }

    private fun cancelSettingsEdits() {
        if (!menuOpen) return
        restoreSettingsBaseline()
        finishSettingsMenu("Settings changes discarded", reconnect = false)
    }

    /**
     * main #500：不主动声明丢弃的出口——返回手势与跳转完整设置页的链接。关闭控件的文案本身
     * 已说明会丢弃，USB 插入也不算司机离开，因此这两处仍直接调用 [cancelSettingsEdits] 不弹询问。
     * [onLeft] 只在菜单真正关闭后运行，避免有导航发生在对话框背后（作者：TRAE AI）。
     */
    private fun leaveSettingsMenu(onLeft: () -> Unit = {}) {
        if (!menuOpen) return
        if (menuSettingsSignature?.let { it != menuSettingsSignature() } == true) {
            // 对话框明暗跟随菜单外观，与 DiPlay 其它对话框一致。
            AlertDialog.Builder(this, if (appNight) R.style.Theme_Xcertplay_Dialog_Dark else R.style.Theme_Xcertplay_Dialog_Light)
                .setTitle(getString(R.string.settings_discard_pending_title))
                .setMessage(getString(R.string.settings_discard_pending_message))
                .setPositiveButton(getString(R.string.save_and_reconnect)) { _, _ -> saveSettingsAndReconnect() }
                .setNegativeButton(getString(R.string.settings_discard_pending_confirm)) { _, _ ->
                    cancelSettingsEdits()
                    onLeft()
                }
                .show()
            return
        }
        cancelSettingsEdits()
        onLeft()
    }

    private fun finishSettingsMenu(prefix: String, reconnect: Boolean) {
        if (!menuOpen) return
        menuOpen = false
        updateCallPopupGuard()
        menuSettingsSignature = null
        settingsMenu?.visibility = View.GONE
        gestureOverlay?.visibility = View.VISIBLE
        settingsGestureHint?.text = settingsGestureHintText()
        updateDebugOverlays()
        logLines.clear()
        appendLog(
            "$prefix; resolution " +
                "${displayScalePercent}% with " +
                (if (sessionHevc) "HEVC (H.265)" else "H.264") +
                ", MFI ${mfiTargetLabel(mfiTarget)}" +
                ", Wi-Fi session ${hotspotModeLabel(wirelessHotspotMode)}",
        )
        val failure = failurePendingAfterMenu
        failurePendingAfterMenu = null
        val recoveryPending = recoveryPendingAfterMenu || failure != null
        recoveryPendingAfterMenu = false
        if (reconnect) {
            startupRetryBudget.manualRetry()
            startupRetryStopped = false
        }
        if (failure?.startupFailure != null && !reconnect) {
            createStatusReporter(restartGeneration)(failure)
        } else if (failure?.wifiResetRequired == true && (!reconnect || wirelessEnabled)) {
            createStatusReporter(restartGeneration)(failure)
        } else if (handshakeResetInProgress) {
            startAfterHandshakeReset = true
        } else if ((reconnect || recoveryPending) && controller != null) {
            restartCarPlay(if (reconnect) "Settings saved; reconnecting" else "Connection lost while settings were open; reconnecting")
        } else {
            maybeStartCarPlay()
        }
    }

    private fun exitApplication() {
        if (shuttingDown.get()) return
        restoreSettingsBaseline()
        // API17 适配：Activity.finishAndRemoveTask 是 API 21+，dalvik 在 API 17 上把它替换成抛
        // NoSuchMethodError 的桩。低版本退回 finish()（随后 shutdown 会终止进程，效果等价）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) finishAndRemoveTask() else finish()
        shutdown(terminateProcess = true, reason = "settings exit application")
    }

    private fun shutdown(terminateProcess: Boolean, reason: String, completion: () -> Unit = {}) {
        if (!shuttingDown.compareAndSet(false, true)) { completion(); return }
        callPopupSessionReady = false
        updateCallPopupGuard()
        resetSidePanel()
        startupRetryBudget.disconnected()
        restartGeneration += 1
        mainHandler.removeCallbacks(applyDisplaySize)
        val oldController = controller
        val oldSink = sink
        CarPlayMediaKeys.detach(oldController)
        CarPlayBackgroundSession.clear(oldController)
        controller = null
        oldSink?.let(retiringSinks::add)
        sink = null
        sessionDisplay = null
        discardLastPicture()
        Log.i(TAG, "shutdown reason=$reason terminateProcess=$terminateProcess")
        teardownExecutor.execute {
            oldController?.close()
            val clean = oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS) ?: true
            oldSink?.let(::closeRetiringSink)
            airPlayCommandExecutor.shutdown()
            if (terminateProcess) {
                applicationContext.stopService(Intent(applicationContext, CarPlayVpnService::class.java))
            }
            Log.i(TAG, "shutdown complete clean=$clean")
            applicationContext.stopService(Intent(applicationContext, DiPlaySessionService::class.java))
            teardownExecutor.shutdown()
            mainHandler.post { completion() }
            if (terminateProcess) Process.killProcess(Process.myPid())
        }
    }

    private fun observeVideoWindow(texture: TextureView) {
        val probe = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                // isAttachedToWindow 是 API 19+，API 17 设备直接调用会抛 NoSuchMethodError，走 UiCompat 兜底
                if (!UiCompat.isAttachedToWindow(texture)) return true
                removeVideoSurfaceProbe()
                if (isDestroyed || videoView !== texture) return true
                val unsafeDecoder = textureUnsafeVideoDecoder()
                val mode = carPlayVideoSurfaceMode(
                    texture.isHardwareAccelerated, smoothVideo, directVideoOutput, textureUnsafeDecoder = unsafeDecoder != null)
                appendLog("Video output mode=$mode windowHardwareAccelerated=${texture.isHardwareAccelerated} " +
                    "smoothVideo=$smoothVideo directVideoOutput=$directVideoOutput textureUnsafeDecoder=${unsafeDecoder ?: "none"}")
                if (mode == CarPlayVideoSurfaceMode.TEXTURE) return true
                useFallbackVideoSurface(texture, unsafeDecoder)
                return false // Measure the replacement before drawing the software window.
            }
        }
        videoSurfaceProbe = probe
        texture.viewTreeObserver.addOnPreDrawListener(probe)
    }

    private fun removeVideoSurfaceProbe() {
        val probe = videoSurfaceProbe ?: return
        videoView?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(probe)
        videoSurfaceProbe = null
    }

    /** The default H.264 decoder, and the HEVC one when HEVC is on, if its pictures cannot be textures. */
    private fun textureUnsafeVideoDecoder(): String? = try {
        // CodecCompat 而非直调 MediaCodecList：后者及其 codecInfos 都是 API 21+，
        // API 17 车机上会抛 NoSuchMethodError（Error 家族，下面的 catch 才有意义）。
        val decoders = CodecCompat.all()
            .filter { !it.isEncoder }
            .map { VideoDecoderInfo(it.name, it.supportedTypes.toList()) }
        val mimes = listOfNotNull(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC.takeIf { hevcEnabled })
        textureUnsafeDecoder(decoders, mimes)
    } catch (error: Throwable) {
        // 这是"要么用 TextureView、要么换 SurfaceView"的探测：任何失败都应退回旧的
        // TextureView 行为，绝不能把 NoSuchMethodError 这类 LinkageError 漏出去杀掉进程。
        appendLog("Video decoder list unavailable: ${error.message}")
        null
    }

    private fun useFallbackVideoSurface(texture: TextureView, unsafeDecoder: String?) {
        val root = texture.parent as? FrameLayout ?: return
        val index = root.indexOfChild(texture)
        val viewport = FrameLayout(this).apply { clipChildren = true }
        val surfaceView = SurfaceView(this)
        pictureBinding?.close()
        pictureBinding = null
        // Detach our wrapper before removing its TextureView; a late destruction callback
        // must not clear the framework-owned replacement surface.
        videoSurfaceOwner.clear()
        currentSurfaceTexture = null
        texture.surfaceTextureListener = null
        root.removeView(texture)
        videoView = viewport
        fallbackVideoView = surfaceView
        surfaceView.holder.addCallback(fallbackSurfaceCallback)
        viewport.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        viewport.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val width = right - left
            val height = bottom - top
            updateVideoLayout(width, height)
            if (width != oldRight - oldLeft || height != oldBottom - oldTop) {
                scheduleDisplaySize(width, height)
            }
        }
        root.addView(viewport, index, texture.layoutParams)
        appendLog(if (smoothVideo) {
            "Using SurfaceView video output: smooth video, frames shown at the iPhone's frame time + a delay " +
                "starting at ${smoothVideoDelayMillis(fps)} ms; picture adjustments unavailable"
        } else if (directVideoOutput) {
            "Using SurfaceView video output: direct video output, frames shown as soon as they are decoded; " +
                "picture adjustments unavailable"
        } else if (unsafeDecoder != null) {
            "Using SurfaceView video output: $unsafeDecoder pictures cannot be shown as a texture; " +
                "picture adjustments unavailable"
        } else {
            "Using SurfaceView video output: window has no hardware acceleration; picture adjustments unavailable"
        })
    }

    /** A dimension of the platform's own resources in pixels, or 0 when this build has none by [name]. */
    private fun systemDimension(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id != 0) runCatching { resources.getDimensionPixelSize(id) }.getOrDefault(0) else 0
    }

    // 波次十一批次D（main #669）：把 SurfaceView 的当前画面（半尺寸）在其 surface 消失前拷下，短暂等待。
    // 🔴 PixelCopy 是 API 24 新增；API 17 直接调用会 NoSuchMethodError，故必须守卫：低版本不做最后一帧。
    private fun captureLastPicture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val surfaceView = fallbackVideoView ?: return
        val bounds = fallbackVideoBounds ?: return
        if (!surfaceView.holder.surface.isValid) return
        val picture = Bitmap.createBitmap(
            (bounds.width / 2).coerceAtLeast(1), (bounds.height / 2).coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val done = java.util.concurrent.CountDownLatch(1)
        val copied = java.util.concurrent.atomic.AtomicBoolean(false)
        val requested = runCatching {
            android.view.PixelCopy.request(surfaceView, picture, { result ->
                copied.set(result == android.view.PixelCopy.SUCCESS)
                done.countDown()
            }, Handler(pictureCopyThread.looper))
        }.isSuccess
        if (requested) done.await(PICTURE_COPY_WAIT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
        // 不手动 recycle：最后显示的那张图在 DiPlay 再次离开时可能仍在屏上。
        if (copied.get()) lastPicture = picture
        appendLog("Last picture kept=${copied.get()}")
    }

    private fun showLastPicture() {
        if (sink == null) return
        val picture = lastPicture ?: return
        val surfaceView = fallbackVideoView ?: return
        val viewport = surfaceView.parent as? FrameLayout ?: return
        val hold = pictureHold?.takeIf { it.parent === viewport } ?: ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            isClickable = false
            isFocusable = false
        }.also {
            pictureHold?.let { old -> (old.parent as? ViewGroup)?.removeView(old) }
            pictureHold = it
            viewport.addView(it)
        }
        // API17 适配：同型拷贝构造比 API 17 新，走 UiCompat。
        (surfaceView.layoutParams as? FrameLayout.LayoutParams)?.let { hold.layoutParams = UiCompat.copyFrameLayoutParams(it) }
        hold.animate().cancel()
        hold.setImageBitmap(picture)
        hold.alpha = 1f
        hold.visibility = View.VISIBLE
        pictureHoldBaseline = sink?.liveVideoCounters()?.rendered ?: -1L
        pictureHoldStartMillis = SystemClock.uptimeMillis()
        mainHandler.removeCallbacks(pictureHoldTick)
        mainHandler.postDelayed(pictureHoldTick, 50L)
        appendLog("Last picture shown until a new frame")
    }

    // 随会话忘掉最后一帧：停止显示、停止请求。
    private fun discardLastPicture() {
        mainHandler.removeCallbacks(pictureHoldTick)
        mainHandler.removeCallbacks(pictureRetry)
        pictureHold?.let { hold ->
            hold.animate().cancel()
            hold.visibility = View.GONE
            hold.setImageDrawable(null)
        }
        lastPicture = null
    }

    private fun attachSurface(surface: Surface) {
        sink?.setSurface(SCREEN_TYPE_MAIN, surface)
        if (AirPlayPersistence.loadClusterMapEnabled(this)) {
            clusterSurface?.let { sink?.setSurface(SCREEN_TYPE_ALT, it) }
        } else {
            sink?.setSurface(SCREEN_TYPE_ALT, surface)
        }
    }

    private fun onHostTouch(view: View, event: MotionEvent): Boolean {
        if (menuOpen) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureSequenceActive = false
                gestureTracking = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // main #487：手势数 0 表示禁用，不进入追踪（作者：TRAE AI）。
                if (gestureFingerCount > 0 && event.pointerCount == gestureFingerCount && !gestureSequenceActive) {
                    gestureSequenceActive = true
                    gestureTracking = true
                    gestureStartX = pointerCentroid(event, horizontal = true)
                    gestureStartY = pointerCentroid(event, horizontal = false)
                    controller?.sendTouch(emptyList())
                    appendLog("Settings swipe tracking started; fingers=$gestureFingerCount")
                    return true
                }
            }
        }

        if (gestureSequenceActive) {
            if (event.actionMasked == MotionEvent.ACTION_POINTER_UP || event.pointerCount != gestureFingerCount) {
                gestureTracking = false
            }
            if (!gestureTracking || event.pointerCount != gestureFingerCount) {
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    gestureSequenceActive = false
                    gestureTracking = false
                } else if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    gestureTracking = false
                }
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                val deltaX = Math.abs(pointerCentroid(event, horizontal = true) - gestureStartX)
                val deltaY = pointerCentroid(event, horizontal = false) - gestureStartY
                if (
                    Math.abs(deltaY) >= dp(SETTINGS_SWIPE_DISTANCE_DP) &&
                    Math.abs(deltaY) >= deltaX * SETTINGS_SWIPE_DIRECTION_RATIO
                ) {
                    gestureTracking = false
                    if (deltaY > 0) {
                        gestureSequenceActive = false
                        openSettingsMenu()
                    } else {
                        // Keep swallowing remaining finger-up events. Forwarding a release
                        // without its matching press creates phantom taps inside CarPlay.
                        showTemporarySystemBars()
                    }
                    return true
                }
            }
            return true
        }

        val content = contentRect(view.width, view.height)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchOutsideContent = !content.contains(event.x, event.y)
        }
        if (touchOutsideContent) {
            // Ignore the entire touch sequence when it starts in a letterbox bar.
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                touchOutsideContent = false
            }
            return true
        }
        val contacts = CarPlayTouchMapper.contacts(event, content)
        val queued = controller?.sendTouch(contacts) ?: false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> Log.i(
                TAG,
                "touch action=${MotionEvent.actionToString(event.actionMasked)} " +
                    "pointers=${event.pointerCount} queued=$queued",
            )
        }
        return true
    }

    private fun pointerCentroid(event: MotionEvent, horizontal: Boolean): Float {
        var total = 0f
        for (index in 0 until event.pointerCount) {
            total += if (horizontal) event.getX(index) else event.getY(index)
        }
        return total / event.pointerCount
    }

    private fun onScreenStreamStateChanged(generation: Int, type: Int, active: Boolean) {
        runOnUiThread {
            if (shuttingDown.get() || generation != restartGeneration) return@runOnUiThread
            if (active) {
                activeScreenStreamTypes.add(type)
            } else {
                activeScreenStreamTypes.remove(type)
            }
            if (type == SCREEN_TYPE_ALT) {
                Log.i(ClusterMapPresentation.TAG, "cluster stream active=$active")
                appendLog("Cluster map: stream active=$active")
                clusterPresentation?.setStreamActive(active)
                ClusterActivityOutput.setStreamActive(active)
                MapMirrors.setStreamActive(active)
                if (active) {
                    mainHandler.removeCallbacks(hideIdleCenterMap)
                    if (!CenterMapOverlay.shown) CenterMapOverlay.scheduleShow()
                } else {
                    mainHandler.postDelayed(hideIdleCenterMap, CENTER_MAP_IDLE_MILLIS)
                }
            }
            updateDebugOverlays()
        }
    }

    // main #539：BYD 来电弹窗只在"前台 + 活动会话 + 非菜单/非重建"时隐藏（作者：TRAE AI）。
    private fun shouldHideBydCallPopup(): Boolean =
        hostAppearanceResumed && callPopupSessionReady && !menuOpen && !shuttingDown.get() &&
            !handshakeResetInProgress && !reconnectScheduled && CarPlayBackgroundSession.active &&
            CarPlayBackgroundSession.isOwner(this) && AirPlayPersistence.loadHideBydCallPopup(this)

    private fun updateCallPopupGuard() {
        if (CarPlayBackgroundSession.isOwner(this)) CarPlayBackgroundSession.callPopupReady = callPopupSessionReady
        com.shilapi.xcertplay.hud.BydCallPopupGuard.update(this, this, shouldHideBydCallPopup()) {
            CarPlayBackgroundSession.active && CarPlayBackgroundSession.callPopupReady && CarPlayBackgroundSession.isOwner(this)
        }
    }

    private fun setStatus(stringId: Int) {
        val message = getString(stringId)
        val englishMessage = englishResources.getString(stringId)
        runOnUiThread {
            setConnectionStage(message, englishMessage)
            appendLog(message)
        }
    }

    private fun setConnectionStage(stringId: Int) =
        setConnectionStage(getString(stringId), englishResources.getString(stringId))

    /** [englishMessage] is [message] in English, which [friendlyStage] reads for its keywords. */
    private fun setConnectionStage(message: String, englishMessage: String = message) {
        latestStage = message
        stageStatusView?.text = friendlyStage(message, englishMessage)
        updateDebugOverlays()
    }

    /** 波次十一批次B（main #673）：连接失败时显示可操作提示，替换默认说明；会话建立/新失败时更新。 */
    private fun showConnectionHelp(failure: CarPlayStatus.Failed?) {
        connectionInstructionsView?.visibility = if (failure == null) View.VISIBLE else View.GONE
        connectionHelpView?.apply {
            visibility = if (failure == null) View.GONE else View.VISIBLE
            if (failure != null) {
                text = getString(
                    ConnectionFailureGuidance.message(failure, wirelessEnabled, wirelessHotspotMode),
                )
            }
        }
    }

    private fun updateDebugOverlays() {
        statusScrollView?.visibility = View.GONE
        val waitingVisible = activeScreenStreamTypes.isEmpty()
        connectionPanel?.visibility = if (waitingVisible) View.VISIBLE else View.GONE
        // 实验性①：仅当开关打开且处于等待/连接屏时显示 USB 实时诊断日志覆盖层；默认关则不展示。
        usbDiagView?.visibility = if (waitingVisible && AirPlayPersistence.loadUsbRealtimeLog(this)) View.VISIBLE else View.GONE
    }

    // The keywords are English; matching the English text keeps the result the same in every UI language.
    private fun friendlyStage(message: String, englishMessage: String = message): String = when {
        // main 4aee3f80：VPN 授权不可用的提示本身就是用户可读文案，不能被通用 "permission" 分支吞掉。
        message == getString(R.string.vpn_authorization_unavailable) -> message
        // It says where to look itself; its word "permissions" would turn it into the generic permission line.
        message == getString(R.string.could_not_start_carplay_return_to_diplay_and_check_app_per) -> message
        message == getString(R.string.waiting_for_mfi_coprocessor) ||
            message == getString(R.string.requesting_mfi_usb_permission) -> message
        // A startup failure that names its cause, and a line saying the retries stopped, are shown as they are.
        // "Getting CarPlay ready…" or "Retrying…" in their place said DiPlay was still connecting (#809).
        message == getString(R.string.hotspot_network_not_ready) || message == getString(R.string.first_tcp_timeout) ||
            message.endsWith(getString(R.string.wireless_startup_retries_exhausted)) -> message
        // main 8a8a6ffa：iOS 12/15 拒绝 CarPlay USB 模式请求时，提示解锁重连或转无线，而非"连接中断"。
        englishMessage.contains("CarPlay configuration request", true) -> getString(R.string.iphone_usb_mode_rejected)
        englishMessage.contains("Turn on Wi-Fi", true) -> getString(R.string.turn_on_wi_fi_in_the_head_unit_s_settings_to_connect)
        // 波次十一批次A/B（main #677）：蓝牙无线电关闭的提示关键词，走可操作文案。
        englishMessage.contains(BluetoothRadioCheck.RADIO_OFF_MARK, true) ->
            getString(R.string.turn_on_bluetooth_in_the_head_unit_s_settings_to_connect)
        englishMessage.contains("Allow precise Location", true) -> getString(R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p)
        englishMessage.contains("Allow Nearby devices", true) -> getString(R.string.allow_nearby_devices_for_diplay_in_the_head_unit_s_app_per)
        englishMessage.contains("createGroup failed", true) -> getString(R.string.the_head_unit_couldn_t_start_carplay_wi_fi_check_wi_fi_and)
        englishMessage.contains("needs a reset", true) -> getString(R.string.a_previous_wi_fi_direct_connection_is_still_running_reset)
        englishMessage.contains(BluetoothLinkCheck.UNREAL_MARK, true) -> getString(R.string.bluetooth_connections_not_real)
        englishMessage.contains(BluetoothLinkCheck.NO_SOCKETS_MARK, true) -> getString(R.string.bluetooth_cannot_open_connections)
        englishMessage.contains("socket", true) || englishMessage.contains("RFCOMM", true) -> getString(R.string.your_iphone_isn_t_available_unlock_it_and_check_bluetooth)
        englishMessage.contains("unsupported", true) || englishMessage.contains("not supported", true) -> getString(R.string.this_head_unit_may_not_support_wireless_carplay_try_a_usb)
        englishMessage.contains("denied", true) || englishMessage.contains("permission", true) -> getString(R.string.allow_the_connection_permission_to_continue)
        englishMessage.contains("Failed", true) || englishMessage.contains("error", true) -> getString(R.string.connection_interrupted_retrying)
        englishMessage.contains("Waiting for iPhone", true) || englishMessage.contains("Discovering iPhone", true) -> getString(R.string.connect_your_iphone_with_a_usb_cable)
        englishMessage.contains("paired", true) -> getString(R.string.looking_for_your_paired_iphone)
        englishMessage.contains("Bluetooth", true) -> getString(R.string.connecting_to_your_iphone)
        englishMessage.contains("reconnect", true) || englishMessage.contains("ended", true) -> getString(R.string.reconnecting_to_your_iphone)
        englishMessage.contains("active", true) || englishMessage.contains("running", true) -> getString(R.string.opening_carplay)
        else -> getString(R.string.getting_carplay_ready)
    }

    private fun appendLog(message: String) {
        val safe = DiagnosticRedactor.redact(message) ?: return
        sessionLog?.append(formattedLogLine(safe, System.currentTimeMillis()))
    }

    private fun appendFileLog(message: String) {
        sessionLog?.append(formattedLogLine(message, System.currentTimeMillis()))
    }

    private fun formattedLogLine(message: String, nowMillis: Long): String =
        "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(nowMillis))}  $message"

    private fun initializeSessionLog() {
        val logFile = File(File(filesDir, "logs"), "diplay.log")
        val activeLog = SessionLogFile(logFile)
        runCatching {
            activeLog.reset(
                "DiPlay log started " +
                    "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} " +
                    "pid=${Process.myPid()} path=${logFile.absolutePath}",
            )
        }
        sessionLog = activeLog
    }

    private fun refreshLogView(nowMillis: Long) {
        val cutoff = nowMillis - LOG_RETENTION_MILLIS
        while (logLines.firstOrNull()?.timestampMillis?.let { it <= cutoff } == true) {
            logLines.removeFirst()
        }
        statusView?.text = logLines.joinToString("\n") { it.text }
        scrollLogsToBottom()

        mainHandler.removeCallbacks(expireOldLogLines)
        logLines.firstOrNull()?.let { oldest ->
            val delay = (oldest.timestampMillis + LOG_RETENTION_MILLIS - nowMillis + 1L)
                .coerceAtLeast(1L)
            mainHandler.postDelayed(expireOldLogLines, delay)
        }
    }

    private fun scrollLogsToBottom() {
        statusScrollView?.post {
            statusScrollView?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun applyFullscreenMode() {
        val multiWindow = isMultiWindowActive()
        if (harmanFullscreenEnabled) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            // The OEM-specific bit must be written last: the standard insets controller does not preserve it.
            HarmanFullscreenCompat.apply(window)
            harmanFullscreenApplied = true
            return
        }
        if (harmanFullscreenApplied) {
            HarmanFullscreenCompat.clear(window)
            harmanFullscreenApplied = false
        }
        val hideTop = hideTopBar && !multiWindow
        val hideBottom = hideBottomBar && !multiWindow
        // API < 20：WindowInsetsControllerCompat 不实现系统栏可见性，回退到老式窗口标志。
        if (LegacyFullscreen.apply(window, hideTop, hideBottom)) return
        WindowCompat.setDecorFitsSystemWindows(window, !(hideTop && hideBottom))
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        val peek = temporarySystemBarsVisible && !multiWindow && !harmanFullscreenEnabled
        if (hideTop && !peek) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
        if (hideBottom && !peek) {
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        } else {
            controller.show(WindowInsetsCompat.Type.navigationBars())
        }
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /** Temporary reveal without persisting bar visibility or changing AirPlay's negotiated size. */
    private fun showTemporarySystemBars(): Boolean {
        if (!temporarySystemBarsEnabled || gestureFingerCount == 0 || menuOpen ||
            harmanFullscreenEnabled || isMultiWindowActive() || (!hideTopBar && !hideBottomBar)) {
            return false
        }
        mainHandler.removeCallbacks(hideTemporarySystemBars)
        temporarySystemBarsVisible = true
        applyFullscreenMode()
        mainHandler.postDelayed(hideTemporarySystemBars, 5_000L)
        return true
    }

    private fun cancelTemporarySystemBars() {
        mainHandler.removeCallbacks(hideTemporarySystemBars)
        if (temporarySystemBarsVisible) {
            temporarySystemBarsVisible = false
            applyFullscreenMode()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun CarPlayStatus.describe(res: Resources = resources): String = when (this) {
        CarPlayStatus.DiscoveringMfi -> res.getString(R.string.preparing_mfi_authentication)
        CarPlayStatus.WaitingForMfi -> res.getString(R.string.waiting_for_mfi_coprocessor)
        CarPlayStatus.RequestingMfiPermission -> res.getString(R.string.requesting_mfi_usb_permission)
        CarPlayStatus.MfiReady -> res.getString(R.string.mfi_authentication_ready)
        CarPlayStatus.StartingHotspot -> res.getString(if (wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI)
            R.string.existing_wifi_attaching else R.string.starting_wireless_hotspot)
        is CarPlayStatus.HotspotReady ->
            if (wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI) {
                res.getString(R.string.existing_wifi_ready, ssid, band, channel, address)
            } else res.getString(R.string.status_hotspot_ready, backend, ssid, band, if (channel == 0) res.getString(R.string.auto_value) else channel.toString())
        CarPlayStatus.WaitingForPairedIphone -> res.getString(R.string.waiting_for_paired_iphone)
        CarPlayStatus.ConnectingBluetooth -> res.getString(R.string.connecting_bluetooth)
        CarPlayStatus.RunningWireless -> res.getString(R.string.wireless_carplay_control_running)
        CarPlayStatus.WirelessActive, CarPlayStatus.WirelessActiveFallback -> res.getString(R.string.wireless_carplay_active)
        CarPlayStatus.DiscoveringIphone -> res.getString(R.string.discovering_iphone)
        CarPlayStatus.WaitingForIphone -> res.getString(R.string.waiting_for_iphone_over_usb)
        CarPlayStatus.RequestingIphonePermission -> res.getString(R.string.requesting_iphone_usb_permission)
        CarPlayStatus.WaitingForReenumeration -> res.getString(R.string.status_waiting_reenumeration)
        CarPlayStatus.SelectingConfiguration -> res.getString(R.string.selecting_carplay_configuration)
        CarPlayStatus.OpeningDataPaths -> res.getString(R.string.opening_usb_data_paths)
        CarPlayStatus.Pairing -> res.getString(R.string.pairing_with_iphone)
        CarPlayStatus.ConnectingControl -> res.getString(R.string.connecting_iap2_control)
        CarPlayStatus.AttachingNetwork ->
            if (wirelessEnabled) res.getString(R.string.starting_airplay_service) else res.getString(R.string.status_attaching_ncm)
        CarPlayStatus.RunningControl -> res.getString(R.string.carplay_control_running)
        CarPlayStatus.ControlEnded -> res.getString(R.string.carplay_control_window_ended)
        is CarPlayStatus.Failed -> when (startupFailure) {
            WirelessStartupFailure.HOTSPOT_NOT_READY -> res.getString(R.string.hotspot_network_not_ready)
            WirelessStartupFailure.FIRST_TCP_TIMEOUT -> res.getString(R.string.first_tcp_timeout)
            else -> res.getString(R.string.status_failed, message)
        }
    }

    private companion object {
        const val SIDE_PANEL_REFRESH_MILLIS = 5_000L
        /** 拖拽面板边关闭面板的阈值：CarPlay 份额超过它（三分之二到全屏的中点）即关闭。 */
        const val SIDE_PANEL_CLOSE_SHARE = 5f / 6f
        /** 侧栏把手药丸（及面板把手）出现或被轻点后的显示时长。 */
        const val SIDE_PANEL_TAB_HINT_MILLIS = 10_000L
        /** 波次十一批次D（main #669）：返回后最后一帧最长顶替视频的时长，以及拷贝它的等待上限。 */
        const val PICTURE_HOLD_MAX_MILLIS = 30_000L
        const val PICTURE_COPY_WAIT_MILLIS = 150L
        const val TAG = "xcertplay-usb"
        const val SCREEN_TYPE_MAIN = 110
        const val SCREEN_TYPE_ALT = 111
        // On the teardown thread: bounded sequencing wait. Release notification retains ownership beyond
        // this budget when a codec is still busy.
        const val SINK_RELEASE_WAIT_MILLIS = 2_000L
        private const val CENTER_MAP_IDLE_MILLIS = 3_000L // a reconnect is quicker; a session end is not
        const val LOG_RETENTION_MILLIS = 5 * 60_000L
        const val DISPLAY_CHANGE_DEBOUNCE_MILLIS = 500L
        const val CONFIGURATION_POLL_INTERVAL_MILLIS = 2_000L
        const val RECONNECT_DELAY_MILLIS = 2_000L
        const val IAP_TUNNEL_RECONNECT_DELAY_MILLIS = 15_000L
        const val CONTROLLER_CLOSE_TIMEOUT_MILLIS = 4_000L
        const val AUDIO_CAPTURE_MARKER = "audio-capture.enabled"
        const val AUDIO_CAPTURE_DIRECTORY = "audio-captures"
        const val PROTOCOL_TRACE_PREFIX = "TRACE "
        const val SETTINGS_SWIPE_DISTANCE_DP = 72
        const val SETTINGS_SWIPE_DIRECTION_RATIO = 1.15f
        const val MAX_SETTINGS_MENU_WIDTH_PX = 1200
        val MENU_BACKGROUND = Color.rgb(12, 16, 19)
        // 波次十一批次F（main #642）：车机内设置抽屉"玻璃壳"布局常量。
        const val SWIPE_PANEL_MARGIN_DP = 16
        const val SWIPE_PANEL_RADIUS_DP = 28
        const val SWIPE_PANEL_ELEVATION_DP = 24
        const val SWIPE_PANEL_HEIGHT_PERCENT = 94
        const val SWIPE_BORDER_WIDTH_DP = 1
        const val SWIPE_CHROME_PADDING_DP = 16
        const val SWIPE_CONTENT_TOP_PADDING_DP = 16
        const val SWIPE_CONTENT_BOTTOM_PADDING_DP = 36
        const val SWIPE_ACTION_HEIGHT_DP = 52
        const val SWIPE_ACTION_GAP_DP = 8
        const val SWIPE_CATEGORY_ACTION_HEIGHT_DP = 48
        const val SWIPE_CATEGORY_RAIL_WIDTH_DP = 208
        const val SWIPE_CATEGORY_RAIL_MIN_WIDTH_DP = 640
        const val SWIPE_SCRIM_COLOR = 0xB3000000.toInt()
        val MENU_SECONDARY = Color.rgb(170, 180, 190)
        val MENU_ACCENT = Color.rgb(127, 205, 154)
        val MENU_ACCENT_TRACK = Color.rgb(78, 143, 102)
        val MENU_TRACK_OFF = Color.rgb(64, 74, 80)
        val MENU_BUTTON_TEXT = Color.rgb(8, 17, 11)
        val MENU_DANGER = Color.rgb(190, 45, 45)
        val NO_VIDEO_BACKGROUND = Color.rgb(0x16, 0x16, 0x18)
    }

    private data class DisplaySize(val width: Int, val height: Int)
    private data class LogEntry(val timestampMillis: Long, val text: String)
    private data class HotspotStatus(
        val state: String,
        val ssid: String? = null,
        val band: String? = null,
        val channel: Int? = null,
        val backend: String? = null,
    )
}

internal data class CarPlaySessionDisplay(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val hideTopBar: Boolean,
    val hideBottomBar: Boolean,
    // Compare unscaled startup window dimensions, not the scaled video canvas.
    val windowWidth: Int,
    val windowHeight: Int,
    /** The view areas this session declared, or null for the whole screen as one area. */
    val viewAreas: CarPlayViewAreas? = null,
    /** 波次十一批次C（main #643）：会话启动时状态栏是否在屏，null 表示未知。 */
    val statusBarShown: Boolean? = null,
)

/**
 * 波次十一批次C（main #643/#674）：驾驶者保留了状态栏，而另一个窗口（车机的倒车摄像头）把它隐藏了：
 * 窗口只是临时增高了一个状态栏的高度。在返回状态栏之后以该尺寸新建会话会一直缩小显示。
 */
internal fun statusBarHiddenByAnotherWindow(
    display: CarPlaySessionDisplay,
    width: Int,
    height: Int,
    statusBarShown: Boolean?,
): Boolean =
    !display.hideTopBar && display.statusBarShown == true && statusBarShown == false &&
        width == display.windowWidth && height > display.windowHeight

/** Process-local hand-off for keeping the CarPlay session alive while no Activity is visible. */
internal object CarPlayBackgroundSession {
    @Volatile var active = false
    // main #539：会话就绪标记，供 BYD 来电弹窗 guard 在无障碍服务进程中判断（作者：TRAE AI）。
    @Volatile var callPopupReady = false
    private var stopAction: (((() -> Unit)) -> Unit)? = null
    private var stopping = false
    private var owner: Any? = null
    @Synchronized fun isOwner(candidate: Any): Boolean = owner === candidate
    @Synchronized fun hasSession(): Boolean = stopAction != null || stopping
    private val stopWaiters = mutableListOf<() -> Unit>()

    fun stop(completion: () -> Unit = {}) {
        val action: (((() -> Unit)) -> Unit)?
        synchronized(this) {
            if (stopping) { stopWaiters.add(completion); return }
            action = stopAction
            if (action != null) { stopping = true; stopWaiters.add(completion) }
        }
        if (action == null) { completion(); return }
        action.invoke {
            val callbacks = synchronized(this) {
                stopping = false
                stopWaiters.toList().also { stopWaiters.clear() }
            }
            callbacks.forEach { it() }
        }
    }

    data class Snapshot(
        val controller: CarPlayController,
        val sink: AndroidMediaSink,
        val width: Int,
        val height: Int,
        val display: CarPlaySessionDisplay,
    )

    private var controller: CarPlayController? = null
    private var sink: AndroidMediaSink? = null
    private var width = 0
    private var height = 0
    private var display: CarPlaySessionDisplay? = null

    @Synchronized
    fun snapshot(): Snapshot? {
        val currentController = controller ?: return null
        val currentSink = sink ?: return null
        val currentDisplay = display ?: return null
        return Snapshot(currentController, currentSink, width, height, currentDisplay)
    }

    @Synchronized
    fun store(controller: CarPlayController, sink: AndroidMediaSink, width: Int, height: Int,
        owner: Any, display: CarPlaySessionDisplay, stop: (() -> Unit) -> Unit) {
        if (this.controller !== controller) callPopupReady = false
        this.stopAction = stop
        this.owner = owner
        this.controller = controller
        this.sink = sink
        this.width = width
        this.height = height
        this.display = display
    }

    @Synchronized
    fun clear(expected: CarPlayController? = null, keepOwner: Boolean = false) {
        if (expected != null && controller !== expected) return
        controller = null
        sink = null
        if (!keepOwner) { stopAction = null; owner = null }
        active = false
        callPopupReady = false
        width = 0
        height = 0
        display = null
    }
}

/**
 * API 17-22 的 LayerDrawable 没有 setLayerGravity/setLayerInsetBottom 等 API 23 方法，
 * 该类手动摆放各层作为等效实现：每层先在 bounds 内按 insets 收缩，
 * 层有固有尺寸时按 gravity 对齐放置，无固有尺寸时拉伸填满，行为与新版一致。
 *
 * @author TRAE AI
 * @since 2026-10-10
 */
private class GravityLayerDrawable(
    private val layers: Array<GradientDrawable>,
    private val insets: Array<IntArray>,
    private val gravities: IntArray,
) : Drawable() {
    private var alpha = 255

    /** API 17/18 没有 Drawable.getAlpha，外部要读当前透明度只能走这个字段（见 [drawableAlpha]）。 */
    val currentAlpha: Int get() = alpha

    override fun draw(canvas: Canvas) {
        val b = bounds
        for (i in layers.indices) {
            val ins = insets[i]
            // 每层的可用区域：bounds 收缩 insets，层在该区域内按 gravity 放置。
            val area = Rect(b.left + ins[0], b.top + ins[1], b.right - ins[2], b.bottom - ins[3])
            val layer = layers[i]
            val w = if (layer.intrinsicWidth >= 0) layer.intrinsicWidth else area.width()
            val h = if (layer.intrinsicHeight >= 0) layer.intrinsicHeight else area.height()
            val gravity = gravities[i]
            val x = when (gravity and Gravity.HORIZONTAL_GRAVITY_MASK) {
                Gravity.LEFT -> area.left
                Gravity.RIGHT -> area.right - w
                else -> area.left + (area.width() - w) / 2
            }
            val y = when (gravity and Gravity.VERTICAL_GRAVITY_MASK) {
                Gravity.TOP -> area.top
                Gravity.BOTTOM -> area.bottom - h
                else -> area.top + (area.height() - h) / 2
            }
            layer.setBounds(x, y, x + w, y + h)
            layer.draw(canvas)
        }
    }

    override fun setAlpha(value: Int) {
        if (alpha == value) return
        alpha = value
        layers.forEach { it.alpha = value }
        invalidateSelf()
    }

    override fun getAlpha(): Int = alpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        layers.forEach { it.colorFilter = colorFilter }
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * 读一个 [Drawable] 的当前透明度。
 *
 * `Drawable.getAlpha` 是 API 19+，API 17/18 直接读会被 dalvik 替换成抛 [NoSuchMethodError] 的桩，
 * 且它发生在属性初始化/淡出回调里，没有任何 try/catch 兜底，执行即崩。API 17 上侧栏药丸用的是本文件
 * 自绘的 [GravityLayerDrawable]（alpha 存在自己的字段里），取该字段即可；平台的 LayerDrawable 只在
 * API 23+ 才被选中，那时 getAlpha 早已存在。
 */
private fun drawableAlpha(drawable: Drawable): Int =
    if (Build.VERSION.SDK_INT >= 19) drawable.alpha
    else (drawable as? GravityLayerDrawable)?.currentAlpha ?: 255
