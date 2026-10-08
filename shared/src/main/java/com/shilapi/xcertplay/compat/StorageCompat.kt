package com.shilapi.xcertplay.compat

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

/**
 * 存储访问兼容性处理。
 *
 * API 17 与 API 29+ 的存储模型完全不同：低版本可直接写入公共目录，
 * 高版本受 Scoped Storage 限制需通过 [MediaStore] 写入。
 *
 * @author TRAE AI
 * @since 2026-10-08
 */
object StorageCompat {

    /**
     * 获取外部存储根目录。
     */
    @Suppress("DEPRECATION")
    fun getExternalStorageDirectory(): File = Environment.getExternalStorageDirectory()

    /**
     * 获取应用专属外部存储目录。
     */
    fun getExternalFilesDir(context: Context, type: String?): File? =
        context.getExternalFilesDir(type)

    /**
     * 获取 Downloads 目录。
     */
    @Suppress("DEPRECATION")
    fun getDownloadsDir(context: Context): File {
        return if (ApiLevel.isAtLeast(ApiLevel.Q)) {
            // API 29+：Scoped Storage，返回应用专属目录
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(Environment.DIRECTORY_DOWNLOADS)
        } else {
            // API 17-28：直接使用公共目录
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
        }
    }

    /**
     * 保存文件到 Downloads 目录。
     */
    fun saveToDownloads(
        context: Context,
        fileName: String,
        content: ByteArray
    ): Boolean {
        return if (ApiLevel.isAtLeast(ApiLevel.Q)) {
            saveToDownloadsApi29(context, fileName, content)
        } else {
            saveToDownloadsApi17(context, fileName, content)
        }
    }

    /**
     * API 29+：通过 MediaStore 写入 Downloads。
     */
    private fun saveToDownloadsApi29(
        context: Context,
        fileName: String,
        content: ByteArray
    ): Boolean {
        return try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: return false

            resolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(content)
            } ?: return false

            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * API 17-28：直接写入公共 Downloads 目录。
     */
    @Suppress("DEPRECATION")
    private fun saveToDownloadsApi17(
        context: Context,
        fileName: String,
        content: ByteArray
    ): Boolean {
        return try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )

            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }

            val file = File(downloadsDir, fileName)
            FileOutputStream(file).use { outputStream ->
                outputStream.write(content)
            }

            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 检查外部存储是否可写。
     */
    @Suppress("DEPRECATION")
    fun isExternalStorageWritable(): Boolean =
        Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED

    /**
     * 检查外部存储是否可读。
     */
    @Suppress("DEPRECATION")
    fun isExternalStorageReadable(): Boolean {
        val state = Environment.getExternalStorageState()
        return state == Environment.MEDIA_MOUNTED ||
            state == Environment.MEDIA_MOUNTED_READ_ONLY
    }
}
