package com.tokyoxpa3.androidproxy.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.coroutines.resume

/**
 * [Wi-Fi Direct] 以 WifiP2pManager 開一個 legacy 客戶端可直接連的 Group（手機當 GO）。
 *
 * 一般裝置（筆電、平板）把它當成普通 Wi-Fi AP 連上即可，不需要 Wi-Fi Direct 支援；
 * GO 端位址固定在 192.168.49.1（p2p-wlan0-x 等介面），SOCKS listener 由服務的
 * LAN 位址收集流程自動綁上。
 *
 * Android 10+ 才能指定 SSID / 密碼 / 頻段；更舊的系統只能用系統隨機產生的組合。
 */
class WifiDirectGroupManager(context: Context) {

    companion object {
        private const val TAG = "WifiDirectGroup"
        private const val GROUP_IP_TIMEOUT_MS = 10_000L
        private const val CALL_TIMEOUT_MS = 5_000L
    }

    enum class Band { AUTO, GHZ_5, GHZ_2 }

    data class Config(val ssid: String, val passphrase: String, val band: Band)

    data class GroupInfo(
        val ssid: String,
        val passphrase: String,
        val iface: String,
        val ipv4: String,
        /** 實際成功建立的頻段要求（可能因降級而不同於設定值） */
        val band: Band,
        val clientCount: Int
    )

    sealed class Result {
        data class Ok(val info: GroupInfo) : Result()
        /** reason 為 WifiP2pManager.ERROR / P2P_UNSUPPORTED / BUSY，或 -1 = 其他（權限/逾時） */
        data class Failed(val reason: Int, val detail: String) : Result()
    }

    private val appContext = context.applicationContext
    private val manager: WifiP2pManager? =
        appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    @Volatile private var channel: WifiP2pManager.Channel? = null
    @Volatile private var activeConfig: Config? = null

    fun isSupported(): Boolean = manager != null

    private fun ensureChannel(): WifiP2pManager.Channel? {
        channel?.let { return it }
        val m = manager ?: return null
        // onChannelDisconnected：framework 重啟（例如 Wi-Fi 被關掉）後 channel 失效，
        // 丟掉它讓下一次呼叫重新 initialize。
        val ch = m.initialize(appContext, Looper.getMainLooper()) {
            Log.w(TAG, "P2P channel disconnected")
            channel = null
        }
        channel = ch
        return ch
    }

    /**
     * 確保符合設定的 Group 存在：已有同名 Group 就沿用（服務內部重建時不打斷已連上的
     * 裝置），否則移除殘留 Group 後新建。5GHz/2.4GHz 失敗時降級到 AUTO 再試一次。
     */
    suspend fun ensureGroup(config: Config): Result {
        val m = manager ?: return Result.Failed(WifiP2pManager.P2P_UNSUPPORTED, "WifiP2pManager unavailable")
        val ch = ensureChannel() ?: return Result.Failed(WifiP2pManager.P2P_UNSUPPORTED, "channel init failed")

        val existing = requestGroup(m, ch)
        if (existing != null && existing.isGroupOwner && matches(existing, config)) {
            val ip = awaitIpv4(existing.`interface`)
            if (ip != null) {
                activeConfig = config
                Log.i(TAG, "沿用既有 Group ${existing.networkName} @ $ip")
                return Result.Ok(toInfo(existing, ip, config.band))
            }
        }
        if (existing != null) {
            Log.i(TAG, "移除不符的既有 Group ${existing.networkName}")
            removeGroupInternal(m, ch)
        }

        val attempts = if (config.band == Band.AUTO) listOf(Band.AUTO) else listOf(config.band, Band.AUTO)
        var last: Result = Result.Failed(WifiP2pManager.ERROR, "not attempted")
        for (band in attempts) {
            last = createOnce(m, ch, config.copy(band = band))
            if (last is Result.Ok) {
                activeConfig = config
                return last
            }
            Log.w(TAG, "建立 Group 失敗（band=$band）：$last")
            // BUSY 常見於殘留 Group / 正在掃描：清掉再試下一個頻段
            removeGroupInternal(m, ch)
        }
        return last
    }

    /** 目前 Group 是否仍存在（給服務的看門狗用）。 */
    suspend fun currentGroup(): GroupInfo? {
        val m = manager ?: return null
        val ch = ensureChannel() ?: return null
        val g = requestGroup(m, ch) ?: return null
        if (!g.isGroupOwner) return null
        val ip = ifaceIpv4(g.`interface`) ?: return null
        return toInfo(g, ip, activeConfig?.band ?: Band.AUTO)
    }

    suspend fun removeGroup() {
        val m = manager ?: return
        val ch = channel ?: return
        removeGroupInternal(m, ch)
        activeConfig = null
    }

    /**
     * 非 suspend 版的移除＋關閉 channel：給 failStop / onDestroy 這類無法等待的路徑用。
     * 等 removeGroup 回呼後才關 channel，避免請求還沒送達就被一起丟掉。
     */
    fun removeGroupAndClose() {
        val m = manager
        val ch = channel
        activeConfig = null
        if (m == null || ch == null) return
        val done = object : WifiP2pManager.ActionListener {
            override fun onSuccess() { close() }
            override fun onFailure(reason: Int) { close() }
        }
        try { m.removeGroup(ch, done) } catch (e: Exception) { close() }
    }

