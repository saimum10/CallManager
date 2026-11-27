package com.saimum.callmanager.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [RecordingEntity::class], version = 1, exportSchema = false)
abstract class CallManagerDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao

    companion object {
        @Volatile
        private var instance: CallManagerDatabase? = null

        fun getInstance(context: Context): CallManagerDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CallManagerDatabase::class.java,
                    "call_manager.db"
                )
                    // No migrations exist yet (still schema version 1). Without
                    // this, any future schema change would crash the app on
                    // launch for existing users instead of failing gracefully.
                    // The audio files on disk are unaffected either way — only
                    // this metadata table would be rebuilt empty.
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { instance = it }
            }
    }
}
