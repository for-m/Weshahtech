package com.weshah.domain.repository

import com.weshah.core.models.Subscriber
import com.weshah.core.models.SubscriberStatus
import kotlinx.coroutines.flow.Flow

interface SubscriberRepository {
    fun getAllSubscribers(): Flow<List<Subscriber>>
    fun getActiveSubscribers(): Flow<List<Subscriber>>
    fun searchSubscribers(query: String): Flow<List<Subscriber>>
    fun getActiveCount(): Flow<Int>
    suspend fun getSubscriber(id: String): Subscriber?
    suspend fun createSubscriber(subscriber: Subscriber)
    suspend fun updateSubscriber(subscriber: Subscriber)
    suspend fun deleteSubscriber(id: String)
    suspend fun updateStatus(id: String, status: SubscriberStatus)
    suspend fun updateSpeedProfile(id: String, profileId: String)
    suspend fun assignDeviceToSubscriber(mac: String, subscriberId: String?)
    suspend fun checkAndExpireSubscribers()
}
