package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import com.example.data.local.SimulatorDatabase
import com.example.data.model.KafkaMessage
import com.example.data.model.SimulationLog
import com.example.data.repository.SimulatorRepository
import com.example.ui.theme.*
import com.example.viewmodel.SimulatorViewModel
import com.example.viewmodel.ViewModelFactory
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Get database, repository and ViewModel
        val database = SimulatorDatabase.getDatabase(this)
        val repository = SimulatorRepository(database.simulatorDao())
        val factory = ViewModelFactory(application, repository)
        val viewModel = ViewModelProvider(this, factory)[SimulatorViewModel::class.java]

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DeepSlateBackground)
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        MainScreen(viewModel = viewModel)
                    }
                }
            }
        }
    }
}

@Composable
fun MainScreen(viewModel: SimulatorViewModel) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(0) }
    val tabTitles = listOf("Queues & Logs", "Metrics (/metrics)", "Code & Architecture")

    val isKafkaOnline by viewModel.isKafkaOnline.collectAsState()
    val isElasticOnline by viewModel.isElasticsearchOnline.collectAsState()
    val simSpeedMultiplier by viewModel.simSpeed.collectAsState()

    val dlqTotal by viewModel.metricDlqTotal.collectAsState()
    val poisonPillCount by viewModel.metricPoisonPill.collectAsState()
    val vertexBreachCount by viewModel.metricVertexBreach.collectAsState()
    val esOutageCount by viewModel.metricEsOutageCount.collectAsState()

    val mainQueueList by viewModel.mainQueue.collectAsState()
    val dlqQueueList by viewModel.dlqQueue.collectAsState()
    val permanentDlqList by viewModel.permanentDlqQueue.collectAsState()

    val isIngestionActive by viewModel.isIngestionWorkerActive.collectAsState()
    val isRetryActive by viewModel.isRetryWorkerActive.collectAsState()

    val activeBackoff by viewModel.activeBackoffMessage.collectAsState()
    val activeBackoffRemaining by viewModel.activeBackoffTimeRemaining.collectAsState()
    val activeBackoffTotal by viewModel.activeBackoffTimeTotal.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepSlateBackground)
    ) {
        // App Header
        HeaderSection(
            isKafkaOnline = isKafkaOnline,
            isElasticOnline = isElasticOnline,
            simSpeed = simSpeedMultiplier,
            onToggleKafka = { viewModel.toggleKafka() },
            onToggleElastic = { viewModel.toggleElasticsearch() },
            onSpeedChange = { viewModel.setSpeed(it) },
            onReset = { viewModel.clearAll() }
        )

        // Topology Schematic visualizer (Interactive Pipeline)
        TopologyPipeline(
            mainCount = mainQueueList.size,
            dlqCount = dlqQueueList.size,
            permanentCount = permanentDlqList.size,
            isIngestionActive = isIngestionActive,
            isRetryActive = isRetryActive,
            isElasticOnline = isElasticOnline,
            isKafkaOnline = isKafkaOnline,
            activeBackoff = activeBackoff,
            backoffRemaining = activeBackoffRemaining,
            backoffTotal = activeBackoffTotal
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Trigger / Injection Deck
        InjectionDeck(
            onInjectHealthy = { tracing -> viewModel.injectHealthyAsset(tracing) },
            onInjectPoison = { tracing -> viewModel.injectPoisonPill(tracing) },
            onInjectBreach = { tracing -> viewModel.injectVertexLimitBreach(tracing) },
            onCustomInject = { payload, tracing -> viewModel.injectMessage(payload, traceParent = if (tracing) viewModel.generateTraceParent() else null) }
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Tab Row switcher
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = CardSurface,
            contentColor = TelemetryPrimary,
            modifier = Modifier.fillMaxWidth()
        ) {
            tabTitles.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    modifier = Modifier.testTag("tab_$index"),
                    text = {
                        Text(
                            text = title,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedTab == index) TelemetryPrimary else TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                )
            }
        }

        // Tab Content
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            when (selectedTab) {
                0 -> QueuesAndLogsTab(viewModel = viewModel)
                1 -> PrometheusMetricsTab(viewModel = viewModel)
                2 -> RustSourceTab()
            }
        }
    }
}

