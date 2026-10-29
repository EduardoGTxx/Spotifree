package com.spotifree.app.data.importer

import com.spotifree.app.util.runCatchingCancellable

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.spotifree.app.AppGraph
import com.spotifree.app.data.drive.DriveBackup
import java.util.concurrent.TimeUnit

/** Daily / weekly automatic backup (local file in Downloads/Spotifree and, if enabled, Google Drive). */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = AppGraph.get(applicationContext)
        val backup = BackupManager(applicationContext, graph.db, graph.settings)
        runCatchingCancellable { backup.runAutoBackupIfDue(graph.account.account.value, "scheduled") }
        if (graph.settings.current.driveAutoBackup) {
            runCatchingCancellable { DriveBackup(applicationContext, graph).upload(interactive = false) }
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "spotifree-auto-backup"

        fun schedule(context: Context, frequency: String) {
            val wm = WorkManager.getInstance(context)
            val hours = when (frequency) {
                "daily" -> 24L
                "weekly" -> 24L * 7
                else -> 0L
            }
            if (hours == 0L) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val req = PeriodicWorkRequestBuilder<AutoBackupWorker>(hours, TimeUnit.HOURS).build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
