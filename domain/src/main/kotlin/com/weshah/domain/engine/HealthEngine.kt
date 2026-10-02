package com.weshah.domain.engine

import com.weshah.core.models.NetworkHealthReport
import com.weshah.router.api.RouterResult
import kotlinx.coroutines.flow.Flow

interface HealthEngine {
    /** Single-shot health check — runs all probes and returns report. */
    suspend fun runHealthCheck(): RouterResult<NetworkHealthReport>

    /** Continuous health monitoring stream. Emits a new report every [intervalSeconds]. */
    fun observeHealth(intervalSeconds: Int = 30): Flow<NetworkHealthReport>

    /** Stop all active monitoring. */
    fun stop()
}
