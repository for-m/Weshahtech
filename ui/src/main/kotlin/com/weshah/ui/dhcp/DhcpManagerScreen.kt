package com.weshah.ui.dhcp

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weshah.core.utils.IpUtils
import com.weshah.core.utils.MacUtils
import com.weshah.domain.repository.RouterRepository
import com.weshah.router.api.DhcpLease
import com.weshah.router.api.RouterErrorCode
import com.weshah.router.api.RouterResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.text.SimpleDateFormat
import java.util.*

data class DhcpUiState(
    val leases: List<DhcpLease> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val showAddDialog: Boolean = false,
    val operationInProgress: Boolean = false
)

@HiltViewModel
class DhcpManagerViewModel @Inject constructor(
    private val routerRepository: RouterRepository
) : ViewModel() {

    private val _state = MutableStateFlow(DhcpUiState(isLoading = true))
    val state: StateFlow<DhcpUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            when (val r = routerRepository.getStaticLeases()) {
                is RouterResult.Success -> _state.update { it.copy(leases = r.data, isLoading = false) }
                is RouterResult.Error -> _state.update { it.copy(isLoading = false, error = r.message) }
            }
        }
    }

    fun showAddDialog() = _state.update { it.copy(showAddDialog = true) }
    fun hideAddDialog() = _state.update { it.copy(showAddDialog = false) }

    fun createStaticLease(mac: String, ip: String, hostname: String?) {
        viewModelScope.launch {
            _state.update { it.copy(operationInProgress = true, showAddDialog = false) }
            when (val r = routerRepository.createStaticLease(mac, ip, hostname)) {
                is RouterResult.Success -> load()
                is RouterResult.Error -> _state.update { it.copy(operationInProgress = false, error = r.message) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DhcpManagerScreen(
    onBack: () -> Unit,
    viewModel: DhcpManagerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    if (state.showAddDialog) {
        AddLeaseDialog(
            onConfirm = { mac, ip, hostname -> viewModel.createStaticLease(mac, ip, hostname) },
            onDismiss = viewModel::hideAddDialog
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DHCP Manager", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "رجوع") } },
                actions = {
                    IconButton(onClick = viewModel::showAddDialog, enabled = !state.operationInProgress) {
                        Icon(Icons.Default.Add, "إضافة حجز")
                    }
                    IconButton(onClick = viewModel::load) { Icon(Icons.Default.Refresh, "تحديث") }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null && state.leases.isEmpty() ->
                    com.weshah.ui.ports.ErrorMessage(state.error!!, viewModel::load)
                else -> LeaseList(state.leases)
            }
            if (state.operationInProgress) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun LeaseList(leases: List<DhcpLease>) {
    val (static, dynamic) = leases.partition { it.isStatic }

    if (leases.isEmpty()) {
        com.weshah.ui.ports.EmptyMessage("لا توجد تسجيلات DHCP")
        return
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (static.isNotEmpty()) {
            item {
                Text("حجوزات ثابتة (${static.size})", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            items(static, key = { "s_${it.macAddress}" }) { lease ->
                LeaseCard(lease)
            }
        }
        if (dynamic.isNotEmpty()) {
            item {
                Text("تسجيلات ديناميكية (${dynamic.size})", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            }
            items(dynamic, key = { "d_${it.macAddress}" }) { lease ->
                LeaseCard(lease)
            }
        }
    }
}

@Composable
private fun LeaseCard(lease: DhcpLease) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                if (lease.isStatic) Icons.Default.Lock else Icons.Default.DeviceHub,
                null,
                tint = if (lease.isStatic) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(lease.ipAddress, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium)
                Text(lease.macAddress, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                lease.hostname?.let { hn ->
                    Text(hn, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (lease.isStatic) {
                Surface(shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer) {
                    Text("Static", modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            } else {
                lease.leaseExpiry?.let { exp ->
                    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                    Text(fmt.format(Date(exp)), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddLeaseDialog(
    onConfirm: (String, String, String?) -> Unit,
    onDismiss: () -> Unit
) {
    var mac by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf("") }
    var hostname by remember { mutableStateOf("") }

    val macError = mac.isNotBlank() && !MacUtils.isValid(mac)
    val ipError = ip.isNotBlank() && !IpUtils.isValidIp(ip)
    val canConfirm = mac.isNotBlank() && !macError && ip.isNotBlank() && !ipError

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة حجز DHCP ثابت") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = mac, onValueChange = { mac = it.uppercase().take(17) },
                    label = { Text("MAC Address") },
                    isError = macError,
                    supportingText = { if (macError) Text("صيغة MAC غير صحيحة") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = ip, onValueChange = { ip = it },
                    label = { Text("عنوان IP") },
                    isError = ipError,
                    supportingText = { if (ipError) Text("عنوان IP غير صحيح") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = hostname, onValueChange = { hostname = it },
                    label = { Text("اسم الجهاز (اختياري)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onConfirm(mac, ip, hostname.takeIf { it.isNotBlank() })
            }, enabled = canConfirm) { Text("إضافة") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}
