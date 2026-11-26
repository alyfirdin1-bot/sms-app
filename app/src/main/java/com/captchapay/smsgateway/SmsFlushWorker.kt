package com.captchapay.smsgateway

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class SmsFlushWorker(
    context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    override fun doWork(): Result {
        val appCtx = applicationContext
        if (!GatewayConfig.isConfigured(appCtx)) {
            return Result.success()
        }

        val flushed = GatewayApiClient.flushPendingQueueSync(appCtx)
        if (flushed > 0) {
            GatewayApiClient.notifyUiPublic(appCtx)
        }

        val remaining = OfflineSmsDb.getInstance(appCtx).getPendingCount()
        return if (remaining == 0) {
            Result.success()
        } else {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "captchapay_offline_sms_flash_flush"

        fun scheduleFlush(context: Context) {
            val appCtx = context.applicationContext
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<SmsFlushWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(appCtx).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
