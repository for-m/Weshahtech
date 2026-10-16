package com.weshah.ui.subscribers

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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weshah.core.models.*
import com.weshah.core.utils.MacUtils
import com.weshah.domain.repository.RouterRepository
import com.weshah.domain.repository.SubscriberRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

data class SubscriberDetailUiState(
    val subscriber: Subscriber? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
    val saveSuccess: Boolean = false
)

@HiltViewModel
class SubscriberDetailViewModel @Inject constructor(
    private val subscriberRepository: SubscriberRepository,
    private val routerRepository: RouterRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val subscriberId: String = checkNotNull(savedStateHandle["subscriberId"])

    private val _state = MutableStateFlow(SubscriberDetailUiState())
    val state: StateFlow<SubscriberDetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val sub = subscriberRepository.getSubscriber(subscriberId)
            _state.update { it.copy(subscriber = sub, isLoading = false) }
        }
    }

    fun saveSubscriber(name: String, phone: String?, notes: String?,
                       speedProfileId: String, expiryTimestamp: Long?) {
        val current = _state.value.subscriber ?: return
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, error = null) }
            val updated = current.copy(
                name = name.trim(),
                phone = phone?.takeIf { it.isNotBlank() },
                notes = notes?.takeIf { it.isNotBlank() },
                speedProfileId = speedProfileId,
                expiryTimestamp = expiryTimestamp
            )
            subscriberRepository.updateSubscriber(updated)

            // Apply speed profile to all assigned MACs on router
            val profile = SpeedProfile.BUILT_IN_PROFILES.find { it.id == speedProfileId }
            if (profile != null) {
                updated.macAddresses.forEach { mac ->
                    routerRepository.setSpeedLimit(mac, profile.downloadKbps, profile.uploadKbps)
                }
            }

            _state.update { it.copy(isSaving = false, subscriber = updated, saveSuccess = true) }
        }
    }

    fun assignMac(mac: String) {
        val current = _state.value.subscriber ?: return
        if (!MacUtils.isValid(mac)) {
            _state.update { it.copy(error = "MAC Address غير صحيح") }
            return
        }
        if (mac in current.macAddresses) return
        viewModelScope.launch {
            subscriberRepository.assignDeviceToSubscriber(mac, subscriberId)
            val updated = current.copy(macAddresses = current.macAddresses + mac)
            _state.update { it.copy(subscriber = updated) }
        }
    }

    fun removeMac(mac: String) {
        val current = _state.value.subscriber ?: return
        viewModelScope.launch {
            subscriberRepository.assignDeviceToSubscriber(mac, null)
            val updated = current.copy(macAddresses = current.macAddresses - mac)
            _state.update { it.copy(subscriber = updated) }
        }
    }

    fun blockSubscriber() {
        val current = _state.value.subscriber ?: return
        viewModelScope.launch {
            current.macAddresses.forEach { mac -> routerRepository.blockClient(mac, null) }
            subscriberRepository.updateStatus(subscriberId, SubscriberStatus.BLOCKED)
            _state.update { it.copy(subscriber = current.copy(status = SubscriberStatus.BLOCKED)) }
        }
    }

    fun unblockSubscriber() {
        val current = _state.value.subscriber ?: return
        viewModelScope.launch {
            current.macAddresses.forEach { mac -> routerRepository.unblockClient(mac) }
            subscriberRepository.updateStatus(subscriberId, SubscriberStatus.ACTIVE)
            _state.update { it.copy(subscriber = current.copy(status = SubscriberStatus.ACTIVE)) }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }
    fun clearSuccess() = _state.update { it.copy(saveSuccess = false) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriberDetailScreen(
    onBack: () -> Unit,
    viewModel: SubscriberDetailViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var selectedProfileId by remember { mutableStateOf(SpeedProfile.UNLIMITED.id) }
    var expiryDate by remember { mutableStateOf<Long?>(null) }
    var showMacDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.subscriber) {
        state.subscriber?.let { sub ->
            name = sub.name
            phone = sub.phone ?: ""
            notes = sub.notes ?: ""
            selectedProfileId = sub.speedProfileId
            expiryDate = sub.expiryTimestamp
        }
    }

    LaunchedEffect(state.saveSuccess) {
        if (state.saveSuccess) {
            viewModel.clearSuccess()
        }
    }

    if (showMacDialog) {
        AssignMacDialog(
            onConfirm = { mac -> viewModel.assignMac(mac); showMacDialog = false },
            onDismiss = { showMacDialog = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("تفاصيل المشترك", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "رجوع") } },
                actions = {
                    if (state.subscriber != null && !state.isSaving) {
                        val blocked = state.subscriber!!.status == SubscriberStatus.BLOCKED
                        IconButton(onClick = { if (blocked) viewModel.unblockSubscriber() else viewModel.blockSubscriber() }) {
                            Icon(
                                if (blocked) Icons.Default.LockOpen else Icons.Default.Block,
                                if (blocked) "رفع الحظر" else "حظر",
                                tint = if (blocked) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.subscriber == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("المشترك غير موجود", color = MaterialTheme.colorScheme.error)
            }
            else -> {
                val sub = state.subscriber!!
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding)
                        .verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Status badge
                    StatusBadge(sub.status)

                    // Basic info
                    InfoSection {
                        OutlinedTextField(value = name, onValueChange = { name = it },
                            label = { Text("الاسم") },
                            isError = name.isBlank(),
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = phone, onValueChange = { phone = it },
                            label = { Text("رقم الهاتف (اختياري)") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = notes, onValueChange = { notes = it },
                            label = { Text("ملاحظات") },
                            maxLines = 3, modifier = Modifier.fillMaxWidth())
                    }

                    // Speed profile
                    Text("خطة السرعة", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold)
                    SpeedProfileSelector(selectedId = selectedProfileId,
                        onSelect = { selectedProfileId = it })

                    // Expiry
                    ExpirySection(expiryDate) { expiryDate = it }

                    // MAC addresses
                    MacSection(
                        macs = sub.macAddresses,
                        onAdd = { showMacDialog = true },
                        onRemove = viewModel::removeMac
                    )

                    // Error
                    state.error?.let { err ->
                        Card(colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Text(err, modifier = Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    // Save button
                    Button(
                        onClick = {
                            viewModel.saveSubscriber(name, phone, notes, selectedProfileId, expiryDate)
                        },
                        enabled = name.isNotBlank() && !state.isSaving,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.isSaving) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text("حفظ التغييرات")
                    }

                    // Traffic stats (read-only)
                    TrafficStats(sub)
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: SubscriberStatus) {
    val (color, label) = when (status) {
        SubscriberStatus.ACTIVE -> MaterialTheme.colorScheme.primary to "نشط"
        SubscriberStatus.BLOCKED -> MaterialTheme.colorScheme.error to "محظور"
        SubscriberStatus.EXPIRED -> MaterialTheme.colorScheme.tertiary to "منتهي"
        SubscriberStatus.SUSPENDED -> MaterialTheme.colorScheme.outline to "موقوف"
    }
    Surface(shape = MaterialTheme.shapes.medium, color = color.copy(alpha = 0.15f)) {
        Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun InfoSection(content: @Composable ColumnScope.() -> Unit) {
    Card(shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("المعلومات الأساسية", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun SpeedProfileSelector(selectedId: String, onSelect: (String) -> Unit) {
    Card(shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SpeedProfile.BUILT_IN_PROFILES.forEach { profile ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(profile.name, style = MaterialTheme.typography.bodyMedium)
                        val sub = if (profile.downloadKbps == null) "بلا حدود"
                                  else "${profile.downloadKbps / 1024} Mbps↓  ${profile.uploadKbps!! / 1024} Mbps↑"
                        Text(sub, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    RadioButton(selected = selectedId == profile.id,
                        onClick = { onSelect(profile.id) })
                }
            }
        }
    }
}

@Composable
private fun ExpirySection(expiryTimestamp: Long?, onExpiryChange: (Long?) -> Unit) {
    Card(shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("تاريخ الانتهاء", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()) {
                if (expiryTimestamp == null) {
                    Text("بلا انتهاء", style = MaterialTheme.typography.bodyMedium)
                } else {
                    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    Text(fmt.format(Date(expiryTimestamp)),
                        style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = { onExpiryChange(if (expiryTimestamp == null)
                    System.currentTimeMillis() + 30L * 24 * 3600 * 1000 else null) }) {
                    Text(if (expiryTimestamp == null) "تعيين 30 يوم" else "إزالة")
                }
            }
        }
    }
}

@Composable
private fun MacSection(macs: List<String>, onAdd: () -> Unit, onRemove: (String) -> Unit) {
    Card(shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("أجهزة مرتبطة (${macs.size})",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                IconButton(onClick = onAdd, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Add, "ربط جهاز", modifier = Modifier.size(18.dp))
                }
            }
            if (macs.isEmpty()) {
                Text("لا توجد أجهزة مرتبطة",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                macs.forEach { mac ->
                    Row(modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.DeviceHub, null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(mac, style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                        IconButton(onClick = { onRemove(mac) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Remove, "إزالة",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrafficStats(sub: Subscriber) {
    Card(shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("إجمالي الاستهلاك", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly) {
                TrafficStat("↓ تنزيل", formatBytes(sub.totalDownloadBytes))
                TrafficStat("↑ رفع", formatBytes(sub.totalUploadBytes))
            }
        }
    }
}

@Composable
private fun TrafficStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssignMacDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var mac by remember { mutableStateOf("") }
    val macError = mac.isNotBlank() && !MacUtils.isValid(mac)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ربط جهاز جديد") },
        text = {
            OutlinedTextField(
                value = mac,
                onValueChange = { mac = it.uppercase().take(17) },
                label = { Text("MAC Address") },
                isError = macError,
                supportingText = { if (macError) Text("صيغة MAC غير صحيحة") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("XX:XX:XX:XX:XX:XX") }
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(mac) },
                enabled = mac.isNotBlank() && !macError) { Text("ربط") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L -> "%.1f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
