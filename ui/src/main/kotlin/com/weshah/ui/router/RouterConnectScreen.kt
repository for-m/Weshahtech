package com.weshah.ui.router

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weshah.domain.repository.RouterConnectionState
import com.weshah.domain.repository.RouterRepository
import com.weshah.router.api.RouterConnectionResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─── ViewModel ────────────────────────────────────────────────────────────────

data class RouterConnectState(
    val ipAddress: String = "192.168.1.1",
    val port: String = "443",
    val username: String = "root",
    val password: String = "",
    val useHttps: Boolean = true,
    val connectionState: RouterConnectionState = RouterConnectionState.DISCONNECTED,
    val routerModel: String? = null,
    val error: String? = null,
    val isConnecting: Boolean = false
)

@HiltViewModel
class RouterConnectViewModel @Inject constructor(
    private val routerRepository: RouterRepository
) : ViewModel() {

    private val _state = MutableStateFlow(RouterConnectState())
    val state: StateFlow<RouterConnectState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            routerRepository.connectionState.collect { cs ->
                _state.update { it.copy(connectionState = cs, isConnecting = cs == RouterConnectionState.CONNECTING) }
            }
        }
        viewModelScope.launch {
            routerRepository.routerInfo.collect { info ->
                _state.update { it.copy(routerModel = info?.model) }
            }
        }
    }

    fun setIp(v: String) = _state.update { it.copy(ipAddress = v) }
    fun setPort(v: String) = _state.update { it.copy(port = v) }
    fun setUsername(v: String) = _state.update { it.copy(username = v) }
    fun setPassword(v: String) = _state.update { it.copy(password = v) }
    fun setHttps(v: Boolean) = _state.update { it.copy(useHttps = v) }

    fun connect() {
        val s = _state.value
        if (s.ipAddress.isBlank() || s.password.isBlank()) {
            _state.update { it.copy(error = "يرجى إدخال IP وكلمة المرور") }
            return
        }
        val port = s.port.toIntOrNull() ?: 443
        viewModelScope.launch {
            _state.update { it.copy(error = null) }
            when (val result = routerRepository.connectToRouter(
                s.ipAddress, port, s.username, s.password, s.useHttps)) {
                is RouterConnectionResult.Success -> { /* state updated via flow */ }
                is RouterConnectionResult.Failure ->
                    _state.update { it.copy(error = "فشل الاتصال: ${result.reason}") }
            }
        }
    }

    fun disconnect() = viewModelScope.launch { routerRepository.disconnect() }
}

// ─── Screen ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouterConnectScreen(
    onBack: () -> Unit,
    viewModel: RouterConnectViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showPassword by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("اتصال بالراوتر", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "رجوع") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status card
            RouterConnectionStatusCard(state = state, onDisconnect = { viewModel.disconnect() })

            if (state.connectionState != RouterConnectionState.CONNECTED) {
                // Connection form
                Card(shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("إعدادات الاتصال", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

                        OutlinedTextField(
                            value = state.ipAddress,
                            onValueChange = viewModel::setIp,
                            label = { Text("IP الراوتر") },
                            leadingIcon = { Icon(Icons.Default.Router, null) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = state.port,
                                onValueChange = viewModel::setPort,
                                label = { Text("Port") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = state.username,
                                onValueChange = viewModel::setUsername,
                                label = { Text("المستخدم") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                        OutlinedTextField(
                            value = state.password,
                            onValueChange = viewModel::setPassword,
                            label = { Text("كلمة المرور") },
                            leadingIcon = { Icon(Icons.Default.Lock, null) },
                            trailingIcon = {
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                                }
                            },
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Switch(checked = state.useHttps, onCheckedChange = viewModel::setHttps)
                            Text("استخدام HTTPS")
                        }
                    }
                }

                state.error?.let { err ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = MaterialTheme.shapes.medium) {
                        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                            Text(err, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }

                Button(
                    onClick = { viewModel.connect() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    enabled = !state.isConnecting
                ) {
                    if (state.isConnecting) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("اتصال بالراوتر", style = MaterialTheme.typography.titleMedium)
                }

                // Info about security
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    shape = MaterialTheme.shapes.medium) {
                    Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Security, null, modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onTertiaryContainer)
                        Text("كلمة المرور محفوظة بأمان في Android Keystore ولا تُخزَّن كنص واضح.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
            }
        }
    }
}

@Composable
private fun RouterConnectionStatusCard(state: RouterConnectState, onDisconnect: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = when (state.connectionState) {
                RouterConnectionState.CONNECTED -> MaterialTheme.colorScheme.primaryContainer
                RouterConnectionState.ERROR -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Router,
                    null,
                    modifier = Modifier.size(40.dp),
                    tint = when (state.connectionState) {
                        RouterConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
                        RouterConnectionState.ERROR -> MaterialTheme.colorScheme.error
                        RouterConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Column {
                    Text(
                        when (state.connectionState) {
                            RouterConnectionState.CONNECTED -> state.routerModel ?: "متصل"
                            RouterConnectionState.CONNECTING -> "جارٍ الاتصال..."
                            RouterConnectionState.ERROR -> "فشل الاتصال"
                            RouterConnectionState.DISCONNECTED -> "غير متصل"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(state.ipAddress, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.connectionState == RouterConnectionState.CONNECTED) {
                TextButton(onClick = onDisconnect) { Text("قطع") }
            }
        }
    }
}
