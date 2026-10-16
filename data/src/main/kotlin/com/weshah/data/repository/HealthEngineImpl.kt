package com.weshah.data.repository

import com.weshah.core.models.NetworkHealthReport
import com.weshah.domain.engine.HealthEngine
import com.weshah.domain.repository.RouterRepository
import com.weshah.router.api.RouterResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HealthEngineImpl @Inject constructor(
    private val routerRepository: RouterRepository
) : HealthEngine {

    @Volatile private var active = true

    override suspend fun runHealthCheck(): RouterResult<NetworkHealthReport> =
        routerRepository.runHealthCheck()

    override fun observeHealth(intervalSeconds: Int): Flow<NetworkHealthReport> = flow {
        while (active) {
            when (val r = routerRepository.runHealthCheck()) {
                is RouterResult.Success -> emit(r.data)
                is RouterResult.Error -> { /* silently skip failed checks in streaming mode */ }
            }
            delay(intervalSeconds * 1000L)
        }
    }

    override fun stop() { active = false }
}
