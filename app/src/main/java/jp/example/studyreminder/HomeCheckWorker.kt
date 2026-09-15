package jp.example.studyreminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 「家にいる時にこのプログラムは実行される」の判定を定期的に行うWorker。
 * WorkManagerの周期実行は最短15分間隔という制約があるため、
 * 帰宅してから最大15分程度のずれでStudyサイクルが始まる。
 */
class HomeCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Engine.maybeStartCycle(applicationContext)
        return Result.success()
    }

    companion object {
        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<HomeCheckWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                Constants.HOME_CHECK_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(Constants.HOME_CHECK_WORK_NAME)
        }
    }
}
