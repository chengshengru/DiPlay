package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.mfi.MfiProtocolMajorResult
import com.shilapi.xcertplay.mfi.MfiSelfCheck
import com.shilapi.xcertplay.mfi.MfiSelfCheckResult
import com.shilapi.xcertplay.transport.LinuxI2cTransport
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * API 17 迁移：原 Compose 诊断页改为 View 体系（Compose 要求 API 21+）。
 * 功能不变：打开 Linux I2C 设备并运行 MFi 自检。
 *
 * @author TRAE AI
 */
class MainActivity : Activity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    // main B8：诊断页跟随应用外观并观察 CarPlay 主机夜间态（View 版，作者：TRAE AI）。
    private var appNight = true
    private var stopObservingAppearance: (() -> Unit)? = null
    private var status: DiagnosticStatus = DiagnosticStatus.Idle
        set(value) {
            field = value
            findViewById<TextView>(R.id.status).text = value.message()
            val running = value is DiagnosticStatus.Running
            findViewById<Button>(R.id.run_self_check).isEnabled = !running
            findViewById<EditText>(R.id.device_path).isEnabled = !running
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.run_self_check).setOnClickListener {
            val devicePath = findViewById<EditText>(R.id.device_path).text.toString()
            runSelfCheck(devicePath)
        }
        status = DiagnosticStatus.Idle
        applyAppearance()
    }

    // main B8：外观联动生命周期与 main Compose 版语义一致（作者：TRAE AI）。
    override fun onResume() {
        super.onResume()
        appNight = resolveAppNightNow()
        applyAppearance()
        stopObservingAppearance?.invoke()
        stopObservingAppearance = AppAppearanceRuntime.observeHost {
            runOnUiThread {
                if (!isFinishing) {
                    appNight = resolveAppNightNow()
                    applyAppearance()
                }
            }
        }
    }

    override fun onPause() {
        stopObservingAppearance?.invoke()
        stopObservingAppearance = null
        super.onPause()
    }

    // 用调色板为诊断页上色：窗口背景 + 文字控件；输入框/按钮保留系统控件风格（诊断页非用户主路径）。
    private fun applyAppearance() {
        val palette = DiPlayPalette.of(appNight)
        window.setBackgroundDrawable(ColorDrawable(palette.background))
        val content = findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as? ViewGroup ?: return
        for (i in 0 until content.childCount) {
            (content.getChildAt(i) as? TextView)?.setTextColor(palette.primaryText)
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun runSelfCheck(devicePath: String) {
        status = DiagnosticStatus.Running
        executor.execute {
            val next = try {
                LinuxI2cTransport.open(devicePath).use { MfiSelfCheck(it).run() }
                    .let { DiagnosticStatus.Result(it) }
            } catch (error: LinkageError) {
                DiagnosticStatus.Failure(error.message ?: "I2C native library is unavailable")
            } catch (error: Exception) {
                DiagnosticStatus.Failure(error.message ?: error.javaClass.simpleName)
            }
            runOnUiThread {
                // API 17 没有 isDestroyed()，用 isFinishing 判断
                if (!isFinishing) status = next
            }
        }
    }
}

private sealed class DiagnosticStatus {
    data object Idle : DiagnosticStatus()
    data object Running : DiagnosticStatus()
    data class Result(val selfCheck: MfiSelfCheckResult) : DiagnosticStatus()
    data class Failure(val message: String) : DiagnosticStatus()

    // Kotlin 1.9：表达式函数体内不允许 return，改为块体
    fun message(): String {
        return when (this) {
            Idle -> "Idle"
            Running -> "Running…"
            is Failure -> "Failed: $message"
            is Result -> {
                val chip = selfCheck.chip ?: return if (selfCheck.discovery.interrupted) {
                    "MFi scan interrupted"
                } else {
                    "Found: none"
                }
                val major = when (val result = chip.protocolMajor) {
                    is MfiProtocolMajorResult.Value -> "%d".format(result.major)
                    is MfiProtocolMajorResult.MfiFailure -> result.error.message ?: result.error.javaClass.simpleName
                    is MfiProtocolMajorResult.TransportFailure -> result.error.message ?: result.error.javaClass.simpleName
                }
                "Found: 0x%02X; device version: 0x%02X; protocol major (raw): %s".format(
                    chip.address7Bit,
                    chip.deviceVersion,
                    major,
                )
            }
        }
    }
}
