package com.weshah.ui.noc

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weshah.core.models.NetworkEvent
import com.weshah.core.models.NetworkDevice
import com.weshah.domain.repository.DeviceRepository
import com.weshah.domain.repository.RouterConnectionState
import com.weshah.domain.repository.RouterRepository
import com.weshah.domain.repository.SubscriberRepository
import com.weshah.router.api.RouterStats
import com.weshah.router.api.RouterResult
import com.weshah.core.models.WanStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

// ─── State ────────────────────────────────────────────────────────────────────

data class NocState(
    val routerState: RouterConnectionState = RouterConnectionState.DISCONNECTED,
    val routerModel: String? = null,
    val routerIp: String? = null,
    val wanStatus: WanStatus? = null,
    val stats: RouterStats? = null,
    val onlineCount: Int = 0,
    val offlineCount: Int = 0,
    val blockedCount: Int = 0,
    val totalCount: Int = 0,
    val activeSubscriberCount: Int = 0,
    val topConsumers: List<NetworkDevice> = emptyList(),
    val recentEvents: List<NetworkEvent> = emptyList()
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

@HiltViewModel
class NocDashboardViewModel @Inject constructor(
    private val routerRepository: RouterRepository,
    private val deviceRepository: DeviceRepository,
    private val subscriberRepository: SubscriberRepository
) : ViewModel() {

    private val _state = MutableStateFlow(NocState())
    val state: StateFlow<NocState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            routerRepository.connectionState.collect { s ->
                _state.update { it.copy(routerState = s) }
            }
        }
        viewModelScope.launch {
            routerRepository.routerInfo.collect { info ->
                _state.update { it.copy(routerModel = info?.model, routerIp = info?.ipAddress) }
            }
        }
        viewModelScope.launch {
            routerRepository.getSystemStats().collect { stats ->
                _state.update { it.copy(stats = stats) }
            }
        }
        viewModelScope.launch {
            deviceRepository.getAllDevices().collect { devices ->
                val online = devices.count { it.isOnline }
                val offline = devices.count { !it.isOnline }
                val blocked = devices.count { it.isBlocked }
                val top = devices.filter { it.isOnline }
                    .sortedByDescending { it.downloadRateBytes + it.uploadRateBytes }
                    .take(5)
                _state.update { it.copy(
                    onlineCount = online,
                    offlineCount = offline,
                    blockedCount = blocked,
                    totalCount = devices.size,
                    topConsumers = top
                )}
            }
        }
        viewModelScope.launch {
            subscriberRepository.getActiveCount().collect { count ->
                _state.update { it.copy(activeSubscriberCount = count) }
            }
        }
        viewModelScope.launch {
            deviceRepository.getRecentEvents().collect { events ->
                _state.update { it.copy(recentEvents = events.take(8)) }
            }
        }
        refreshWan()
    }

    fun refreshWan() {
        viewModelScope.launch {
            when (val r = routerRepository.getWanStatus()) {
                is RouterResult.Success -> _state.update { it.copy(wanStatus = r.data) }
                is RouterResult.Error -> _state.update { it.copy(wanStatus = null) }
            }
        }
    }
}

// ─── Screen ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NocDashboardScreen(
    onBack: () -> Unit,
    onNavigateToDevices: () -> Unit = {},
    onNavigateToSubscribers: () -> Unit = {},
    onNavigateToAlerts: () -> Unit = {},
    onNavigateToHealth: () -> Unit = {},
    onNavigateToSecurity: () -> Unit = {},
    viewModel: NocDashboardViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("NOC Dashboard", fontWeight = FontWeight.Bold)
                        Text("مركز عمليات الشبكة",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "رجوع") } },
                actions = {
                    IconButton(onClick = viewModel::refreshWan) {
                        Icon(Icons.Default.Refresh, "تحديث")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(padding)
        ) {
            item { RouterStatusBar(state) }
            item { WanStatusCard(state.wanStatus) }
            item { DeviceStatsRow(state, onNavigateToDevices) }
            item { SubscriberStatCard(state.activeSubscriberCount, onNavigateToSubscribers) }
            item { RouterResourcesCard(state.stats) }
            if (state.topConsumers.isNotEmpty()) {
                item { SectionLabel("أعلى استهلاكاً للبيانات") }
                items(state.topConsumers, key = { it.macAddress }) { device ->
                    TopConsumerRow(device)
                }
            }
            if (state.recentEvents.isNotEmpty()) {
                item { SectionLabel("آخر الأحداث") }
                items(state.recentEvents, key = { it.id }) { event ->
                    EventRow(event)
                }
            }
            item {
                QuickActionsRow(
                    onNavigateToHealth, onNavigateToAlerts, onNavigateToSecurity
                )
            }
        }
    }
}

