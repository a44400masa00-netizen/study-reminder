package jp.example.studyreminder

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.WindowManager

/**
 * 画面全体にCAUTION等の演出を出すためのサービス。
 * 「他のアプリの上に重ねて表示」権限(SYSTEM_ALERT_WINDOW)が必要。
 */
class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: OverlayView? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val message = intent?.getStringExtra(EXTRA_MESSAGE)
        if (message == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        showOverlay(message)
        return START_NOT_STICKY
    }

    private fun showOverlay(message: String) {
        if (!Settings.canDrawOverlays(this)) {
            // 権限が無い場合は何もできないので終了する
            stopSelf()
            return
        }
        removeOverlay()

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        val view = OverlayView(this, message) {
            removeOverlay()
            stopSelf()
        }
        overlayView = view
        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            overlayView = null
            stopSelf()
        }
    }

    private fun removeOverlay() {
        val view = overlayView
        if (view != null) {
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                // すでに外れている場合などは無視
            }
        }
        overlayView = null
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val EXTRA_MESSAGE = "extra_message"

        const val MSG_CAUTION = "CAUTION"
        const val MSG_GET_DOWN = "GET DOWN TO"

        /** 権限があれば画面全体にmessageを表示する。権限が無ければ何もしない。 */
        fun show(context: Context, message: String) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, OverlayService::class.java).apply {
                putExtra(EXTRA_MESSAGE, message)
            }
            context.startService(intent)
        }
    }
}
