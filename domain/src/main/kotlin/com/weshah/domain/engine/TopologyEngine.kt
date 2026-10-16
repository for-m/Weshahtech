package com.weshah.domain.engine

import com.weshah.core.models.TopologyNode
import com.weshah.router.api.RouterResult
import kotlinx.coroutines.flow.Flow

interface TopologyEngine {
    /** Build or refresh the network topology graph. */
    suspend fun buildTopology(): RouterResult<List<TopologyNode>>

    /** Live topology updates — emits on LLDP change or ARP table change. */
    fun observeTopology(): Flow<List<TopologyNode>>

    /** Force an LLDP re-discovery cycle (30-60 s). */
    suspend fun refreshLldp(): RouterResult<Unit>
}