@Composable
fun HeaderSection(
    isKafkaOnline: Boolean,
    isElasticOnline: Boolean,
    simSpeed: Double,
    onToggleKafka: () -> Unit,
    onToggleElastic: () -> Unit,
    onSpeedChange: (Double) -> Unit,
    onReset: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Asset Retry Engine",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = TelemetryPrimary
                    )
                    Text(
                        text = "Interactive Chaos & Retry Simulator",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }

                IconButton(
                    onClick = onReset,
                    modifier = Modifier
                        .background(TelemetryError.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                        .testTag("reset_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reset Simulator",
                        tint = TelemetryError
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Cluster Status Toggles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Kafka Broker Switch
                StatusToggleCard(
                    title = "Kafka Broker",
                    subtitle = if (isKafkaOnline) "CLUSTER ONLINE" else "CLUSTER OFFLINE",
                    isActive = isKafkaOnline,
                    activeColor = TelemetrySecondary,
                    inactiveColor = TelemetryError,
                    onClick = onToggleKafka,
                    modifier = Modifier.weight(1f).testTag("toggle_kafka")
                )

                // Elasticsearch Node Switch
                StatusToggleCard(
                    title = "Elasticsearch",
                    subtitle = if (isElasticOnline) "NODE ONLINE" else "OUTAGE CRASHED",
                    isActive = isElasticOnline,
                    activeColor = TelemetrySecondary,
                    inactiveColor = TelemetryTertiary,
                    onClick = onToggleElastic,
                    modifier = Modifier.weight(1f).testTag("toggle_elasticsearch")
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Clock / Simulation speed controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Clock Speed: ${simSpeed.toInt()}x",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = TextPrimary,
                    modifier = Modifier.width(90.dp)
                )

                Slider(
                    value = simSpeed.toFloat(),
                    onValueChange = { onSpeedChange(it.toDouble()) },
                    valueRange = 1f..10f,
                    steps = 3,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("speed_slider"),
                    colors = SliderDefaults.colors(
                        thumbColor = TelemetryPrimary,
                        activeTrackColor = TelemetryPrimary,
                        inactiveTrackColor = DeepSlateBackground
                    )
                )

                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (simSpeed > 1) "⚡ Accelerated" else "⏱️ Normal",
                    fontSize = 10.sp,
                    color = if (simSpeed > 1) TelemetryPrimary else TextSecondary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun StatusToggleCard(
    title: String,
    subtitle: String,
    isActive: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.height(60.dp),
        colors = CardDefaults.cardColors(
            containerColor = DeepSlateBackground
        ),
        border = BorderStroke(1.dp, if (isActive) activeColor.copy(alpha = 0.5f) else inactiveColor.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(if (isActive) activeColor else inactiveColor, RoundedCornerShape(5.dp))
            )

            Spacer(modifier = Modifier.width(8.dp))

            Column {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = TextPrimary
                )
                Text(
                    text = subtitle,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isActive) activeColor else inactiveColor
                )
            }
        }
    }
}

