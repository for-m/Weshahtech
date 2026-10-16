package com.weshah.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.weshah.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val autoScanEnabled: Boolean = true,
    val scanIntervalMinutes: Int = 5,
    val notificationsEnabled: Boolean = true,
    val darkMode: Boolean = true,
    val version: String = "1.0.0"
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(
        settings.autoScanEnabled,
        settings.scanIntervalMinutes,
        settings.notificationsEnabled,
        settings.darkMode
    ) { autoScan, interval, notif, dark ->
        SettingsUiState(
            autoScanEnabled = autoScan,
            scanIntervalMinutes = interval,
            notificationsEnabled = notif,
            darkMode = dark
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    fun setAutoScan(v: Boolean) = viewModelScope.launch { settings.setAutoScanEnabled(v) }
    fun setScanInterval(v: Int) = viewModelScope.launch { settings.setScanIntervalMinutes(v) }
    fun setNotifications(v: Boolean) = viewModelScope.launch { settings.setNotificationsEnabled(v) }
    fun setDarkMode(v: Boolean) = viewModelScope.launch { settings.setDarkMode(v) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("الإعدادات", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "رجوع") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingsSection(title = "الشبكة") {
                SettingsSwitchItem(
                    icon = Icons.Default.Refresh,
                    title = "المسح التلقائي",
                    subtitle = "مسح الشبكة كل ${state.scanIntervalMinutes} دقائق",
                    checked = state.autoScanEnabled,
                    onCheckedChange = viewModel::setAutoScan
                )
            }

            SettingsSection(title = "الإشعارات") {
                SettingsSwitchItem(
                    icon = Icons.Default.Notifications,
                    title = "الإشعارات",
                    subtitle = "تنبيه عند اتصال أجهزة جديدة",
                    checked = state.notificationsEnabled,
                    onCheckedChange = viewModel::setNotifications
                )
            }

            SettingsSection(title = "المظهر") {
                SettingsSwitchItem(
                    icon = Icons.Default.DarkMode,
                    title = "الوضع الداكن",
                    subtitle = "استخدام الخلفية الداكنة",
                    checked = state.darkMode,
                    onCheckedChange = viewModel::setDarkMode
                )
            }

            SettingsSection(title = "عن التطبيق") {
                SettingsInfoItem(icon = Icons.Default.Info, title = "الإصدار", value = state.version)
                SettingsInfoItem(icon = Icons.Default.Business, title = "الشركة", value = "WESHAH Technology")
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 4.dp))
    Card(shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(4.dp)) { content() }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SettingsSwitchItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String, subtitle: String,
    checked: Boolean, onCheckedChange: (Boolean) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsInfoItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String, value: String
) {
    Row(modifier = Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        Text(value, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
