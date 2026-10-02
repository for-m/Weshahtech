package com.weshah.ui.vlan

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.weshah.core.models.VlanInfo
import com.weshah.core.utils.IpUtils
import com.weshah.domain.repository.RouterRepository
import com.weshah.router.api.RouterErrorCode
import com.weshah.router.api.RouterResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VlanManagerUiState(
    val vlans: List<VlanInfo> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val notSupported: Boolean = false,
    val showAddDialog: Boolean = false,
    val operationInProgress: Boolean = false
)

@HiltViewModel
class VlanManagerViewModel @Inject constructor(
    private val routerRepository: RouterRepository
) : ViewModel() {

    private val _state = MutableStateFlow(VlanManagerUiState(isLoading = true))
    val state: StateFlow<VlanManagerUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            when (val r = routerRepository.getVlans()) {
                is RouterResult.Success -> _state.update { it.copy(vlans = r.data, isLoading = false) }
                is RouterResult.Error -> {
                    val ns = r.code == RouterErrorCode.NOT_SUPPORTED
                    _state.update { it.copy(isLoading = false, error = r.message, notSupported = ns) }
                }
            }
        }
    }

    fun showAddDialog() = _state.update { it.copy(showAddDialog = true) }
    fun hideAddDialog() = _state.update { it.copy(showAddDialog = false) }

    fun createVlan(vlanId: Int, name: String, gateway: String?, dhcpEnabled: Boolean,
                   internetAccess: Boolean, clientIsolation: Boolean) {
        if (vlanId !in 2..4094) {
            _state.update { it.copy(error = "VLAN ID يجب أن يكون بين 2 و 4094") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(operationInProgress = true, showAddDialog = false) }
            val vlan = VlanInfo(
                vlanId = vlanId, name = name, gateway = gateway?.takeIf { it.isNotBlank() },
                subnet = null, dhcpEnabled = dhcpEnabled, internetAccess = internetAccess,
                clientIsolation = clientIsolation, ssid = null,
                taggedPorts = emptyList(), untaggedPorts = emptyList()
            )
            when (val r = routerRepository.createVlan(vlan)) {
                is RouterResult.Success -> load()
                is RouterResult.Error -> _state.update { it.copy(operationInProgress = false, error = r.message) }
            }
        }
    }

    fun deleteVlan(vlanId: Int) {
        viewModelScope.launch {
            _state.update { it.copy(operationInProgress = true) }
            when (val r = routerRepository.deleteVlan(vlanId)) {
                is RouterResult.Success -> load()
                is RouterResult.Error -> _state.update { it.copy(operationInProgress = false, error = r.message) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VlanManagerScreen(
    onBack: () -> Unit,
    viewModel: VlanManagerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    if (state.showAddDialog) {
        AddVlanDialog(
            onConfirm = { vlanId, name, gateway, dhcp, internet, isolation ->
                viewModel.createVlan(vlanId, name, gateway, dhcp, internet, isolation)
            },
            onDismiss = viewModel::hideAddDialog
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("إدارة VLAN", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "رجوع") } },
                actions = {
                    if (!state.notSupported) {
                        IconButton(onClick = viewModel::showAddDialog, enabled = !state.operationInProgress) {
                            Icon(Icons.Default.Add, "إضافة VLAN")
                        }
                    }
                    IconButton(onClick = viewModel::load) { Icon(Icons.Default.Refresh, "تحديث") }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.notSupported -> com.weshah.ui.ports.NotSupportedMessage(
                    "إدارة VLAN", "يتطلب weshah-agent و DSA أو swconfig")
                state.error != null && state.vlans.isEmpty() ->
                    com.weshah.ui.ports.ErrorMessage(state.error!!, viewModel::load)
                else -> VlanList(state.vlans, viewModel::deleteVlan)
            }
            if (state.operationInProgress) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun VlanList(vlans: List<VlanInfo>, onDelete: (Int) -> Unit) {
    if (vlans.isEmpty()) {
        com.weshah.ui.ports.EmptyMessage("لا توجد VLANs مكوّنة")
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(vlans, key = { it.vlanId }) { vlan ->
            VlanCard(vlan, onDelete)
        }
    }
}

@Composable
private fun VlanCard(vlan: VlanInfo, onDelete: (Int) -> Unit) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("حذف VLAN ${vlan.vlanId}") },
            text = { Text("هل أنت متأكد من حذف VLAN \"${vlan.name}\"؟ لا يمكن التراجع عن هذا الإجراء.") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete(vlan.vlanId) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("حذف")
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("إلغاء") } }
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("${vlan.vlanId}", fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(vlan.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                    vlan.gateway?.let { gw ->
                        Text("GW: $gw", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(Icons.Default.Delete, "حذف", tint = MaterialTheme.colorScheme.error)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VlanChip("DHCP", vlan.dhcpEnabled)
                VlanChip("Internet", vlan.internetAccess)
                VlanChip("Isolation", vlan.clientIsolation)
            }
            if (vlan.taggedPorts.isNotEmpty()) {
                Text("Tagged: ${vlan.taggedPorts.joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun VlanChip(label: String, active: Boolean) {
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Surface(shape = MaterialTheme.shapes.small, color = color.copy(alpha = 0.15f)) {
        Text(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddVlanDialog(
    onConfirm: (Int, String, String?, Boolean, Boolean, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var vlanIdText by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var gateway by remember { mutableStateOf("") }
    var dhcpEnabled by remember { mutableStateOf(true) }
    var internetAccess by remember { mutableStateOf(true) }
    var clientIsolation by remember { mutableStateOf(false) }

    val vlanId = vlanIdText.toIntOrNull()
    val vlanIdError = vlanId == null || vlanId !in 2..4094
    val nameError = name.isBlank()
    val gatewayError = gateway.isNotBlank() && !IpUtils.isValidIp(gateway)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة VLAN جديد") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = vlanIdText, onValueChange = { vlanIdText = it },
                    label = { Text("VLAN ID (2-4094)") },
                    isError = vlanIdText.isNotBlank() && vlanIdError,
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("الاسم") },
                    isError = name.isNotBlank() && nameError,
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = gateway, onValueChange = { gateway = it },
                    label = { Text("Default Gateway (اختياري)") },
                    isError = gateway.isNotBlank() && gatewayError,
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()) {
                    Text("DHCP", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = dhcpEnabled, onCheckedChange = { dhcpEnabled = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()) {
                    Text("وصول للإنترنت", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = internetAccess, onCheckedChange = { internetAccess = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()) {
                    Text("عزل العملاء", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = clientIsolation, onCheckedChange = { clientIsolation = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!vlanIdError && !nameError && !gatewayError) {
                        onConfirm(vlanId!!, name, gateway.takeIf { it.isNotBlank() },
                            dhcpEnabled, internetAccess, clientIsolation)
                    }
                },
                enabled = !vlanIdError && !nameError && !gatewayError
            ) { Text("إنشاء") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}
