package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * 车机按钮图标随 /info 回复发送给 iPhone(移植自 main #622,#567)。
 *
 * 自定义图标此前按原图保存(可达 1024 像素 PNG,照片可到数 MB),替换后 iPhone 拒绝连接。
 * 因此发送前统一缩放到与内置图标相同的尺寸。
 *
 * API 17 适配:BitmapFactory/Bitmap 全部为 API 1 能力,可直接使用。
 */
internal object AirPlayIconSizing {
    const val MAX_PIXELS = 192

    /** 返回 (边长, PNG 字节),不超过 [MAX_PIXELS];[encoded] 非正方形图片时为 null。 */
    fun fit(encoded: ByteArray): Pair<Int, ByteArray>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        val size = bounds.outWidth
        if (size <= 0 || size != bounds.outHeight) return null
        if (size <= MAX_PIXELS) return size to encoded
        var sample = 1
        while (size / (sample * 2) >= MAX_PIXELS) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(encoded, 0, encoded.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scaled = Bitmap.createScaledBitmap(decoded, MAX_PIXELS, MAX_PIXELS, true)
        if (scaled !== decoded) decoded.recycle()
        val bytes = ByteArrayOutputStream().use { output ->
            if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, output)) null else output.toByteArray()
        }
        scaled.recycle()
        return bytes?.let { MAX_PIXELS to it }
    }
}
