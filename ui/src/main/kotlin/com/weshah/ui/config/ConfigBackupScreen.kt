package com.weshah.ui.config

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weshah.domain.repository.RouterRepository
import com.weshah.router.api.RouterErrorCode
import com.weshah.router.api.RouterResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

data class ConfigBackupUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val notSupported: Boolean = false,
    val backupFile: File? = null,
    val restoreInProgress: Boolean = false,
    val restoreSuccess: Boolean = false,
    val lastBackupSize: Int = 0
)

@HiltViewModel
class ConfigBackupViewModel @Inject constructor(
    private val routerRepository: RouterRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ConfigBackupUiState())
    val state: StateFlow<ConfigBackupUiState> = _state.asStateFlow()

    fun createBackup(context: Context) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, backupFile = null) }
            when (val r = routerRepository.createConfigBackup()) {
                is RouterResult.Success -> {
                    val data = r.data
                    val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    val filename = "weshah_backup_${fmt.format(Date())}.tar.gz"
                    val file = File(context.cacheDir, filename)
                    file.writeBytes(data)
                    _state.update { it.copy(isLoading = false, backupFile = file, lastBackupSize = data.size) }
                }
                is RouterResult.Error -> {
                    val ns = r.code == RouterErrorCode.NOT_SUPPORTED
                    _state.update { it.copy(isLoading = false, error = r.message, notSupported = ns) }
                }
            }
        }
    }

    fun restoreBackup(context: Context, uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(restoreInProgress = true, error = null, restoreSuccess = false) }
            try {
                val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: run {
                        _state.update { it.copy(restoreInProgress = false, error = "فشل قراءة الملف") }
                        return@launch
                    }
                when (val r = routerRepository.restoreConfigBackup(data)) {
                    is RouterResult.Success ->
                        _state.update { it.copy(restoreInProgress = false, restoreSuccess = true) }
                    is RouterResult.Error ->
                        _state.update { it.copy(restoreInProgress = false, error = r.message) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(restoreInProgress = false, error = e.message) }
            }
        }
    }

    fun clearState() = _state.update { it.copy(error = null, restoreSuccess = false) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigBackupScreen(
    onBack: () -> Unit,
    viewModel: ConfigBackupViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.restoreBackup(context, it) }
    }

    LaunchedEffect(state.backupFile) {
        state.backupFile?.let { file -> shareFile(context, file) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("نسخ احتياطي", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "رجوع") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (state.notSupported) {
                com.weshah.ui.ports.NotSupportedMessage(
                    "Config Backup",
                    "يتطلب weshah-agent v2 أو sysupgrade API"
                )
                return@Scaffold
            }

            // Backup card
            BackupCard(
                isLoading = state.isLoading,
                backupFile = state.backupFile,
                onBackup = { viewModel.createBackup(context) }
            )

            // Restore card
            RestoreCard(
                isInProgress = state.restoreInProgress,
                restoreSuccess = state.restoreSuccess,
                onRestore = { restoreLauncher.launch(arrayOf("*/*")) }
            )

            // Error
            state.error?.let { err ->
                Card(colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Error, null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(16.dp))
                        Text(err, color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Warning about restore
            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Row(modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Warning, null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(16.dp))
                    Column {
                        Text("تحذير", fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text("استعادة النسخة الاحتياطية ستُعيد تشغيل الراوتر وتُعيد ضبط جميع الإعدادات.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }
    }
}

@Composable
private fun BackupCard(isLoading: Boolean, backupFile: File?, onBackup: () -> Unit) {
    Card(shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudDownload, null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text("إنشاء نسخة احتياطية",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Text("تحميل إعدادات الراوتر الكاملة (UCI / sysupgrade)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            backupFile?.let { file ->
                Surface(shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer) {
                    Row(modifier = Modifier.padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Text("${file.name} (${file.length() / 1024} KB)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
            Button(
                onClick = onBackup,
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("جاري إنشاء النسخة...")
                } else {
                    Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("تحميل النسخة الاحتياطية")
                }
            }
        }
    }
}

@Composable
private fun RestoreCard(isInProgress: Boolean, restoreSuccess: Boolean, onRestore: () -> Unit) {
    Card(shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudUpload, null,
                    tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
                Text("استعادة نسخة احتياطية",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Text("رفع ملف نسخة احتياطية (.tar.gz) لاستعادة الإعدادات",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (restoreSuccess) {
                Surface(shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer) {
                    Row(modifier = Modifier.padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Text("تمت الاستعادة بنجاح — جاري إعادة تشغيل الراوتر",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
            OutlinedButton(
                onClick = onRestore,
                enabled = !isInProgress,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isInProgress) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("جاري الاستعادة...")
                } else {
                    Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("اختيار ملف للاستعادة")
                }
            }
        }
    }
}

private fun shareFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/gzip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "حفظ النسخة الاحتياطية"))
}
