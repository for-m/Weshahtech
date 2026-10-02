package com.weshah.ui.ports

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
import com.weshah.core.models.*
import com.weshah.domain.engine.PortEngine
import com.weshah.router.api.RouterResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PortManagerUiState(
    val ports: List<PortInfo> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val notSupported: Boolean = false
)

@HiltViewModel
class PortManagerViewModel @Inject constructor(
    private val portEngine: PortEngine
) : ViewModel() {

    private val _state = MutableStateFlow(PortManagerUiState(isLoading = true))
    val state: StateFlow<PortManagerUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            when (val r = portEngine.getPorts()) {
                is RouterResult.Success -> _state.update { it.copy(ports = r.data, isLoading = false) }
                is RouterResult.Error -> {
                    val notSupported = r.code == com.weshah.router.api.RouterErrorCode.NOT_SUPPORTED
                    _state.update { it.copy(isLoading = false, error = r.message, notSupported = notSupported) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortManagerScreen(
    onBack: () -> Unit,
    onRunDiagnostics: (portId: String) -> Unit,
    viewModel: PortManagerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("مدير المنافذ", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "رجوع") } },
                actions = {
                    IconButton(onClick = viewModel::load) { Icon(Icons.Default.Refresh, "تحديث") }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.notSupported -> NotSupportedMessage(
                    "إحصائيات المنافذ",
                    "يتطلب weshah-agent وعتاد يدعم port stats"
                )
                state.error != null -> ErrorMessage(state.error!!, viewModel::load)
                state.ports.isEmpty() -> EmptyMessage("لم يتم اكتشاف منافذ")
                else -> PortList(state.ports, onRunDiagnostics)
            }
        }
    }
}

@Composable
private fun PortList(ports: List<PortInfo>, onRunDiagnostics: (String) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(ports, key = { it.portId }) { port ->
            PortCard(port, onRunDiagnostics)
        }
    }
}

@Composable
private fun PortCard(port: PortInfo, onRunDiagnostics: (String) -> Unit) {
    val linkColor = if (port.isUp) Color(0xFF22C55E) else MaterialTheme.colorScheme.outline

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.SettingsEthernet, null, tint = linkColor, modifier = Modifier.size(20.dp))
                Text(port.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                Surface(shape = MaterialTheme.shapes.small, color = linkColor.copy(alpha = 0.15f)) {
                    Text(
                        if (port.isUp) "${port.speedMbps ?: "?"}Mbps" else "Down",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = linkColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            port.connectedMac?.let { mac ->
                Text("MAC: $mac", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (port.rxBytes > 0 || port.txBytes > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatChip("RX", formatBytes(port.rxBytes))
                    StatChip("TX", formatBytes(port.txBytes))
                    val totalErrors = port.rxErrors + port.txErrors + port.crcErrors
                    if (totalErrors > 0) StatChip("Errors", "$totalErrors", isError = true)
                }
            }

            if (port.isUp) {
                OutlinedButton(
                    onClick = { onRunDiagnostics(port.portId) },
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(Icons.Default.Cable, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("فحص الكابل", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, isError: Boolean = false) {
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
fun NotSupportedMessage(feature: String, reason: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Memory, null, modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))
        Text("غير مدعوم بالعتاد", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(feature, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(reason, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
fun ErrorMessage(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.ErrorOutline, null, modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("إعادة المحاولة") }
    }
}

@Composable
fun EmptyMessage(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
