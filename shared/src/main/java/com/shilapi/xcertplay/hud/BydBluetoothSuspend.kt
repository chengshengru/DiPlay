package com.shilapi.xcertplay.hud

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.compat.BluetoothCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 移植自 main #307：CarPlay 会话期间可选地通过已授权的本地 ADB 暂停车机蓝牙，
 * 避免来电同时弹在车机蓝牙电话与 CarPlay 上；配对信息从不改动。
 *
 * API 17 适配：无线电状态读取走 [BluetoothCompat.getAdapter]
 * （`getSystemService(Class)` 与 `BluetoothManager` 均为高版本 API）。
 */
object BydBluetoothSuspend {
    private const val TAG = "DiPlay-BT-Suspend"
    private const val PREFS = "diplay_bt_suspend"
    private const val KEY_RESTORE_ENABLED = "restore_initially_enabled"
    private const val STATE_TIMEOUT_MILLIS = 8_000L
    private val shell = BydAdbShell(TAG)
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "diplay-bt-suspend").apply { isDaemon = true }
    }
    private val gate = Any()
    private var lease: BluetoothSuspendLease? = null
    private var pending: ScheduledFuture<*>? = null

    private fun lease(context: Context): BluetoothSuspendLease = synchronized(gate) {
        lease ?: run {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            BluetoothSuspendLease(
                journal = object : BluetoothRestoreJournal {
                    override fun pending(): Boolean = prefs.getBoolean(KEY_RESTORE_ENABLED, false)
                    override fun write(pending: Boolean): Boolean = prefs.edit()
                        .putBoolean(KEY_RESTORE_ENABLED, pending).commit()
                },
                readEnabled = { radioEnabled(app) },
                requestEnabled = { enabled ->
                    // shell 返回非空并不构成无线电已变更状态的证据。
                    shell.run(app, if (enabled) "svc bluetooth enable" else "svc bluetooth disable")
                },
                awaitEnabled = { enabled -> awaitRadio(app, enabled) },
            ).also { lease = it }
        }
    }

    /**
     * [stillActive] 与 [resume] 在同一把锁下检查：在暂停真正开始前就已关闭的控制器或结束的会话，
     * 不会留下无人释放的租约。
     */
    fun suspend(
        context: Context,
        owner: Any,
        delayMillis: Long = 10_000L,
        log: (String) -> Unit = {},
        stillActive: () -> Boolean = { true },
    ) {
        synchronized(gate) {
            if (!stillActive()) return
            val state = lease(context)
            val ticket = state.begin(owner) ?: return
            pending?.cancel(false)
            // main #18：暂停的每一步（排队、结果、恢复）都写入诊断报告，不再只进 logcat（作者：TRAE AI）。
            val delay = delayMillis.coerceAtLeast(0)
            log("car Bluetooth pause in ${TimeUnit.MILLISECONDS.toSeconds(delay)} s")
            pending = worker.schedule({
                val radioBefore = state.radioEnabled()
                val paused = state.suspend(ticket)
                val outcome = BluetoothPauseOutcome.describe(paused, radioBefore, paused || state.isCurrent(ticket),
                    shell.lastAccess?.name)
                if (paused) Log.i(TAG, outcome) else Log.w(TAG, "$outcome; recovery retained if needed")
                log(outcome)
            }, delay, TimeUnit.MILLISECONDS)
        }
    }

    /** 只作废本控制器的工作；迟到的旧控制器关闭不会释放新会话的租约。 */
    fun resume(context: Context, owner: Any, log: (String) -> Unit = {}) {
        synchronized(gate) {
            val state = lease(context)
            val ticket = state.end(owner) ?: state.recoveryOnAppOpen() ?: return
            pending?.cancel(false)
            pending = null
            worker.execute { restore(state, ticket, log) }
        }
    }

    fun onAppOpened(context: Context) {
        synchronized(gate) {
            val state = lease(context)
            val ticket = state.recoveryOnAppOpen() ?: return
            pending?.cancel(false)
            pending = null
            worker.execute { restore(state, ticket) }
        }
    }

    /** 无线引导前的有界恢复；从不打开本来就已关闭的无线电。 */
    fun resumeAndWait(context: Context, adapter: BluetoothAdapter?, timeoutMillis: Long = STATE_TIMEOUT_MILLIS): Boolean {
        val task = synchronized(gate) {
            val state = lease(context)
            val ticket = state.beforeHandshake()
            pending?.cancel(false)
            pending = null
            worker.submit<Boolean> { state.restore(ticket) }
        }
        return runCatching {
            task.get(timeoutMillis.coerceAtLeast(1), TimeUnit.MILLISECONDS) && adapter?.isEnabled == true
        }.getOrDefault(false)
    }

    fun isSuspendedByUs(context: Context): Boolean {
        val app = context.applicationContext
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_RESTORE_ENABLED, false) && radioEnabled(app) == false
    }

    private fun restore(state: BluetoothSuspendLease, ticket: Long, log: (String) -> Unit = {}) {
        // main #18：恢复结果也入报告（作者：TRAE AI）。
        val wasPaused = state.isSuspended()
        if (!state.restore(ticket)) {
            Log.w(TAG, "Bluetooth recovery incomplete or superseded")
            if (wasPaused) log("car Bluetooth recovery incomplete; DiPlay retries when it opens again")
        } else if (wasPaused) {
            log("car Bluetooth back on")
        }
    }

    private fun radioEnabled(context: Context): Boolean? = runCatching {
        // API 17 适配：BluetoothManager 是 API 18+，统一走 BluetoothCompat。
        val adapter = BluetoothCompat.getAdapter(context) ?: return@runCatching null
        when (adapter.state) {
            BluetoothAdapter.STATE_ON -> true
            BluetoothAdapter.STATE_OFF -> false
            else -> null
        }
    }.getOrNull()

    private fun awaitRadio(context: Context, enabled: Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + STATE_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (radioEnabled(context) == enabled) return true
            SystemClock.sleep(100)
        }
        return radioEnabled(context) == enabled
    }
}