@Composable
private fun RouterStatusBar(state: NocState) {
    val connected = state.routerState == RouterConnectionState.CONNECTED
    val bgColor = if (connected) com.weshah.ui.common.theme.WeshahStatusColors.Online.copy(alpha = 0.12f)
                  else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
    val fgColor = if (connected) com.weshah.ui.common.theme.WeshahStatusColors.Online
                  else MaterialTheme.colorScheme.error

    Surface(shape = MaterialTheme.shapes.large, color = bgColor) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Router, null, tint = fgColor, modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (connected) "متصل بالراوتر" else "غير متصل",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold, color = fgColor
                )
                if (state.routerIp != null) {
                    Text(
                        "${state.routerModel ?: "راوتر"} · ${state.routerIp}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            Surface(shape = MaterialTheme.shapes.small,
                color = fgColor.copy(alpha = 0.15f)) {
                Text(
                    state.routerState.name,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall, color = fgColor
                )
            }
        }
    }
}

@Composable
private fun WanStatusCard(wan: WanStatus?) {
    Card(shape = MaterialTheme.shapes.large) {
        Column(modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (wan?.isConnected == true) Icons.Default.Cloud else Icons.Default.CloudOff,
                    null, modifier = Modifier.size(18.dp),
                    tint = if (wan?.isConnected == true) com.weshah.ui.common.theme.WeshahStatusColors.Online
                           else MaterialTheme.colorScheme.error
                )
                Text("WAN / الإنترنت", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                val label = if (wan == null) "غير معروف"
                            else if (wan.isConnected) "متصل" else "منقطع"
                val labelColor = if (wan?.isConnected == true) com.weshah.ui.common.theme.WeshahStatusColors.Online
                                 else MaterialTheme.colorScheme.error
                Text(label, style = MaterialTheme.typography.labelSmall, color = labelColor,
                    fontWeight = FontWeight.Medium)
            }
            if (wan != null && wan.isConnected) {
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    WanDetail("IP", wan.ipAddress ?: "—")
                    WanDetail("Gateway", wan.gateway ?: "—")
                    WanDetail("DNS", wan.dns?.firstOrNull() ?: "—")
                }
            }
        }
    }
}