    fun close() {
        if (Build.VERSION.SDK_INT >= 27) {
            try { channel?.close() } catch (e: Exception) {}
        }
        channel = null
    }

    // ---------- 內部 ----------

    @SuppressLint("MissingPermission")
    private suspend fun createOnce(m: WifiP2pManager, ch: WifiP2pManager.Channel, config: Config): Result {
        val code = withTimeoutOrNull(CALL_TIMEOUT_MS) {
            suspendCancellableCoroutine<Int> { cont ->
                val listener = object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { if (cont.isActive) cont.resume(-2) }
                    override fun onFailure(reason: Int) { if (cont.isActive) cont.resume(reason) }
                }
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        m.createGroup(ch, buildP2pConfig(config), listener)
                    } else {
                        m.createGroup(ch, listener)
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "createGroup 權限不足", e)
                    if (cont.isActive) cont.resume(-1)
                } catch (e: IllegalArgumentException) {
                    // SSID / 密碼格式不合（Builder 驗證）
                    Log.e(TAG, "createGroup 參數不合法", e)
                    if (cont.isActive) cont.resume(-3)
                }
            }
        }
        if (code == null) return Result.Failed(-1, "createGroup timeout")
        when (code) {
            -2 -> Unit
            -1 -> return Result.Failed(-1, "permission denied")
            -3 -> return Result.Failed(-1, "invalid SSID or passphrase")
            else -> return Result.Failed(code, reasonName(code))
        }

        // createGroup 成功只代表請求被接受；Group 與介面位址稍後才出現
        val deadline = System.currentTimeMillis() + GROUP_IP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val g = requestGroup(m, ch)
            if (g != null && g.isGroupOwner) {
                val ip = ifaceIpv4(g.`interface`)
                if (ip != null) {
                    Log.i(TAG, "Group 建立完成 ${g.networkName} iface=${g.`interface`} ip=$ip band=${config.band}")
                    return Result.Ok(toInfo(g, ip, config.band))
                }
            }
            delay(300)
        }
        return Result.Failed(-1, "group created but no IPv4 on p2p interface")
    }

    @android.annotation.TargetApi(29)
    private fun buildP2pConfig(config: Config): WifiP2pConfig {
        val band = when (config.band) {
            Band.GHZ_5 -> WifiP2pConfig.GROUP_OWNER_BAND_5GHZ
            Band.GHZ_2 -> WifiP2pConfig.GROUP_OWNER_BAND_2GHZ
            Band.AUTO -> WifiP2pConfig.GROUP_OWNER_BAND_AUTO
        }
        return WifiP2pConfig.Builder()
            .setNetworkName(config.ssid)
            .setPassphrase(config.passphrase)
            .setGroupOperatingBand(band)
            .enablePersistentMode(false)
            .build()
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestGroup(m: WifiP2pManager, ch: WifiP2pManager.Channel): WifiP2pGroup? =
        withTimeoutOrNull(CALL_TIMEOUT_MS) {
            suspendCancellableCoroutine<WifiP2pGroup?> { cont ->
                try {
                    m.requestGroupInfo(ch) { g -> if (cont.isActive) cont.resume(g) }
                } catch (e: SecurityException) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    private suspend fun removeGroupInternal(m: WifiP2pManager, ch: WifiP2pManager.Channel) {
        withTimeoutOrNull(CALL_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { cont ->
                m.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { if (cont.isActive) cont.resume(Unit) }
                    override fun onFailure(reason: Int) { if (cont.isActive) cont.resume(Unit) }
                })
            }
        }
    }

    private fun matches(g: WifiP2pGroup, config: Config): Boolean {
        // Android 10 以前無法指定 SSID：任何自己建立的 GO Group 都算數
        if (Build.VERSION.SDK_INT < 29) return true
        return g.networkName == config.ssid && g.passphrase == config.passphrase
    }

    private suspend fun awaitIpv4(iface: String?): String? {
        val deadline = System.currentTimeMillis() + 2_000L
        while (System.currentTimeMillis() < deadline) {
            ifaceIpv4(iface)?.let { return it }
            delay(200)
        }
        return null
    }

    private fun ifaceIpv4(iface: String?): String? {
        if (iface.isNullOrEmpty()) return null
        return try {
            val nif = NetworkInterface.getByName(iface) ?: return null
            if (!nif.isUp) return null
            nif.inetAddresses.toList().filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }?.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    private fun toInfo(g: WifiP2pGroup, ip: String, band: Band) = GroupInfo(
        ssid = g.networkName ?: "",
        passphrase = g.passphrase ?: "",
        iface = g.`interface` ?: "",
        ipv4 = ip,
        band = band,
        clientCount = g.clientList?.size ?: 0
    )

    private fun reasonName(code: Int) = when (code) {
        WifiP2pManager.ERROR -> "ERROR"
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
        WifiP2pManager.BUSY -> "BUSY"
        else -> "reason=$code"
    }
}
