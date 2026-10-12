package com.tokyoxpa3.androidproxy.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.security.SecureRandom
import kotlin.coroutines.resume

/**
 * Wi-Fi Direct legacy group(자율 GO)을 열어 일반 Wi-Fi 기기가 접속할 수 있는 AP로 쓴다.
 *
 * - Android 10+: WifiP2pConfig.Builder로 SSID/비밀번호/대역(5GHz 우선)을 고정한다.
 * - Android 8~9: 고정 불가. 시스템이 만든 랜덤 SSID/비밀번호를 그룹 정보에서 읽어 노출한다.
 *
 * 그룹 소유자(GO)의 주소는 거의 항상 192.168.49.1이며, 인터페이스는 p2p-wlan0-N
 * (N은 그룹을 만들 때마다 증가)이다. 리스너 바인딩은 Socks5ProxyService의 "p2p"
 * 접두어 수집 + native reconcile이 그대로 처리하므로 여기서는 그룹 수명만 관리한다.
 */
class WifiDirectManager(context: Context) {

    data class Client(val name: String, val mac: String)

    data class GroupInfo(
        val ssid: String,
        val passphrase: String,
        val iface: String?,
        val ownerIp: String,
        val frequencyMhz: Int,
        val clients: List<Client>
    )

    class WifiDirectException(message: String) : Exception(message)

    companion object {
        private const val TAG = "WifiDirectManager"
        const val DEFAULT_OWNER_IP = "192.168.49.1"

        const val PREF_ENABLED = "wifi_direct_enabled"
        const val PREF_SSID = "wifi_direct_ssid"
        const val PREF_PASS = "wifi_direct_pass"
        const val PREF_5GHZ = "wifi_direct_5ghz"

        private const val CALLBACK_TIMEOUT_MS = 5000L
        private const val GROUP_READY_TIMEOUT_MS = 10000L
        private const val PERMISSION_MESSAGE = "Wi-Fi Direct 권한이 없습니다 (근처 기기/위치 권한 허용 필요)"

        /** 현재 열려 있는 그룹. UI·알림 표시용(서비스 프로세스 내 공유). */
        @Volatile var currentGroup: GroupInfo? = null
            private set
        @Volatile var onGroupChanged: ((GroupInfo?) -> Unit)? = null

        // WifiP2pConfig.Builder.setNetworkName이 검증하는 규칙과 동일:
        // "DIRECT-" + 영숫자 2자로 시작, UTF-8 32바이트 이하.
        private val NAME_REGEX = Regex("^DIRECT-[a-zA-Z0-9]{2}.*")
        private const val ALNUM = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"

        fun isValidNetworkName(name: String): Boolean =
            NAME_REGEX.matches(name) && name.toByteArray(Charsets.UTF_8).size <= 32

        fun isValidPassphrase(pass: String): Boolean =
            pass.length in 8..63 && pass.all { it.code in 0x20..0x7E }

        private fun randomString(len: Int): String {
            val rnd = SecureRandom()
            return String(CharArray(len) { ALNUM[rnd.nextInt(ALNUM.length)] })
        }

        /** 저장된 SSID/비밀번호를 읽고, 없거나 형식이 틀리면 새로 만들어 저장한다. */
        fun loadOrCreateCredentials(prefs: SharedPreferences): Pair<String, String> {
            var ssid = prefs.getString(PREF_SSID, null)
            var pass = prefs.getString(PREF_PASS, null)
            val editor = prefs.edit()
            if (ssid == null || !isValidNetworkName(ssid)) {
                ssid = "DIRECT-${randomString(2)}-Proxy"
                editor.putString(PREF_SSID, ssid)
            }
            if (pass == null || !isValidPassphrase(pass)) {
                pass = randomString(12)
                editor.putString(PREF_PASS, pass)
            }
            editor.apply()
            return ssid to pass
        }

        /** 그룹 생성에 필요한 런타임 권한. 13+는 NEARBY_WIFI_DEVICES, 그 이하는 위치. */
        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            }

