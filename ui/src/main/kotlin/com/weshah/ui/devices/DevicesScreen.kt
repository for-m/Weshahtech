package com.weshah.ui.devices

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.weshah.core.models.ConnectionType
import com.weshah.core.models.NetworkDevice
import com.weshah.ui.common.components.StatusIndicator
import com.weshah.ui.common.theme.WeshahStatusColors
import com.weshah.core.utils.IpUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    onDeviceClick: (String) -> Unit,
    viewModel: DevicesViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showSearch by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("الأجهزة", fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(onClick = { showSearch = !showSearch }) {
                            Icon(Icons.Default.Search, "بحث")
                        }
                        IconButton(onClick = { viewModel.scanNetwork() }) {
                            if (state.isScanning) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, "مسح")
                            }
                        }
                    }
                )
                if (showSearch) {
                    SearchBar(
                        query = state.searchQuery,
                        onQueryChange = viewModel::setQuery,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                FilterChipsRow(
                    activeFilter = state.activeFilter,
                    onFilterChange = viewModel::setFilter,
                    deviceCount = state.filteredDevices.size
                )
            }
        }
    ) { padding ->
        if (state.filteredDevices.isEmpty()) {
            EmptyDevicesState(
                isScanning = state.isScanning,
                onScanClick = { viewModel.scanNetwork() },
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.filteredDevices, key = { it.macAddress }) { device ->
                    DeviceListItem(
                        device = device,
                        onClick = { onDeviceClick(device.macAddress) },
                        onBlock = { viewModel.blockDevice(device.macAddress) },
                        onUnblock = { viewModel.unblockDevice(device.macAddress) },
                        onDisconnect = { viewModel.disconnectDevice(device.macAddress) }
                    )
                }
            }
        }

        state.error?.let { error ->
            Snackbar(
                modifier = Modifier.padding(16.dp),
                action = { TextButton(onClick = {}) { Text("حسناً") } }
            ) { Text(error) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = { Text("ابحث بالاسم أو IP أو MAC...") },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = if (query.isNotEmpty()) {
            { IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Default.Clear, null) } }
        } else null,
        singleLine = true,
        shape = MaterialTheme.shapes.large
    )
}

@Composable
private fun FilterChipsRow(
    activeFilter: DeviceFilter,
    onFilterChange: (DeviceFilter) -> Unit,
    deviceCount: Int
) {
    val filters = listOf(
        DeviceFilter.ALL to "الكل",
        DeviceFilter.ONLINE to "متصل",
        DeviceFilter.WIFI to "WiFi",
        DeviceFilter.WIRED to "سلكي",
        DeviceFilter.BLOCKED to "محظور",
        DeviceFilter.FAVORITES to "المفضلة"
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        filters.forEach { (filter, label) ->
            FilterChip(
                selected = activeFilter == filter,
                onClick = { onFilterChange(filter) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListItem(
    device: NetworkDevice,
    onClick: () -> Unit,
    onBlock: () -> Unit,
    onUnblock: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Device type icon + status
                Box {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = deviceTypeIcon(device),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    StatusIndicator(
                        isOnline = device.isOnline,
                        modifier = Modifier.align(Alignment.BottomEnd)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.customName ?: device.hostname ?: device.ipAddress,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(
                        text = buildString {
                            append(device.ipAddress)
                            device.manufacturer?.let { append("  •  $it") }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    if (device.isOnline && (device.downloadRateBytes > 0 || device.uploadRateBytes > 0)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "↓ ${IpUtils.bpsToHuman(device.downloadRateBytes)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = WeshahStatusColors.Online
                            )
                            Text(
                                "↑ ${IpUtils.bpsToHuman(device.uploadRateBytes)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = WeshahStatusColors.Accent
                            )
                        }
                    }
                }
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "خيارات")
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("حظر الإنترنت") },
                        onClick = { onBlock(); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.Block, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("رفع الحظر") },
                        onClick = { onUnblock(); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.CheckCircle, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("قطع الاتصال") },
                        onClick = { onDisconnect(); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.WifiOff, null) }
                    )
                }
            }
        }
    }
}

@Composable
private fun deviceTypeIcon(device: NetworkDevice): androidx.compose.ui.graphics.vector.ImageVector =
    when (device.deviceType) {
        com.weshah.core.models.DeviceType.ROUTER -> Icons.Default.Router
        com.weshah.core.models.DeviceType.PHONE -> Icons.Default.Smartphone
        com.weshah.core.models.DeviceType.COMPUTER -> Icons.Default.Computer
        com.weshah.core.models.DeviceType.TABLET -> Icons.Default.TabletMac
        com.weshah.core.models.DeviceType.TV -> Icons.Default.Tv
        com.weshah.core.models.DeviceType.PRINTER -> Icons.Default.Print
        com.weshah.core.models.DeviceType.CAMERA -> Icons.Default.Videocam
        com.weshah.core.models.DeviceType.NAS -> Icons.Default.Storage
        com.weshah.core.models.DeviceType.SMART_HOME -> Icons.Default.Home
        com.weshah.core.models.DeviceType.GAME_CONSOLE -> Icons.Default.SportsEsports
        com.weshah.core.models.DeviceType.AP -> Icons.Default.Wifi
        else -> Icons.Default.DeviceUnknown
    }

@Composable
private fun EmptyDevicesState(
    isScanning: Boolean,
    onScanClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (isScanning) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("جارٍ مسح الشبكة...", style = MaterialTheme.typography.bodyMedium)
        } else {
            Icon(Icons.Default.DevicesOther, null, modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Text("لا توجد أجهزة مكتشفة", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("اضغط مسح لاكتشاف الأجهزة", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onScanClick) { Text("مسح الشبكة") }
        }
    }
}
