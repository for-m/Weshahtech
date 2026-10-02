package com.weshah.ui.wifi

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
import com.weshah.core.models.WiFiBand
import com.weshah.domain.repository.RouterRepository
import com.weshah.router.api.RouterErrorCode
import com.weshah.router.api.RouterResult
import com.weshah.router.api.WifiClient
import com.weshah.router.api.WifiNetwork
import com.weshah.router.api.WifiRadio
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WifiAnalyzerUiState(
    val radios: List<WifiRadio> = emptyList(),
    val networks: List<WifiNetwork> = emptyList(),
    val clients: List<WifiClient> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val notSupported: Boolean = false,
    val selectedTab: Int = 0
)

@HiltViewModel
class WifiAnalyzerViewModel @Inject constructor(
    private val routerRepository: RouterRepository
) : ViewModel() {

    private val _state = MutableStateFlow(WifiAnalyzerUiState(isLoading = true))
    val state: StateFlow<WifiAnalyzerUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }

            val radiosResult = routerRepository.getWifiRadios()
            val clientsResult = routerRepository.getWifiClients()

            if (radiosResult is RouterResult.Error && radiosResult.code == RouterErrorCode.NOT_SUPPORTED) {
                _state.update { it.copy(isLoading = false, notSupported = true, error = radiosResult.message) }
                return@launch
            }

            _state.update { state ->
                state.copy(
                    isLoading = false,
                    radios = (radiosResult as? RouterResult.Success)?.data ?: state.radios,
                    clients = (clientsResult as? RouterResult.Success)?.data ?: state.clients,
                    error = (radiosResult as? RouterResult.Error)?.message
                )
            }
        }
    }

    fun selectTab(tab: Int) = _state.update { it.copy(selectedTab = tab) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiAnalyzerScreen(
    onBack: () -> Unit,
    viewModel: WifiAnalyzerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("محلل WiFi", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "رجوع") } },
                actions = { IconButton(onClick = viewModel::load) { Icon(Icons.Default.Refresh, "تحديث") } }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.notSupported -> com.weshah.ui.ports.NotSupportedMessage(
                    "WiFi Analyzer", "يتطلب weshah-agent")
                state.error != null && state.radios.isEmpty() ->
                    com.weshah.ui.ports.ErrorMessage(state.error!!, viewModel::load)
                else -> WifiContent(state, viewModel::selectTab)
            }
        }
    }
}

@Composable
private fun WifiContent(state: WifiAnalyzerUiState, onTabSelect: (Int) -> Unit) {
    Column {
        TabRow(selectedTabIndex = state.selectedTab) {
            Tab(selected = state.selectedTab == 0, onClick = { onTabSelect(0) },
                text = { Text("الراديوات") })
            Tab(selected = state.selectedTab == 1, onClick = { onTabSelect(1) },
                text = { Text("العملاء (${state.clients.size})") })
        }

        when (state.selectedTab) {
            0 -> RadioList(state.radios)
            1 -> ClientList(state.clients)
        }
    }
}

@Composable
private fun RadioList(radios: List<WifiRadio>) {
    if (radios.isEmpty()) {
        com.weshah.ui.ports.EmptyMessage("لم يتم اكتشاف راديوات WiFi")
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(radios, key = { it.name }) { radio ->
            RadioCard(radio)
        }
    }
}

@Composable
private fun RadioCard(radio: WifiRadio) {
    val bandColor = when (radio.band) {
        WiFiBand.GHZ_5 -> Color(0xFF6366F1)
        WiFiBand.GHZ_2_4 -> Color(0xFF22C55E)
        else -> MaterialTheme.colorScheme.primary
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Wifi, null, tint = bandColor, modifier = Modifier.size(24.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(radio.ssid ?: radio.name, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyLarge)
                    Text(radio.name, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(shape = MaterialTheme.shapes.small, color = bandColor.copy(alpha = 0.15f)) {
                    Text(
                        radio.band?.name?.replace("GHZ_", "") ?: "?",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = bandColor, fontWeight = FontWeight.Bold
                    )
                }
                if (!radio.isEnabled) {
                    Surface(shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.errorContainer) {
                        Text("Disabled", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                radio.channel?.let { ch ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Channel", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("$ch", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    }
                }
                radio.txPowerDbm?.let { pwr ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("TX Power", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${pwr} dBm", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    }
                }
                radio.standard?.let { std ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Standard", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(std, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ClientList(clients: List<WifiClient>) {
    if (clients.isEmpty()) {
        com.weshah.ui.ports.EmptyMessage("لا توجد أجهزة متصلة بـ WiFi")
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(clients, key = { it.macAddress }) { client ->
            WifiClientCard(client)
        }
    }
}

@Composable
private fun WifiClientCard(client: WifiClient) {
    val signalColor = when {
        client.rssi >= -50 -> Color(0xFF22C55E)
        client.rssi >= -70 -> Color(0xFFF59E0B)
        else -> MaterialTheme.colorScheme.error
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Devices, null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(client.macAddress, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold)
                client.ipAddress?.let { ip ->
                    Text(ip, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                client.ssid?.let { ssid ->
                    Text(ssid, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${client.rssi} dBm", style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold, color = signalColor)
                client.txRate?.let { tx ->
                    Text("TX: ${tx/1000}M", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
