package com.weshah.data.di

import android.content.Context
import androidx.room.Room
import com.weshah.data.database.WeshahDatabase
import com.weshah.data.database.dao.*
import com.weshah.data.repository.*
import com.weshah.domain.repository.*
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): WeshahDatabase =
        Room.databaseBuilder(context, WeshahDatabase::class.java, WeshahDatabase.DATABASE_NAME)
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideDeviceDao(db: WeshahDatabase): NetworkDeviceDao = db.networkDeviceDao()
    @Provides fun provideSubscriberDao(db: WeshahDatabase): SubscriberDao = db.subscriberDao()
    @Provides fun provideRouterDao(db: WeshahDatabase): RouterDao = db.routerDao()
    @Provides fun provideEventDao(db: WeshahDatabase): NetworkEventDao = db.networkEventDao()
    @Provides fun provideTrafficDao(db: WeshahDatabase): TrafficSampleDao = db.trafficSampleDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds @Singleton
    abstract fun bindDeviceRepository(impl: DeviceRepositoryImpl): DeviceRepository

    @Binds @Singleton
    abstract fun bindRouterRepository(impl: RouterRepositoryImpl): RouterRepository

    @Binds @Singleton
    abstract fun bindSubscriberRepository(impl: SubscriberRepositoryImpl): SubscriberRepository
}
