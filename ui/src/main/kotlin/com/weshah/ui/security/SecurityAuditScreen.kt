package com.weshah.ui.security

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weshah.domain.repository.DeviceRepository
import com.weshah.domain.repository.RouterConnectionState
import com.weshah.domain.repository.RouterRepository
import com.weshah.router.api.RouterErrorCode
import com.weshah.router.api.RouterResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject

// ─── Models ──────────────────────────────────────────────────────────────────

enum class AuditSeverity { HIGH, MEDIUM, LOW, INFO }

data class AuditFinding(
    val id: String,
    val title: String,
    val detail: String,
    val severity: AuditSeverity,
    val icon: ImageVector = Icons.Default.Warning,
    val affectedCount: Int = 0
)

data class SecurityAuditState(
    val findings: List<AuditFinding> = emptyList(),
    val isRunning: Boolean = false,
    val lastRunMs: Long = 0L,
    val notConnected: Boolean = false
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

@HiltViewModel
class SecurityAuditViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val routerRepository: RouterRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SecurityAuditState(isRunning = true))
    val state: StateFlow<SecurityAuditState> = _state.asStateFlow()

    init { runAudit() }

    fun runAudit() {
        viewModelScope.launch {
            val connState = routerRepository.connectionState.value
            if (connState != RouterConnectionState.CONNECTED) {
                _state.update { it.copy(isRunning = false, notConnected = true, findings = emptyList()) }
                return@launch
            }
            _state.update { it.copy(isRunning = true, notConnected = false) }

            val findings = mutableListOf<AuditFinding>()

            // 1. Devices online with no subscriber assigned
            val allDevices = deviceRepository.getAllDevices().first()
            val unassigned = allDevices.filter { it.isOnline && it.associatedSubscriberId == null }
            if (unassigned.isNotEmpty()) {
                findings.add(AuditFinding(
                    id = "unassigned_devices",
                    title = "أجهزة غير مرتبطة بمشترك",
                    detail = "توجد ${unassigned.size} جهاز متصل لا يرتبط بأي مشترك مسجّل. قد تكون أجهزة غير مصرّحة.",
                    severity = AuditSeverity.MEDIUM,
                    icon = Icons.Default.PersonOff,
                    affectedCount = unassigned.size
                ))
            }

            // 2. Blocked devices that are still appearing as online
            val blockedOnline = allDevices.filter { it.isBlocked && it.isOnline }
            if (blockedOnline.isNotEmpty()) {
                findings.add(AuditFinding(
                    id = "blocked_online",
                    title = "أجهزة محجوبة لا تزال متصلة",
                    detail = "توجد ${blockedOnline.size} جهاز مشار إليه كمحجوب لكنه ظاهر متصل. قد يكون فلتر الراوتر لم يُطبّق بعد.",
                    severity = AuditSeverity.HIGH,
                    icon = Icons.Default.Block,
                    affectedCount = blockedOnline.size
                ))
            }

            // 3. New devices seen in the last 24 h with no subscriber
            val last24h = System.currentTimeMillis() - 86_400_000L
            val newUnknown = allDevices.filter {
                it.firstSeen >= last24h && it.associatedSubscriberId == null
            }
            if (newUnknown.isNotEmpty()) {
                findings.add(AuditFinding(
                    id = "new_unknown_devices",
                    title = "أجهزة جديدة غير معروفة (24 ساعة)",
                    detail = "اتصل ${newUnknown.size} جهاز جديد لأول مرة خلال آخر 24 ساعة ولا يرتبط بمشترك.",
                    severity = AuditSeverity.HIGH,
                    icon = Icons.Default.DeviceUnknown,
                    affectedCount = newUnknown.size
                ))
            }

            // 4. Probe management ports on the router gateway
            val routerInfo = routerRepository.routerInfo.value
            val gatewayIp = routerInfo?.ipAddress
            if (gatewayIp != null) {
                val exposedPorts = mutableListOf<Int>()
                withContext(Dispatchers.IO) {
                    val dangerousPorts = listOf(23, 8080, 8443, 9000)
                    dangerousPorts.forEach { port ->
                        try {
                            Socket().use { s ->
                                s.connect(InetSocketAddress(gatewayIp, port), 600)
                                exposedPorts.add(port)
                            }
                        } catch (_: Exception) {}
                    }
                }
                if (exposedPorts.isNotEmpty()) {
                    findings.add(AuditFinding(
                        id = "exposed_ports",
                        title = "منافذ إدارة مكشوفة على الراوتر",
                        detail = "المنافذ ${exposedPorts.joinToString(", ")} مفتوحة على ${gatewayIp}. Telnet (23) وAdmin panels (8080/8443) تُعدّ مخاطر أمنية.",
                        severity = AuditSeverity.HIGH,
                        icon = Icons.Default.LockOpen,
                        affectedCount = exposedPorts.size
                    ))
                }
            }

            // 5. Port errors from router port stats
            when (val portResult = routerRepository.getPortStats()) {
                is RouterResult.Success -> {
                    val portsWithErrors = portResult.data.filter { it.errors > 0 }
                    if (portsWithErrors.isNotEmpty()) {
                        findings.add(AuditFinding(
                            id = "port_errors",
                            title = "منافذ فيزيائية بها أخطاء",
                            detail = "${portsWithErrors.size} منفذ يُسجّل أخطاء إرسال/استقبال. قد تشير إلى كبل تالف أو جهاز معيب.",
                            severity = AuditSeverity.MEDIUM,
                            icon = Icons.Default.SettingsEthernet,
                            affectedCount = portsWithErrors.size
                        ))
                    }
                }
                is RouterResult.Error -> {
                    if (portResult.code != RouterErrorCode.NOT_SUPPORTED) {
                        findings.add(AuditFinding(
                            id = "port_stats_failed",
                            title = "تعذّر قراءة إحصائيات المنافذ",
                            detail = portResult.message ?: "خطأ غير معروف عند قراءة إحصائيات المنافذ.",
                            severity = AuditSeverity.LOW,
                            icon = Icons.Default.ErrorOutline
                        ))
                    }
                }
            }

            // 6. WiFi clients with very weak signal (<= -80 dBm)
            when (val wifiResult = routerRepository.getWifiClients()) {
                is RouterResult.Success -> {
                    val weakSignal = wifiResult.data.filter { (it.rssi ?: 0) <= -80 }
                    if (weakSignal.isNotEmpty()) {
                        findings.add(AuditFinding(
                            id = "weak_wifi_clients",
                            title = "عملاء WiFi بإشارة ضعيفة جداً",
                            detail = "${weakSignal.size} جهاز WiFi بقوة إشارة ≤ -80 dBm. قد يكون مصدر إشارة بعيداً أو طاقة إرسال غير طبيعية.",
                            severity = AuditSeverity.LOW,
                            icon = Icons.Default.SignalWifi1Bar,
                            affectedCount = weakSignal.size
                        ))
                    }
                }
                is RouterResult.Error -> { /* NOT_SUPPORTED is fine — skip silently */ }
            }

            // 7. WAN connectivity
            when (val wan = routerRepository.getWanStatus()) {
                is RouterResult.Success -> {
                    if (!wan.data.isConnected) {
                        findings.add(AuditFinding(
                            id = "wan_down",
                            title = "الاتصال بالإنترنت منقطع",
                            detail = "WAN غير متصل. المشتركون لا يملكون وصولاً للإنترنت حالياً.",
                            severity = AuditSeverity.HIGH,
                            icon = Icons.Default.CloudOff
                        ))
                    }
                }
                is RouterResult.Error -> { /* skip */ }
            }

            if (findings.isEmpty()) {
                findings.add(AuditFinding(
                    id = "clean",
                    title = "لا توجد مشكلات أمنية",
                    detail = "اجتازت الشبكة جميع فحوصات التدقيق الأمني. استمر في المراقبة بصفة منتظمة.",
                    severity = AuditSeverity.INFO,
                    icon = Icons.Default.VerifiedUser
                ))
            }

            _state.update { it.copy(
                findings = findings.sortedByDescending { f -> f.severity.ordinal.unaryMinus() },
                isRunning = false,
                lastRunMs = System.currentTimeMillis()
            )}
        }
    }
}