        fun failureReasonText(reason: Int): String = when (reason) {
            WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
            WifiP2pManager.BUSY -> "BUSY"
            WifiP2pManager.ERROR -> "ERROR"
            else -> "reason=$reason"
        }
    }

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    @Volatile private var channel: WifiP2pManager.Channel? = null
    @Volatile private var receiver: BroadcastReceiver? = null

    /**
     * 그룹을 연다. 같은 이름의 그룹이 이미 떠 있으면(서비스 내부 재시작 등) 재사용한다.
     * 실패 시 WifiDirectException(사용자에게 그대로 보여줄 메시지)을 던진다.
     */
    suspend fun start(networkName: String, passphrase: String, prefer5Ghz: Boolean): GroupInfo {
        val mgr = manager ?: throw WifiDirectException("이 기기는 Wi-Fi Direct를 지원하지 않습니다")
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi != null && !wifi.isWifiEnabled) {
            throw WifiDirectException("Wi-Fi가 꺼져 있습니다 (Wi-Fi Direct는 Wi-Fi를 켜야 동작합니다)")
        }
        val ch = channel ?: mgr.initialize(appContext, Looper.getMainLooper()) {
            Log.w(TAG, "P2P channel 끊김")
            channel = null
        }.also { channel = it }
        registerReceiver()

        val existing = requestGroup(mgr, ch)
        val reusable = existing != null && existing.isGroupOwner &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                (existing.networkName == networkName && existing.passphrase == passphrase))
        if (!reusable) {
            if (existing != null) {
                Log.i(TAG, "기존 P2P 그룹(${existing.networkName}) 제거 후 새로 생성")
                action { mgr.removeGroup(ch, it) }
                delay(500)
            }
            createGroup(mgr, ch, networkName, passphrase, prefer5Ghz)
        } else {
            Log.i(TAG, "기존 P2P 그룹 재사용: ${existing!!.networkName}")
        }

        // 생성 성공 콜백은 인터페이스가 올라오기 전에 올 수 있다. GO로 확정될 때까지 폴링.
        var group: WifiP2pGroup? = null
        val deadline = SystemClock.elapsedRealtime() + GROUP_READY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val g = requestGroup(mgr, ch)
            if (g != null && g.isGroupOwner && g.`interface` != null) { group = g; break }
            delay(300)
        }
        if (group == null) {
            throw WifiDirectException("그룹 생성 후 ${GROUP_READY_TIMEOUT_MS / 1000}초 안에 활성화되지 않았습니다")
        }

        val ownerIp = requestOwnerIp(mgr, ch) ?: ifaceIpv4(group.`interface`) ?: DEFAULT_OWNER_IP
        return publish(group, ownerIp)
    }

    /** 그룹이 아직 살아 있는지(우리가 GO인지) 확인. Wi-Fi off 등으로 시스템이 내렸는지 감지용. */
    suspend fun isGroupAlive(): Boolean {
        val mgr = manager ?: return false
        val ch = channel ?: return false
        val g = requestGroup(mgr, ch) ?: return false
        return g.isGroupOwner
    }

    /** 그룹을 내리고 리소스를 정리한다. 한 번도 start하지 않았으면 아무것도 하지 않는다. */
    fun stop() {
        val ch = channel ?: return
        try {
            manager?.removeGroup(ch, null)
        } catch (e: Exception) {
            Log.w(TAG, "removeGroup 실패: ${e.message}")
        }
        unregisterReceiver()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try { ch.close() } catch (e: Exception) {}
        }
        channel = null
        setGroup(null)
    }

    @SuppressLint("MissingPermission")
    private suspend fun createGroup(
        mgr: WifiP2pManager, ch: WifiP2pManager.Channel,
        networkName: String, passphrase: String, prefer5Ghz: Boolean
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val r = action { mgr.createGroup(ch, it) }
            if (r != null) throw WifiDirectException("그룹 생성 실패 (${failureReasonText(r)})")
            return
        }
        // 5GHz를 지원하지 않는 기기/국가 설정이면 ERROR로 실패한다 → AUTO로 한 번 더.
        val bands = if (prefer5Ghz) {
            listOf(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ, WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
        } else {
            listOf(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
        }
        var lastReason = WifiP2pManager.ERROR
        for (band in bands) {
            val config = WifiP2pConfig.Builder()
                .setNetworkName(networkName)
                .setPassphrase(passphrase)
                .setGroupOperatingBand(band)
                .enablePersistentMode(false)
                .build()
            var r = action { mgr.createGroup(ch, config, it) }
            if (r == WifiP2pManager.BUSY) {
                // 다른 앱이나 이전 실행이 남긴 그룹/탐색이 점유 중인 경우가 대부분.
                action { mgr.removeGroup(ch, it) }
                delay(800)
                r = action { mgr.createGroup(ch, config, it) }
            }
            if (r == null) {
                Log.i(TAG, "P2P 그룹 생성 성공 (band=$band, ssid=$networkName)")
                return
            }
            lastReason = r
            Log.w(TAG, "P2P 그룹 생성 실패 (band=$band): ${failureReasonText(r)}")
        }
        throw WifiDirectException("그룹 생성 실패 (${failureReasonText(lastReason)})")
    }

    /**
     * ActionListener 콜백을 suspend로 감싼다. 성공 = null, 실패 = reason 코드.
     * 콜백이 아예 오지 않는 기기도 있어 타임아웃도 null(성공 간주)로 처리하고,
     * 실제 성공 여부는 이후 그룹 정보 폴링으로 확인한다.
     */
    private suspend fun action(block: (WifiP2pManager.ActionListener) -> Unit): Int? {
        var denied = false
        val result = withTimeoutOrNull(CALLBACK_TIMEOUT_MS) {
            suspendCancellableCoroutine<Int?> { cont ->
                try {
                    block(object : WifiP2pManager.ActionListener {
                        override fun onSuccess() { if (cont.isActive) cont.resume(null) }
                        override fun onFailure(reason: Int) { if (cont.isActive) cont.resume(reason) }
                    })
                } catch (e: SecurityException) {
                    denied = true
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
        if (denied) throw WifiDirectException(PERMISSION_MESSAGE)
        return result
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestGroup(mgr: WifiP2pManager, ch: WifiP2pManager.Channel): WifiP2pGroup? {
        var denied = false
        val group = withTimeoutOrNull(CALLBACK_TIMEOUT_MS) {
            suspendCancellableCoroutine<WifiP2pGroup?> { cont ->
                try {
                    mgr.requestGroupInfo(ch) { g -> if (cont.isActive) cont.resume(g) }
                } catch (e: SecurityException) {
                    denied = true
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
        if (denied) throw WifiDirectException(PERMISSION_MESSAGE)
        return group
    }

    private suspend fun requestOwnerIp(mgr: WifiP2pManager, ch: WifiP2pManager.Channel): String? {
        return withTimeoutOrNull(CALLBACK_TIMEOUT_MS) {
            suspendCancellableCoroutine<WifiP2pInfo?> { cont ->
                mgr.requestConnectionInfo(ch) { info -> if (cont.isActive) cont.resume(info) }
            }
        }?.groupOwnerAddress?.hostAddress
    }

    private fun ifaceIpv4(name: String?): String? {
        if (name == null) return null
        return try {
            java.net.NetworkInterface.getByName(name)?.inetAddresses?.toList()
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        } catch (e: Exception) { null }
    }

    private fun publish(group: WifiP2pGroup, ownerIp: String): GroupInfo {
        val info = GroupInfo(
            ssid = group.networkName ?: "",
            passphrase = group.passphrase ?: "",
            iface = group.`interface`,
            ownerIp = ownerIp,
            frequencyMhz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) group.frequency else 0,
            clients = group.clientList.map { Client(it.deviceName ?: "", it.deviceAddress ?: "") }
        )
        setGroup(info)
        return info
    }

    private fun setGroup(info: GroupInfo?) {
        currentGroup = info
        try { onGroupChanged?.invoke(info) } catch (e: Exception) {}
    }

    // 접속 기기 변동은 CONNECTION_CHANGED 브로드캐스트의 그룹 정보로 갱신한다.
    private fun registerReceiver() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) return
                val group: WifiP2pGroup? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP, WifiP2pGroup::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP)
                }
                val prev = currentGroup ?: return
                if (group == null || !group.isGroupOwner) {
                    // 그룹 해제는 서비스 워치독(isGroupAlive)이 최종 판단·재생성한다.
                    Log.w(TAG, "P2P 그룹 정보 사라짐 (CONNECTION_CHANGED)")
                    return
                }
                val clients = group.clientList.map { Client(it.deviceName ?: "", it.deviceAddress ?: "") }
                if (clients != prev.clients) {
                    Log.i(TAG, "접속 기기 변경: ${clients.size}대")
                    setGroup(prev.copy(clients = clients))
                }
            }
        }
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(r, filter)
        }
        receiver = r
    }

    private fun unregisterReceiver() {
        val r = receiver ?: return
        try { appContext.unregisterReceiver(r) } catch (e: Exception) {}
        receiver = null
    }
}
