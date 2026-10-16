package com.weshah.data.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 1 → 2: Add alerts table.
 * network_devices already has isBlocked/isFavorite from version 1.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS alerts (
                id TEXT NOT NULL PRIMARY KEY,
                timestamp INTEGER NOT NULL,
                severity TEXT NOT NULL,
                type TEXT NOT NULL,
                title TEXT NOT NULL,
                message TEXT NOT NULL,
                deviceMac TEXT,
                portId TEXT,
                isRead INTEGER NOT NULL DEFAULT 0,
                isResolved INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_alerts_timestamp ON alerts(timestamp)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_alerts_isRead ON alerts(isRead)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_alerts_isResolved ON alerts(isResolved)")
    }
}
