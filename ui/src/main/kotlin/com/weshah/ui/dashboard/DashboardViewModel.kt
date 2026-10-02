package com.weshah.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weshah.core.models.NetworkDevice
import com.weshah.core.utils.IpUtils
import com.weshah.domain.repository.DeviceRepository
import com.weshah.domain.repository.RouterConnectionState
import com.weshah.domain.repository.RouterRepository
import com.weshah.router.api.RouterStats
import com.weshah.router.api.WanStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardUiState(
    val isRouterConnected: Boolean = false,
    val routerConnectionState: RouterConnectionState = RouterConnectionState.DISCONNECTED,
    val routerModel: String? = null,
    val routerIp: String? = null,
    val wanStatus: WanStatus? = null,
    val routerStats: RouterStats? = null,
    val onlineDeviceCount: Int = 0,
    val totalDeviceCount: Int = 0,
    val blockedDeviceCount: Int = 0,
    val topConsumers: List<NetworkDevice> = emptyList(),
    val totalDownloadBps: Long = 0,
    val totalUploadBps: Long = 0,
    val isScanning: Boolean = false,
    val lastScanTime: Long = 0,
    val isDataCached: Boolean = false
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val routerRepository: RouterRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        observeRouterState()
        observeDevices()
        observeRouterStats()
    }

    private fun observeRouterState() {
        viewModelScope.launch {
            routerRepository.connectionState.collect { state ->
                _uiState.update { it.copy(
                    routerConnectionState = state,
                    isRouterConnected = state == RouterConnectionState.CONNECTED
                )}
            }
        }
        viewModelScope.launch {
            routerRepository.routerInfo.collect { info ->
                _uiState.update { it.copy(
                    routerModel = info?.model,
                    routerIp = info?.ipAddress
                )}
            }
        }
    }

    private fun observeDevices() {
        viewModelScope.launch {
            deviceRepository.getAllDevices().collect { devices ->
                val online = devices.filter { it.isOnline }
                val topConsumers = online
                    .sortedByDescending { it.downloadRateBytes + it.uploadRateBytes }
                    .take(5)
                val totalDown = online.sumOf { it.downloadRateBytes }
                val totalUp = online.sumOf { it.uploadRateBytes }

                _uiState.update { it.copy(
                    onlineDeviceCount = online.size,
                    totalDeviceCount = devices.size,
                    topConsumers = topConsumers,
                    totalDownloadBps = totalDown,
                    totalUploadBps = totalUp
                )}
            }
        }
        viewModelScope.launch {
            deviceRepository.getBlockedCount().collect { count ->
                _uiState.update { it.copy(blockedDeviceCount = count) }
            }
        }
    }

    private fun observeRouterStats() {
        viewModelScope.launch {
            routerRepository.connectionState
                .filter { it == RouterConnectionState.CONNECTED }
                .flatMapLatest { routerRepository.getSystemStats() }
                .collect { stats ->
                    _uiState.update { it.copy(routerStats = stats) }
                }
        }
    }

    fun triggerScan() {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true) }
            try {
                deviceRepository.triggerScan()
                _uiState.update { it.copy(
                    isScanning = false,
                    lastScanTime = System.currentTimeMillis()
                )}
            } catch (e: Exception) {
                _uiState.update { it.copy(isScanning = false) }
            }
        }
    }

    fun refreshWanStatus() {
        viewModelScope.launch {
            if (routerRepository.connectionState.value == RouterConnectionState.CONNECTED) {
                routerRepository.getWanStatus().onSuccess { wan ->
                    _uiState.update { it.copy(wanStatus = wan) }
                }
            }
        }
    }
}
