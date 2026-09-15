package jp.example.studyreminder

import android.content.Context
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager

object WifiUtil {

    /** 現在接続中のWi-FiのSSIDを取得する。位置情報権限が無い場合は "<unknown ssid>" になることがある。 */
    fun getCurrentSsid(context: Context): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val info = wifiManager.connectionInfo ?: return null
        var ssid = info.ssid ?: return null
        // SSIDはダブルクォートで囲まれて返ってくることがあるため除去する
        if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length >= 2) {
            ssid = ssid.substring(1, ssid.length - 1)
        }
        if (ssid == "<unknown ssid>" || ssid.isBlank()) return null
        return ssid
    }

    /** 現在、登録済みのいずれかの自宅Wi-Fiに接続しているかどうか */
    fun isHome(context: Context): Boolean {
        val prefs = Prefs(context)
        val homeSsids = prefs.homeSsids
        if (homeSsids.isEmpty()) return false
        val current = getCurrentSsid(context) ?: return false
        return homeSsids.contains(current)
    }
}
