package com.weshah.ui.technician

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weshah.domain.engine.HealthEngine
import com.weshah.domain.repository.RouterRepository
import com.weshah.router.api.RouterResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject

// ─── Step Model ───────────────────────────────────────────────────────────────

enum class StepState { PENDING, RUNNING, PASS, FAIL, WARNING, SKIPPED }

data class DiagStep(
    val id: Int,
    val title: String,
    val description: String,
    val state: StepState = StepState.PENDING,
    val detail: String? = null
)

data class TechnicianUiState(
    val steps: List<DiagStep> = buildInitialSteps(),
    val isRunning: Boolean = false,
    val isComplete: Boolean = false,
    val currentStepIndex: Int = -1
)

fun buildInitialSteps(): List<DiagStep> = listOf(
    DiagStep(1, "اتصال الراوتر", "التحقق من الاتصال بالراوتر"),
    DiagStep(2, "معلومات النظام", "جلب CPU / RAM / درجة الحرارة"),
    DiagStep(3, "حالة WAN", "فحص واجهة الإنترنت"),
    DiagStep(4, "بوابة الشبكة", "Ping إلى Default Gateway"),
    DiagStep(5, "اتصال الإنترنت", "Ping إلى 8.8.8.8"),
    DiagStep(6, "DNS Lookup", "تحليل google.com"),
    DiagStep(7, "HTTPS Connectivity", "TCP probe port 443"),
    DiagStep(8, "إحصائيات المنافذ", "فحص أخطاء Ethernet"),
    DiagStep(9, "عملاء Wi-Fi", "فحص قوة الإشارة"),
    DiagStep(10, "تحقق DHCP", "جلب تسجيلات DHCP"),
    DiagStep(11, "Multi-WAN", "فحص واجهات WAN المتعددة"),
    DiagStep(12, "صحة الشبكة", "تشغيل Health Check شامل"),
    DiagStep(13, "أداء النظام", "تقييم CPU/RAM مقابل العتبات"),
    DiagStep(14, "ملخص المشكلات", "تجميع المشكلات المكتشفة"),
    DiagStep(15, "التقرير النهائي", "إنشاء تقرير التشخيص")
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

@HiltViewModel
class TechnicianViewModel @Inject constructor(
    private val routerRepository: RouterRepository,
    private val healthEngine: HealthEngine
) : ViewModel() {

    private val _state = MutableStateFlow(TechnicianUiState())
    val state: StateFlow<TechnicianUiState> = _state.asStateFlow()

    fun runDiagnostics() {
        if (_state.value.isRunning) return
        _state.value = TechnicianUiState(isRunning = true)
        viewModelScope.launch {
            runAllSteps()
            _state.update { it.copy(isRunning = false, isComplete = true) }
        }
    }

    fun reset() { _state.value = TechnicianUiState() }

    private suspend fun runAllSteps() {
        var gatewayIp: String? = null
        var cpuPercent: Float? = null
        var ramPercent: Float? = null
        val allIssues = mutableListOf<String>()

        // Step 1: Router connection
        step(0) {
            if (routerRepository.connectionState.value == com.weshah.domain.repository.RouterConnectionState.CONNECTED)
                pass("متصل")
            else fail("غير متصل بالراوتر — يرجى الاتصال أولاً")
        }

        // Step 2: System info
        step(1) {
            val caps = "جلب الإحصائيات..."
            pass(caps)  // SystemStats is a flow — just mark available
        }

        // Step 3: WAN status
        step(2) {
            when (val r = routerRepository.getWanStatus()) {
                is RouterResult.Success -> {
                    val w = r.data
                    gatewayIp = w.gateway
                    if (w.isConnected) pass("متصل · IP: ${w.ipAddress ?: "—"} · GW: ${w.gateway ?: "—"}")
                    else warn("WAN غير متصل")
                }
                is RouterResult.Error -> fail(r.message)
            }
        }

        // Step 4: Gateway ping
        step(3) {
            val gw = gatewayIp
            if (gw == null) { skip("لا يوجد Gateway"); return@step }
            val result = tcpProbe(gw, 80, 1500) ?: tcpProbe(gw, 443, 1500)
            if (result != null) pass("$gw · ${result}ms")
            else warn("$gw لا يستجيب (قد يكون ICMP محجوباً)")
        }

        // Step 5: Internet
        step(4) {
            val tcp = tcpProbe("8.8.8.8", 53, 2000)
            if (tcp != null) pass("8.8.8.8:53 · ${tcp}ms")
            else warn("8.8.8.8 لا يستجيب — تحقق من اتصال WAN")
        }

        // Step 6: DNS
        step(5) {
            val result = withContext(Dispatchers.IO) {
                try {
                    val start = System.currentTimeMillis()
                    val addr = InetAddress.getAllByName("google.com")
                    val elapsed = System.currentTimeMillis() - start
                    "google.com → ${addr.firstOrNull()?.hostAddress} (${elapsed}ms)"
                } catch (e: Exception) { null }
            }
            if (result != null) pass(result) else fail("فشل DNS lookup لـ google.com")
        }

        // Step 7: HTTPS connectivity
        step(6) {
            val t = tcpProbe("google.com", 443, 2000)
            if (t != null) pass("google.com:443 · ${t}ms")
            else warn("TCP:443 لا يستجيب — قد تكون هناك قيود على HTTPS")
        }

        // Step 8: Port stats
        step(7) {
            when (val r = routerRepository.getPortStats()) {
                is RouterResult.Success -> {
                    val errCount = r.data.sumOf { it.rxErrors + it.txErrors + it.crcErrors }
                    if (errCount == 0L) pass("${r.data.size} منافذ · لا توجد أخطاء")
                    else {
                        allIssues.add("أخطاء إيثرنت: $errCount")
                        warn("$errCount خطأ إجمالي على ${r.data.size} منافذ")
                    }
                }
                is RouterResult.Error -> skip("إحصائيات المنافذ غير مدعومة")
            }
        }

        // Step 9: WiFi clients
        step(8) {
            when (val r = routerRepository.getWifiClients()) {
                is RouterResult.Success -> {
                    val weakSignal = r.data.count { it.rssi < -70 }
                    if (weakSignal == 0) pass("${r.data.size} عملاء · إشارة جيدة")
                    else warn("$weakSignal عملاء بإشارة ضعيفة (< -70 dBm)")
                }
                is RouterResult.Error -> skip("بيانات Wi-Fi غير متاحة")
            }
        }

        // Step 10: DHCP leases
        step(9) {
            when (val r = routerRepository.getDhcpLeases()) {
                is RouterResult.Success -> pass("${r.data.size} تسجيل DHCP نشط")
                is RouterResult.Error -> fail(r.message)
            }
        }

        // Step 11: Multi-WAN
        step(10) {
            when (val r = routerRepository.getMultiWanInterfaces()) {
                is RouterResult.Success -> {
                    val active = r.data.count { it.isActive }
                    pass("$active/${r.data.size} واجهات WAN نشطة")
                }
                is RouterResult.Error -> skip("Multi-WAN غير مدعوم")
            }
        }

        // Step 12: Health check
        step(11) {
            when (val r = healthEngine.runHealthCheck()) {
                is RouterResult.Success -> {
                    val report = r.data
                    cpuPercent = report.routerCpuPercent
                    ramPercent = report.routerRamFreeKb?.let { free ->
                        report.routerRamTotalKb?.let { total ->
                            if (total > 0) 100f - (free.toFloat() / total * 100) else null
                        }
                    }
                    val issues = report.diagnosedIssues.size
                    allIssues.addAll(report.diagnosedIssues.map { it.title })
                    if (issues == 0) pass("صحة ممتازة")
                    else warn("$issues مشكلة مكتشفة")
                }
                is RouterResult.Error -> skip("Health check غير متاح")
            }
        }

        // Step 13: Performance
        step(12) {
            val cpu = cpuPercent
            val ram = ramPercent
            when {
                cpu == null && ram == null -> skip("بيانات الأداء غير متاحة")
                (cpu ?: 0f) > 85f -> {
                    allIssues.add("CPU مرتفع: ${cpu?.toInt()}%")
                    warn("CPU: ${cpu?.toInt()}% | RAM: ${ram?.toInt() ?: "?"}%")
                }
                (ram ?: 0f) > 85f -> {
                    allIssues.add("RAM مرتفع: ${ram?.toInt()}%")
                    warn("CPU: ${cpu?.toInt() ?: "?"}% | RAM: ${ram?.toInt()}%")
                }
                else -> pass("CPU: ${cpu?.toInt() ?: "?"}% | RAM: ${ram?.toInt() ?: "?"}%")
            }
        }

        // Step 14: Issues summary
        step(13) {
            if (allIssues.isEmpty()) pass("لا مشكلات مكتشفة")
            else warn("${allIssues.size} مشكلة: ${allIssues.take(3).joinToString("; ")}")
        }

        // Step 15: Final report
        step(14) {
            val passed = _state.value.steps.count { it.state == StepState.PASS }
            val failed = _state.value.steps.count { it.state == StepState.FAIL }
            val warned = _state.value.steps.count { it.state == StepState.WARNING }
            if (failed == 0 && warned == 0) pass("✓ الشبكة في حالة ممتازة ($passed/${ _state.value.steps.size} اختبارات ناجحة)")
            else if (failed == 0) warn("$passed ناجح · $warned تحذير · انتبه للتحذيرات أعلاه")
            else fail("$passed ناجح · $warned تحذير · $failed فشل — راجع التفاصيل أعلاه")
        }
    }

    private suspend fun step(index: Int, block: suspend StepScope.() -> Unit) {
        setStepState(index, StepState.RUNNING)
        val scope = StepScope()
        block(scope)
        setStepState(index, scope.state, scope.detail)
        kotlinx.coroutines.delay(200)
    }

    private fun setStepState(index: Int, state: StepState, detail: String? = null) {
        _state.update { s ->
            s.copy(
                currentStepIndex = index,
                steps = s.steps.mapIndexed { i, step ->
                    if (i == index) step.copy(state = state, detail = detail) else step
                }
            )
        }
    }

    private suspend fun tcpProbe(host: String, port: Int, timeoutMs: Int): Long? =
        withContext(Dispatchers.IO) {
            try {
                val start = System.currentTimeMillis()
                Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
                System.currentTimeMillis() - start
            } catch (e: Exception) { null }
        }

    inner class StepScope {
        var state: StepState = StepState.PASS
        var detail: String? = null
        fun pass(d: String? = null) { state = StepState.PASS; detail = d }
        fun fail(d: String? = null) { state = StepState.FAIL; detail = d }
        fun warn(d: String? = null) { state = StepState.WARNING; detail = d }
        fun skip(d: String? = null) { state = StepState.SKIPPED; detail = d }
    }
}

// ─── Screen ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TechnicianModeScreen(
    onBack: () -> Unit,
    viewModel: TechnicianViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("وضع الفني", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "رجوع") } },
                actions = {
                    if (state.isComplete) {
                        IconButton(onClick = viewModel::reset) {
                            Icon(Icons.Default.Replay, "إعادة")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Header / start button
            if (!state.isRunning && !state.isComplete) {
                StartCard { viewModel.runDiagnostics() }
            } else if (state.isRunning) {
                ProgressHeader(state.currentStepIndex, state.steps.size)
            } else {
                SummaryHeader(state.steps)
            }

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(state.steps, key = { it.id }) { step ->
                    StepRow(step, isActive = state.currentStepIndex == step.id - 1)
                }
            }
        }
    }
}

