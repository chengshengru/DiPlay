package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbRequest
import android.os.Build
import java.nio.ByteBuffer

/**
 * API 17 迁移：`UsbRequest.queue(ByteBuffer)` 单参重载是 API 26 新增，API 17-25 真机上直接
 * 调用会抛 NoSuchMethodError。低版本改用已废弃的 `queue(ByteBuffer, Int)`（API 12+），该调用
 * 从缓冲区起点填充 length 字节，因此要求 position 为 0（两条读路径的缓冲区均满足）。
 */
internal fun UsbRequest.queueRead(buffer: ByteBuffer): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        queue(buffer)
    } else {
        require(buffer.position() == 0) { "Android 7 USB reads must start at buffer position 0" }
        @Suppress("DEPRECATION")
        queue(buffer, buffer.remaining())
    }

internal data class UsbReadQueueResult(val queued: Boolean, val firstBytes: Int, val fallbackBytes: Int? = null)

/** Android 8.0/8.1 usbfs 对 queue(ByteBuffer) 强制的 16 KiB 上限（main 波次二 764e0ba1 引入）。 */
internal const val USBFS_BULK_URB_CEILING_BYTES = 16 * 1024

/**
 * USB 读队列的大小兼容策略（main 波次二 422a7d8e 最终版，含阶梯降级）。
 *
 * Android 8.0/8.1 的 queue(ByteBuffer) 在超过 16 KiB 时抛异常，因此首次提交先压到上限；
 * 被显式拒绝（返回 false）时按 16K→8K→4K→2K 阶梯降级重试，最小 2 KiB。false 回退只是
 * "厂商显式拒绝"的兼容假设：Android 9 的 queue(ByteBuffer) 接受任意大小并在 false 时
 * 清空排队状态（见 AOSP UsbRequest.java）。小于回退底线的区域不重试；抛异常或缓冲区状态
 * 被改动的歧义请求绝不重试。调用方必须在管道的状态锁内执行本方法（含发布、排队、开闭检查）。
 *
 * API 17 迁移：main 只对 API 26/27 设上限，分支按迁移指南 5.3 第 2 条把上限守卫下探到
 * API 17-27（更老内核的 usbfs 上限相同或更低，全部启用 16 KiB 首提上限更保守）。
 */
internal class UsbReadQueuePolicy(private val queueCeiling: Int? = null) {
    private var successfulLimit: Int? = null

    fun queue(buffer: ByteBuffer, checkOpen: () -> Unit, submit: (ByteBuffer) -> Boolean): UsbReadQueueResult {
        require(buffer.isDirect && !buffer.isReadOnly) { "USB read requires a writable direct buffer" }
        checkOpen()
        val position = buffer.position()
        val originalLimit = buffer.limit()
        val originalRemaining = buffer.remaining()
        // 首次提交取 剩余量 / 已缓存成功大小 / 平台上限 三者最小值。
        val firstBytes = minOf(
            originalRemaining, successfulLimit ?: originalRemaining, queueCeiling ?: originalRemaining,
        )
        buffer.limit(position + firstBytes)
        if (submitUnchanged(buffer, position, firstBytes, submit)) {
            return UsbReadQueueResult(true, firstBytes)
        }
        // 短区域（不超过回退底线）或已降到阶梯底部仍被拒绝：不再重试，恢复原始范围。
        if (originalRemaining <= COMPATIBILITY_BYTES || firstBytes <= MIN_COMPATIBILITY_BYTES) {
            buffer.limit(originalLimit)
            return UsbReadQueueResult(false, firstBytes)
        }

        // 阶梯降级：从上限（或首次提交的一半）逐级减半，直到 2 KiB 底线。
        var size = if (firstBytes > COMPATIBILITY_BYTES) COMPATIBILITY_BYTES else {
            maxOf(MIN_COMPATIBILITY_BYTES, firstBytes / 2)
        }
        var lastAttempt = firstBytes
        while (size >= MIN_COMPATIBILITY_BYTES) {
            checkOpen()
            buffer.limit(position + size)
            lastAttempt = size
            if (submitUnchanged(buffer, position, size, submit)) {
                successfulLimit = size
                return UsbReadQueueResult(true, firstBytes, size)
            }
            size /= 2
        }
        buffer.limit(originalLimit)
        return UsbReadQueueResult(false, firstBytes, lastAttempt)
    }

    /** 只有缓冲区范围保持不变的 false 排队结果才允许重试（AOSP 保证显式 false 时缓冲区不变）。 */
    private fun submitUnchanged(
        buffer: ByteBuffer,
        position: Int,
        size: Int,
        submit: (ByteBuffer) -> Boolean,
    ): Boolean {
        if (submit(buffer)) return true
        check(buffer.position() == position && buffer.limit() == position + size) {
            "Rejected USB queue changed its buffer state"
        }
        return false
    }

    companion object {
        private const val COMPATIBILITY_BYTES = USBFS_BULK_URB_CEILING_BYTES
        private const val MIN_COMPATIBILITY_BYTES = 2 * 1024

        /** 按当前系统版本构造策略；API 17-27 首提上限 16 KiB，API 28+ 不设上限。 */
        fun forCurrentPlatform(): UsbReadQueuePolicy = UsbReadQueuePolicy(
            // API 17 迁移：Android 7 使用 queue(ByteBuffer, Int)，Android 9 移除了新 queue 的大小限制。
            // 分支把上限守卫从 main 的 26/27 下探到全部 < P（更老内核 usbfs 上限相同或更低）。
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                USBFS_BULK_URB_CEILING_BYTES
            } else {
                null
            },
        )
    }
}
