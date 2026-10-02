package com.weshah.data.repository

import com.weshah.core.models.Subscriber
import com.weshah.core.models.SubscriberStatus
import com.weshah.data.database.dao.NetworkDeviceDao
import com.weshah.data.database.dao.SubscriberDao
import com.weshah.data.database.entity.toEntity
import com.weshah.domain.repository.SubscriberRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubscriberRepositoryImpl @Inject constructor(
    private val subscriberDao: SubscriberDao,
    private val deviceDao: NetworkDeviceDao
) : SubscriberRepository {

    override fun getAllSubscribers(): Flow<List<Subscriber>> =
        subscriberDao.getAllSubscribers().map { it.map { e -> e.toModel() } }

    override fun getActiveSubscribers(): Flow<List<Subscriber>> =
        subscriberDao.getByStatus(SubscriberStatus.ACTIVE.name).map { it.map { e -> e.toModel() } }

    override fun searchSubscribers(query: String): Flow<List<Subscriber>> =
        subscriberDao.search(query).map { it.map { e -> e.toModel() } }

    override fun getActiveCount(): Flow<Int> = subscriberDao.getActiveCount()

    override suspend fun getSubscriber(id: String): Subscriber? =
        subscriberDao.getById(id)?.toModel()

    override suspend fun createSubscriber(subscriber: Subscriber) {
        subscriberDao.upsert(subscriber.toEntity())
    }

    override suspend fun updateSubscriber(subscriber: Subscriber) {
        subscriberDao.upsert(subscriber.toEntity())
    }

    override suspend fun deleteSubscriber(id: String) {
        val entity = subscriberDao.getById(id) ?: return
        subscriberDao.delete(entity)
    }

    override suspend fun updateStatus(id: String, status: SubscriberStatus) {
        subscriberDao.updateStatus(id, status.name)
    }

    override suspend fun updateSpeedProfile(id: String, profileId: String) {
        subscriberDao.updateSpeedProfile(id, profileId)
    }

    override suspend fun assignDeviceToSubscriber(mac: String, subscriberId: String?) {
        deviceDao.assignSubscriber(mac, subscriberId)
    }

    override suspend fun checkAndExpireSubscribers() {
        val now = System.currentTimeMillis()
        val expired = subscriberDao.getExpiredSubscribers(now)
        expired.forEach { subscriber ->
            subscriberDao.updateStatus(subscriber.id, SubscriberStatus.EXPIRED.name)
        }
    }
}
