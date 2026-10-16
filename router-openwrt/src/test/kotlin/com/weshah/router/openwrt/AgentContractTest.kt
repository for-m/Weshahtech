package com.weshah.router.openwrt

import com.weshah.core.models.VlanInfo
import com.weshah.router.api.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Contract test against a REAL weshah-agent (test/target/services.sh start). The adapter, its Moshi
 * DTOs and the agent's shell JSON must agree byte for byte. Skipped unless WESHAH_AGENT_PORT is set.
 * The fixture router is the one built by test/target/setup.sh (TL-WR841N v11, OpenWrt 18.06.9).
 */
class AgentContractTest {

    private val port = System.getenv("WESHAH_AGENT_PORT")?.toIntOrNull()
    private val password = System.getenv("WESHAH_AGENT_PASSWORD") ?: "weshah-test"
    private val mac = "aa:bb:cc:00:00:01"
    private lateinit var a: OpenWrtAdapter

    private fun <T> ok(r: RouterResult<T>): T {
        if (r is RouterResult.Error) fail("${r.code}: ${r.message}")
        return (r as RouterResult.Success).data
    }
    private fun config() = RouterConnectionConfig("127.0.0.1", port!!, "root", useHttps = false)

    @Before fun connect() {
        assumeTrue("set WESHAH_AGENT_PORT to run against a live agent", port != null)
        a = OpenWrtAdapter(object : CredentialProvider { override fun getCredential(keyAlias: String) = password }, port!!)
        val r = runBlocking { a.connect(config()) }
        assertTrue("connect: $r", r is RouterConnectionResult.Success)
    }

    @After fun cleanup() {
        if (port == null) return
        runBlocking {
            a.unblockClient(mac); a.removeClientSchedule(mac)
            a.removeStaticLease("aa:bb:cc:00:00:02"); a.deleteVlan(40)
        }
    }

    @Test fun `wrong password is an authentication failure`() {
        val other = OpenWrtAdapter(object : CredentialProvider { override fun getCredential(keyAlias: String) = "nope" }, port!!)
        val r = runBlocking { other.connect(config()) }
        assertTrue("$r", r is RouterConnectionResult.Failure)
        assertEquals(RouterErrorCode.AUTHENTICATION_FAILED, (r as RouterConnectionResult.Failure).code)
    }

    @Test fun `capabilities reflect the stock 18-06 image honestly`() {
        val c = a.getCapabilities()
        assertTrue(c.weshahAgent); assertEquals("2.1.0", c.agentVersion)
        assertTrue(c.vlanManagement); assertTrue(c.configBackup); assertTrue(c.configRestore); assertTrue(c.perClientTraffic)
        assertFalse("no tc/sched modules on this image", c.bandwidthControl)
        assertFalse(c.hotspot); assertFalse(c.lldpNeighbors); assertFalse(c.temperature)
    }

    @Test fun `system info interfaces and health parse`() = runBlocking {
        val info = ok(a.getSystemInfo())
        assertEquals("TP-Link TL-WR841N/ND v11", info.model); assertTrue(info.firmware!!.contains("18.06.9"))
        assertEquals(setOf("br-lan", "eth0", "eth1", "wlan0"), ok(a.getInterfaces()).map { it.name }.toSet())
        assertTrue(ok(a.getWanStatus()).isConnected)
        val h = ok(a.runHealthCheck())
        assertEquals(com.weshah.core.models.WanHealthState.CONNECTED, h.wanState)
        assertEquals(11.962f, h.internetLatencyMs!!, 0.01f)
    }

    @Test fun `devices wifi and ports parse`() = runBlocking {
        val clients = ok(a.getConnectedClients())
        assertEquals(setOf("aa:bb:cc:00:00:01", "aa:bb:cc:00:00:02", "aa:bb:cc:00:00:09"), clients.map { it.macAddress }.toSet())
        val wifi = ok(a.getWifiClients())
        assertEquals(-52, wifi.first { it.macAddress == mac }.rssi)
        assertEquals("Weshah-Test", ok(a.getWifiNetworks()).single().ssid)
        val ports = ok(a.getPortStats()).associateBy { it.portId }
        assertTrue(ports.getValue("switch0:1").isUp); assertEquals(100, ports.getValue("switch0:1").speedMbps)
        assertFalse(ports.getValue("switch0:2").isUp)
        val diag = ok(a.runCableDiagnostics("eth1"))
        assertNull("no fake length without TDR", diag.estimatedLengthMeters)
    }

    @Test fun `block status is the real firewall state`() = runBlocking {
        assertFalse(ok(a.getClientBlockStatus(mac))!!.isBlocked)
        ok(a.blockClient(mac, System.currentTimeMillis() + 3_600_000))
        val s = ok(a.getClientBlockStatus(mac))!!
        assertTrue(s.isBlocked); assertNotNull(s.expiresAt); assertEquals("weshah_block_aa_bb_cc_00_00_01", s.ruleId)
        ok(a.unblockClient(mac))
        assertFalse(ok(a.getClientBlockStatus(mac))!!.isBlocked)
        val bad = a.blockClient("not-a-mac")
        assertEquals(RouterErrorCode.VALIDATION_ERROR, (bad as RouterResult.Error).code)
    }

