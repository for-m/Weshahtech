package com.weshah.domain.engine

import com.weshah.core.models.CableDiagResult
import com.weshah.core.models.PortInfo
import com.weshah.router.api.RouterResult
import kotlinx.coroutines.flow.Flow

interface PortEngine {
    /** Get current state of all switch ports. */
    suspend fun getPorts(): RouterResult<List<PortInfo>>

    /** Live port status stream — emits when any port changes state. */
    fun observePorts(): Flow<List<PortInfo>>

    /**
     * Run cable diagnostics on a specific port.
     * Returns [CableDiagResult.supported] == false with [CableDiagResult.unsupportedReason]
     * when the PHY does not support TDR. Never returns fake length estimates.
     */
    suspend fun runCableDiagnostics(portId: String): RouterResult<CableDiagResult>
}