@Composable
fun TopologyPipeline(
    mainCount: Int,
    dlqCount: Int,
    permanentCount: Int,
    isIngestionActive: Boolean,
    isRetryActive: Boolean,
    isElasticOnline: Boolean,
    isKafkaOnline: Boolean,
    activeBackoff: KafkaMessage?,
    backoffRemaining: Int,
    backoffTotal: Int
) {
    // Pipeline Topology diagram using Canvas / Widgets
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = "LIVE CLUSTER TOPOLOGY MAP",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = TextSecondary,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        // Draw pipeline system connectors
                        val pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                        // Connector 1: Main -> Ingestion Worker
                        drawLine(
                            color = TelemetryPrimary.copy(alpha = 0.4f),
                            start = Offset(size.width * 0.16f, size.height * 0.25f),
                            end = Offset(size.width * 0.45f, size.height * 0.25f),
                            strokeWidth = 3f,
                            pathEffect = pathEffect
                        )
                        // Connector 2: Ingestion -> Elasticsearch
                        drawLine(
                            color = if (isElasticOnline) TelemetrySecondary.copy(alpha = 0.4f) else TelemetryTertiary.copy(alpha = 0.4f),
                            start = Offset(size.width * 0.55f, size.height * 0.25f),
                            end = Offset(size.width * 0.84f, size.height * 0.25f),
                            strokeWidth = 3f,
                            pathEffect = pathEffect
                        )
                        // Connector 3: Ingestion -> DLQ (Failure branch)
                        drawLine(
                            color = TelemetryError.copy(alpha = 0.4f),
                            start = Offset(size.width * 0.5f, size.height * 0.35f),
                            end = Offset(size.width * 0.5f, size.height * 0.65f),
                            strokeWidth = 3f,
                            pathEffect = pathEffect
                        )
                        // Connector 4: DLQ -> Retry Worker
                        drawLine(
                            color = TelemetryTertiary.copy(alpha = 0.4f),
                            start = Offset(size.width * 0.5f, size.height * 0.75f),
                            end = Offset(size.width * 0.16f, size.height * 0.75f),
                            strokeWidth = 3f,
                            pathEffect = pathEffect
                        )
                        // Connector 5: Retry Worker -> Main (Retry injection loop)
                        drawLine(
                            color = TelemetrySecondary.copy(alpha = 0.4f),
                            start = Offset(size.width * 0.16f, size.height * 0.65f),
                            end = Offset(size.width * 0.16f, size.height * 0.35f),
                            strokeWidth = 3f,
                            pathEffect = pathEffect
                        )
                        // Connector 6: DLQ -> Permanent Graveyard
                        drawLine(
                            color = TelemetryError.copy(alpha = 0.5f),
                            start = Offset(size.width * 0.62f, size.height * 0.75f),
                            end = Offset(size.width * 0.84f, size.height * 0.75f),
                            strokeWidth = 3f,
                            pathEffect = pathEffect
                        )
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Column 1: Topics (Left side)
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(28.dp)
                ) {
                    // MAIN Topic Node
                    PipelineNode(
                        label = "Main Topic",
                        topicName = "vr.assets.events.v1",
                        badgeText = "$mainCount msgs",
                        borderColor = TelemetryPrimary,
                        backgroundColor = TelemetryPrimary.copy(alpha = 0.08f),
                        icon = Icons.Default.Dns
                    )

                    // RETRY WORKER Node
                    PipelineNode(
                        label = "Retry Worker",
                        topicName = "asset-retry-worker",
                        badgeText = if (isRetryActive) "ACTIVE" else "IDLE",
                        borderColor = if (isRetryActive) TelemetryTertiary else TextSecondary,
                        backgroundColor = if (isRetryActive) TelemetryTertiary.copy(alpha = 0.08f) else Color.Transparent,
                        icon = Icons.Default.Cached,
                        isProcessing = isRetryActive
                    )
                }

                // Column 2: Engines (Middle column)
                Column(
                    modifier = Modifier.weight(1.1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(28.dp)
                ) {
                    // INGESTION WORKER Node
                    PipelineNode(
                        label = "Ingestion Worker",
                        topicName = "asset-ingester",
                        badgeText = if (isIngestionActive) "POLLING" else "IDLE",
                        borderColor = if (isIngestionActive) TelemetrySecondary else TextSecondary,
                        backgroundColor = if (isIngestionActive) TelemetrySecondary.copy(alpha = 0.08f) else Color.Transparent,
                        icon = Icons.Default.SettingsInputComponent,
                        isProcessing = isIngestionActive
                    )

                    // TEMPORARY DLQ Node
                    PipelineNode(
                        label = "Temp DLQ",
                        topicName = "events.v1.DLQ",
                        badgeText = "$dlqCount msgs",
                        borderColor = TelemetryTertiary,
                        backgroundColor = TelemetryTertiary.copy(alpha = 0.08f),
                        icon = Icons.Default.Warning
                    )
                }

                // Column 3: Sinks (Right Column)
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(28.dp)
                ) {
                    // ELASTICSEARCH Node
                    PipelineNode(
                        label = "Elasticsearch",
                        topicName = "ES Cluster Sink",
                        badgeText = if (isElasticOnline) "HEALTHY" else "CRASHED",
                        borderColor = if (isElasticOnline) TelemetrySecondary else TelemetryError,
                        backgroundColor = if (isElasticOnline) TelemetrySecondary.copy(alpha = 0.08f) else TelemetryError.copy(alpha = 0.08f),
                        icon = Icons.Default.CloudQueue
                    )

                    // PERMANENT GRAVEYARD Node
                    PipelineNode(
                        label = "Permanent DLQ",
                        topicName = "DLQ.PERMANENT",
                        badgeText = "$permanentCount terminal",
                        borderColor = TelemetryError,
                        backgroundColor = TelemetryError.copy(alpha = 0.08f),
                        icon = Icons.Default.DeleteForever
                    )
                }
            }

            // Live Backoff animation status banner
            if (activeBackoff != null) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { (backoffTotal - backoffRemaining).toFloat() / backoffTotal },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = TelemetryTertiary,
                    trackColor = DeepSlateBackground
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⏳ Backoff Delay: ${backoffRemaining}s remaining (Msg: ${activeBackoff.id})",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TelemetryTertiary
                    )
                    Text(
                        text = "2^${activeBackoff.retryCount} * 5s backoff",
                        fontSize = 10.sp,
                        color = TextSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
fun PipelineNode(
    label: String,
    topicName: String,
    badgeText: String,
    borderColor: Color,
    backgroundColor: Color,
    icon: ImageVector,
    isProcessing: Boolean = false
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulsing")
    val alphaGlow by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = BorderStroke(
            width = 1.dp,
            color = if (isProcessing) borderColor.copy(alpha = alphaGlow) else borderColor.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .background(backgroundColor)
                .padding(8.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = borderColor,
                modifier = Modifier.size(20.dp)
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = topicName,
                fontSize = 8.sp,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Surface(
                color = borderColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = badgeText,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = borderColor,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
fun InjectionDeck(
    onInjectHealthy: (Boolean) -> Unit,
    onInjectPoison: (Boolean) -> Unit,
    onInjectBreach: (Boolean) -> Unit,
    onCustomInject: (String, Boolean) -> Unit
) {
    var useTracingContext by remember { mutableStateOf(false) }
    var showCustomDialog by remember { mutableStateOf(false) }
    var customPayload by remember { mutableStateOf("""{
  "id": "asset_custom_01",
  "vertex_count": 8500,
  "resolution": "high",
  "format": "fbx"
}""") }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "INJECT SIMULATED PAYLOADS",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary,
                    letterSpacing = 1.sp
                )

                // Tracing Switch
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Go OTel Tracing",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (useTracingContext) TelemetryPrimary else TextSecondary
                    )
                    Switch(
                        checked = useTracingContext,
                        onCheckedChange = { useTracingContext = it },
                        modifier = Modifier.scale(0.7f).testTag("otel_trace_switch"),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TelemetryPrimary,
                            checkedTrackColor = TelemetryPrimary.copy(alpha = 0.5f)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 1. Healthy Asset
                Button(
                    onClick = { onInjectHealthy(useTracingContext) },
                    colors = ButtonDefaults.buttonColors(containerColor = TelemetrySecondary),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .weight(1.3f)
                        .height(44.dp)
                        .testTag("inject_healthy_btn"),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Healthy", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                // 2. Poison Pill
                Button(
                    onClick = { onInjectPoison(useTracingContext) },
                    colors = ButtonDefaults.buttonColors(containerColor = TelemetryError),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .weight(1.3f)
                        .height(44.dp)
                        .testTag("inject_poison_btn"),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Poison Pill", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                // 3. Vertex Breach
                Button(
                    onClick = { onInjectBreach(useTracingContext) },
                    colors = ButtonDefaults.buttonColors(containerColor = TelemetryTertiary),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .weight(1.4f)
                        .height(44.dp)
                        .testTag("inject_breach_btn"),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Vertex Limit", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                // 4. Custom
                OutlinedButton(
                    onClick = { showCustomDialog = true },
                    border = BorderStroke(1.dp, TelemetryPrimary),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .weight(1.1f)
                        .height(44.dp)
                        .testTag("custom_payload_btn"),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TelemetryPrimary),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Custom", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showCustomDialog) {
        AlertDialog(
            onDismissRequest = { showCustomDialog = false },
            title = { Text("Publish Custom Payload", color = TelemetryPrimary) },
            text = {
                OutlinedTextField(
                    value = customPayload,
                    onValueChange = { customPayload = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .testTag("custom_payload_field"),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TelemetryPrimary,
                        unfocusedBorderColor = TextSecondary,
                        focusedLabelColor = TelemetryPrimary,
                        cursorColor = TelemetryPrimary
                    ),
                    label = { Text("Payload String (JSON format)") }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onCustomInject(customPayload, useTracingContext)
                        showCustomDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TelemetryPrimary)
                ) {
                    Text("Inject Payload")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = CardSurface
        )
    }
}

@Composable
fun QueuesAndLogsTab(viewModel: SimulatorViewModel) {
    val logs by viewModel.allLogs.collectAsState()
    val messages by viewModel.allMessages.collectAsState()

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Left Column: Message Queues state
        Column(
            modifier = Modifier
                .weight(1.1f)
                .fillMaxHeight()
        ) {
            Text(
                text = "ACTIVE BROKER QUEUES",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = DeepSlateBackground),
                            border = BorderStroke(1.dp, CardSurface)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.Inbox,
                                        contentDescription = null,
                                        tint = TextSecondary,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "No messages in queue.\nClick an Injector to start.",
                                        fontSize = 11.sp,
                                        color = TextSecondary,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                } else {
                    items(messages, key = { it.id }) { msg ->
                        MessageCard(msg = msg)
                    }
                }
            }
        }

        // Right Column: Live scrolling Server logs
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SERVER LOG OUTPUT",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary
                )

                TextButton(
                    onClick = { viewModel.clearAll() },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("Clear Logs", fontSize = 10.sp, color = TelemetryError)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Simulated terminal view
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(TermBackground, RoundedCornerShape(8.dp))
                    .border(1.dp, CardSurface, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    reverseLayout = true
                ) {
                    items(logs) { log ->
                        LogLineItem(log = log)
                    }
                }
            }
        }
    }
}

@Composable
fun MessageCard(msg: KafkaMessage) {
    val badgeColor = when (msg.topic) {
        "vr.assets.pack.events.v1" -> TelemetryPrimary
        "vr.assets.pack.events.v1.DLQ" -> TelemetryTertiary
        else -> TelemetryError
    }

    val statusBg = when (msg.status) {
        "PROCESSING" -> TelemetrySecondary.copy(alpha = 0.2f)
        "BACKOFF" -> TelemetryTertiary.copy(alpha = 0.2f)
        "FAILED" -> TelemetryError.copy(alpha = 0.2f)
        else -> TextSecondary.copy(alpha = 0.15f)
    }

    val statusColor = when (msg.status) {
        "PROCESSING" -> TelemetrySecondary
        "BACKOFF" -> TelemetryTertiary
        "FAILED" -> TelemetryError
        else -> TextPrimary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.25f))
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = msg.id,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Surface(
                    color = statusBg,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = msg.status,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Topic: ${msg.topic.substringAfterLast(".")}",
                fontSize = 9.sp,
                color = TextSecondary,
                fontFamily = FontFamily.Monospace
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = msg.payload,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = if (msg.errorType != null) LogErrorColor else LogInfoColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TermBackground, RoundedCornerShape(4.dp))
                    .padding(6.dp)
            )

            if (msg.retryCount > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Cached,
                        contentDescription = "Retry Count",
                        tint = TelemetryTertiary,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Header x-retry-count: ${msg.retryCount}",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = TelemetryTertiary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
fun LogLineItem(log: SimulationLog) {
    val formatter = remember<SimpleDateFormat> { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val timeStr = formatter.format(Date(log.timestamp))

    val color = when (log.level) {
        "INFO" -> LogInfoColor
        "WARN" -> LogWarnColor
        else -> LogErrorColor
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "[$timeStr]",
            fontSize = 9.sp,
            color = TextSecondary,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(50.dp)
        )

        Text(
            text = log.level,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(36.dp)
        )

        Text(
            text = "${log.tag}: ${log.message}",
            fontSize = 9.5.sp,
            color = TextPrimary,
            fontFamily = FontFamily.Monospace,
            lineHeight = 12.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun PrometheusMetricsTab(viewModel: SimulatorViewModel) {
    val metricsText = viewModel.getPrometheusMetricsText()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "PROMETHEUS ENDPOINT EXPORT",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary
                )
                Text(
                    text = "HTTP Server exposed at :8889/metrics",
                    fontSize = 9.sp,
                    color = TextSecondary
                )
            }

            IconButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(metricsText))
                    Toast.makeText(context, "Metrics copied to clipboard!", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.testTag("copy_metrics_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy Metrics",
                    tint = TelemetryPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(TermBackground, RoundedCornerShape(8.dp))
                .border(1.dp, CardSurface, RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            Text(
                text = metricsText,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = LogInfoColor,
                lineHeight = 16.sp,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            )
        }
    }
}

@Composable
fun RustSourceTab() {
    var activeSubTab by remember { mutableStateOf(0) }
    val files = listOf(
        "retry-worker/Cargo.toml",
        "retry-worker/src/main.rs",
        "metrics.rs",
        "exporter/main.rs",
        "ports/producers.go",
        "adapters/kafka_producer_tx.go",
        "docker-compose-observability.yml",
        "otel-collector-config.yaml",
        "prometheus.yml"
    )
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    val fileContents = listOf(
        // Cargo.toml
        """[package]
name = "asset-retry-worker"
version = "0.1.0"
edition = "2021"

[dependencies]
rdkafka = { version = "0.36.0", features = ["tokio"] }
tokio = { version = "1.36.0", features = ["full"] }
tracing = "0.1.40"
tracing-subscriber = { version = "0.3.18", features = ["json"] }""",

        // main.rs
        """use rdkafka::config::ClientConfig;
use rdkafka::consumer::{Consumer, StreamConsumer, CommitMode};
use rdkafka::producer::{FutureProducer, FutureRecord};
use rdkafka::message::{Message, Headers, BorrowedHeaders, OwnedHeaders, Header};
use std::time::Duration;
use tracing::{info, error, warn};

const DLQ_TOPIC: &str = "vr.assets.pack.events.v1.DLQ";
const RETRY_TOPIC: &str = "vr.assets.pack.events.v1";
const PERMANENT_DLQ_TOPIC: &str = "vr.assets.pack.events.v1.DLQ.PERMANENT";
const MAX_RETRIES: i32 = 3;

fn extract_retry_count(headers: Option<&BorrowedHeaders>) -> i32 {
    if let Some(hdrs) = headers {
        for header in hdrs.iter() {
            if header.key == "x-retry-count" {
                if let Some(val) = header.value {
                    if let Ok(s) = std::str::from_utf8(val) {
                        return s.parse::<i32>().unwrap_or(0);
                    }
                }
            }
        }
    }
    0
}

fn increment_retry_headers(headers: Option<&BorrowedHeaders>, current_count: i32) -> OwnedHeaders {
    let mut owned = OwnedHeaders::new();
    if let Some(hdrs) = headers {
        for header in hdrs.iter() {
            if header.key != "x-retry-count" {
                owned = owned.insert(Header {
                    key: header.key,
                    value: header.value,
                });
            }
        }
    }
    let next_count = (current_count + 1).to_string();
    owned.insert(Header {
        key: "x-retry-count",
        value: Some(&next_count),
    })
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt::json().init();
    info!("Initializing isolated Asset Retry Engine worker process...");

    let consumer: StreamConsumer = ClientConfig::new()
        .set("group.id", "vr_retry_workers")
        .set("bootstrap.servers", "localhost:9092")
        .set("enable.auto.commit", "false")
        .set("auto.offset.reset", "earliest")
        .create()?;
    consumer.subscribe(&[DLQ_TOPIC])?;

    let producer: FutureProducer = ClientConfig::new()
        .set("bootstrap.servers", "localhost:9092")
        .create()?;

    loop {
        match consumer.recv().await {
            Ok(msg) => {
                let payload = match msg.payload() {
                    Some(p) => p,
                    None => {
                        let _ = consumer.commit_message(&msg, CommitMode::Async);
                        continue;
                    }
                };

                let current_retry = extract_retry_count(msg.headers());
                
                if current_retry >= MAX_RETRIES {
                    warn!(retry_count = current_retry, "Max retry ceiling broken. Relocating payload to structural terminal graveyard topic.");
                    
                    let record = FutureRecord::to(PERMANENT_DLQ_TOPIC)
                        .payload(payload)
                        .key(msg.key().unwrap_or(&[]))
                        .headers(msg.headers().map(|h| h.detach()).unwrap_or_else(OwnedHeaders::new));

                    match producer.send(record, Duration::from_secs(5)).await {
                        Ok(_) => {
                            let _ = consumer.commit_message(&msg, CommitMode::Async);
                        }
                        Err((err, _)) => {
                            error!(error = ?err, "Terminal safety-net sink cluster failure. Halting pipeline execution.");
                        }
                    }
                } else {
                    // Exponential Backoff Delay Equation: 2^current_retry * 5 seconds
                    let backoff_secs = 2u64.pow(current_retry as u32) * 5;
                    info!(backoff_delay = backoff_secs, retry_attempt = current_retry + 1, "Executing exponential backoff sequence step...");
                    tokio::time::sleep(Duration::from_secs(backoff_secs)).await;

                    let updated_headers = increment_retry_headers(msg.headers(), current_retry);
                    let record = FutureRecord::to(RETRY_TOPIC)
                        .payload(payload)
                        .key(msg.key().unwrap_or(&[]))
                        .headers(updated_headers);

                    match producer.send(record, Duration::from_secs(5)).await {
                        Ok(_) => {
                            let _ = consumer.commit_message(&msg, CommitMode::Async);
                        }
                        Err((err, _)) => {
                            error!(error = ?err, "Failed to re-inject record frame back to ingestion stream. Aborting commit.");
                        }
                    }
                }
            }
            Err(e) => error!(error = ?e, "Retry worker consumer socket loss exception thrown"),
        }
    }
}""",

        // metrics.rs
        """use prometheus::{register_int_counter, register_int_counter_vec, IntCounter, IntCounterVec};
use lazy_static::lazy_static;

lazy_static! {
    pub static ref DLQ_MESSAGES_TOTAL: IntCounter = register_int_counter!(
        "kafka_dlq_messages_total", 
        "Total number of structural elements dropped to DLQ sink paths"
    ).unwrap();
    
    pub static ref DLQ_MESSAGES_BY_ERROR: IntCounterVec = register_int_counter_vec!(
        "kafka_dlq_messages_by_error", 
        "Messages isolated inside DLQ partitioned streams filtered by technical validation types",
        &["error_type"]
    ).unwrap();
}""",

        // metrics main.rs
        """// Append this initialization inside the main entrypoint to expose the scrapable metrics endpoint
use axum::{routing::get, Router};
use prometheus::{Encoder, TextEncoder};

async fn metrics_handler() -> String {
    let encoder = TextEncoder::new();
    let metric_families = prometheus::gather();
    let mut buffer = Vec::new();
    if encoder.encode(&metric_families, &mut buffer).is_err() {
        return "Internal instrumentation error mapping schema frames".to_string();
    }
    String::from_utf8(buffer).unwrap_or_default()
}

fn start_metrics_server() {
    tokio::spawn(async {
        let app = Router::new().route("/metrics", get(metrics_handler));
        let listener = tokio::net::TcpListener::bind("0.0.0.0:8889").await.unwrap();
        info!("Prometheus metrics engine exposed live over endpoint space on port 8889");
        axum::serve(listener, app).await.unwrap();
    });
}""",

        // ports/producers.go
        """package ports

import (
	"context"
)

type VRAssetPackPayload struct {
	ID                string    `json:"ID"`
	Title             string    `json:"Title"`
	Style             string    `json:"Style"`
	Meshes            []MeshRef `json:"Meshes"`
	TargetedPlatforms []string  `json:"TargetedPlatforms"`
}

type MeshRef struct {
	ID                 string `json:"ID"`
	VertexCount        int32  `json:"VertexCount"`
	LODLevels          int32  `json:"LODLevels"`
	HasCustomCollision bool   `json:"HasCustomCollision"`
}

// AssetPackProducerAdapter represents our Hexagonal outbound port
type AssetPackProducerAdapter interface {
	PublishAssetPackCreated(ctx context.Context, payload VRAssetPackPayload) error
}""",

        // adapters/kafka_producer_tx.go
        """package adapters

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"apps/asset-command-service/internal/ports"

	"github.com/confluentinc/confluent-kafka-go/kafka"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/propagation"
)

type ConfluentKafkaAdapter struct {
	producer *kafka.Producer
	topic    string
}

func NewConfluentKafkaAdapter(topic string) (*ConfluentKafkaAdapter, error) {
	bootstrapServers := os.Getenv("KAFKA_BOOTSTRAP_SERVERS")
	apiKey := os.Getenv("KAFKA_API_KEY")
	apiSecret := os.Getenv("KAFKA_API_SECRET")

	if bootstrapServers == "" || apiKey == "" || apiSecret == "" {
		return nil, fmt.Errorf("missing secure Kafka environment configurations")
	}

	config := &kafka.ConfigMap{
		"bootstrap.servers":                     bootstrapServers,
		"security.protocol":                     "SASL_SSL",
		"sasl.mechanisms":                       "PLAIN",
		"sasl.username":                         apiKey,
		"sasl.password":                         apiSecret,
		"ssl.endpoint.identification.algorithm": "https",
		"acks":                                  "all",
		"enable.idempotence":                    true,
	}

	p, err := kafka.NewProducer(config)
	if err != nil {
		return nil, err
	}

	return &ConfluentKafkaAdapter{producer: p, topic: topic}, nil
}

// KafkaHeaderCarrier implements the OTel TextMapCarrier interface
type KafkaHeaderCarrier []kafka.Header

func (c *KafkaHeaderCarrier) Get(key string) string {
	for _, h := range *c {
		if h.Key == key {
			return string(h.Value)
		}
	}
	return ""
}

func (c *KafkaHeaderCarrier) Set(key string, value string) {
	*c = append(*c, kafka.Header{Key: key, Value: []byte(value)})
}

func (c *KafkaHeaderCarrier) Keys() []string {
	keys := make([]string, len(*c))
	for i, h := range *c {
		keys[i] = h.Key
	}
	return keys
}

func (k *ConfluentKafkaAdapter) PublishAssetPackCreated(ctx context.Context, payload ports.VRAssetPackPayload) error {
	tr := otel.Tracer("asset-command-producer")
	ctx, span := tr.Start(ctx, "PublishAssetPackCreated")
	defer span.End()

	envelope := map[string]interface{}{
		"Type":    "AssetPackCreated",
		"Payload": payload,
	}

	jsonData, err := json.Marshal(envelope)
	if err != nil {
		return fmt.Errorf("failed serialization sequence: %w", err)
	}

	// Extract active trace context and inject into the header transport carrier
	var headers KafkaHeaderCarrier
	otel.GetTextMapPropagator().Inject(ctx, &headers)

	msg := &kafka.Message{
		TopicPartition: kafka.TopicPartition{Topic: &k.topic, Partition: kafka.PartitionAny},
		Value:          jsonData,
		Key:            []byte(payload.ID),
		Headers:        headers,
	}

	deliveryChan := make(chan kafka.Event)
	defer close(deliveryChan)

	err = k.producer.Produce(msg, deliveryChan)
	if err != nil {
		return fmt.Errorf("production pipeline exception: %w", err)
	}

	e := <-deliveryChan
	m := e.(*kafka.Message)

	if m.TopicPartition.Error != nil {
		return fmt.Errorf("hard delivery acknowledgment loss: %w", m.TopicPartition.Error)
	}

	return nil
}""",

        // docker-compose-observability.yml
        """version: '3.8'
services:
  otel-collector:
    image: otel/opentelemetry-collector-contrib:0.90.0
    command: ["--config=/etc/otelcol/config.yaml"]
    volumes:
      - ./opentelemetry/otel-collector-config.yaml:/etc/otelcol/config.yaml
    ports:
      - "4317:4317" # gRPC Receiver
      - "8889:8889" # Prometheus Exporter Metrics Route

  prometheus:
    image: prom/prometheus:v2.48.0
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
      - '--storage.tsdb.path=/prometheus'
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml
    ports:
      - "9090:9090"

  jaeger:
    image: jaegertracing/all-in-one:1.50
    environment:
      - COLLECTOR_OTLP_ENABLED=true
    ports:
      - "16686:16686" # Web UI Dashboard Interface Port
      - "4318:4318" # HTTP OTLP Input Port

  grafana:
    image: grafana/grafana:10.2.2
    environment:
      - GF_SECURITY_ADMIN_USER=admin
      - GF_SECURITY_ADMIN_PASSWORD=secure_grafana_99
    ports:
      - "3000:3000"
    volumes:
      - ./grafana/provisioning:/etc/grafana/provisioning""",

        // otel-collector-config.yaml
        """receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
processors:
  batch:
    timeout: 1s
    send_batch_size: 256
exporters:
  prometheus:
    endpoint: 0.0.0.0:8889
    namespace: "pipeline"
  otlp:
    endpoint: jaeger:4317
    tls:
      insecure: true
service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [batch]
      exporters: [otlp]
    metrics:
      receivers: [otlp]
      processors: [batch]
      exporters: [prometheus]""",

        // prometheus.yml
        """global:
  scrape_interval: 5s
scrape_configs:
  - job_name: 'rust-worker-direct'
    static_configs:
      - targets: ['localhost:8889']

  - job_name: 'otel-collector-metrics'
    static_configs:
      - targets: ['otel-collector:8889']"""
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        // Files select row
        ScrollableTabRow(
            selectedTabIndex = activeSubTab,
            containerColor = DeepSlateBackground,
            contentColor = TelemetryPrimary,
            edgePadding = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            files.forEachIndexed { index, file ->
                Tab(
                    selected = activeSubTab == index,
                    onClick = { activeSubTab = index },
                    modifier = Modifier.testTag("subtab_$index"),
                    text = {
                        Text(
                            text = file,
                            fontSize = 11.sp,
                            fontWeight = if (activeSubTab == index) FontWeight.Bold else FontWeight.Normal,
                            fontFamily = FontFamily.Monospace,
                            color = if (activeSubTab == index) TelemetryPrimary else TextSecondary
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val sectionTitle = when (activeSubTab) {
                in 0..3 -> "RUST RETRY ENGINE"
                in 4..5 -> "GO HEXAGONAL ADAPTER"
                else -> "DOCKER OBSERVABILITY FABRIC"
            }
            Text(
                text = sectionTitle,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = TextSecondary
            )

            IconButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(fileContents[activeSubTab]))
                    Toast.makeText(context, "Source code copied!", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.testTag("copy_source_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy Source",
                    tint = TelemetryPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(TermBackground, RoundedCornerShape(8.dp))
                .border(1.dp, CardSurface, RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            Text(
                text = fileContents[activeSubTab],
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp,
                color = TextPrimary,
                lineHeight = 15.sp,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            )
        }
    }
}
