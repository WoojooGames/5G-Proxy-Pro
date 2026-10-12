package com.tokyoxpa3.androidproxy

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * [백그라운드 안정성] 화면 꺼짐/백그라운드에서 프록시가 절전·우선순위에 밀리지 않게 하는
 * 옵션과 시스템 제한 점검.
 *
 * 이미 원본에 있는 것: 포그라운드 서비스(connectedDevice) + PARTIAL_WAKE_LOCK(CPU 유지)
 * + 시작 시 배터리 최적화 예외 요청. 여기서 더하는 것:
 *  - Wi-Fi 잠금: Wi-Fi 칩 절전(PS-Poll 등)으로 인한 지연·패킷 손실 방지
 *  - 고우선 처리: native relay 스레드 nice 상향
 *  - 점검: 배터리 최적화 / 백그라운드 사용 제한 / 데이터 절약 모드 상태와 설정 바로가기
 */
object BackgroundStability {
    private const val TAG = "BackgroundStability"

    const val PREF_WIFI_LOCK = "stability_wifi_lock"
    const val PREF_HIGH_PRIORITY = "stability_high_priority"

    fun wifiLockEnabled(context: Context): Boolean =
        context.getSharedPreferences("proxy_config", Context.MODE_PRIVATE).getBoolean(PREF_WIFI_LOCK, true)

    fun highPriorityEnabled(context: Context): Boolean =
        context.getSharedPreferences("proxy_config", Context.MODE_PRIVATE).getBoolean(PREF_HIGH_PRIORITY, true)

    /**
     * Wi-Fi 잠금 묶음. HIGH_PERF는 Wi-Fi 절전을 끄고(API 34에서 deprecated지만 대부분
     * 기기에서 여전히 동작), LOW_LATENCY(API 29+)는 화면이 켜져 있을 때 지연을 더 줄인다.
     * 둘 다 잡아 두면 시스템이 상황에 맞는 쪽을 적용한다.
     */
    class WifiLocks(context: Context) {
        private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        private val locks = mutableListOf<WifiManager.WifiLock>()

        @Synchronized
        @Suppress("DEPRECATION")
        fun acquire() {
            if (locks.isNotEmpty() || wifi == null) return
            val modes = mutableListOf(WifiManager.WIFI_MODE_FULL_HIGH_PERF)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) modes.add(WifiManager.WIFI_MODE_FULL_LOW_LATENCY)
            for (mode in modes) {
                try {
                    val lock = wifi.createWifiLock(mode, "5GProxy::WifiLock$mode")
                    lock.setReferenceCounted(false)
                    lock.acquire()
                    locks.add(lock)
                } catch (e: Exception) {
                    Log.w(TAG, "WifiLock(mode=$mode) 획득 실패: ${e.message}")
                }
            }
            Log.i(TAG, "WifiLock 획득: ${locks.size}개")
        }

        @Synchronized
        fun release() {
            for (lock in locks) {
                try { if (lock.isHeld) lock.release() } catch (e: Exception) {}
            }
            locks.clear()
        }
    }

    // ---------- 점검 ----------

    fun isBatteryOptimizationIgnored(context: Context): Boolean = PowerPermissionHelper.isWhitelisted(context)

    /** 설정 > 앱 > 배터리 "제한"(백그라운드 사용 제한)이 걸려 있으면 true. */
    fun isBackgroundRestricted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return am.isBackgroundRestricted
    }

    /** 데이터 절약 모드가 켜져 있고 이 앱이 예외가 아니면 true(백그라운드 셀룰러가 막힐 수 있음). */
    fun isDataSaverBlocking(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    }

    fun openAppDetails(context: Context) {
        start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    }

    fun openDataSaverException(context: Context) {
        val intent = Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS,
            Uri.fromParts("package", context.packageName, null))
        if (!start(context, intent)) openAppDetails(context)
    }

    private fun start(context: Context, intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "설정 화면 열기 실패: ${intent.action}: ${e.message}")
            false
        }
    }
}
