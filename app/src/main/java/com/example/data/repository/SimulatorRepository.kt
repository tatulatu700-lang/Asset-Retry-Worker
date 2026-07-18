package com.example.data.repository

import com.example.data.local.SimulatorDao
import com.example.data.model.KafkaMessage
import com.example.data.model.SimulationLog
import kotlinx.coroutines.flow.Flow

class SimulatorRepository(private val dao: SimulatorDao) {
    val allLogs: Flow<List<SimulationLog>> = dao.getAllLogs()
    val allMessages: Flow<List<KafkaMessage>> = dao.getAllMessages()

    fun getMessagesByTopic(topic: String): Flow<List<KafkaMessage>> = dao.getMessagesByTopic(topic)

    suspend fun insertLog(log: SimulationLog) {
        dao.insertLog(log)
    }

    suspend fun clearLogs() {
        dao.clearLogs()
    }

    suspend fun insertMessage(message: KafkaMessage) {
        dao.insertMessage(message)
    }

    suspend fun deleteMessageById(id: String) {
        dao.deleteMessageById(id)
    }

    suspend fun clearMessages() {
        dao.clearMessages()
    }
}
