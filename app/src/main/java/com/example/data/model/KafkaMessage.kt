package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "kafka_messages")
data class KafkaMessage(
    @PrimaryKey val id: String,
    val payload: String,
    val key: String,
    val topic: String, // "vr.assets.pack.events.v1", "vr.assets.pack.events.v1.DLQ", "vr.assets.pack.events.v1.DLQ.PERMANENT"
    val retryCount: Int = 0,
    val errorType: String? = null, // "poison_pill", "elasticsearch_outage", "vertex_limit_breach", null
    val status: String = "QUEUED", // "QUEUED", "PROCESSING", "BACKOFF", "COMPLETED", "FAILED"
    val traceParent: String? = null, // "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
    val timestamp: Long = System.currentTimeMillis()
)
