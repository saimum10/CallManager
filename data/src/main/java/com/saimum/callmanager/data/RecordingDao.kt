package com.saimum.callmanager.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Insert
    suspend fun insert(recording: RecordingEntity): Long

    @Delete
    suspend fun delete(recording: RecordingEntity)

    @Query("SELECT * FROM recordings ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<RecordingEntity>>
}
