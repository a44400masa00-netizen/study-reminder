package jp.example.studyreminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 端末再起動後にアラームとWorkManagerのスケジュールを再登録する */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = Prefs(context)
            if (prefs.enabled) {
                AlarmScheduler.scheduleDailyStart(context)
                HomeCheckWorker.enqueuePeriodic(context)
            }
        }
    }
}
