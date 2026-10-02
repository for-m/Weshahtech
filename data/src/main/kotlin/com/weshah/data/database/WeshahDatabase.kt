package com.weshah.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.weshah.data.database.dao.*
import com.weshah.data.database.entity.*

@Database(
    entities = [
        NetworkDeviceEntity::class,
        SubscriberEntity::class,
        RouterEntity::class,
        NetworkEventEntity::class,
        TrafficSampleEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class WeshahDatabase : RoomDatabase() {

    abstract fun networkDeviceDao(): NetworkDeviceDao
    abstract fun subscriberDao(): SubscriberDao
    abstract fun routerDao(): RouterDao
    abstract fun networkEventDao(): NetworkEventDao
    abstract fun trafficSampleDao(): TrafficSampleDao

    companion object {
        const val DATABASE_NAME = "weshah_network.db"
    }
}
