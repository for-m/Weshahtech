package com.weshah.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weshah.core.models.NetworkDevice
import com.weshah.domain.repository.DeviceRepository
import com.weshah.domain.repository.RouterRepository
import com.weshah.router.api.RouterResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class DeviceFilter { ALL, ONLINE, OFFLINE, BLOCKED, WIFI, WIRED, FAVORITES }

data class DevicesUiState(
    val devices: List<NetworkDevice> = emptyList(),
    val filteredDevices: List<NetworkDevice> = emptyList(),
    val activeFilter: DeviceFilter = DeviceFilter.ALL,
    val searchQuery: String = "",
    val isScanning: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val routerRepository: RouterRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DevicesUiState())
    val uiState: StateFlow<DevicesUiState> = _uiState.asStateFlow()

    private val _filter = MutableStateFlow(DeviceFilter.ALL)
    private val _query = MutableStateFlow("")

    init {
        viewModelScope.launch {
            combine(
                deviceRepository.getAllDevices(),
                _filter,
                _query
            ) { devices, filter, query ->
                val filtered = devices
                    .filter { device ->
                        when (filter) {
                            DeviceFilter.ALL -> true
                            DeviceFilter.ONLINE -> device.isOnline
                            DeviceFilter.OFFLINE -> !device.isOnline
                            DeviceFilter.BLOCKED -> device.isBlocked
                            DeviceFilter.WIFI -> device.connectionType == com.weshah.core.models.ConnectionType.WIFI
                            DeviceFilter.WIRED -> device.connectionType == com.weshah.core.models.ConnectionType.WIRED
                            DeviceFilter.FAVORITES -> device.isFavorite
                        }
                    }
                    .filter { device ->
                        if (query.isBlank()) true
                        else {
                            val q = query.lowercase()
                            device.ipAddress.contains(q) ||
                            device.macAddress.lowercase().contains(q) ||
                            device.hostname?.lowercase()?.contains(q) == true ||
                            device.customName?.lowercase()?.contains(q) == true ||
                            device.manufacturer?.lowercase()?.contains(q) == true
                        }
                    }
                Triple(devices, filtered, filter)
            }.collect { (all, filtered, filter) ->
                _uiState.update { it.copy(
                    devices = all,
                    filteredDevices = filtered,
                    activeFilter = filter,
                    searchQuery = _query.value
                )}
            }
        }
    }

    fun setFilter(filter: DeviceFilter) { _filter.value = filter }
    fun setQuery(query: String) { _query.value = query }

    fun scanNetwork() {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, error = null) }
            try {
                deviceRepository.triggerScan()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            } finally {
                _uiState.update { it.copy(isScanning = false) }
            }
        }
    }

    fun renameDevice(mac: String, name: String) {
        viewModelScope.launch { deviceRepository.updateCustomName(mac, name) }
    }

    fun blockDevice(mac: String) {
        viewModelScope.launch {
            routerRepository.blockClient(mac, null)
                .onError { _, msg -> _uiState.update { it.copy(error = msg) } }
        }
    }

    fun unblockDevice(mac: String) {
        viewModelScope.launch {
            routerRepository.unblockClient(mac)
                .onError { _, msg -> _uiState.update { it.copy(error = msg) } }
        }
    }

    fun setSpeedLimit(mac: String, downloadKbps: Long?, uploadKbps: Long?) {
        viewModelScope.launch {
            routerRepository.setSpeedLimit(mac, downloadKbps, uploadKbps)
                .onError { _, msg -> _uiState.update { it.copy(error = msg) } }
        }
    }

    fun disconnectDevice(mac: String) {
        viewModelScope.launch {
            routerRepository.disconnectClient(mac)
                .onError { _, msg -> _uiState.update { it.copy(error = msg) } }
        }
    }
}
