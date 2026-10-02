package com.weshah.ui.topology

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weshah.core.models.*
import com.weshah.domain.engine.TopologyEngine
import com.weshah.router.api.RouterErrorCode
import com.weshah.router.api.RouterResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TopologyUiState(
    val nodes: List<TopologyNode> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val notSupported: Boolean = false
)

@HiltViewModel
class NetworkTopologyViewModel @Inject constructor(
    private val topologyEngine: TopologyEngine
) : ViewModel() {

    private val _state = MutableStateFlow(TopologyUiState(isLoading = true))
    val state: StateFlow<TopologyUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            when (val r = topologyEngine.buildTopology()) {
                is RouterResult.Success -> _state.update { it.copy(nodes = r.data, isLoading = false) }
                is RouterResult.Error -> {
                    val ns = r.code == RouterErrorCode.NOT_SUPPORTED
                    _state.update { it.copy(isLoading = false, error = r.message, notSupported = ns) }
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            topologyEngine.refreshLldp()
            load()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkTopologyScreen(
    onBack: () -> Unit,
    viewModel: NetworkTopologyViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("طوبولوجيا الشبكة", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "رجوع") } },
                actions = { IconButton(onClick = viewModel::refresh) { Icon(Icons.Default.Refresh, "تحديث") } }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.notSupported -> com.weshah.ui.ports.NotSupportedMessage(
                    "LLDP / طوبولوجيا الشبكة",
                    "يتطلب تفعيل LLDP على الراوتر أو weshah-agent"
                )
                state.error != null && state.nodes.isEmpty() ->
                    com.weshah.ui.ports.ErrorMessage(state.error!!, viewModel::load)
                state.nodes.isEmpty() -> EmptyTopology()
                else -> TopologyList(state.nodes)
            }
        }
    }
}

@Composable
private fun EmptyTopology() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.AccountTree, null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.outline)
            Text("لم يُعثر على جيران LLDP",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("تأكد من تفعيل LLDP على الأجهزة المتصلة",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TopologyList(nodes: List<TopologyNode>) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            // Router root node (always present)
            TopologyNodeCard(
                icon = Icons.Default.Router,
                label = "الراوتر (هذا الجهاز)",
                sublabel = "جذر الشبكة",
                badge = null,
                confidence = TopologyConfidence.CONFIRMED,
                depth = 0
            )
        }
        items(nodes, key = { it.id }) { node ->
            TopologyNodeCard(
                icon = deviceTypeIcon(node.deviceType),
                label = node.label,
                sublabel = buildSublabel(node),
                badge = node.linkSpeedMbps?.let { "${it} Mbps" },
                confidence = node.connectionConfidence,
                depth = node.depth
            )
        }
    }
}

@Composable
private fun TopologyNodeCard(
    icon: ImageVector,
    label: String,
    sublabel: String,
    badge: String?,
    confidence: TopologyConfidence,
    depth: Int
) {
    val indent = (depth * 16).dp
    Card(modifier = Modifier.fillMaxWidth().padding(start = indent)) {
        Row(modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium)
                Text(sublabel, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                badge?.let {
                    Surface(shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(it, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                val (confColor, confLabel) = when (confidence) {
                    TopologyConfidence.CONFIRMED -> MaterialTheme.colorScheme.primary to "LLDP"
                    TopologyConfidence.INFERRED -> MaterialTheme.colorScheme.tertiary to "ARP"
                    TopologyConfidence.GUESSED -> MaterialTheme.colorScheme.outline to "?"
                }
                Surface(shape = MaterialTheme.shapes.small,
                    color = confColor.copy(alpha = 0.15f)) {
                    Text(confLabel,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall, color = confColor)
                }
            }
        }
    }
}

private fun buildSublabel(node: TopologyNode): String {
    val parts = mutableListOf<String>()
    node.ipAddress?.let { parts.add(it) }
    node.portId?.let { parts.add("Port: $it") }
    node.vlanId?.let { parts.add("VLAN $it") }
    parts.add("via ${node.inferredVia}")
    return parts.joinToString(" · ")
}

private fun deviceTypeIcon(type: DeviceType): ImageVector = when (type) {
    DeviceType.ROUTER -> Icons.Default.Router
    DeviceType.COMPUTER -> Icons.Default.Computer
    DeviceType.PHONE -> Icons.Default.PhoneAndroid
    DeviceType.TABLET -> Icons.Default.TabletMac
    DeviceType.TV -> Icons.Default.Tv
    DeviceType.PRINTER -> Icons.Default.Print
    DeviceType.CAMERA -> Icons.Default.CameraAlt
    DeviceType.AP -> Icons.Default.Wifi
    DeviceType.NAS -> Icons.Default.Storage
    DeviceType.SMART_HOME -> Icons.Default.Home
    DeviceType.GAME_CONSOLE -> Icons.Default.SportsEsports
    DeviceType.UNKNOWN -> Icons.Default.DeviceHub
}
