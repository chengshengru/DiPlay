package com.shilapi.xcertplay.compat

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * `java.time` API 的兼容层。
 *
 * `java.time` 仅在 API 26+ 原生可用（低版本需 core library desugaring）。
 * 这里用 [SimpleDateFormat] 与 [TimeUnit] 提供等价能力，保证 API 17 可运行。
 *
 * @author TRAE AI
 * @since 2026-10-08
 */
object TimeCompat {

    /**
     * 获取当前时间戳（毫秒）。
     */
    @JvmStatic
    fun currentTimeMillis(): Long = System.currentTimeMillis()

    /**
     * 获取当前时间戳（秒）。
     */
    @JvmStatic
    fun currentTimeSeconds(): Long = System.currentTimeMillis() / 1000

    /**
     * 获取当前纳秒时间（单调时钟，用于耗时测量）。
     */
    @JvmStatic
    fun currentTimeNanos(): Long = System.nanoTime()

    /**
     * 将毫秒时间戳格式化为字符串。
     *
     * @param millis 毫秒时间戳
     * @param pattern [SimpleDateFormat] 模式串
     * @param locale 地区设置
     */
    @JvmStatic
    fun formatTimestamp(
        millis: Long,
        pattern: String = "yyyy-MM-dd HH:mm:ss",
        locale: Locale = Locale.getDefault()
    ): String {
        val sdf = SimpleDateFormat(pattern, locale)
        return sdf.format(Date(millis))
    }

    /**
     * 将毫秒时间戳按指定时区格式化为字符串。
     */
    @JvmStatic
    fun formatTimestampWithZone(
        millis: Long,
        pattern: String = "yyyy-MM-dd HH:mm:ss",
        timeZone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault()
    ): String {
        val sdf = SimpleDateFormat(pattern, locale)
        sdf.timeZone = timeZone
        return sdf.format(Date(millis))
    }

    /**
     * 解析时间字符串为毫秒时间戳，解析失败返回 0。
     */
    @JvmStatic
    fun parseTimestamp(
        timeString: String,
        pattern: String = "yyyy-MM-dd HH:mm:ss",
        locale: Locale = Locale.getDefault()
    ): Long {
        val sdf = SimpleDateFormat(pattern, locale)
        return sdf.parse(timeString)?.time ?: 0L
    }

    /**
     * 时间单位转换。
     */
    @JvmStatic
    fun convertTime(value: Long, fromUnit: TimeUnit, toUnit: TimeUnit): Long {
        return toUnit.convert(value, fromUnit)
    }

    /**
     * 获取 UTC 时间字符串。
     */
    @JvmStatic
    fun utcTime(millis: Long = currentTimeMillis()): String {
        return formatTimestampWithZone(
            millis,
            timeZone = TimeZone.getTimeZone("UTC")
        )
    }
}

/**
 * 持续时间兼容类，替代 `java.time.Duration`。
 *
 * 内部以毫秒存储，避免依赖 API 26+ 的 `Duration`。
 *
 * @author TRAE AI
 * @since 2026-10-08
 */
class DurationCompat private constructor(private val millis: Long) {

    companion object {
        @JvmStatic
        fun ofMillis(millis: Long): DurationCompat = DurationCompat(millis)

        @JvmStatic
        fun ofSeconds(seconds: Long): DurationCompat =
            DurationCompat(TimeUnit.SECONDS.toMillis(seconds))

        @JvmStatic
        fun ofMinutes(minutes: Long): DurationCompat =
            DurationCompat(TimeUnit.MINUTES.toMillis(minutes))

        @JvmStatic
        fun ofHours(hours: Long): DurationCompat =
            DurationCompat(TimeUnit.HOURS.toMillis(hours))

        @JvmStatic
        fun ofDays(days: Long): DurationCompat =
            DurationCompat(TimeUnit.DAYS.toMillis(days))

        @JvmStatic
        fun between(startMillis: Long, endMillis: Long): DurationCompat =
            DurationCompat(endMillis - startMillis)
    }

    fun toMillis(): Long = millis
    fun toSeconds(): Long = TimeUnit.MILLISECONDS.toSeconds(millis)
    fun toMinutes(): Long = TimeUnit.MILLISECONDS.toMinutes(millis)
    fun toHours(): Long = TimeUnit.MILLISECONDS.toHours(millis)
    fun toDays(): Long = TimeUnit.MILLISECONDS.toDays(millis)

    fun isZero(): Boolean = millis == 0L
    fun isNegative(): Boolean = millis < 0L

    operator fun plus(other: DurationCompat): DurationCompat =
        DurationCompat(this.millis + other.millis)

    operator fun minus(other: DurationCompat): DurationCompat =
        DurationCompat(this.millis - other.millis)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DurationCompat) return false
        return millis == other.millis
    }

    override fun hashCode(): Int = millis.hashCode()

    override fun toString(): String = when {
        millis < 1000 -> "${millis}ms"
        millis < 60_000 -> "${toSeconds()}s"
        millis < 3_600_000 -> "${toMinutes()}m"
        else -> "${toHours()}h"
    }
}
