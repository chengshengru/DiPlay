package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CrashFileLoggerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val crashDir get() = File(context.getExternalFilesDir(null)!!, "crash-reports")

    @Before fun clean() {
        crashDir.deleteRecursively()
        File(context.filesDir, "crash-reports").deleteRecursively()
        // Reset the install guard so each test can install a fresh handler.
        val field = CrashFileLogger::class.java.getDeclaredField("installed").apply { isAccessible = true }
        field.set(null, false)
    }

    @Test fun buildReportIncludesThreadAndStackTraceWithoutAndroid() {
        val error = RuntimeException("boom")
        val report = CrashFileLogger.buildReport(Thread.currentThread(), error, "package=test\n")
        assertTrue(report.contains("thread="))
        assertTrue(report.contains("package=test"))
        assertTrue(report.contains("java.lang.RuntimeException: boom"))
        assertTrue(report.contains("CrashFileLoggerTest"))
    }

    @Test fun writeCrashPersistsLastPointerAndTimestampedArchive() {
        CrashFileLogger.writeCrash(context, Thread.currentThread(), IllegalStateException("nope"))
        val last = CrashFileLogger.lastCrashFile(context)
        assertTrue(last.isFile)
        val content = last.readText()
        assertTrue(content.contains("IllegalStateException: nope"))
        val archives = CrashFileLogger.allCrashFiles(context).filter { it.name.startsWith("crash-") }
        assertEquals(1, archives.size)
    }

    @Test fun installWritesBeforeDelegatingToPreviousHandler() {
        CrashFileLogger.install(context)
        // The current default handler is our wrapper; invoking it must persist the crash
        // and then forward to the system handler.
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        val t = Thread {
            handler.uncaughtException(Thread.currentThread(), OutOfMemoryError("oom"))
        }
        t.start()
        t.join(2000)
        val content = CrashFileLogger.lastCrashFile(context).readText()
        assertTrue(content.contains("OutOfMemoryError: oom"))
        assertFalse(t.isAlive)
    }
}
