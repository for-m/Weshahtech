package com.weshah.ui.alerts

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
import com.weshah.core.models.Alert
import com.weshah.core.models.AlertSeverity
import com.weshah.domain.engine.AlertEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlertsViewModel @Inject constructor(
    private val alertEngine: AlertEngine
) : ViewModel() {

    val alerts: StateFlow<List<Alert>> = alertEngine.observeAlerts()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val unreadCount: StateFlow<Int> = alertEngine.observeUnreadCount()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    fun markRead(id: String) = viewModelScope.launch { alertEngine.markRead(id) }
    fun markAllRead() = viewModelScope.launch { alertEngine.markAllRead() }
    fun resolve(id: String) = viewModelScope.launch { alertEngine.resolveAlert(id) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(
    onBack: () -> Unit,
    viewModel: AlertsViewModel = hiltViewModel()
) {
    val alerts by viewModel.alerts.collectAsState()
    val unreadCount by viewModel.unreadCount.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("التنبيهات", fontWeight = FontWeight.Bold)
                        if (unreadCount > 0) {
                            Badge { Text("$unreadCount") }
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "رجوع") } },
                actions = {
                    if (unreadCount > 0) {
                        TextButton(onClick = viewModel::markAllRead) { Text("قراءة الكل") }
                    }
                }
            )
        }
    ) { padding ->
        if (alerts.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.NotificationsNone, null, modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outlineVariant)
                    Text("لا توجد تنبيهات نشطة", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(alerts, key = { it.id }) { alert ->
                    AlertCard(
                        alert = alert,
                        onRead = { viewModel.markRead(alert.id) },
                        onResolve = { viewModel.resolve(alert.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AlertCard(alert: Alert, onRead: () -> Unit, onResolve: () -> Unit) {
    val severityColor = when (alert.severity) {
        AlertSeverity.CRITICAL -> MaterialTheme.colorScheme.error
        AlertSeverity.WARNING -> Color(0xFFF59E0B)
        AlertSeverity.INFO -> MaterialTheme.colorScheme.primary
    }
    val severityIcon = when (alert.severity) {
        AlertSeverity.CRITICAL -> Icons.Default.Error
        AlertSeverity.WARNING -> Icons.Default.Warning
        AlertSeverity.INFO -> Icons.Default.Info
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (!alert.isRead) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                             else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(severityIcon, null, tint = severityColor, modifier = Modifier.size(20.dp))
                Text(alert.title, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (!alert.isRead) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f))
                if (!alert.isRead) {
                    Box(modifier = Modifier.size(8.dp),
                        contentAlignment = Alignment.Center) {
                        Surface(shape = MaterialTheme.shapes.small, color = severityColor,
                            modifier = Modifier.size(8.dp)) {}
                    }
                }
            }
            Text(alert.message, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!alert.isRead) {
                    OutlinedButton(onClick = onRead, modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Text("تحديد كمقروء", style = MaterialTheme.typography.labelSmall)
                    }
                }
                OutlinedButton(onClick = onResolve, modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Text("حل", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
