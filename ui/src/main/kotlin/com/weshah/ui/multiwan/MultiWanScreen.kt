package com.weshah.ui.multiwan

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
import com.weshah.core.models.WanInterface
import com.weshah.domain.repository.RouterRepository
import com.weshah.router.api.RouterErrorCode
import com.weshah.router.api.RouterResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MultiWanUiState(
    val interfaces: List<WanInterface> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val notSupported: Boolean = false
)

@HiltViewModel
class MultiWanViewModel @Inject constructor(
    private val routerRepository: RouterRepository
) : ViewModel() {

    private val _state = MutableStateFlow(MultiWanUiState(isLoading = true))
    val state: StateFlow<MultiWanUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            when (val r = routerRepository.getMultiWanInterfaces()) {
                is RouterResult.Success -> _state.update { it.copy(interfaces = r.data, isLoading = false) }
                is RouterResult.Error -> {
                    val ns = r.code == RouterErrorCode.NOT_SUPPORTED
                    _state.update { it.copy(isLoading = false, error = r.message, notSupported = ns) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiWanScreen(
    onBack: () -> Unit,
    viewModel: MultiWanViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Multi-WAN", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "رجوع") } },
                actions = { IconButton(onClick = viewModel::load) { Icon(Icons.Default.Refresh, "تحديث") } }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.notSupported -> com.weshah.ui.ports.NotSupportedMessage(
                    "Multi-WAN", "يتطلب weshah-agent ومكتبة mwan3")
                state.error != null -> com.weshah.ui.ports.ErrorMessage(state.error!!, viewModel::load)
                state.interfaces.isEmpty() -> com.weshah.ui.ports.EmptyMessage("لا توجد واجهات WAN مكتشفة")
                else -> WanList(state.interfaces)
            }
        }
    }
}

@Composable
private fun WanList(interfaces: List<WanInterface>) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(interfaces, key = { it.id }) { wan ->
            WanCard(wan)
        }
    }
}

@Composable
private fun WanCard(wan: WanInterface) {
    val statusColor = when {
        wan.isActive && wan.isHealthy -> Color(0xFF22C55E)
        wan.isActive && !wan.isHealthy -> Color(0xFFF59E0B)
        else -> MaterialTheme.colorScheme.outline
    }
    val statusText = when {
        wan.isActive && wan.isHealthy -> "نشط وصحي"
        wan.isActive && !wan.isHealthy -> "نشط / متدهور"
        else -> "غير نشط"
    }

    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.06f))) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Router, null, tint = statusColor, modifier = Modifier.size(24.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(wan.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                    Text(wan.interface_, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(shape = MaterialTheme.shapes.small, color = statusColor.copy(alpha = 0.15f)) {
                    Text(statusText, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall, color = statusColor, fontWeight = FontWeight.Bold)
                }
            }

            wan.ipAddress?.let { ip ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("IP:", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(ip, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("RX", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatBytes(wan.rxBytes), style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("TX", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatBytes(wan.txBytes), style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold)
                }
                wan.latencyMs?.let { lat ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Latency", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${lat.toInt()}ms", style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = if (lat > 100) MaterialTheme.colorScheme.error else Color(0xFF22C55E))
                    }
                }
                wan.packetLossPercent?.let { loss ->
                    if (loss > 0f) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Loss", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error)
                        Text("%.1f%%".format(loss), style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Priority: ${wan.priority}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Weight: ${wan.weight}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