// ─── Screen ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityAuditScreen(
    onBack: () -> Unit,
    viewModel: SecurityAuditViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("التدقيق الأمني", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "رجوع") } },
                actions = {
                    if (!state.isRunning) {
                        IconButton(onClick = viewModel::runAudit) {
                            Icon(Icons.Default.Refresh, "إعادة الفحص")
                        }
                    }
                }
            )
        }
    ) { padding ->
        when {
            state.isRunning -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator()
                        Text("جارٍ فحص الشبكة...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            state.notConnected -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(32.dp)) {
                        Icon(Icons.Default.Router, null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.outlineVariant)
                        Text("غير متصل بالراوتر",
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("اتصل بالراوتر أولاً لإجراء التدقيق الأمني.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = viewModel::runAudit) { Text("إعادة المحاولة") }
                    }
                }
            }
            else -> {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(padding)
                ) {
                    item {
                        SummaryHeader(state.findings)
                    }
                    items(state.findings, key = { it.id }) { finding ->
                        FindingCard(finding)
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryHeader(findings: List<AuditFinding>) {
    val high = findings.count { it.severity == AuditSeverity.HIGH }
    val medium = findings.count { it.severity == AuditSeverity.MEDIUM }
    val low = findings.count { it.severity == AuditSeverity.LOW }

    val overallColor = when {
        high > 0 -> MaterialTheme.colorScheme.error
        medium > 0 -> MaterialTheme.colorScheme.tertiary
        else -> com.weshah.ui.common.theme.WeshahStatusColors.Online
    }

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = overallColor.copy(alpha = 0.12f))
    ) {
        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (high > 0) Icons.Default.GppBad
                else if (medium > 0) Icons.Default.GppMaybe
                else Icons.Default.GppGood,
                null, tint = overallColor, modifier = Modifier.size(36.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (high > 0) "توجد مشكلات عالية الخطورة"
                    else if (medium > 0) "توجد مشكلات متوسطة الخطورة"
                    else "الشبكة آمنة",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold, color = overallColor
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (high > 0) SeverityChip("$high عالية", AuditSeverity.HIGH)
                    if (medium > 0) SeverityChip("$medium متوسطة", AuditSeverity.MEDIUM)
                    if (low > 0) SeverityChip("$low منخفضة", AuditSeverity.LOW)
                }
            }
        }
    }
}

