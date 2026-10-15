package com.shilapi.xcertplay.hud

/**
 * 移植自 main #307：在发出禁用命令前观察到蓝牙处于 ON 状态时，持久化"必须恢复"的凭据。
 * 进程被杀后凭 journal 恢复，避免车机蓝牙被永久关闭。
 */
internal interface BluetoothRestoreJournal {
    fun pending(): Boolean
    fun write(pending: Boolean): Boolean
}

/**
 * 蓝牙暂停租约（移植自 main #307，含 8681254b 生命周期修复）。
 *
 * 取消操作保持快速，即使已发出的无线电命令被阻塞：所有状态判定在 commandLock 串行化下完成，
 * 世代号（ticket）保证旧控制器的迟到关闭不会释放新会话的租约。
 */
internal class BluetoothSuspendLease(
    private val journal: BluetoothRestoreJournal,
    private val readEnabled: () -> Boolean?,
    private val requestEnabled: (Boolean) -> Unit,
    private val awaitEnabled: (Boolean) -> Boolean,
) {
    private val stateLock = Any()
    private val commandLock = Any()
    private var owner: Any? = null
    private var generation = 0L

    fun begin(requester: Any): Long? = synchronized(stateLock) {
        if (owner === requester) return null
        owner = requester
        ++generation
    }

    fun end(requester: Any): Long? = synchronized(stateLock) {
        if (owner !== requester) return null
        owner = null
        ++generation
    }

    /** 会话进行中打开设置页不得释放该会话的租约。 */
    fun recoveryOnAppOpen(): Long? = synchronized(stateLock) {
        if (owner != null) return null
        // 非属主清理与已排队的握手恢复目标一致；复用其票号，避免迟到的旧控制器关闭使握手失败。
        generation
    }

    /** 新的无线握手同样会作废更早的待执行禁用。 */
    fun beforeHandshake(): Long = synchronized(stateLock) {
        owner = null
        ++generation
    }

    fun isSuspended(): Boolean = pending() == true && enabled() == false

    // main #18：入报告的暂停起点无线电状态与票号归属（作者：TRAE AI）。
    /** For the report: the radio state the pause starts from, and whether [ticket] still owns it. */
    fun radioEnabled(): Boolean? = enabled()
    fun isCurrent(ticket: Long): Boolean = current(ticket)

    fun suspend(ticket: Long): Boolean = synchronized(commandLock) {
        if (!current(ticket)) return false
        // 先调和被中断的旧租约，再观察本会话的原始状态。
        if (!restoreOwned()) return false
        if (!current(ticket) || enabled() != true) return false
        // 用 commit() 而非 apply()：命令之后进程被杀也必须留下恢复凭据。
        if (!runCatching { journal.write(true) }.getOrDefault(false)) return false
        if (!current(ticket)) {
            restoreOwned()
            return false
        }
        runCatching { requestEnabled(false) }
        val disabled = runCatching { awaitEnabled(false) }.getOrDefault(false)
        if (!disabled || !current(ticket)) {
            // 被取消的命令可能已经执行。仍在串行化内做补偿恢复。
            restoreOwned()
            return false
        }
        true
    }

    fun restore(ticket: Long): Boolean = synchronized(commandLock) {
        if (!currentRecovery(ticket)) return false
        restoreOwned()
    }

    private fun current(ticket: Long): Boolean = synchronized(stateLock) {
        generation == ticket && owner != null
    }

    private fun currentRecovery(ticket: Long): Boolean = synchronized(stateLock) {
        generation == ticket && owner == null
    }

    private fun pending(): Boolean? = runCatching { journal.pending() }.getOrNull()
    private fun enabled(): Boolean? = runCatching { readEnabled() }.getOrNull()

    private fun restoreOwned(): Boolean {
        val owned = pending() ?: return false
        if (!owned) return true
        when (enabled()) {
            true -> Unit // 从未关闭，或用户已自行恢复原始状态。
            false -> {
                runCatching { requestEnabled(true) }
                if (!runCatching { awaitEnabled(true) }.getOrDefault(false)) return false
            }
            null -> return false // 状态未知时不得擅自打开用户的无线电。
        }
        return runCatching { journal.write(false) }.getOrDefault(false)
    }
}

/**
 * main #18：每次暂停尝试写一行报告，报告能说明来电为何仍响在车机上（作者：TRAE AI）。
 * One report line for each pause attempt, so a report shows why calls still ring on the car (#18).
 */
internal object BluetoothPauseOutcome {
    fun describe(paused: Boolean, radioBefore: Boolean?, stillCurrent: Boolean, adbAccess: String?): String = when {
        paused -> "car Bluetooth paused: Android Bluetooth is off until CarPlay ends"
        !stillCurrent -> "car Bluetooth pause cancelled: CarPlay ended first"
        radioBefore == false -> "car Bluetooth pause skipped: Android Bluetooth was already off"
        radioBefore == null -> "car Bluetooth pause skipped: Android Bluetooth state unknown"
        adbAccess != null && adbAccess != "READY" -> "car Bluetooth pause failed: ADB access $adbAccess"
        else -> "car Bluetooth pause failed: Android Bluetooth stayed on"
    }
}
