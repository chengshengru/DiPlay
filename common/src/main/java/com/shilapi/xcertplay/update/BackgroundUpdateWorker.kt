package com.shilapi.xcertplay.update

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.shilapi.xcertplay.CarPlayBackgroundSession
import java.util.concurrent.TimeUnit

internal object BackgroundUpdatePolicy {
    /** A stored but inactive session is connecting or recovering. Active sessions are safe. */
    fun connectionBusy(hasSession: Boolean, active: Boolean): Boolean = hasSession && !active
}

internal class BackgroundUpdateWorker(context: Context, parameters: WorkerParameters) :
    Worker(context, parameters) {
    override fun doWork(): Result {
        if (!UpdateAvailability.backgroundChecksEnabled(applicationContext)) return Result.success()
        if (BackgroundUpdatePolicy.connectionBusy(
                CarPlayBackgroundSession.hasSession(),
                CarPlayBackgroundSession.active,
            )) {
            return retryOrGiveUp("session busy")
        }
        val now = System.currentTimeMillis()
        if (!UpdateAvailability.shouldCheck(applicationContext, now)) return Result.success()
        // API17 适配：getSystemService(Class) 是 API 23+，低版本走 ContextCompat（作者：TRAE AI）。
        val connectivity = androidx.core.content.ContextCompat.getSystemService(
            applicationContext, ConnectivityManager::class.java,
        ) ?: return Result.success()
        // API17 适配：Network/allNetworks 是 API 21+；低版本用 ActiveNetworkInfo 判断，走默认路由（作者：TRAE AI）。
        val legacyInternet = android.os.Build.VERSION.SDK_INT < 21 && InternetNetworkSelector.hasLegacyInternet(connectivity)
        val network = if (android.os.Build.VERSION.SDK_INT >= 21) runCatching { InternetNetworkSelector.select(connectivity) }
            .onFailure { Log.i(TAG, "network lookup failed", it) }
            .getOrNull() ?: return retryOrGiveUp("no validated network") else null
        if (android.os.Build.VERSION.SDK_INT < 21 && !legacyInternet) return retryOrGiveUp("no validated network")
        val installedVersion = applicationContext.packageManager
            .getPackageInfo(applicationContext.packageName, 0).versionName
        // A dev build with no parsable version would otherwise show a permanent banner.
        if (installedVersion == null || UpdateVersion.parse(installedVersion) == null) return Result.success()
        return try {
            val release = UpdateReleaseLookup.latest(
                installedVersion = installedVersion,
                userAgent = "DiPlay/$installedVersion",
                openConnection = { url -> network?.openConnection(url) ?: url.openConnection() },
            )
            if (release == null) UpdateAvailability.clear(applicationContext)
            else UpdateAvailability.save(applicationContext, release)
            UpdateAvailability.recordAttempt(applicationContext, now)
            UpdateAvailability.recordResult(applicationContext, now, if (release == null) "up to date" else "found ${release.tagName}")
            Result.success()
        } catch (failure: Exception) {
            Log.i(TAG, "background update check failed", failure)
            retryOrGiveUp("fetch failed: ${failure.javaClass.simpleName}")
        }
    }

    private fun retryOrGiveUp(reason: String): Result {
        UpdateAvailability.recordResult(applicationContext, System.currentTimeMillis(), "$reason (attempt ${runAttemptCount + 1})")
        return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    private companion object {
        const val TAG = "DiPlay-Update"
        const val MAX_ATTEMPTS = 5
    }
}

internal object BackgroundUpdateScheduler {
    private const val PERIODIC_WORK = "diplay-periodic-update-check"
    private const val OPEN_WORK = "diplay-open-update-check"

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val manager = WorkManager.getInstance(context.applicationContext)
        val periodic = PeriodicWorkRequestBuilder<BackgroundUpdateWorker>(24, TimeUnit.HOURS, 6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setInitialDelay(10, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
            .build()
        manager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, periodic)

        val afterOpen = OneTimeWorkRequestBuilder<BackgroundUpdateWorker>()
            .setConstraints(constraints)
            .setInitialDelay(2, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
            .build()
        manager.enqueueUniqueWork(OPEN_WORK, ExistingWorkPolicy.KEEP, afterOpen)
    }
}
