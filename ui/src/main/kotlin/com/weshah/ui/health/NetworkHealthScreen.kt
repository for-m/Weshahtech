package com.weshah.ui.health

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
import com.weshah.domain.engine.HealthEngine
import com.weshah.router.api.RouterErrorCode
import com.weshah.router.api.RouterResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HealthUiState(
    val report: NetworkHealthReport? = null,
    val isRunning: Boolean = false,
    val error: String? = null,
    val notSupported: Boolean = false
)

@HiltViewModel
class NetworkHealthViewModel @Inject constructor(
    private val healthEngine: HealthEngine
) : ViewModel() {

    private val _state = MutableStateFlow(HealthUiState(isRunning = true))
    val state: StateFlow<HealthUiState> = _state.asStateFlow()

    init { runCheck() }

    fun runCheck() {
        viewModelScope.launch {
            _state.update { it.copy(isRunning = true, error = null) }
            when (val r = healthEngine.runHealthCheck()) {
                is RouterResult.Success -> _state.update { it.copy(report = r.data, isRunning = false) }
                is RouterResult.Error -> {
                    val notSupported = r.code == RouterErrorCode.NOT_SUPPORTED
                    _state.update { it.copy(isRunning = false, error = r.message, notSupported = notSupported) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkHealthScreen(
    onBack: () -> Unit,
    viewModel: NetworkHealthViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("صحة الشبكة", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "رجوع") } },
                actions = {
                    if (!state.isRunning) {
                        IconButton(onClick = viewModel::runCheck) { Icon(Icons.Default.Refresh, "تحديث") }
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isRunning -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("جارٍ فحص الشبكة...", style = MaterialTheme.typography.bodyMedium)
                }
                state.notSupported -> com.weshah.ui.ports.NotSupportedMessage(
                    "فحص صحة الشبكة", "يتطلب weshah-agent")
                state.error != null -> com.weshah.ui.ports.ErrorMessage(state.error!!, viewModel::runCheck)
                state.report != null -> HealthReport(state.report!!)
            }
        }
    }
}

@Composable
private fun HealthReport(report: NetworkHealthReport) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

        item { WanStatusCard(report) }
        item { LatencyCard(report) }

        if (report.routerCpuPercent != null || report.routerTemperatureCelsius != null) {
            item { RouterResourcesCard(report) }
        }

        if (report.diagnosedIssues.isNotEmpty()) {
            item {
                Text("المشاكل المكتشفة (${report.diagnosedIssues.size})",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            items(report.diagnosedIssues) { issue ->
                IssueCard(issue)
            }
        } else {
            item {
                Card(modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF22C55E).copy(alpha = 0.1f))) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF22C55E))
                        Text("لا توجد مشاكل مكتشفة", fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun WanStatusCard(report: NetworkHealthReport) {
    val (color, text) = when (report.wanState) {
        WanHealthState.CONNECTED -> Color(0xFF22C55E) to "متصل"
        WanHealthState.DEGRADED -> Color(0xFFF59E0B) to "متدهور"
        WanHealthState.DISCONNECTED -> MaterialTheme.colorScheme.error to "منقطع"
        WanHealthState.UNKNOWN -> MaterialTheme.colorScheme.outline to "غير معروف"
    }
    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.NetworkCheck, null, tint = color, modifier = Modifier.size(32.dp))
            Column {
                Text("حالة الإنترنت", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text, style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, color = color)
            }
            Spacer(Modifier.weight(1f))
            Text("${report.activeClientCount} جهاز",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LatencyCard(report: NetworkHealthReport) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("زمن الاستجابة", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LatencyMetric("Gateway", report.gatewayLatencyMs)
                LatencyMetric("Internet", report.internetLatencyMs)
                LatencyMetric("DNS", report.dnsLatencyMs)
                LatencyMetric("Jitter", report.jitterMs)
            }
            report.packetLossPercent?.let { loss ->
                if (loss > 0f) {
                    val lossColor = if (loss > 5f) MaterialTheme.colorScheme.error else Color(0xFFF59E0B)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.Warning, null, tint = lossColor, modifier = Modifier.size(16.dp))
                        Text("Packet Loss: %.1f%%".format(loss), style = MaterialTheme.typography.bodySmall,
                            color = lossColor, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun LatencyMetric(label: String, ms: Float?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ms != null) {
            val color = when {
                ms > 150 -> MaterialTheme.colorScheme.error
                ms > 50 -> Color(0xFFF59E0B)
                else -> Color(0xFF22C55E)
            }
            Text("${ms.toInt()}ms", style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold, color = color)
        } else {
            Text("—", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun RouterResourcesCard(report: NetworkHealthReport) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("موارد الراوتر", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                report.routerCpuPercent?.let { cpu ->
                    val color = if (cpu > 80) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("CPU", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("%.0f%%".format(cpu), style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold, color = color)
                    }
                }
                if (report.routerRamFreeKb != null && report.routerRamTotalKb != null && report.routerRamTotalKb > 0) {
                    val usedPercent = 100f * (report.routerRamTotalKb - report.routerRamFreeKb) / report.routerRamTotalKb
                    val ramColor = if (usedPercent > 85) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("RAM", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("%.0f%%".format(usedPercent), style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold, color = ramColor)
                    }
                }
                report.routerTemperatureCelsius?.let { temp ->
                    val tempColor = when {
                        temp > 80f -> MaterialTheme.colorScheme.error
                        temp > 70f -> Color(0xFFF59E0B)
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Temp", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${temp.toInt()}°C", style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold, color = tempColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun IssueCard(issue: NetworkIssue) {
    val (color, icon) = when (issue.severity) {
        IssueSeverity.CRITICAL -> MaterialTheme.colorScheme.error to Icons.Default.Error
        IssueSeverity.WARNING -> Color(0xFFF59E0B) to Icons.Default.Warning
        IssueSeverity.INFO -> MaterialTheme.colorScheme.primary to Icons.Default.Info
    }
    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.08f))) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
                Text(issue.title, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Surface(shape = MaterialTheme.shapes.small, color = color.copy(alpha = 0.15f)) {
                    Text(issue.category.name, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall, color = color)
                }
            }
            Text(issue.description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (issue.recommendation.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Lightbulb, null, tint = Color(0xFFF59E0B),
                        modifier = Modifier.size(14.dp).padding(top = 2.dp))
                    Text(issue.recommendation, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
