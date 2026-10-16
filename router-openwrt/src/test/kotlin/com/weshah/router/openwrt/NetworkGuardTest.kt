package com.weshah.router.openwrt

import com.weshah.router.api.RouterConnectionConfig
import com.weshah.router.api.RouterConnectionResult
import com.weshah.router.api.RouterErrorCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NetworkGuardTest {

    @Test fun `private ranges are accepted`() {
        listOf("192.168.1.1", "192.168.88.1", "10.0.0.1", "10.255.255.254", "172.16.0.1", "172.31.255.1",
            "169.254.1.1", "127.0.0.1", "::1", "fd12:3456::1", "fc00::1", "fe80::1", "[fe80::1]").forEach {
            assertTrue(it, NetworkGuard.isPrivateLiteral(it))
        }
    }

    @Test fun `public addresses and host names are refused`() {
        listOf("8.8.8.8", "172.15.0.1", "172.32.0.1", "192.169.1.1", "11.0.0.1", "1.1.1.1",
            "router.example.com", "localhost", "999.1.1.1", "192.168.1", "192.168.1.1.1", "2001:db8::1", "").forEach {
            assertFalse(it, NetworkGuard.isPrivateLiteral(it))
        }
    }

    @Test fun `connect to a public address fails before any request is made`() {
        val a = OpenWrtAdapter(object : CredentialProvider { override fun getCredential(keyAlias: String) = "pw" })
        val r = runBlocking { a.connect(RouterConnectionConfig("8.8.8.8", 80, "root", useHttps = false)) }
        assertTrue(r is RouterConnectionResult.Failure)
        assertEquals(RouterErrorCode.VALIDATION_ERROR, (r as RouterConnectionResult.Failure).code)
    }
}
