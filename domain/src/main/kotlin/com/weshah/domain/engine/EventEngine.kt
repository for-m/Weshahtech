package com.weshah.domain.engine

import com.weshah.core.models.NetworkEvent
import kotlinx.coroutines.flow.Flow

interface EventEngine {
    /** Stream of all network events (device seen, WAN change, alert, etc.). */
    fun observeEvents(): Flow<NetworkEvent>

    /** Start polling and ingesting events from router + agent. */
    fun start()

    /** Stop event ingestion. */
    fun stop()
}
