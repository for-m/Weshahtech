package com.weshah.ui.timeline

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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weshah.core.models.*
import com.weshah.data.database.dao.NetworkEventDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@HiltViewModel
class NetworkTimelineViewModel @Inject constructor(
    networkEventDao: NetworkEventDao
) : ViewModel() {

    val events: StateFlow<List<NetworkEvent>> = networkEventDao.getRecentEvents(200)
        .map { list -> list.map { it.toModel() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkTimelineScreen(
    onBack: () -> Unit,
    viewModel: NetworkTimelineViewModel = hiltViewModel()
) {
    val events by viewModel.events.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("سجل الأحداث", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "رجوع") } }
            )
        }
    ) { padding ->
        if (events.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.EventNote, null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.outline)
                    Text("لا توجد أحداث مسجّلة",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(padding)
            ) {
                items(events, key = { it.id }) { event ->
                    EventCard(event)
                }
            }
        }
    }
}

@Composable
private fun EventCard(event: NetworkEvent) {
    val icon = eventIcon(event.type)
    val color = when (event.severity) {
        EventSeverity.INFO -> MaterialTheme.colorScheme.primary
        EventSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
        EventSeverity.CRITICAL -> MaterialTheme.colorScheme.error
    }
    val severityLabel = when (event.severity) {
        EventSeverity.INFO -> "معلومة"
        EventSeverity.WARNING -> "تحذير"
        EventSeverity.CRITICAL -> "حرج"
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = color.copy(alpha = 0.15f),
            modifier = Modifier.size(36.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, modifier = Modifier.size(18.dp), tint = color)
            }
        }
        Card(modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top) {
                    Text(event.message, style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(formatTime(event.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    event.deviceName?.let { name ->
                        Text(name, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    event.ipAddress?.let { ip ->
                        Text(ip, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace)
                    }
                }
                Surface(shape = MaterialTheme.shapes.small, color = color.copy(alpha = 0.15f)) {
                    Text(severityLabel,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall, color = color)
                }
            }
        }
    }
}

private fun eventIcon(type: NetworkEventType): ImageVector = when (type) {
    NetworkEventType.NEW_DEVICE_CONNECTED -> Icons.Default.DeviceHub
    NetworkEventType.KNOWN_DEVICE_ONLINE -> Icons.Default.Wifi
    NetworkEventType.DEVICE_OFFLINE -> Icons.Default.WifiOff
    NetworkEventType.ROUTER_OFFLINE -> Icons.Default.Router
    NetworkEventType.ROUTER_ONLINE -> Icons.Default.Router
    NetworkEventType.WAN_OFFLINE -> Icons.Default.CloudOff
    NetworkEventType.WAN_ONLINE -> Icons.Default.Cloud
    NetworkEventType.HIGH_TRAFFIC_DEVICE -> Icons.Default.Speed
    NetworkEventType.IP_CONFLICT_SUSPECTED -> Icons.Default.Warning
    NetworkEventType.BLOCK_APPLIED -> Icons.Default.Block
    NetworkEventType.BLOCK_REMOVED -> Icons.Default.LockOpen
    NetworkEventType.SPEED_LIMIT_APPLIED -> Icons.Default.NetworkLocked
    NetworkEventType.SPEED_LIMIT_REMOVED -> Icons.Default.NetworkCheck
    NetworkEventType.SCAN_COMPLETED -> Icons.Default.Search
}

private fun formatTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000L -> "الآن"
        diff < 3_600_000L -> "${diff / 60_000} د"
        diff < 86_400_000L -> "${diff / 3_600_000} س"
        else -> SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}
