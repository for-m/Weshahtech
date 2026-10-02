package com.weshah.data.repository

import com.weshah.core.models.TopologyNode
import com.weshah.domain.engine.TopologyEngine
import com.weshah.domain.repository.RouterRepository
import com.weshah.router.api.RouterErrorCode
import com.weshah.router.api.RouterResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TopologyEngineImpl @Inject constructor(
    private val routerRepository: RouterRepository
) : TopologyEngine {

    override suspend fun buildTopology(): RouterResult<List<TopologyNode>> {
        return when (val r = routerRepository.getLldpNeighbors()) {
            is RouterResult.Success -> {
                val nodes = r.data.mapIndexed { i, neighbor ->
                    TopologyNode(
                        id = neighbor.remoteChassisId,
                        label = neighbor.remoteHostname ?: neighbor.remoteChassisId,
                        ipAddress = null,
                        macAddress = neighbor.remoteChassisId,
                        deviceType = com.weshah.core.models.DeviceType.UNKNOWN,
                        parentId = "router",
                        connectionConfidence = com.weshah.core.models.TopologyConfidence.CONFIRMED,
                        inferredVia = "LLDP",
                        portId = neighbor.localPort,
                        vlanId = null,
                        linkSpeedMbps = null,
                        isOnline = true,
                        depth = 2
                    )
                }
                RouterResult.Success(nodes)
            }
            is RouterResult.Error -> r
        }
    }

    override fun observeTopology(): Flow<List<TopologyNode>> = emptyFlow()

    override suspend fun refreshLldp(): RouterResult<Unit> {
        return when (routerRepository.getLldpNeighbors()) {
            is RouterResult.Success -> RouterResult.Success(Unit)
            is RouterResult.Error -> RouterResult.Error(RouterErrorCode.NOT_SUPPORTED, "LLDP refresh not supported")
        }
    }
}