@Composable
private fun WanDetail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DeviceStatsRow(state: NocState, onNavigateToDevices: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NocStatCard(
            label = "متصل الآن",
            value = state.onlineCount.toString(),
            icon = Icons.Default.Wifi,
            iconColor = com.weshah.ui.common.theme.WeshahStatusColors.Online,
            modifier = Modifier.weight(1f),
            onClick = onNavigateToDevices
        )
        NocStatCard(
            label = "غير متصل",
            value = state.offlineCount.toString(),
            icon = Icons.Default.WifiOff,
            iconColor = MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(1f)
        )
        NocStatCard(
            label = "محجوب",
            value = state.blockedCount.toString(),
            icon = Icons.Default.Block,
            iconColor = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SubscriberStatCard(count: Int, onClick: () -> Unit) {
    Card(shape = MaterialTheme.shapes.large, onClick = onClick) {
        Row(modifier = Modifier.padding(14.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.People, null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("المشتركون النشطون",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(count.toString(), style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold)
            }
            Icon(Icons.Default.ChevronRight, null,
                tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun RouterResourcesCard(stats: RouterStats?) {
    Card(shape = MaterialTheme.shapes.large) {
        Column(modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("موارد الراوتر", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold)
            if (stats == null) {
                Text("غير متوفر — اتصل بالراوتر أولاً",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val ramUsedPercent = if (stats.ramTotalKb > 0)
                    ((stats.ramTotalKb - stats.ramFreeKb).toFloat() / stats.ramTotalKb * 100).toInt() else 0

                ResourceRow(
                    label = "CPU",
                    value = "${stats.cpuUsagePercent.toInt()}%",
                    progress = stats.cpuUsagePercent / 100f,
                    warn = stats.cpuUsagePercent > 80f
                )
                ResourceRow(
                    label = "RAM",
                    value = "$ramUsedPercent%",
                    progress = ramUsedPercent / 100f,
                    warn = ramUsedPercent > 85
                )
                stats.temperatureCelsius?.let { temp ->
                    ResourceRow(
                        label = "حرارة",
                        value = "${temp.toInt()} °C",
                        progress = (temp / 100f).coerceIn(0f, 1f),
                        warn = temp > 75f
                    )
                }
                val uptimeHours = stats.uptime / 3600
                Text("مدة التشغيل: $uptimeHours ساعة",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun ResourceRow(label: String, value: String, progress: Float, warn: Boolean) {
    val color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(48.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.weight(1f).height(6.dp),
            color = color,
            trackColor = color.copy(alpha = 0.2f)
        )
        Text(value, style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium, color = color,
            modifier = Modifier.width(40.dp))
    }
}

@Composable
private fun TopConsumerRow(device: NetworkDevice) {
    val totalBps = device.downloadRateBytes + device.uploadRateBytes
    Card(shape = MaterialTheme.shapes.medium) {
        Row(modifier = Modifier.padding(10.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.DevicesOther, null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(device.customName ?: device.hostname ?: device.macAddress,
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                Text(device.ipAddress, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace)
            }
            Text(formatBps(totalBps), style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun EventRow(event: com.weshah.core.models.NetworkEvent) {
    val color = when (event.severity) {
        com.weshah.core.models.EventSeverity.CRITICAL -> MaterialTheme.colorScheme.error
        com.weshah.core.models.EventSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
        com.weshah.core.models.EventSeverity.INFO -> MaterialTheme.colorScheme.primary
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top) {
        Surface(shape = MaterialTheme.shapes.extraSmall,
            color = color.copy(alpha = 0.15f), modifier = Modifier.size(28.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Circle, null,
                    modifier = Modifier.size(8.dp), tint = color)
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(event.message, style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                event.deviceName?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(formatRelativeTime(event.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun QuickActionsRow(
    onNavigateToHealth: () -> Unit,
    onNavigateToAlerts: () -> Unit,
    onNavigateToSecurity: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickActionButton(Icons.Default.NetworkCheck, "صحة الشبكة", onNavigateToHealth, Modifier.weight(1f))
        QuickActionButton(Icons.Default.Notifications, "التنبيهات", onNavigateToAlerts, Modifier.weight(1f))
        QuickActionButton(Icons.Default.Security, "التدقيق الأمني", onNavigateToSecurity, Modifier.weight(1f))
    }
}

@Composable
private fun QuickActionButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun NocStatCard(
    label: String,
    value: String,
    icon: ImageVector,
    iconColor: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val card: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = iconColor, modifier = Modifier.size(20.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (onClick != null) {
        Card(shape = MaterialTheme.shapes.large, modifier = modifier, onClick = onClick) { card() }
    } else {
        Card(shape = MaterialTheme.shapes.large, modifier = modifier) { card() }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp))
}

private fun formatBps(bps: Long): String = when {
    bps >= 1_000_000L -> "${"%.1f".format(bps / 1_000_000.0)} MB/s"
    bps >= 1_000L -> "${"%.0f".format(bps / 1_000.0)} KB/s"
    else -> "$bps B/s"
}

private fun formatRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000L -> "الآن"
        diff < 3_600_000L -> "${diff / 60_000} د"
        diff < 86_400_000L -> "${diff / 3_600_000} س"
        else -> SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}
