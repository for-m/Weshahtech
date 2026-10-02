package com.weshah.ui.dashboard

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.weshah.core.models.NetworkDevice
import com.weshah.core.utils.IpUtils
import com.weshah.domain.repository.RouterConnectionState
import com.weshah.ui.common.components.StatCard
import com.weshah.ui.common.components.StatusIndicator
import com.weshah.ui.common.theme.WeshahStatusColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToDevices: () -> Unit,
    onNavigateToRouter: () -> Unit,
    onNavigateToSubscribers: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.refreshWanStatus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("WESHAH", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("مدير الشبكات", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.triggerScan() }) {
                        if (state.isScanning) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "مسح الشبكة")
                        }
                    }
                    IconButton(onClick = onNavigateToRouter) {
                        Icon(Icons.Default.Router, contentDescription = "إعدادات الراوتر")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Router & Internet Status Banner
            RouterStatusBanner(
                connectionState = state.routerConnectionState,
                routerModel = state.routerModel,
                routerIp = state.routerIp,
                wanConnected = state.wanStatus?.isConnected,
                wanIp = state.wanStatus?.ipAddress,
                onConnectClick = onNavigateToRouter
            )

            // Router Stats Row
            state.routerStats?.let { stats ->
                RouterStatsRow(stats = stats)
            }

            // Device Summary Cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    title = "متصل",
                    value = state.onlineDeviceCount.toString(),
                    subtitle = "جهاز نشط",
                    icon = Icons.Default.Devices,
                    iconTint = WeshahStatusColors.Online,
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "محظور",
                    value = state.blockedDeviceCount.toString(),
                    subtitle = "جهاز محظور",
                    icon = Icons.Default.Block,
                    iconTint = WeshahStatusColors.Blocked,
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "الكل",
                    value = state.totalDeviceCount.toString(),
                    subtitle = "إجمالي الأجهزة",
                    icon = Icons.Default.DevicesOther,
                    modifier = Modifier.weight(1f)
                )
            }

            // Traffic Summary
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    title = "تنزيل",
                    value = IpUtils.bpsToHuman(state.totalDownloadBps),
                    icon = Icons.Default.ArrowDownward,
                    iconTint = WeshahStatusColors.Online,
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "رفع",
                    value = IpUtils.bpsToHuman(state.totalUploadBps),
                    icon = Icons.Default.ArrowUpward,
                    iconTint = WeshahStatusColors.Accent,
                    modifier = Modifier.weight(1f)
                )
            }

            // Top Consumers
            if (state.topConsumers.isNotEmpty()) {
                TopConsumersSection(
                    devices = state.topConsumers,
                    onDeviceClick = { onNavigateToDevices() }
                )
            }

            // Quick Actions
            QuickActionsSection(
                onScanClick = { viewModel.triggerScan() },
                onDevicesClick = onNavigateToDevices,
                onSubscribersClick = onNavigateToSubscribers,
                isScanning = state.isScanning
            )

            // Cached data notice
            if (state.isDataCached) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.WifiOff, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(16.dp))
                        Text("البيانات المعروضة من الكاش — الراوتر غير متاح",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
    }
}

@Composable
private fun RouterStatusBanner(
    connectionState: RouterConnectionState,
    routerModel: String?,
    routerIp: String?,
    wanConnected: Boolean?,
    wanIp: String?,
    onConnectClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Router,
                    contentDescription = null,
                    tint = when (connectionState) {
                        RouterConnectionState.CONNECTED -> WeshahStatusColors.Online
                        RouterConnectionState.CONNECTING -> WeshahStatusColors.Warning
                        else -> WeshahStatusColors.Offline
                    },
                    modifier = Modifier.size(36.dp)
                )
                Column {
                    Text(
                        text = when (connectionState) {
                            RouterConnectionState.CONNECTED -> routerModel ?: "الراوتر متصل"
                            RouterConnectionState.CONNECTING -> "جارٍ الاتصال..."
                            RouterConnectionState.ERROR -> "خطأ في الاتصال"
                            RouterConnectionState.DISCONNECTED -> "غير متصل بالراوتر"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (connectionState == RouterConnectionState.CONNECTED) {
                        Text(
                            text = buildString {
                                routerIp?.let { append(it) }
                                if (wanConnected == true && wanIp != null) append("  •  $wanIp")
                                else if (wanConnected == false) append("  •  الإنترنت مقطوع")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (connectionState != RouterConnectionState.CONNECTED) {
                FilledTonalButton(onClick = onConnectClick) {
                    Text("اتصال")
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatusIndicator(isOnline = wanConnected ?: false)
                    Text(
                        text = if (wanConnected == true) "إنترنت" else "لا إنترنت",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (wanConnected == true) WeshahStatusColors.Online else WeshahStatusColors.Blocked
                    )
                }
            }
        }
    }
}

@Composable
private fun RouterStatsRow(stats: com.weshah.router.api.RouterStats) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatCard(
            title = "CPU",
            value = "${stats.cpuUsagePercent.toInt()}%",
            modifier = Modifier.weight(1f)
        )
        StatCard(
            title = "RAM",
            value = run {
                val used = stats.ramTotalKb - stats.ramFreeKb
                "${IpUtils.bytesToHuman(used * 1024)} / ${IpUtils.bytesToHuman(stats.ramTotalKb * 1024)}"
            },
            modifier = Modifier.weight(2f)
        )
        stats.temperatureCelsius?.let { temp ->
            StatCard(
                title = "درجة الحرارة",
                value = "${temp.toInt()}°C",
                iconTint = if (temp > 70) WeshahStatusColors.Blocked else WeshahStatusColors.Online,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun TopConsumersSection(
    devices: List<NetworkDevice>,
    onDeviceClick: (NetworkDevice) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("أعلى استهلاكاً", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        devices.forEach { device ->
            Card(
                onClick = { onDeviceClick(device) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusIndicator(isOnline = device.isOnline)
                        Column {
                            Text(
                                device.customName ?: device.hostname ?: device.ipAddress,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(device.ipAddress, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ArrowDownward, null, modifier = Modifier.size(12.dp),
                                tint = WeshahStatusColors.Online)
                            Text(IpUtils.bpsToHuman(device.downloadRateBytes),
                                style = MaterialTheme.typography.labelSmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ArrowUpward, null, modifier = Modifier.size(12.dp),
                                tint = WeshahStatusColors.Accent)
                            Text(IpUtils.bpsToHuman(device.uploadRateBytes),
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickActionsSection(
    onScanClick: () -> Unit,
    onDevicesClick: () -> Unit,
    onSubscribersClick: () -> Unit,
    isScanning: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("إجراءات سريعة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onScanClick,
                modifier = Modifier.weight(1f),
                enabled = !isScanning
            ) {
                Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("مسح الشبكة")
            }
            OutlinedButton(onClick = onDevicesClick, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Devices, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("الأجهزة")
            }
        }
        OutlinedButton(onClick = onSubscribersClick, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.People, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("المشتركون")
        }
    }
}
