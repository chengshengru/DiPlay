package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures the full Java/Kotlin stack trace of any uncaught exception and writes it to disk
 * before the process dies, so crashes on head units without adb can still be recovered.
 *
 * Native (SIGSEGV) crashes and ANRs are NOT caught here; [ProcessExitDiagnostics] records the
 * exit reason for native crashes on Android 11+. Install once from [DiPlayApplication.onCreate].
 */
internal object CrashFileLogger {
    private const val DIR = "crash-reports"
    private const val LAST = "crash-last.log"
    private const val MAX_ARCHIVES = 5

    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Persist first so a disk/permission failure can never hide the crash from the user.
            writeCrash(appContext, thread, throwable)
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                // Extremely unlikely: the VM default handler was absent. Mimic its death so the
                // system still tears the process down instead of silently swallowing the crash.
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    /** Stable pointer to the most recent crash, for easy retrieval and in-app surfacing. */
    fun lastCrashFile(context: Context): File =
        File(resolveDir(context), LAST)

    fun allCrashFiles(context: Context): List<File> {
        val dir = resolveDir(context)
        return dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".log") }
            .sortedByDescending { it.lastModified() }
    }

    internal fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val report = runCatching { buildReport(thread, throwable, deviceMeta(context)) }
            .getOrDefault("DiPlay crash report\n(time=unavailable)\n" + stackTrace(throwable))
        // 1) App-private path: feeds the in-app Diagnostics export. May be unavailable before
        //    the base context is attached (e.g. a crash inside MultiDex.install).
        runCatching {
            val dir = resolveDir(context)
            if (dir.isDirectory || dir.mkdirs()) {
                File(dir, "crash-$stamp.log").writeText(report, Charsets.UTF_8)
                File(dir, LAST).writeText(report, Charsets.UTF_8)
                prune(dir)
            }
        }
        // 2) Public external path: world-readable on API 17 and retrievable even when the app
        //    (the head unit's foreground/launcher UI) is dead and no DiPlay screen can export.
        //    Needs WRITE_EXTERNAL_STORAGE, auto-granted at install on API 17.
        writePublic(stamp, report)
    }

    @Suppress("DEPRECATION")
    private fun writePublic(stamp: String, report: String) {
        runCatching {
            if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) return@runCatching
            val dir = File(Environment.getExternalStorageDirectory(), "DiPlay")
            if (!dir.isDirectory && !dir.mkdirs()) return@runCatching
            File(dir, "crash-$stamp.log").writeText(report, Charsets.UTF_8)
            File(dir, LAST).writeText(report, Charsets.UTF_8)
        }
    }

    /** Public, always-retrievable copy of the last crash (for dead-launcher scenarios). */
    fun publicCrashFile(): File =
        File(Environment.getExternalStorageDirectory(), "DiPlay/$LAST")

    /** Pure, Android-free: includes device metadata + the full stack trace. */
    internal fun buildReport(thread: Thread, throwable: Throwable, deviceMeta: String): String = buildString {
        append("DiPlay crash report\n")
        append("time=").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
        append("thread=").append(thread.name).append(" (id=").append(thread.id).append(")\n")
        if (deviceMeta.isNotEmpty()) append(deviceMeta)
        append("------------\n")
        append(stackTrace(throwable))
    }

    private fun stackTrace(throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }

    private fun deviceMeta(context: Context): String = buildString {
        append("package=").append(context.packageName).append('\n')
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            append("version=").append(info.versionName ?: "?").append('\n')
        } catch (_: Exception) {
            // Package info is non-essential; never block the crash write on it.
        }
        append("android=").append(Build.VERSION.RELEASE).append(" sdk=").append(Build.VERSION.SDK_INT).append('\n')
        append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
    }

    // API 17 (Android 4.2.2) safe: the typed getExternalFilesDir(String) overload is unreliable on
    // old platforms, so resolve the files root with the null overload (API 8) and append the subdir.
    private fun resolveDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, DIR)
    }

    private fun prune(dir: File) {
        dir.listFiles().orEmpty()
            .filter { it.name.startsWith("crash-") && it.name.endsWith(".log") }
            .sortedByDescending { it.lastModified() }
            .drop(MAX_ARCHIVES)
            .forEach { runCatching { it.delete() } }
    }
}
