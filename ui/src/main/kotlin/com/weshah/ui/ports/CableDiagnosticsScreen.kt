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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weshah.core.models.*
import com.weshah.domain.engine.PortEngine
import com.weshah.router.api.RouterErrorCode
import com.weshah.router.api.RouterResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CableDiagUiState(
    val portId: String = "",
    val result: CableDiagResult? = null,
    val isRunning: Boolean = false,
    val error: String? = null,
    val notSupported: Boolean = false,
    val unsupportedReason: String? = null
)

@HiltViewModel
class CableDiagViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val portEngine: PortEngine
) : ViewModel() {

    private val portId: String = checkNotNull(savedStateHandle["portId"])

    private val _state = MutableStateFlow(CableDiagUiState(portId = portId))
    val state: StateFlow<CableDiagUiState> = _state.asStateFlow()

    init { runDiagnostics() }

    fun runDiagnostics() {
        viewModelScope.launch {
            _state.update { it.copy(isRunning = true, error = null) }
            when (val r = portEngine.runCableDiagnostics(portId)) {
                is RouterResult.Success -> {
                    val result = r.data
                    if (!result.supported) {
                        _state.update { it.copy(
                            isRunning = false,
                            notSupported = true,
                            unsupportedReason = result.unsupportedReason,
                            result = result
                        )}
                    } else {
                        _state.update { it.copy(isRunning = false, result = result) }
                    }
                }
                is RouterResult.Error -> {
                    val notSupported = r.code == RouterErrorCode.NOT_SUPPORTED
                    _state.update { it.copy(
                        isRunning = false,
                        error = r.message,
                        notSupported = notSupported
                    )}
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CableDiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: CableDiagViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("فحص الكابل", fontWeight = FontWeight.Bold)
                        Text(state.portId, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "رجوع") } },
                actions = {
                    if (!state.isRunning && !state.notSupported) {
                        IconButton(onClick = viewModel::runDiagnostics) {
                            Icon(Icons.Default.Refresh, "إعادة الفحص")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isRunning -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("جارٍ فحص الكابل...", style = MaterialTheme.typography.bodyMedium)
                    Text("قد يستغرق حتى 10 ثوانٍ", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                state.notSupported -> {
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
                        Text("TDR — Cable Diagnostics", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        state.unsupportedReason?.let {
                            Spacer(Modifier.height(8.dp))
                            Card(colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))) {
                                Text(it, modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                        // Show basic link info if available from the result
                        state.result?.let { r ->
                            Spacer(Modifier.height(24.dp))
                            BasicLinkInfo(r)
                        }
                    }
                }

                state.error != null && state.result == null -> ErrorMessage(state.error!!, viewModel::runDiagnostics)

                state.result != null -> CableDiagResult(state.result!!)
            }
        }
    }
}

@Composable
private fun CableDiagResult(result: CableDiagResult) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

        item {
            CableStatusCard(result)
        }

        if (result.pairs.isNotEmpty()) {
            item {
                Text("نتائج الأزواج", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold)
            }
            items(result.pairs) { pair ->
                PairResultCard(pair)
            }
        }

        item {
            BasicLinkInfo(result)
        }
    }
}

@Composable
private fun CableStatusCard(result: CableDiagResult) {
    val (statusColor, statusText, statusIcon) = when (result.cableStatus) {
        CableStatus.CONNECTED -> Triple(Color(0xFF22C55E), "متصل", Icons.Default.CheckCircle)
        CableStatus.OPEN -> Triple(MaterialTheme.colorScheme.error, "مفتوح / منقطع", Icons.Default.Error)
        CableStatus.SHORT -> Triple(MaterialTheme.colorScheme.error, "قصر كهربائي", Icons.Default.Warning)
        CableStatus.IMPEDANCE_MISMATCH -> Triple(Color(0xFFF59E0B), "تعارض مقاومة", Icons.Default.Warning)
        CableStatus.UNKNOWN -> Triple(MaterialTheme.colorScheme.outline, "غير معروف", Icons.Default.Help)
    }

    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.1f))) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(statusIcon, null, tint = statusColor, modifier = Modifier.size(40.dp))
            Column {
                Text(statusText, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, color = statusColor)
                result.estimatedLengthMeters?.let { len ->
                    Text("طول الكابل: ${len.toInt()} متر",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun BasicLinkInfo(result: CableDiagResult) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("معلومات الرابط", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            result.linkSpeedMbps?.let { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("السرعة:", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
                Text("${it} Mbps", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            }}
            result.duplexFull?.let { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Duplex:", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
                Text(if (it) "Full" else "Half", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            }}
            if (result.crcErrors > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("CRC Errors:", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    Text("${result.crcErrors}", style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun PairResultCard(pair: PairResult) {
    val pairColor = when (pair.status) {
        PairStatus.OK -> Color(0xFF22C55E)
        PairStatus.OPEN, PairStatus.SHORT -> MaterialTheme.colorScheme.error
        PairStatus.CROSSTALK -> Color(0xFFF59E0B)
        PairStatus.UNKNOWN -> MaterialTheme.colorScheme.outline
    }

    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = MaterialTheme.shapes.small, color = pairColor.copy(alpha = 0.15f),
            modifier = Modifier.size(48.dp, 32.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text("Pair\n${pair.pair}", style = MaterialTheme.typography.labelSmall,
                    color = pairColor, fontWeight = FontWeight.Bold)
            }
        }
        Text(pair.status.name, style = MaterialTheme.typography.bodySmall,
            color = pairColor, modifier = Modifier.weight(1f))
        pair.faultDistanceMeters?.let {
            Text("${it.toInt()}m", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
