package com.weshah.ui.devices

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weshah.core.models.NetworkDevice
import com.weshah.core.models.SpeedProfile
import com.weshah.core.utils.IpUtils
import com.weshah.domain.repository.DeviceRepository
import com.weshah.domain.repository.RouterRepository
import com.weshah.router.api.TrafficSample
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─── ViewModel ────────────────────────────────────────────────────────────────

data class DeviceDetailState(
    val device: NetworkDevice? = null,
    val trafficSample: TrafficSample? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val operationSuccess: String? = null
)

@HiltViewModel
class DeviceDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val deviceRepository: DeviceRepository,
    private val routerRepository: RouterRepository
) : ViewModel() {

    private val mac: String = checkNotNull(savedStateHandle["mac"])
    private val _state = MutableStateFlow(DeviceDetailState())
    val state: StateFlow<DeviceDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            deviceRepository.getAllDevices()
                .map { list -> list.firstOrNull { it.macAddress == mac } }
                .collect { device ->
                    _state.update { it.copy(device = device, isLoading = false) }
                }
        }
        viewModelScope.launch {
            deviceRepository.getTrafficFlow(mac).collect { sample ->
                _state.update { it.copy(trafficSample = sample) }
            }
        }
    }

    fun rename(name: String) = viewModelScope.launch {
        deviceRepository.updateCustomName(mac, name)
        _state.update { it.copy(operationSuccess = "تم تغيير الاسم") }
    }

    fun block(expiresAt: Long? = null) = viewModelScope.launch {
        routerRepository.blockClient(mac, expiresAt)
            .onSuccess { _state.update { it.copy(operationSuccess = "تم حظر الجهاز على الراوتر") } }
            .onError { _, msg -> _state.update { it.copy(error = msg) } }
    }

    fun unblock() = viewModelScope.launch {
        routerRepository.unblockClient(mac)
            .onSuccess { _state.update { it.copy(operationSuccess = "تم رفع الحظر") } }
            .onError { _, msg -> _state.update { it.copy(error = msg) } }
    }

    fun setSpeed(profile: SpeedProfile) = viewModelScope.launch {
        routerRepository.setSpeedLimit(mac, profile.downloadKbps, profile.uploadKbps)
            .onSuccess { _state.update { it.copy(operationSuccess = "تم تطبيق حد السرعة: ${profile.name}") } }
            .onError { _, msg -> _state.update { it.copy(error = msg) } }
    }

    fun disconnect() = viewModelScope.launch {
        routerRepository.disconnectClient(mac)
            .onSuccess { _state.update { it.copy(operationSuccess = "تم قطع الاتصال") } }
            .onError { _, msg -> _state.update { it.copy(error = msg) } }
    }

    fun setStaticIp(ip: String) = viewModelScope.launch {
        routerRepository.createStaticLease(mac, ip, state.value.device?.hostname)
            .onSuccess { _state.update { it.copy(operationSuccess = "تم حفظ IP ثابت: $ip") } }
            .onError { _, msg -> _state.update { it.copy(error = msg) } }
    }

    fun clearMessage() = _state.update { it.copy(error = null, operationSuccess = null) }
}

// ─── Screen ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailScreen(
    onBack: () -> Unit,
    viewModel: DeviceDetailViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var showRenameDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showStaticIpDialog by remember { mutableStateOf(false) }

    val device = state.device

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(device?.customName ?: device?.hostname ?: device?.ipAddress ?: "الجهاز") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "رجوع") }
                }
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        if (device == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("الجهاز غير موجود")
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status Header
            DeviceStatusHeader(device = device, trafficSample = state.trafficSample)

            // Info Section
            InfoSection(device = device, onCopyIp = {
                clipboard.setText(AnnotatedString(device.ipAddress))
            }, onCopyMac = {
                clipboard.setText(AnnotatedString(device.macAddress))
            })

            // Traffic Section
            TrafficSection(device = device, trafficSample = state.trafficSample)

            // Actions
            ActionsSection(
                device = device,
                onRename = { showRenameDialog = true },
                onBlock = { viewModel.block() },
                onUnblock = { viewModel.unblock() },
                onDisconnect = { viewModel.disconnect() },
                onSetSpeed = { showSpeedDialog = true },
                onSetStaticIp = { showStaticIpDialog = true }
            )
        }

        // Dialogs
        if (showRenameDialog) {
            RenameDialog(
                currentName = device.customName ?: "",
                onConfirm = { viewModel.rename(it); showRenameDialog = false },
                onDismiss = { showRenameDialog = false }
            )
        }
        if (showSpeedDialog) {
            SpeedLimitDialog(
                onConfirm = { profile -> viewModel.setSpeed(profile); showSpeedDialog = false },
                onDismiss = { showSpeedDialog = false }
            )
        }
        if (showStaticIpDialog) {
            StaticIpDialog(
                currentIp = device.ipAddress,
                onConfirm = { ip -> viewModel.setStaticIp(ip); showStaticIpDialog = false },
                onDismiss = { showStaticIpDialog = false }
            )
        }

        // Snackbar messages
        state.operationSuccess?.let { msg ->
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(2000)
                viewModel.clearMessage()
            }
        }
    }
}

