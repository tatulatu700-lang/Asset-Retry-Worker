package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.SimulatorDatabase
import com.example.data.model.KafkaMessage
import com.example.data.model.SimulationLog
import com.example.data.repository.SimulatorRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class SimulatorViewModel(
    application: Application,
    private val repository: SimulatorRepository
) : AndroidViewModel(application) {

    // Simulation settings
    val isKafkaOnline = MutableStateFlow(true)
    val isElasticsearchOnline = MutableStateFlow(true)
    val simSpeed = MutableStateFlow(1.0) // 1x, 2x, 5x, 10x

    // Live Metrics
    val metricDlqTotal = MutableStateFlow(0)
    val metricPoisonPill = MutableStateFlow(0)
    val metricVertexBreach = MutableStateFlow(0)
    val metricEsOutageCount = MutableStateFlow(0)

    // Current active processes state
    val isIngestionWorkerActive = MutableStateFlow(false)
    val isRetryWorkerActive = MutableStateFlow(false)

    // Current backoff timer state
    val activeBackoffMessage = MutableStateFlow<KafkaMessage?>(null)
    val activeBackoffTimeRemaining = MutableStateFlow(0)
    val activeBackoffTimeTotal = MutableStateFlow(0)

    // Flow lists
    val allLogs: StateFlow<List<SimulationLog>> = repository.allLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allMessages: StateFlow<List<KafkaMessage>> = repository.allMessages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Filtered lists for simple representation
    val mainQueue: StateFlow<List<KafkaMessage>> = repository.getMessagesByTopic("vr.assets.pack.events.v1")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dlqQueue: StateFlow<List<KafkaMessage>> = repository.getMessagesByTopic("vr.assets.pack.events.v1.DLQ")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val permanentDlqQueue: StateFlow<List<KafkaMessage>> = repository.getMessagesByTopic("vr.assets.pack.events.v1.DLQ.PERMANENT")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var ingestionJob: Job? = null
    private var retryJob: Job? = null

    init {
        // Pre-populate with logs and start the background loops
        viewModelScope.launch {
            if (repository.allLogs.first().isEmpty()) {
                addLog("INFO", "ClusterEngine", "Initializing isolated Asset Retry Engine worker process...")
                addLog("INFO", "ClusterEngine", "Prometheus metrics engine exposed live over endpoint space on port 8889")
            }
        }
        startSimulationLoops()
    }

    private fun startSimulationLoops() {
        ingestionJob?.cancel()
        retryJob?.cancel()

        // 1. Primary Ingestion Worker Thread
        ingestionJob = viewModelScope.launch {
            while (true) {
                // Control processing speed
                delay((1000 / simSpeed.value).toLong())

                if (!isKafkaOnline.value) {
                    isIngestionWorkerActive.value = false
                    continue
                }

                val mainMsgs = mainQueue.value
                val processingMsg = mainMsgs.firstOrNull { it.status == "QUEUED" }

                if (processingMsg != null) {
                    isIngestionWorkerActive.value = true
                    processIngestionMessage(processingMsg)
                    isIngestionWorkerActive.value = false
                } else {
                    isIngestionWorkerActive.value = false
                }
            }
        }

        // 2. Isolated Retry Worker Process
        retryJob = viewModelScope.launch {
            while (true) {
                delay((1000 / simSpeed.value).toLong())

                if (!isKafkaOnline.value) {
                    isRetryWorkerActive.value = false
                    continue
                }

                val dlqMsgs = dlqQueue.value
                val retryMsg = dlqMsgs.firstOrNull { it.status == "QUEUED" }

                if (retryMsg != null) {
                    isRetryWorkerActive.value = true
                    processRetryMessage(retryMsg)
                    isRetryWorkerActive.value = false
                } else {
                    isRetryWorkerActive.value = false
                }
            }
        }
    }

    private suspend fun processIngestionMessage(msg: KafkaMessage) {
        val traceSuffix = if (msg.traceParent != null) " [Trace ID: ${msg.traceParent.substringBeforeLast("-").substringAfter("-")}]" else ""
        // Update to PROCESSING status
        repository.insertMessage(msg.copy(status = "PROCESSING"))
        addLog("INFO", "IngestionWorker", "Fetched message frame '${msg.id}' from main ingestion stream.$traceSuffix")

        // Loop until Elasticsearch is back Online (outage logic)
        while (!isElasticsearchOnline.value) {
            addLog("WARN", "IngestionWorker", "Elasticsearch cluster unreachable. Transient network warning thrown. Pausing pipeline to prevent message loss.$traceSuffix")
            metricEsOutageCount.value += 1
            // Sleep and retry within ingestion worker without committing
            delay((3000 / simSpeed.value).toLong())
        }

        // Check if message is a poison pill (invalid JSON lacking curly braces)
        val isMalformed = !msg.payload.trim().startsWith("{") || !msg.payload.trim().endsWith("}")

        if (isMalformed) {
            // Poison Pill isolation logic
            addLog("ERROR", "IngestionWorker", "Payload decoding failure. Malformed message structure lacking valid JSON braces. Isolating to DLQ.$traceSuffix")
            metricDlqTotal.value += 1
            metricPoisonPill.value += 1

            repository.insertMessage(msg.copy(
                topic = "vr.assets.pack.events.v1.DLQ",
                status = "QUEUED",
                errorType = "poison_pill",
                retryCount = 0,
                traceParent = msg.traceParent,
                timestamp = System.currentTimeMillis()
            ))
            return
        }

        // Parse vertex count if possible for business limit breach checks
        val vertexCount = extractVertexCount(msg.payload)
        if (vertexCount > 10000) {
            // Business Rule Violation limit breached
            addLog("ERROR", "IngestionWorker", "Business rule violation: vertex count limit breached ($vertexCount > 10000). Routing to DLQ.$traceSuffix")
            metricDlqTotal.value += 1
            metricVertexBreach.value += 1

            repository.insertMessage(msg.copy(
                topic = "vr.assets.pack.events.v1.DLQ",
                status = "QUEUED",
                errorType = "vertex_limit_breach",
                retryCount = 0,
                traceParent = msg.traceParent,
                timestamp = System.currentTimeMillis()
            ))
            return
        }

        // Healthy Asset Pack complete
        addLog("INFO", "IngestionWorker", "Successfully indexed asset pack '${msg.id}' in Elasticsearch. Ingestion complete.$traceSuffix")
        repository.deleteMessageById(msg.id)
    }

    private suspend fun processRetryMessage(msg: KafkaMessage) {
        val currentRetry = msg.retryCount
        val traceSuffix = if (msg.traceParent != null) " [Trace ID: ${msg.traceParent.substringBeforeLast("-").substringAfter("-")}]" else ""

        if (currentRetry >= 3) {
            addLog("WARN", "RetryWorker", "Max retry ceiling (3) broken. Relocating payload ${msg.id} to structural terminal graveyard topic vr.assets.pack.events.v1.DLQ.PERMANENT.$traceSuffix")
            repository.insertMessage(msg.copy(
                topic = "vr.assets.pack.events.v1.DLQ.PERMANENT",
                status = "FAILED",
                traceParent = msg.traceParent,
                timestamp = System.currentTimeMillis()
            ))
            return
        }

        // Exponential Backoff Delay Equation: 2^current_retry * 5 seconds
        val backoffSecs = Math.pow(2.0, currentRetry.toDouble()).toInt() * 5
        addLog("INFO", "RetryWorker", "Executing exponential backoff sequence step. Delay: ${backoffSecs}s (Retry attempt ${currentRetry + 1}/3)$traceSuffix")

        // Live countdown simulation in UI
        activeBackoffMessage.value = msg
        activeBackoffTimeTotal.value = backoffSecs
        activeBackoffTimeRemaining.value = backoffSecs

        repository.insertMessage(msg.copy(status = "BACKOFF"))

        for (i in backoffSecs downTo 1) {
            if (!isKafkaOnline.value) {
                // If Kafka goes down during backoff, pause backoff timer
                while (!isKafkaOnline.value) {
                    delay(200)
                }
            }
            activeBackoffTimeRemaining.value = i
            delay((1000 / simSpeed.value).toLong())
        }

        activeBackoffMessage.value = null

        // Increment retry headers and place back on RETRY_TOPIC (MAIN_TOPIC)
        val nextRetryCount = currentRetry + 1
        addLog("INFO", "RetryWorker", "Backoff complete. Re-injecting record frame ${msg.id} back into ingestion stream. Next Retry: $nextRetryCount$traceSuffix")

        repository.insertMessage(msg.copy(
            topic = "vr.assets.pack.events.v1",
            status = "QUEUED",
            retryCount = nextRetryCount,
            traceParent = msg.traceParent,
            timestamp = System.currentTimeMillis()
        ))
    }

    private fun extractVertexCount(payload: String): Int {
        // Simple regex/substring check for "vertex_count" or "vertices"
        val regex = "\"vertex_count\"\\s*:\\s*(\\d+)".toRegex()
        val matchResult = regex.find(payload)
        return matchResult?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    // Public actions
    fun generateTraceParent(): String {
        val traceId = UUID.randomUUID().toString().replace("-", "")
        val spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
        return "00-$traceId-$spanId-01"
    }

    fun injectMessage(payload: String, key: String = "asset_key_${Random().nextInt(1000)}", traceParent: String? = null) {
        viewModelScope.launch {
            val id = "msg_" + UUID.randomUUID().toString().substring(0, 8)
            val msg = KafkaMessage(
                id = id,
                payload = payload,
                key = key,
                topic = "vr.assets.pack.events.v1",
                status = "QUEUED",
                traceParent = traceParent
            )
            repository.insertMessage(msg)
            val traceLogInfo = if (traceParent != null) " [OTel traceparent context injected: $traceParent]" else ""
            addLog("INFO", "GoProducerAdapter", "Hexagonal adapter published event frame to vr.assets.pack.events.v1 (Msg ID: $id)$traceLogInfo")
        }
    }

    fun injectPoisonPill(useGoTracing: Boolean = false) {
        val trace = if (useGoTracing) generateTraceParent() else null
        injectMessage("id: asset_982, vertices: malformed_poison_pill_missing_json_braces", traceParent = trace)
    }

    fun injectVertexLimitBreach(useGoTracing: Boolean = false) {
        val trace = if (useGoTracing) generateTraceParent() else null
        injectMessage("""
            {
              "id": "terrain_patch_402",
              "vertex_count": 45000,
              "resolution": "high",
              "format": "gltf"
            }
        """.trimIndent(), "terrain_patch_402", traceParent = trace)
    }

    fun injectHealthyAsset(useGoTracing: Boolean = false) {
        val id = Random().nextInt(1000)
        val trace = if (useGoTracing) generateTraceParent() else null
        injectMessage("""
            {
              "id": "asset_pack_$id",
              "vertex_count": 4200,
              "resolution": "medium",
              "format": "usd"
            }
        """.trimIndent(), "asset_pack_$id", traceParent = trace)
    }

    fun toggleElasticsearch() {
        isElasticsearchOnline.value = !isElasticsearchOnline.value
        viewModelScope.launch {
            addLog("INFO", "Simulator", "Elasticsearch status toggled to: " + if (isElasticsearchOnline.value) "ONLINE" else "OFFLINE/CRASHED")
        }
    }

    fun toggleKafka() {
        isKafkaOnline.value = !isKafkaOnline.value
        viewModelScope.launch {
            addLog("INFO", "Simulator", "Kafka Broker cluster status toggled to: " + if (isKafkaOnline.value) "ONLINE" else "OFFLINE")
        }
    }

    fun setSpeed(speed: Double) {
        simSpeed.value = speed
        viewModelScope.launch {
            addLog("INFO", "Simulator", "Simulation clock speed multiplier adjusted to: ${speed}x")
        }
        startSimulationLoops()
    }

    fun clearAll() {
        viewModelScope.launch {
            repository.clearMessages()
            repository.clearLogs()
            metricDlqTotal.value = 0
            metricPoisonPill.value = 0
            metricVertexBreach.value = 0
            metricEsOutageCount.value = 0
            activeBackoffMessage.value = null
            addLog("INFO", "Simulator", "Simulator reset complete. Counters and histories purged.")
        }
    }

    private suspend fun addLog(level: String, tag: String, message: String) {
        repository.insertLog(SimulationLog(
            level = level,
            tag = tag,
            message = message
        ))
    }

    // Generate Raw Prometheus metrics format text representation
    fun getPrometheusMetricsText(): String {
        return """
            # HELP kafka_dlq_messages_total Total number of structural elements dropped to DLQ sink paths
            # TYPE kafka_dlq_messages_total counter
            kafka_dlq_messages_total ${metricDlqTotal.value}
            
            # HELP kafka_dlq_messages_by_error Messages isolated inside DLQ partitioned streams filtered by technical validation types
            # TYPE kafka_dlq_messages_by_error counter
            kafka_dlq_messages_by_error{error_type="poison_pill"} ${metricPoisonPill.value}
            kafka_dlq_messages_by_error{error_type="vertex_limit_breach"} ${metricVertexBreach.value}
            kafka_dlq_messages_by_error{error_type="elasticsearch_outage"} ${metricEsOutageCount.value}
        """.trimIndent()
    }
}

class ViewModelFactory(
    private val application: Application,
    private val repository: SimulatorRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SimulatorViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SimulatorViewModel(application, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
