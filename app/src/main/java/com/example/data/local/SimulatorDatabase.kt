package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.model.KafkaMessage
import com.example.data.model.SimulationLog

@Database(entities = [SimulationLog::class, KafkaMessage::class], version = 1, exportSchema = false)
abstract class SimulatorDatabase : RoomDatabase() {
    abstract fun simulatorDao(): SimulatorDao

    companion object {
        @Volatile
        private var INSTANCE: SimulatorDatabase? = null

        fun getDatabase(context: Context): SimulatorDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SimulatorDatabase::class.java,
                    "simulator_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
