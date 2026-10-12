package com.tokyoxpa3.androidproxy.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiDirectSettingsTest {

    @Test
    fun buildSsid_basic() {
        assertEquals("DIRECT-Ab-5GProxy", WifiDirectSettings.buildSsid("Ab", "5GProxy"))
    }

    @Test
    fun buildSsid_emptySuffix() {
        assertEquals("DIRECT-Ab", WifiDirectSettings.buildSsid("Ab", "   "))
    }

    @Test
    fun buildSsid_truncatesTo32Bytes() {
        val ssid = WifiDirectSettings.buildSsid("Ab", "x".repeat(40))
        assertEquals(32, ssid.toByteArray(Charsets.UTF_8).size)
        assertTrue(ssid.startsWith("DIRECT-Ab-"))
    }

    @Test
    fun buildSsid_doesNotSplitMultibyte() {
        // "DIRECT-Ab-" = 10 bytes，剩 22 bytes；한글 1 字 = 3 bytes → 最多 7 字
        val ssid = WifiDirectSettings.buildSsid("Ab", "가".repeat(10))
        assertEquals("DIRECT-Ab-" + "가".repeat(7), ssid)
        assertTrue(ssid.toByteArray(Charsets.UTF_8).size <= 32)
    }

    @Test
    fun passphraseValidation() {
        assertTrue(WifiDirectSettings.isValidPassphrase("12345678"))
        assertTrue(WifiDirectSettings.isValidPassphrase("a".repeat(63)))
        assertFalse(WifiDirectSettings.isValidPassphrase("1234567"))
        assertFalse(WifiDirectSettings.isValidPassphrase("a".repeat(64)))
        assertFalse(WifiDirectSettings.isValidPassphrase("비밀번호비밀번호"))
    }
}
