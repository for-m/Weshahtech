package com.weshah.data.repository

import com.weshah.core.models.CableDiagResult
import com.weshah.core.models.PortInfo
import com.weshah.domain.engine.PortEngine
import com.weshah.domain.repository.RouterRepository
import com.weshah.router.api.RouterErrorCode
import com.weshah.router.api.RouterResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PortEngineImpl @Inject constructor(
    private val routerRepository: RouterRepository
) : PortEngine {

    override suspend fun getPorts(): RouterResult<List<PortInfo>> =
        routerRepository.getPortStats()

    override fun observePorts(): Flow<List<PortInfo>> = emptyFlow()

    override suspend fun runCableDiagnostics(portId: String): RouterResult<CableDiagResult> =
        routerRepository.runCableDiagnostics(portId)
}
