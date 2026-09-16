package jp.example.studyreminder

import android.Manifest
import android.app.AlarmManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import jp.example.studyreminder.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        NotificationHelper.createChannels(this)

        binding.switchEnabled.isChecked = prefs.enabled
        binding.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            prefs.enabled = isChecked
            if (isChecked) {
                AlarmScheduler.scheduleDailyStart(this)
                HomeCheckWorker.enqueuePeriodic(this)
                Engine.maybeStartCycle(this)
            } else {
                HomeCheckWorker.cancel(this)
            }
        }

        binding.buttonPickApp.setOnClickListener { showAppPicker() }
        binding.buttonSetStartTime.setOnClickListener { showTimePicker(isStart = true) }
        binding.buttonSetEndTime.setOnClickListener { showTimePicker(isStart = false) }
        binding.buttonRegisterSsid.setOnClickListener { registerCurrentSsid() }
        binding.buttonManageSsid.setOnClickListener { showSsidManageDialog() }

        binding.buttonNotifPermission.setOnClickListener { requestNotificationPermission() }
        binding.buttonLocationPermission.setOnClickListener { requestLocationPermission() }
        binding.buttonUsageAccess.setOnClickListener { openUsageAccessSettings() }
        binding.buttonExactAlarm.setOnClickListener { openExactAlarmSettings() }
        binding.buttonBatteryOptimization.setOnClickListener { openBatteryOptimizationSettings() }
        binding.buttonOverlayPermission.setOnClickListener { openOverlayPermissionSettings() }

        binding.buttonDebugStatus.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("現在の状態")
                .setMessage(Engine.debugStatus(this))
                .setPositiveButton("閉じる", null)
                .show()
        }
        binding.buttonTestNotification.setOnClickListener {
            NotificationHelper.createChannels(this)
            NotificationHelper.showStudyNotification(this)
            Toast.makeText(this, "テスト通知を送信しました。通知が表示されない場合は通知権限を確認してください", Toast.LENGTH_LONG).show()
        }
        binding.buttonTestCaution.setOnClickListener {
            if (!hasOverlayPermission()) {
                Toast.makeText(this, "先に「他のアプリの上に重ねて表示」を許可してください", Toast.LENGTH_LONG).show()
            } else {
                OverlayService.show(this, OverlayService.MSG_CAUTION)
            }
        }
        binding.buttonTestGetDown.setOnClickListener {
            if (!hasOverlayPermission()) {
                Toast.makeText(this, "先に「他のアプリの上に重ねて表示」を許可してください", Toast.LENGTH_LONG).show()
            } else {
                OverlayService.show(this, OverlayService.MSG_GET_DOWN)
            }
        }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        binding.textTargetApp.text = prefs.targetAppLabel ?: "未設定"
        binding.textWindow.text = "${Prefs.formatMinutes(prefs.windowStartMinutes)} 〜 ${Prefs.formatMinutes(prefs.windowEndMinutes)}"
        val ssids = prefs.homeSsids
        binding.textHomeSsid.text = if (ssids.isEmpty()) "未設定" else
            ssids.joinToString("、") + "（${ssids.size}/${Prefs.MAX_HOME_SSIDS}件）"

        setBadge(binding.badgeNotif, hasNotificationPermission())
        setBadge(binding.badgeLocation, hasLocationPermission())
        setBadge(binding.badgeUsage, hasUsageAccess())
        setBadge(binding.badgeAlarm, hasExactAlarmPermission())
        setBadge(binding.badgeBattery, isIgnoringBatteryOptimizations())
        setBadge(binding.badgeOverlay, hasOverlayPermission())
    }

    private fun setBadge(view: android.widget.TextView, granted: Boolean) {
        view.text = if (granted) "ON" else "OFF"
        view.setTextColor(if (granted) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
    }

    // ---------- 通知時間帯の変更 ----------

    /** 特定の設定画面を開こうとして失敗した場合、アプリの詳細設定画面にフォールバックする */
    private fun safeStartSettings(intent: Intent, notFoundMessage: String) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, notFoundMessage, Toast.LENGTH_LONG).show()
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            } catch (e2: Exception) {
                Toast.makeText(this, "設定画面を開けませんでした。端末の設定アプリから手動で確認してください", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showTimePicker(isStart: Boolean) {
        val current = if (isStart) prefs.windowStartMinutes else prefs.windowEndMinutes
        val currentHour = current / 60
        val currentMinute = current % 60

        android.app.TimePickerDialog(
            this,
            { _, hourOfDay, minute ->
                val newMinutes = hourOfDay * 60 + minute
                if (isStart) {
                    if (newMinutes >= prefs.windowEndMinutes) {
                        Toast.makeText(this, "開始時刻は終了時刻より前にしてください", Toast.LENGTH_LONG).show()
                        return@TimePickerDialog
                    }
                    prefs.windowStartMinutes = newMinutes
                } else {
                    if (newMinutes <= prefs.windowStartMinutes) {
                        Toast.makeText(this, "終了時刻は開始時刻より後にしてください", Toast.LENGTH_LONG).show()
                        return@TimePickerDialog
                    }
                    prefs.windowEndMinutes = newMinutes
                }
                refreshStatus()
                if (prefs.enabled) {
                    AlarmScheduler.scheduleDailyStart(this)
                }
                Toast.makeText(this, "時間帯を更新しました", Toast.LENGTH_SHORT).show()
            },
            currentHour,
            currentMinute,
            true
        ).show()
    }

    // ---------- アプリ選択 ----------

    private fun showAppPicker() {
        val pm = packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launchIntent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(pm).toString() }

        val labels = apps.map { it.loadLabel(pm).toString() }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("強制的に開くアプリを選択")
            .setItems(labels) { _, which ->
                val info = apps[which]
                prefs.targetPackage = info.activityInfo.packageName
                prefs.targetAppLabel = info.loadLabel(pm).toString()
                refreshStatus()
                Toast.makeText(this, "「${prefs.targetAppLabel}」を設定しました", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // ---------- 自宅Wi-Fi登録 ----------

    private fun registerCurrentSsid() {
        if (!hasLocationPermission()) {
            Toast.makeText(this, "先に位置情報の権限を許可してください", Toast.LENGTH_LONG).show()
            return
        }
        val ssid = WifiUtil.getCurrentSsid(this)
        if (ssid == null) {
            Toast.makeText(this, "Wi-Fiに接続していないか、SSIDを取得できませんでした", Toast.LENGTH_LONG).show()
            return
        }
        val added = prefs.addHomeSsid(ssid)
        refreshStatus()
        if (added) {
            Toast.makeText(this, "「$ssid」を自宅Wi-Fiに追加しました", Toast.LENGTH_SHORT).show()
            if (prefs.enabled) Engine.maybeStartCycle(this)
        } else {
            Toast.makeText(this, "登録は最大${Prefs.MAX_HOME_SSIDS}件までです。先に不要なものを削除してください", Toast.LENGTH_LONG).show()
        }
    }

    private fun showSsidManageDialog() {
        val current = prefs.homeSsids.toList()
        if (current.isEmpty()) {
            Toast.makeText(this, "まだ登録がありません", Toast.LENGTH_SHORT).show()
            return
        }
        val checked = BooleanArray(current.size) { true }
        AlertDialog.Builder(this)
            .setTitle("チェックを外して「保存」を押すと削除されます")
            .setMultiChoiceItems(current.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("保存") { _, _ ->
                val kept = current.filterIndexed { index, _ -> checked[index] }.toMutableSet()
                prefs.homeSsids = kept
                refreshStatus()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // ---------- 権限まわり ----------

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        } else {
            Toast.makeText(this, "このAndroidバージョンでは追加の許可は不要です", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 101)
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName
            )
        }
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun openUsageAccessSettings() {
        Toast.makeText(this, "一覧から「${getString(R.string.app_name)}」を探してONにしてください", Toast.LENGTH_LONG).show()
        safeStartSettings(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
            "使用状況アクセスの設定画面を開けませんでした"
        )
    }

    private fun hasExactAlarmPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.canScheduleExactAlarms()
        } else true
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            safeStartSettings(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")),
                "アラーム許可の設定画面を開けませんでした。端末の「設定→アプリ→Study Reminder→アラームとリマインダー」から許可してください"
            )
        } else {
            Toast.makeText(this, "このAndroidバージョンでは追加の許可は不要です", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openBatteryOptimizationSettings() {
        safeStartSettings(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            "バッテリー最適化の設定画面を開けませんでした"
        )
    }

    private fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun openOverlayPermissionSettings() {
        safeStartSettings(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            "重ねて表示の設定画面を開けませんでした"
        )
    }
}