@Composable
private fun DeviceStatusHeader(device: NetworkDevice, trafficSample: com.weshah.router.api.TrafficSample?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Devices, null, tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(32.dp))
                }
            }
            Column {
                Text(device.customName ?: device.hostname ?: device.ipAddress,
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(device.manufacturer ?: "جهاز غير معروف",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = MaterialTheme.shapes.extraSmall,
                        color = if (device.isOnline) com.weshah.ui.common.theme.WeshahStatusColors.Online
                                else com.weshah.ui.common.theme.WeshahStatusColors.Offline,
                        modifier = Modifier.size(8.dp)) {}
                    Text(if (device.isOnline) "متصل" else "غير متصل",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun InfoSection(device: NetworkDevice, onCopyIp: () -> Unit, onCopyMac: () -> Unit) {
    Card(shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("معلومات الجهاز", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            InfoRow("IP", device.ipAddress, Icons.Default.Lan, onCopy = onCopyIp)
            InfoRow("MAC", device.macAddress, Icons.Default.Fingerprint, onCopy = onCopyMac)
            device.hostname?.let { InfoRow("Hostname", it, Icons.Default.Label) }
            device.interface_?.let { InfoRow("Interface", it, Icons.Default.Cable) }
            device.rssi?.let { InfoRow("RSSI", "$it dBm", Icons.Default.SignalWifi4Bar) }
            device.band?.let { InfoRow("Band", it.name.replace("BAND_", "").replace("_", " "), Icons.Default.Wifi) }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
                   onCopy: (() -> Unit)? = null) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            onCopy?.let {
                IconButton(onClick = it, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.ContentCopy, "نسخ", modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
private fun TrafficSection(device: NetworkDevice, trafficSample: com.weshah.router.api.TrafficSample?) {
    Card(shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("حركة البيانات", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TrafficStatItem("تنزيل الآن", IpUtils.bpsToHuman(
                    trafficSample?.downloadBps ?: device.downloadRateBytes))
                TrafficStatItem("رفع الآن", IpUtils.bpsToHuman(
                    trafficSample?.uploadBps ?: device.uploadRateBytes))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TrafficStatItem("إجمالي تنزيل", IpUtils.bytesToHuman(device.totalDownloadBytes))
                TrafficStatItem("إجمالي رفع", IpUtils.bytesToHuman(device.totalUploadBytes))
            }
        }
    }
}

@Composable
private fun TrafficStatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ActionsSection(
    device: NetworkDevice,
    onRename: () -> Unit, onBlock: () -> Unit, onUnblock: () -> Unit,
    onDisconnect: () -> Unit, onSetSpeed: () -> Unit, onSetStaticIp: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("إجراءات", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRename, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp)); Text("تغيير الاسم")
            }
            OutlinedButton(onClick = onSetSpeed, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Speed, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp)); Text("حد السرعة")
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onBlock, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                Icon(Icons.Default.Block, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp)); Text("حظر")
            }
            OutlinedButton(onClick = onUnblock, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp)); Text("رفع الحظر")
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDisconnect, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.WifiOff, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp)); Text("قطع WiFi")
            }
            OutlinedButton(onClick = onSetStaticIp, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.PinDrop, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp)); Text("IP ثابت")
            }
        }
    }
}

// ─── Dialogs ──────────────────────────────────────────────────────────────────

@Composable
private fun RenameDialog(currentName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تغيير اسم الجهاز") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("الاسم الجديد") }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim()) }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
private fun SpeedLimitDialog(onConfirm: (SpeedProfile) -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(SpeedProfile.UNLIMITED) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تحديد السرعة") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("سيتم حفظ حد السرعة على الراوتر ويبقى فعالاً بعد إغلاق التطبيق.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                SpeedProfile.BUILT_IN_PROFILES.forEach { profile ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()) {
                        RadioButton(selected = selected == profile, onClick = { selected = profile })
                        Text(buildString {
                            append(profile.name)
                            if (profile.downloadKbps != null)
                                append("  (${profile.downloadKbps / 1024} Mbps ↓ / ${profile.uploadKbps!! / 1024} Mbps ↑)")
                        }, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text("تطبيق") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
private fun StaticIpDialog(currentIp: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var ip by remember { mutableStateOf(currentIp) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تعيين IP ثابت") },
        text = {
            OutlinedTextField(value = ip, onValueChange = { ip = it }, label = { Text("عنوان IP") },
                singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(ip.trim()) }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}
