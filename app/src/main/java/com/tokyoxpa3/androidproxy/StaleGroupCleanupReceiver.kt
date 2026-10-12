package com.tokyoxpa3.androidproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tokyoxpa3.androidproxy.network.WifiDirectManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * [남은 그룹 정리] 앱 업데이트로 이전 프로세스가 죽으면 그 프로세스가 연 Wi-Fi Direct 그룹이 남는다.
 * 업데이트 직후 시스템이 보내는 MY_PACKAGE_REPLACED에서 바로 치운다(앱을 열지 않아도 됨).
 * 새 프로세스라 서비스는 돌고 있지 않으므로 isServiceRunning 확인은 안전장치다.
 */
class StaleGroupCleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Socks5ProxyService.isServiceRunning) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                WifiDirectManager(context.applicationContext).removeStaleGroup()
            } finally {
                pending.finish()
            }
        }
    }
}
