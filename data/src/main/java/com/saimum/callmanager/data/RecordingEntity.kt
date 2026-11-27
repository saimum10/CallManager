package com.saimum.callmanager.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val fileName: String,
    val direction: String, // CallDirection.name — kept as a plain string at the DB boundary
    val createdAtMillis: Long,
    val durationMillis: Long,
    val isSilent: Boolean
)
