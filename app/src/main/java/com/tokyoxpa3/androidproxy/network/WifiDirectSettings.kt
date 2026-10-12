package com.tokyoxpa3.androidproxy.network

import android.content.Context
import java.security.SecureRandom

/**
 * [Wi-Fi Direct] Group 設定的持久化與驗證（存於 "proxy_config"）。
 *
 * SSID 一律為 "DIRECT-xy-<suffix>"：framework 要求 GO 的 SSID 以 "DIRECT-" 加兩個
 * 英數字開頭。xy 首次使用時隨機產生並保存，之後 SSID 固定，客戶端不必重新輸入密碼。
 */
object WifiDirectSettings {

    const val PREF_ENABLED = "wfd_enabled"
    const val PREF_SUFFIX = "wfd_ssid_suffix"
    const val PREF_PASSPHRASE = "wfd_passphrase"
    const val PREF_BAND = "wfd_band"
    private const val PREF_TAG = "wfd_ssid_tag"

    const val DEFAULT_SUFFIX = "5GProxy"
    const val MAX_SSID_BYTES = 32
    const val MIN_PASS = 8
    const val MAX_PASS = 63

    private const val ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    // 密碼預設值避開易混淆字元（0/O、1/l/I），方便手動輸入
    private const val PASS_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
    private val random = SecureRandom()

    private fun prefs(context: Context) =
        context.getSharedPreferences("proxy_config", Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(PREF_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(PREF_ENABLED, enabled).apply()
    }

    fun suffix(context: Context): String =
        prefs(context).getString(PREF_SUFFIX, DEFAULT_SUFFIX) ?: DEFAULT_SUFFIX

    fun setSuffix(context: Context, value: String) {
        prefs(context).edit().putString(PREF_SUFFIX, value).apply()
    }

    /** 首次讀取時產生隨機密碼並保存。 */
    fun passphrase(context: Context): String {
        val p = prefs(context)
        p.getString(PREF_PASSPHRASE, null)?.let { return it }
        val generated = randomString(PASS_CHARS, 10)
        p.edit().putString(PREF_PASSPHRASE, generated).apply()
        return generated
    }

    fun setPassphrase(context: Context, value: String) {
        prefs(context).edit().putString(PREF_PASSPHRASE, value).apply()
    }

    fun band(context: Context): WifiDirectGroupManager.Band {
        val name = prefs(context).getString(PREF_BAND, WifiDirectGroupManager.Band.GHZ_5.name)
        return WifiDirectGroupManager.Band.values().firstOrNull { it.name == name }
            ?: WifiDirectGroupManager.Band.GHZ_5
    }

    fun setBand(context: Context, band: WifiDirectGroupManager.Band) {
        prefs(context).edit().putString(PREF_BAND, band.name).apply()
    }

    private fun ssidTag(context: Context): String {
        val p = prefs(context)
        p.getString(PREF_TAG, null)?.let { if (it.length == 2) return it }
        val tag = randomString(ALNUM, 2)
        p.edit().putString(PREF_TAG, tag).apply()
        return tag
    }

    fun ssid(context: Context): String = buildSsid(ssidTag(context), suffix(context))

    fun config(context: Context) = WifiDirectGroupManager.Config(
        ssid = ssid(context),
        passphrase = passphrase(context),
        band = band(context)
    )

    // ---------- 純函式（可單元測試） ----------

    /**
     * 組出 "DIRECT-<tag>-<suffix>"，suffix 依 UTF-8 位元組截斷到總長 ≤ 32 bytes
     * （不會把多位元組字元切成一半）。suffix 為空時只回 "DIRECT-<tag>"。
     */
    fun buildSsid(tag: String, suffix: String): String {
        val base = "DIRECT-$tag"
        val clean = suffix.trim()
        if (clean.isEmpty()) return base
        val prefix = "$base-"
        var budget = MAX_SSID_BYTES - prefix.toByteArray(Charsets.UTF_8).size
        val sb = StringBuilder(prefix)
        var i = 0
        while (i < clean.length) {
            val cp = clean.codePointAt(i)
            val chunk = String(Character.toChars(cp))
            val n = chunk.toByteArray(Charsets.UTF_8).size
            if (n > budget) break
            sb.append(chunk)
            budget -= n
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /** WPA2 passphrase：8–63 個可列印 ASCII 字元。 */
    fun isValidPassphrase(value: String): Boolean =
        value.length in MIN_PASS..MAX_PASS && value.all { it.code in 0x20..0x7E }

    private fun randomString(chars: String, len: Int): String =
        buildString(len) { repeat(len) { append(chars[random.nextInt(chars.length)]) } }
}