    @Test fun `schedule is accepted and enforced`() = runBlocking {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        val hourNow = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val later = (hourNow + 2) % 24
        val sched = AccessSchedule(mac, listOf(TimeRange(later, 0, (later + 1) % 24, 0, emptySet())), null, null)
        ok(a.setClientSchedule(mac, sched))
        assertTrue("outside the allowed window the device must be blocked", ok(a.getClientBlockStatus(mac))!!.isBlocked)
        ok(a.removeClientSchedule(mac))
        assertFalse(ok(a.getClientBlockStatus(mac))!!.isBlocked)
        val invalid = a.setClientSchedule(mac, AccessSchedule(mac, listOf(TimeRange(25, 0, 3, 0, emptySet())), null, null))
        assertEquals(RouterErrorCode.VALIDATION_ERROR, (invalid as RouterResult.Error).code)
    }

    @Test fun `static leases round trip and conflicts`() = runBlocking {
        ok(a.createStaticLease("aa:bb:cc:00:00:02", "192.168.1.60", "sara-laptop"))
        assertEquals("192.168.1.60", ok(a.getStaticLeases()).single { it.macAddress == "aa:bb:cc:00:00:02" }.ipAddress)
        val dup = a.createStaticLease("aa:bb:cc:00:00:03", "192.168.1.60", null)
        assertTrue((dup as RouterResult.Error).message.startsWith("ip_already_reserved_for"))
        ok(a.removeStaticLease("aa:bb:cc:00:00:02"))
    }

    @Test fun `speed limit is honestly unsupported without tc`() = runBlocking {
        val r = a.setClientSpeedLimit(mac, 1024, 512)
        assertEquals(RouterErrorCode.NOT_SUPPORTED, (r as RouterResult.Error).code)
        assertTrue(r.message, r.message.contains("tc_missing"))
        assertNull(ok(a.getClientSpeedLimit(mac)))
        ok(a.removeClientSpeedLimit(mac)) // removing a limit that does not exist is a harmless no-op
    }

    @Test fun `vlan create is confirmed by the adapter and delete restores the ports`() = runBlocking {
        val vlan = VlanInfo(40, "Lab", "192.168.40.1", "192.168.40.0/24", true, false, false, null, emptyList(), listOf("4"))
        val before = ok(a.getVlans()).first { it.vlanId == 1 }.untaggedPorts.toSet()
        assertTrue(before.contains("4"))
        ok(a.createVlan(vlan))
        val after = ok(a.getVlans())
        assertEquals(listOf("4"), after.first { it.vlanId == 40 }.untaggedPorts)
        assertFalse("port moved out of VLAN 1", after.first { it.vlanId == 1 }.untaggedPorts.contains("4"))
        // the adapter confirmed => nothing is pending any more
        assertTrue(rawPost("vlans/confirm").contains("nothing_pending"))
        val clash = a.createVlan(vlan.copy(vlanId = 41, subnet = "192.168.1.0/24", gateway = "192.168.1.9", untaggedPorts = listOf("2")))
        assertTrue((clash as RouterResult.Error).message.startsWith("subnet_overlaps"))
        ok(a.deleteVlan(40))
        assertEquals(before, ok(a.getVlans()).first { it.vlanId == 1 }.untaggedPorts.toSet())
    }

    @Test fun `backup restore round trip is validated by the agent and confirmed on reconnect`() = runBlocking {
        val backup = ok(a.createConfigBackup())
        assertTrue(backup.size > 100)
        ok(a.restoreConfigBackup(backup))
        // the router would now reboot; reconnecting (what the app does) confirms the restore
        val again = OpenWrtAdapter(object : CredentialProvider { override fun getCredential(keyAlias: String) = password }, port!!)
        assertTrue(runBlocking { again.connect(config()) } is RouterConnectionResult.Success)
        assertTrue(rawPost("restore/confirm").contains("nothing_pending"))
        val junk = a.restoreConfigBackup(ByteArray(2000) { 7 })
        assertEquals(RouterErrorCode.VALIDATION_ERROR, (junk as RouterResult.Error).code)
    }

    private fun rawPost(path: String): String {
        val token = System.getenv("WESHAH_AGENT_TOKEN") ?: "0123456789abcdef0123456789abcdef0123456789abcdef"
        val req = Request.Builder().url("http://127.0.0.1:$port/api/v1/$path").header("Authorization", "Bearer $token")
            .post(ByteArray(0).toRequestBody()).build()
        return OkHttpClient().newCall(req).execute().use { it.body!!.string() }
    }
}