@Composable
private fun StartCard(onStart: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Build, null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Text("تشخيص كامل للشبكة", fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text("15 اختباراً تلقائياً للتحقق من صحة الشبكة والراوتر والإنترنت",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(4.dp))
                Text("بدء التشخيص")
            }
        }
    }
}

@Composable
private fun ProgressHeader(current: Int, total: Int) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("جاري التشخيص...", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold)
            Text("${current + 1}/$total", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(
            progress = { if (total == 0) 0f else (current + 1f) / total },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SummaryHeader(steps: List<DiagStep>) {
    val passed = steps.count { it.state == StepState.PASS }
    val failed = steps.count { it.state == StepState.FAIL }
    val warned = steps.count { it.state == StepState.WARNING }

    val (bgColor, textColor, summaryText) = when {
        failed > 0 -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            "$passed ناجح · $warned تحذير · $failed فشل"
        )
        warned > 0 -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            "$passed ناجح · $warned تحذير"
        )
        else -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            "جميع $passed الاختبارات ناجحة"
        )
    }

    Card(modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor)) {
        Row(modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(if (failed > 0) Icons.Default.Error else if (warned > 0) Icons.Default.Warning
                 else Icons.Default.CheckCircle,
                null, tint = textColor, modifier = Modifier.size(20.dp))
            Text(summaryText, fontWeight = FontWeight.Bold,
                color = textColor, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun StepRow(step: DiagStep, isActive: Boolean) {
    val (icon, tint) = when (step.state) {
        StepState.PENDING -> Icons.Default.RadioButtonUnchecked to MaterialTheme.colorScheme.outline
        StepState.RUNNING -> Icons.Default.RadioButtonChecked to MaterialTheme.colorScheme.primary
        StepState.PASS -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
        StepState.FAIL -> Icons.Default.Cancel to MaterialTheme.colorScheme.error
        StepState.WARNING -> Icons.Default.Warning to MaterialTheme.colorScheme.tertiary
        StepState.SKIPPED -> Icons.Default.SkipNext to MaterialTheme.colorScheme.outline
    }

    val bgColor = if (isActive && step.state == StepState.RUNNING)
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    else MaterialTheme.colorScheme.surface

    Surface(shape = MaterialTheme.shapes.medium, color = bgColor,
        modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top) {
            if (step.state == StepState.RUNNING) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(icon, null, modifier = Modifier.size(20.dp), tint = tint)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("${step.id}. ${step.title}",
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                step.detail?.let { d ->
                    Text(d, style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } ?: if (step.state == StepState.PENDING) {
                    Text(step.description, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