@Composable
private fun FindingCard(finding: AuditFinding) {
    val sevColor = severityColor(finding.severity)
    Card(shape = MaterialTheme.shapes.large) {
        Row(modifier = Modifier.padding(14.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = sevColor.copy(alpha = 0.15f),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(finding.icon, null, tint = sevColor, modifier = Modifier.size(20.dp))
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top) {
                    Text(finding.title, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    SeverityChip(severityLabel(finding.severity), finding.severity)
                }
                Text(finding.detail, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (finding.affectedCount > 0) {
                    Text("${finding.affectedCount} عنصر متأثر",
                        style = MaterialTheme.typography.labelSmall,
                        color = sevColor, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun SeverityChip(label: String, severity: AuditSeverity) {
    val color = severityColor(severity)
    Surface(shape = MaterialTheme.shapes.small, color = color.copy(alpha = 0.15f)) {
        Text(label, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun severityColor(severity: AuditSeverity): Color = when (severity) {
    AuditSeverity.HIGH -> MaterialTheme.colorScheme.error
    AuditSeverity.MEDIUM -> MaterialTheme.colorScheme.tertiary
    AuditSeverity.LOW -> MaterialTheme.colorScheme.primary
    AuditSeverity.INFO -> com.weshah.ui.common.theme.WeshahStatusColors.Online
}

private fun severityLabel(severity: AuditSeverity): String = when (severity) {
    AuditSeverity.HIGH -> "عالية"
    AuditSeverity.MEDIUM -> "متوسطة"
    AuditSeverity.LOW -> "منخفضة"
    AuditSeverity.INFO -> "معلومة"
}
