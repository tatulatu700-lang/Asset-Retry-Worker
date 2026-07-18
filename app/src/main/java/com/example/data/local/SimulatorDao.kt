package com.example.data.local

import androidx.room.*
import com.example.data.model.KafkaMessage
import com.example.data.model.SimulationLog
import kotlinx.coroutines.flow.Flow

@Dao
interface SimulatorDao {
    @Query("SELECT * FROM simulation_logs ORDER BY timestamp DESC LIMIT 300")
    fun getAllLogs(): Flow<List<SimulationLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: SimulationLog)

    @Query("DELETE FROM simulation_logs")
    suspend fun clearLogs()

    @Query("SELECT * FROM kafka_messages ORDER BY timestamp DESC")
    fun getAllMessages(): Flow<List<KafkaMessage>>

    @Query("SELECT * FROM kafka_messages WHERE topic = :topic ORDER BY timestamp ASC")
    fun getMessagesByTopic(topic: String): Flow<List<KafkaMessage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: KafkaMessage)

    @Query("DELETE FROM kafka_messages")
    suspend fun clearMessages()

    @Query("DELETE FROM kafka_messages WHERE id = :id")
    suspend fun deleteMessageById(id: String)
}
