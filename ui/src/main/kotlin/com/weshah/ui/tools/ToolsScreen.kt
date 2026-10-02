package com.weshah.ui.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.Socket
import java.net.InetSocketAddress
import javax.inject.Inject

// ─── ViewModel ────────────────────────────────────────────────────────────────

data class ToolsUiState(
    val pingTarget: String = "",
    val pingResults: List<String> = emptyList(),
    val isPinging: Boolean = false,
    val dnsTarget: String = "",
    val dnsResults: List<String> = emptyList(),
    val isResolvingDns: Boolean = false,
    val portScanTarget: String = "",
    val portScanPorts: String = "22,23,80,443,8080",
    val portScanResults: List<Pair<Int, Boolean>> = emptyList(),
    val isPortScanning: Boolean = false
)

@HiltViewModel
class ToolsViewModel @Inject constructor() : ViewModel() {

    private val _state = MutableStateFlow(ToolsUiState())
    val state: StateFlow<ToolsUiState> = _state.asStateFlow()

    fun setPingTarget(v: String) = _state.update { it.copy(pingTarget = v) }
    fun setDnsTarget(v: String) = _state.update { it.copy(dnsTarget = v) }
    fun setPortScanTarget(v: String) = _state.update { it.copy(portScanTarget = v) }
    fun setPortScanPorts(v: String) = _state.update { it.copy(portScanPorts = v) }

    /**
     * Android limitation: ICMP ping requires CAP_NET_RAW (not available to normal apps).
     * We use InetAddress.isReachable() which falls back to TCP echo on port 7.
     * Most modern hosts will respond as unreachable even when online.
     * A TCP probe on port 80/443 is used as a secondary check.
     * See ANDROID_LIMITATIONS.md for details.
     */
    fun ping() {
        val target = _state.value.pingTarget.trim()
        if (target.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(isPinging = true, pingResults = listOf("Pinging $target...")) }
            withContext(Dispatchers.IO) {
                val results = mutableListOf<String>()
                repeat(4) { i ->
                    val start = System.currentTimeMillis()
                    try {
                        val addr = InetAddress.getByName(target)
                        val reachable = addr.isReachable(2000)
                        val elapsed = System.currentTimeMillis() - start
                        if (reachable) {
                            results.add("Reply from ${addr.hostAddress}: time=${elapsed}ms")
                        } else {
                            // Try TCP probe
                            val tcpOk = try {
                                Socket().use { s -> s.connect(InetSocketAddress(target, 80), 1000); true }
                            } catch (e: Exception) {
                                try {
                                    Socket().use { s -> s.connect(InetSocketAddress(target, 443), 1000); true }
                                } catch (e2: Exception) { false }
                            }
                            if (tcpOk) {
                                val elapsed2 = System.currentTimeMillis() - start
                                results.add("TCP probe ${addr.hostAddress}: time=${elapsed2}ms (ICMP blocked)")
                            } else {
                                results.add("Request timeout for $target")
                            }
                        }
                    } catch (e: Exception) {
                        results.add("Error: ${e.message}")
                    }
                    _state.update { it.copy(pingResults = results.toList()) }
                    kotlinx.coroutines.delay(500)
                }
            }
            _state.update { it.copy(isPinging = false) }
        }
    }

    fun dnsLookup() {
        val target = _state.value.dnsTarget.trim()
        if (target.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(isResolvingDns = true, dnsResults = listOf("Resolving $target...")) }
            withContext(Dispatchers.IO) {
                try {
                    val addresses = InetAddress.getAllByName(target)
                    val results = addresses.map { addr ->
                        "${addr.javaClass.simpleName}: ${addr.hostAddress}"
                    }
                    _state.update { it.copy(dnsResults = results, isResolvingDns = false) }
                } catch (e: Exception) {
                    _state.update { it.copy(
                        dnsResults = listOf("Failed: ${e.message}"),
                        isResolvingDns = false
                    )}
                }
            }
        }
    }

    fun portScan() {
        val target = _state.value.portScanTarget.trim()
        if (target.isBlank()) return
        val ports = _state.value.portScanPorts
            .split(",").mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..65535 }
        if (ports.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(isPortScanning = true, portScanResults = emptyList()) }
            withContext(Dispatchers.IO) {
                val results = ports.map { port ->
                    val open = try {
                        Socket().use { s -> s.connect(InetSocketAddress(target, port), 500); true }
                    } catch (e: Exception) { false }
                    Pair(port, open)
                }
                _state.update { it.copy(portScanResults = results, isPortScanning = false) }
            }
        }
    }
}

// ─── Screen ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(viewModel: ToolsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("أدوات الشبكة", fontWeight = FontWeight.Bold) }) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PingTool(state = state, viewModel = viewModel)
            DnsLookupTool(state = state, viewModel = viewModel)
            PortScanTool(state = state, viewModel = viewModel)
            TracerouteNotice()
        }
    }
}

@Composable
private fun PingTool(state: ToolsUiState, viewModel: ToolsViewModel) {
    ToolCard(title = "Ping", icon = Icons.Default.NetworkPing) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.pingTarget,
                onValueChange = viewModel::setPingTarget,
                label = { Text("IP أو Hostname") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Button(onClick = { viewModel.ping() }, enabled = !state.isPinging) {
                if (state.isPinging) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("Ping")
            }
        }
        if (state.pingResults.isNotEmpty()) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    state.pingResults.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun DnsLookupTool(state: ToolsUiState, viewModel: ToolsViewModel) {
    ToolCard(title = "DNS Lookup", icon = Icons.Default.Dns) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.dnsTarget,
                onValueChange = viewModel::setDnsTarget,
                label = { Text("Domain أو IP") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Button(onClick = { viewModel.dnsLookup() }, enabled = !state.isResolvingDns) {
                Text("Lookup")
            }
        }
        if (state.dnsResults.isNotEmpty()) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    state.dnsResults.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun PortScanTool(state: ToolsUiState, viewModel: ToolsViewModel) {
    ToolCard(title = "Port Scan", icon = Icons.Default.Radar) {
        OutlinedTextField(value = state.portScanTarget, onValueChange = viewModel::setPortScanTarget,
            label = { Text("IP Target") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(value = state.portScanPorts, onValueChange = viewModel::setPortScanPorts,
            label = { Text("Ports (مفصولة بفاصلة)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(onClick = { viewModel.portScan() }, enabled = !state.isPortScanning,
            modifier = Modifier.fillMaxWidth()) {
            if (state.isPortScanning) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            else Text("فحص المنافذ")
        }
        if (state.portScanResults.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.portScanResults.forEach { (port, open) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (open) Icons.Default.CheckCircle else Icons.Default.Cancel,
                            null, modifier = Modifier.size(16.dp),
                            tint = if (open) com.weshah.ui.common.theme.WeshahStatusColors.Online
                                   else com.weshah.ui.common.theme.WeshahStatusColors.Offline)
                        Text("Port $port: ${if (open) "مفتوح" else "مغلق"}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun TracerouteNotice() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = MaterialTheme.shapes.large) {
        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(16.dp))
            Column {
                Text("Traceroute: NOT IMPLEMENTED",
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text("Android لا يسمح بـ Raw ICMP بدون صلاحيات Root. Traceroute يتطلب ICMP Time Exceeded أو UDP.\nبديل: استخدم أداة Ping المتوفرة فوق.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

@Composable
private fun ToolCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
                    content: @Composable ColumnScope.() -> Unit) {
    Card(shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}
