package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "simulation_logs")
data class SimulationLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val level: String, // "INFO", "WARN", "ERROR"
    val tag: String,   // e.g. "RetryWorker" or "IngestionWorker"
    val message: String,
    val retryCount: Int? = null
)
