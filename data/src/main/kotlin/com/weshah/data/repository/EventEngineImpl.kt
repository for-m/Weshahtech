package com.weshah.data.repository

import com.weshah.core.models.NetworkEvent
import com.weshah.data.database.dao.NetworkEventDao
import com.weshah.domain.engine.EventEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EventEngineImpl @Inject constructor(
    val networkEventDao: NetworkEventDao
) : EventEngine {

    /** Emits individual events from the DB, re-emitting all when the list changes. */
    override fun observeEvents(): Flow<NetworkEvent> = flow {
        networkEventDao.getRecentEvents(200).collect { list ->
            list.forEach { emit(it.toModel()) }
        }
    }

    override fun start() {}
    override fun stop() {}
}
