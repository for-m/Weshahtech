package com.weshah.data.repository

import com.weshah.core.models.*
import com.weshah.data.database.dao.*
import com.weshah.data.database.entity.*
import com.weshah.discovery.scanner.NetworkScanner
import com.weshah.discovery.fingerprint.DeviceFingerprinter
import com.weshah.domain.repository.DeviceRepository
import com.weshah.router.api.TrafficSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.emptyFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceRepositoryImpl @Inject constructor(
    private val deviceDao: NetworkDeviceDao,
    private val eventDao: NetworkEventDao,
    private val trafficDao: TrafficSampleDao,
    private val scanner: NetworkScanner,
    private val routerRepo: RouterRepositoryImpl
) : DeviceRepository {

    override fun getAllDevices(): Flow<List<NetworkDevice>> =
        deviceDao.getAllDevices().map { list -> list.map { it.toModel() } }

    override fun getOnlineDevices(): Flow<List<NetworkDevice>> =
        deviceDao.getOnlineDevices().map { list -> list.map { it.toModel() } }

    override fun getBlockedDevices(): Flow<List<NetworkDevice>> =
        deviceDao.getBlockedDevices().map { list -> list.map { it.toModel() } }

    override fun getFavoriteDevices(): Flow<List<NetworkDevice>> =
        deviceDao.getFavoriteDevices().map { list -> list.map { it.toModel() } }

    override fun getOnlineCount(): Flow<Int> = deviceDao.getOnlineCount()

    override fun getBlockedCount(): Flow<Int> = deviceDao.getBlockedCount()

    override fun searchDevices(query: String): Flow<List<NetworkDevice>> =
        deviceDao.search(query).map { list -> list.map { it.toModel() } }

    override suspend fun getDevice(mac: String): NetworkDevice? =
        deviceDao.getByMac(mac)?.toModel()

    override suspend fun upsertDevice(device: NetworkDevice) {
        val existing = deviceDao.getByMac(device.macAddress)
        deviceDao.upsert(device.toEntity(
            isFavorite = existing?.isFavorite ?: false,
            isBlocked = existing?.isBlocked ?: false,
            speedProfileId = existing?.speedProfileId
        ))
    }

    override suspend fun updateCustomName(mac: String, name: String) {
        deviceDao.updateCustomName(mac, name)
    }

    override suspend fun setFavorite(mac: String, favorite: Boolean) {
        deviceDao.updateFavorite(mac, favorite)
    }

    override suspend fun setBlocked(mac: String, blocked: Boolean) {
        deviceDao.updateBlocked(mac, blocked)
    }

    override suspend fun markOffline(cutoffMs: Long) {
        deviceDao.markOfflineBeforeTimestamp(cutoffMs)
    }

    override suspend fun triggerScan() {
        Timber.d("Starting network scan")
        val now = System.currentTimeMillis()

        // First: pull DHCP leases from router if connected
        try {
            val leases = routerRepo.getDhcpLeases()
            if (leases is com.weshah.router.api.RouterResult.Success) {
                leases.data.forEach { lease ->
                    val existing = deviceDao.getByMac(lease.macAddress)
                    val device = NetworkDeviceEntity(
                        macAddress = lease.macAddress,
                        ipAddress = lease.ipAddress,
                        hostname = lease.hostname,
                        customName = existing?.customName,
                        manufacturer = existing?.manufacturer ?: com.weshah.core.utils.OuiDatabase.lookupVendor(lease.macAddress),
                        deviceType = existing?.deviceType ?: DeviceType.UNKNOWN.name,
                        connectionType = ConnectionType.UNKNOWN.name,
                        isOnline = true,
                        firstSeen = existing?.firstSeen ?: now,
                        lastSeen = now,
                        interface_ = existing?.interface_,
                        rssi = existing?.rssi,
                        band = existing?.band,
                        uploadRateBytes = existing?.uploadRateBytes ?: 0,
                        downloadRateBytes = existing?.downloadRateBytes ?: 0,
                        totalUploadBytes = existing?.totalUploadBytes ?: 0,
                        totalDownloadBytes = existing?.totalDownloadBytes ?: 0,
                        associatedSubscriberId = existing?.associatedSubscriberId,
                        isFavorite = existing?.isFavorite ?: false,
                        isBlocked = existing?.isBlocked ?: false,
                        speedProfileId = existing?.speedProfileId
                    )
                    deviceDao.upsert(device)
                }
                Timber.d("Populated ${leases.data.size} devices from DHCP leases")
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not fetch DHCP leases from router")
        }

        // Second: local ARP cache scan (fast, no router needed)
        val arpEntries = scanner.readArpCache()
        arpEntries.forEach { (ip, mac) ->
            if (mac == null) return@forEach
            val existing = deviceDao.getByMac(mac)
            if (existing == null) {
                val vendor = com.weshah.core.utils.OuiDatabase.lookupVendor(mac)
                val deviceType = DeviceFingerprinter.identify(mac, null)
                deviceDao.upsert(NetworkDeviceEntity(
                    macAddress = mac,
                    ipAddress = ip,
                    hostname = null,
                    customName = null,
                    manufacturer = vendor,
                    deviceType = deviceType.name,
                    connectionType = ConnectionType.UNKNOWN.name,
                    isOnline = true,
                    firstSeen = now,
                    lastSeen = now,
                    interface_ = null, rssi = null, band = null,
                    uploadRateBytes = 0, downloadRateBytes = 0,
                    totalUploadBytes = 0, totalDownloadBytes = 0,
                    associatedSubscriberId = null
                ))

                // Log new device event
                eventDao.insert(NetworkEventEntity(
                    type = NetworkEventType.NEW_DEVICE_CONNECTED.name,
                    macAddress = mac, ipAddress = ip, deviceName = vendor,
                    message = "New device discovered: $ip ($mac) ${vendor ?: ""}",
                    timestamp = now, severity = EventSeverity.INFO.name
                ))
            } else {
                deviceDao.updateOnlineStatus(mac, true, now)
            }
        }

        // Mark devices not seen in 3 minutes as offline
        deviceDao.markOfflineBeforeTimestamp(now - 3 * 60 * 1000)
        Timber.d("Scan completed")
    }

    override fun getTrafficFlow(mac: String): Flow<TrafficSample> =
        routerRepo.getTrafficFlow(mac)

    override fun getRecentEvents(): Flow<List<NetworkEvent>> =
        eventDao.getRecentEvents().map { list -> list.map { it.toModel() } }
}
