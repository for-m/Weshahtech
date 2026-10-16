package com.weshah.ui.subscribers

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
import com.weshah.core.models.Subscriber
import com.weshah.core.models.SubscriberStatus
import com.weshah.domain.repository.RouterRepository
import com.weshah.domain.repository.SubscriberRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

// ─── ViewModel ────────────────────────────────────────────────────────────────

data class SubscribersUiState(
    val subscribers: List<Subscriber> = emptyList(),
    val searchQuery: String = "",
    val error: String? = null
)

@HiltViewModel
class SubscribersViewModel @Inject constructor(
    private val subscriberRepository: SubscriberRepository,
    private val routerRepository: RouterRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SubscribersUiState())
    val state: StateFlow<SubscribersUiState> = _state.asStateFlow()
    private val _query = MutableStateFlow("")

    init {
        viewModelScope.launch {
            _query.debounce(300).flatMapLatest { q ->
                if (q.isBlank()) subscriberRepository.getAllSubscribers()
                else subscriberRepository.searchSubscribers(q)
            }.collect { list ->
                _state.update { it.copy(subscribers = list) }
            }
        }
    }

    fun setQuery(q: String) { _query.value = q }

    fun createSubscriber(name: String, phone: String?, notes: String?, speedProfileId: String) {
        viewModelScope.launch {
            subscriberRepository.createSubscriber(Subscriber(
                id = UUID.randomUUID().toString(),
                name = name, phone = phone, notes = notes,
                macAddresses = emptyList(),
                speedProfileId = speedProfileId,
                expiryTimestamp = null,
                status = SubscriberStatus.ACTIVE,
                createdAt = System.currentTimeMillis(),
                totalUploadBytes = 0, totalDownloadBytes = 0
            ))
        }
    }

    fun blockSubscriber(subscriber: Subscriber) {
        viewModelScope.launch {
            subscriber.macAddresses.forEach { mac ->
                routerRepository.blockClient(mac, null)
            }
            subscriberRepository.updateStatus(subscriber.id, SubscriberStatus.BLOCKED)
        }
    }

    fun unblockSubscriber(subscriber: Subscriber) {
        viewModelScope.launch {
            subscriber.macAddresses.forEach { mac ->
                routerRepository.unblockClient(mac)
            }
            subscriberRepository.updateStatus(subscriber.id, SubscriberStatus.ACTIVE)
        }
    }

    fun deleteSubscriber(id: String) {
        viewModelScope.launch { subscriberRepository.deleteSubscriber(id) }
    }
}

// ─── Screen ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscribersScreen(
    onSubscriberClick: (String) -> Unit,
    viewModel: SubscribersViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("المشتركون", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.PersonAdd, "إضافة مشترك")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, "إضافة")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                placeholder = { Text("ابحث عن مشترك...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                shape = MaterialTheme.shapes.large
            )
            if (state.subscribers.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.People, null, modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text("لا يوجد مشتركون", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { showAddDialog = true }) { Text("إضافة مشترك") }
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.subscribers, key = { it.id }) { subscriber ->
                        SubscriberListItem(
                            subscriber = subscriber,
                            onClick = { onSubscriberClick(subscriber.id) },
                            onBlock = { viewModel.blockSubscriber(subscriber) },
                            onUnblock = { viewModel.unblockSubscriber(subscriber) },
                            onDelete = { viewModel.deleteSubscriber(subscriber.id) }
                        )
                    }
                }
            }
        }

        if (showAddDialog) {
            AddSubscriberDialog(
                onConfirm = { name, phone, notes, profileId ->
                    viewModel.createSubscriber(name, phone, notes, profileId)
                    showAddDialog = false
                },
                onDismiss = { showAddDialog = false }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubscriberListItem(
    subscriber: Subscriber,
    onClick: () -> Unit,
    onBlock: () -> Unit,
    onUnblock: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = MaterialTheme.shapes.large,
                    color = when (subscriber.status) {
                        SubscriberStatus.ACTIVE -> MaterialTheme.colorScheme.primaryContainer
                        SubscriberStatus.BLOCKED -> MaterialTheme.colorScheme.errorContainer
                        SubscriberStatus.EXPIRED -> MaterialTheme.colorScheme.secondaryContainer
                        SubscriberStatus.SUSPENDED -> MaterialTheme.colorScheme.tertiaryContainer
                    }, modifier = Modifier.size(40.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Person, null, modifier = Modifier.size(22.dp))
                    }
                }
                Column {
                    Text(subscriber.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${subscriber.macAddresses.size} جهاز",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val statusLabel = when (subscriber.status) {
                            SubscriberStatus.ACTIVE -> "نشط"
                            SubscriberStatus.BLOCKED -> "محظور"
                            SubscriberStatus.EXPIRED -> "منتهي"
                            SubscriberStatus.SUSPENDED -> "موقوف"
                        }
                        AssistChip(
                            onClick = {},
                            label = { Text(statusLabel, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(20.dp)
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, null) }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(text = { Text("حظر الإنترنت") }, onClick = { onBlock(); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.Block, null) })
                    DropdownMenuItem(text = { Text("رفع الحظر") }, onClick = { onUnblock(); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.CheckCircle, null) })
                    DropdownMenuItem(text = { Text("حذف") }, onClick = { onDelete(); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.Delete, null) })
                }
            }
        }
    }
}

@Composable
private fun AddSubscriberDialog(onConfirm: (String, String?, String?, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة مشترك جديد") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("الاسم *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = phone, onValueChange = { phone = it },
                    label = { Text("رقم الهاتف") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = notes, onValueChange = { notes = it },
                    label = { Text("ملاحظات") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) {
                    onConfirm(name.trim(), phone.ifBlank { null }, notes.ifBlank { null }, "unlimited")
                }
            }) { Text("إضافة") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}
