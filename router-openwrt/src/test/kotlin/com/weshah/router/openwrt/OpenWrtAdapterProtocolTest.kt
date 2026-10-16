package com.weshah.router.openwrt

import com.weshah.core.models.TimeRange
import com.weshah.core.models.VlanInfo
import com.weshah.router.api.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Protocol-level tests of OpenWrtAdapter against a scripted server that speaks weshah-agent's REST
 * contract (HTTP status + {"success":false,"error":...} bodies). The REAL agent is exercised by
 * [AgentContractTest]; these tests cover branches that are awkward to provoke on a real router.
 */
class OpenWrtAdapterProtocolTest {

    private lateinit var server: MockWebServer
    private val seen = CopyOnWriteArrayList<RecordedRequest>()
    private val routes = mutableMapOf<String, () -> MockResponse>()

    private val json = "application/json"
    private fun ok(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", json).setBody(body)
    private fun fail(code: Int, error: String) = ok("""{"success":false,"error":"$error"}""", code)

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val key = "${request.method} ${request.path}"
                return routes[key]?.invoke() ?: ok("""{"success":false,"error":"not_found"}""", 404)
            }
        }
        server.start()
        routes["POST /api/v1/auth/login"] = { ok("""{"token":"tok123","expires":4102444800}""") }
        routes["GET /api/v1/capabilities"] = { ok(CAPS) }
        routes["POST /api/v1/vlans/confirm"] = { ok("""{"success":true,"error":null,"note":"nothing_pending"}""") }
        routes["POST /api/v1/restore/confirm"] = { ok("""{"success":true,"error":null,"note":"nothing_pending"}""") }
        routes["GET /api/v1/status"] = { ok(STATUS) }
    }

    @After fun stop() = server.shutdown()

    private fun adapter() = OpenWrtAdapter(object : CredentialProvider { override fun getCredential(keyAlias: String) = "pw" }, server.port)

    private fun connected(): OpenWrtAdapter = adapter().also {
        val r = runBlocking { it.connect(RouterConnectionConfig("127.0.0.1", server.port, "root", useHttps = false)) }
        assertTrue("connect: $r", r is RouterConnectionResult.Success)
    }

    private fun count(method: String, path: String) = seen.count { it.method == method && it.path == path }
    private fun err(r: RouterResult<*>) = r as RouterResult.Error

    // ── connection ────────────────────────────────────────────────────────────

    @Test fun `connect logs in with the stored credential and detects capabilities`() {
        val a = connected()
        val login = seen.first { it.path == "/api/v1/auth/login" }
        assertTrue(login.body.readUtf8().contains("\"password\":\"pw\""))
        assertTrue(a.getCapabilities().weshahAgent)
        assertTrue(a.getCapabilities().vlanManagement)
        assertFalse(a.getCapabilities().bandwidthControl)
        assertTrue(a.getCapabilities().configRestore)
    }

    @Test fun `requests carry the bearer token`() {
        val a = connected()
        runBlocking { a.getSystemInfo() }
        assertEquals("Bearer tok123", seen.last { it.path == "/api/v1/status" }.getHeader("Authorization"))
    }

    @Test fun `connecting confirms pending vlan changes and restores`() {
        connected()
        assertEquals(1, count("POST", "/api/v1/vlans/confirm"))
        assertEquals(1, count("POST", "/api/v1/restore/confirm"))
    }

    // ── error mapping ─────────────────────────────────────────────────────────

    @Test fun `501 maps to NOT_SUPPORTED with the agent's reason`() {
        routes["POST /api/v1/devices/aa:bb:cc:00:00:01/speed"] = { fail(501, "speed_limit_unsupported:tc_missing") }
        val e = err(runBlocking { adapterWithBandwidth().setClientSpeedLimit("aa:bb:cc:00:00:01", 1024, 512) })
        assertEquals(RouterErrorCode.NOT_SUPPORTED, e.code)
        assertEquals("speed_limit_unsupported:tc_missing", e.message)
    }

    private fun adapterWithBandwidth(): OpenWrtAdapter {
        routes["GET /api/v1/capabilities"] = { ok(CAPS.replace("\"bandwidthControl\":false", "\"bandwidthControl\":true")) }
        return connected()
    }

    @Test fun `400 maps to VALIDATION_ERROR and 409 to ROUTER_ERROR keeping the message`() {
        routes["POST /api/v1/dhcp/static"] = { fail(400, "ip_outside_lan_subnet") }
        val a = connected()
        val v = err(runBlocking { a.createStaticLease("aa:bb:cc:00:00:01", "10.9.9.9", null) })
        assertEquals(RouterErrorCode.VALIDATION_ERROR, v.code); assertEquals("ip_outside_lan_subnet", v.message)
        routes["POST /api/v1/dhcp/static"] = { fail(409, "ip_already_reserved_for:aa:bb:cc:00:00:02") }
        val c = err(runBlocking { a.createStaticLease("aa:bb:cc:00:00:01", "192.168.1.60", null) })
        assertEquals(RouterErrorCode.ROUTER_ERROR, c.code); assertTrue(c.message.startsWith("ip_already_reserved_for"))
    }

    @Test fun `401 maps to AUTHENTICATION_FAILED`() {
        val a = connected()
        routes["POST /api/v1/devices/aa:bb:cc:00:00:01/unblock"] = { fail(401, "unauthorized") }
        assertEquals(RouterErrorCode.AUTHENTICATION_FAILED, err(runBlocking { a.unblockClient("aa:bb:cc:00:00:01") }).code)
    }

    @Test fun `non JSON error bodies still produce a readable message`() {
        val a = connected()
        routes["POST /api/v1/devices/aa:bb:cc:00:00:01/disconnect"] = { MockResponse().setResponseCode(502).setBody("Bad gateway") }
        val e = err(runBlocking { a.disconnectClient("aa:bb:cc:00:00:01") })
        assertEquals(RouterErrorCode.ROUTER_ERROR, e.code); assertEquals("Bad gateway", e.message)
    }

    // ── real state readers (no more stubs) ────────────────────────────────────

    @Test fun `block status reflects firewall state including expiry`() {
        routes["GET /api/v1/devices/aa:bb:cc:00:00:01/block"] = {
            ok("""{"mac":"aa:bb:cc:00:00:01","isBlocked":true,"manual":true,"scheduled":false,"expiresAt":1893456000000,"ruleId":"weshah_block_aa_bb_cc_00_00_01"}""")
        }
        val s = (runBlocking { connected().getClientBlockStatus("aa:bb:cc:00:00:01") } as RouterResult.Success).data!!
        assertTrue(s.isBlocked); assertEquals(1893456000000L, s.expiresAt); assertEquals("weshah_block_aa_bb_cc_00_00_01", s.ruleId)
    }

    @Test fun `speed limit reads what the router holds and distinguishes stored from applied`() {
        val a = connected()
        routes["GET /api/v1/devices/aa:bb:cc:00:00:01/speed"] = {
            ok("""{"mac":"aa:bb:cc:00:00:01","source":"manual","downloadKbps":5120,"uploadKbps":1024,"active":true,"applied":true,"reason":null,"appliedAt":1700000000000}""")
        }
        val on = (runBlocking { a.getClientSpeedLimit("aa:bb:cc:00:00:01") } as RouterResult.Success).data!!
        assertEquals(5120L, on.downloadKbps); assertEquals(1024L, on.uploadKbps); assertTrue(on.isActive)
        routes["GET /api/v1/devices/aa:bb:cc:00:00:01/speed"] = {
            ok("""{"mac":"aa:bb:cc:00:00:01","source":"manual","downloadKbps":5120,"uploadKbps":null,"active":true,"applied":false,"reason":"conflicting_qdisc:eth1","appliedAt":null}""")
        }
        assertFalse((runBlocking { a.getClientSpeedLimit("aa:bb:cc:00:00:01") } as RouterResult.Success).data!!.isActive)
        routes["GET /api/v1/devices/aa:bb:cc:00:00:01/speed"] = {
            ok("""{"mac":"aa:bb:cc:00:00:01","source":"none","downloadKbps":null,"uploadKbps":null,"active":false,"applied":false,"reason":null,"appliedAt":null}""")
        }
        assertNull((runBlocking { a.getClientSpeedLimit("aa:bb:cc:00:00:01") } as RouterResult.Success).data)
    }

    // ── schedule ──────────────────────────────────────────────────────────────

    @Test fun `schedule is sent as ISO days with the speed limits`() {
        routes["POST /api/v1/devices/aa:bb:cc:00:00:01/schedule"] = { ok("""{"success":true,"error":null}""") }
        val a = connected()
        val r = runBlocking {
            a.setClientSchedule("aa:bb:cc:00:00:01",
                AccessSchedule("aa:bb:cc:00:00:01", listOf(com.weshah.router.api.TimeRange(8, 0, 22, 0, setOf(5, 1, 3))), 2048, 512))
        }
        assertTrue(r is RouterResult.Success)
        val body = seen.last { it.path?.endsWith("/schedule") == true }.body.readUtf8()
        assertTrue(body, body.contains("\"daysOfWeek\":[1,3,5]") && body.contains("\"downloadKbps\":2048") && body.contains("\"startHour\":8"))
    }

    // ── VLAN commit-confirm ───────────────────────────────────────────────────

    private fun vlan() = VlanInfo(20, "Guests", "192.168.20.1", "192.168.20.0/24", true, true, false, null, emptyList(), listOf("3"))

    @Test fun `vlan create confirms once the router answers again`() {
        routes["POST /api/v1/vlans"] = { ok("""{"success":true,"error":null,"confirmRequired":true,"confirmWithinSeconds":30}""") }
        val a = connected(); seen.clear()
        val r = runBlocking { a.createVlan(vlan()) }
        assertTrue("$r", r is RouterResult.Success)
        assertEquals(1, count("POST", "/api/v1/vlans/confirm"))
        assertTrue("confirm must come after the reachability probe",
            seen.indexOfFirst { it.path == "/api/v1/status" } < seen.indexOfFirst { it.path == "/api/v1/vlans/confirm" })
    }

    @Test fun `vlan change is NOT confirmed when the router stays unreachable so it rolls back`() {
        routes["POST /api/v1/vlans"] = { ok("""{"success":true,"error":null,"confirmRequired":true,"confirmWithinSeconds":15}""") }
        val a = connected()
        routes["GET /api/v1/status"] = { MockResponse().setResponseCode(503) }
        seen.clear()
        val e = err(runBlocking { a.createVlan(vlan()) })
        assertEquals(RouterErrorCode.NETWORK_ERROR, e.code)
        assertEquals(0, count("POST", "/api/v1/vlans/confirm"))
    }

    @Test fun `rejected vlan is reported and never confirmed`() {
        routes["POST /api/v1/vlans"] = { fail(409, "subnet_overlaps:192.168.1.1/24") }
        val a = connected(); seen.clear()
        val e = err(runBlocking { a.createVlan(vlan()) })
        assertTrue(e.message.startsWith("subnet_overlaps"))
        assertEquals(0, count("POST", "/api/v1/vlans/confirm"))
    }

    // ── restore ───────────────────────────────────────────────────────────────

    private fun validation(valid: Boolean = true, manifest: Boolean = true, match: String = "true") =
        """{"success":true,"error":null,"valid":$valid,"sha256":"abc123","sizeBytes":10,"files":3,"unpackedBytes":9,"hasManifest":$manifest,"boardMatch":$match,"backupBoard":"tl-wr841n-v11","routerBoard":"tl-wr841n-v11","warnings":[]}"""

    @Test fun `restore validates first and applies by sha256`() {
        routes["POST /api/v1/restore/validate"] = { ok(validation()) }
        routes["POST /api/v1/restore/apply"] = { ok("""{"success":true,"error":null,"rebootRequired":true,"rebootScheduled":true}""") }
        val a = connected(); seen.clear()
        assertTrue(runBlocking { a.restoreConfigBackup(byteArrayOf(1, 2, 3)) } is RouterResult.Success)
        val upload = seen.first { it.path == "/api/v1/restore/validate" }
        assertEquals("application/octet-stream", upload.getHeader("Content-Type"))
        assertArrayEquals(byteArrayOf(1, 2, 3), upload.body.readByteArray())
        val apply = seen.first { it.path == "/api/v1/restore/apply" }.body.readUtf8()
        assertTrue(apply, apply.contains("\"sha256\":\"abc123\"") && apply.contains("\"reboot\":true") && apply.contains("\"allowUnverified\":false"))
    }

    @Test fun `restore refuses another board's backup without ever calling apply`() {
        routes["POST /api/v1/restore/validate"] = { ok(validation(match = "false")) }
        val e = err(runBlocking { connected().restoreConfigBackup(byteArrayOf(1)) })
        assertEquals(RouterErrorCode.VALIDATION_ERROR, e.code)
        assertEquals(0, count("POST", "/api/v1/restore/apply"))
    }

    @Test fun `restore refuses archives without a board manifest`() {
        routes["POST /api/v1/restore/validate"] = { ok(validation(manifest = false, match = "null")) }
        val e = err(runBlocking { connected().restoreConfigBackup(byteArrayOf(1)) })
        assertEquals(RouterErrorCode.VALIDATION_ERROR, e.code)
        assertEquals(0, count("POST", "/api/v1/restore/apply"))
    }

    @Test fun `restore surfaces the agent's rejection of a hostile archive`() {
        routes["POST /api/v1/restore/validate"] = { fail(400, "unsafe_path:tar: removing leading '/' from member names") }
        val e = err(runBlocking { connected().restoreConfigBackup(byteArrayOf(1)) })
        assertEquals(RouterErrorCode.VALIDATION_ERROR, e.code); assertTrue(e.message.startsWith("unsafe_path"))
        assertEquals(0, count("POST", "/api/v1/restore/apply"))
    }

    companion object {
        const val CAPS = """{"agentVersion":"2.1.0","firewallBackend":"ipt","bandwidthControl":false,"perClientTraffic":true,"portStats":true,"cableDiagnosticsTdr":false,"cableDiagnosticsBasic":true,"wifiRssiPerClient":true,"vlanManagement":true,"vlanMode":"swconfig","multiWan":false,"dhcpStaticLeases":true,"scheduling":true,"lldpNeighbors":false,"configBackup":true,"configRestore":true,"hotspot":false,"temperature":false,"phyDriver":"UNKNOWN","reasons":{}}"""
        const val STATUS = """{"model":"m","firmware":"OpenWrt 18.06.9","kernel":"4.9","hostname":"r","uptimeSeconds":10,"cpuPercent":1.5,"ramTotalKb":1000,"ramUsedKb":100,"temperatureCelsius":null,"load1m":0.1,"load5m":0.1,"load15m":0.1,"wanState":"up","wanIp":"1.2.3.4","agentVersion":"2.1.0"}"""
    }
}
