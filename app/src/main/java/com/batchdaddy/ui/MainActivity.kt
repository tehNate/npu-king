package com.batchdaddy.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batchdaddy.Prefs
import com.batchdaddy.net.BatchApi
import com.batchdaddy.npu.NpuDetector
import com.batchdaddy.npu.NpuInfo
import com.batchdaddy.worker.WorkerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val npuInfo = NpuDetector(this).detect()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                NpuKingScreen(
                    prefs = prefs,
                    npuInfo = npuInfo,
                    onStart = { startWorker() },
                    onStop = { stopWorker() },
                )
            }
        }
    }

    private fun startWorker() {
        val intent = Intent(this, WorkerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopWorker() {
        stopService(Intent(this, WorkerService::class.java))
        prefs.resetStatus()
    }
}

private val Gold = Color(0xFFF5C518)
private val Green = Color(0xFF3FB950)
private val Red = Color(0xFFF85149)
private val Dim = Color(0xFF8B949E)
private val CardBg = Color(0xFF161B22)

@Composable
fun NpuKingScreen(
    prefs: Prefs,
    npuInfo: NpuInfo,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(prefs.mode) }
    var serverUrl by remember { mutableStateOf(prefs.serverUrl) }
    var companySlug by remember { mutableStateOf(prefs.companySlug) }
    var payout by remember { mutableStateOf(prefs.payoutAddress) }
    var running by remember { mutableStateOf(prefs.running) }
    var state by remember { mutableStateOf(prefs.statusState) }
    var status by remember { mutableStateOf(prefs.statusText) }
    var workerId by remember { mutableStateOf(prefs.workerId) }
    var pending by remember { mutableStateOf(prefs.pendingBatch) }
    var uptime by remember { mutableStateOf(prefs.uptimeSeconds) }
    var lastTask by remember { mutableStateOf(prefs.lastTask) }
    var error by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf("") }
    var probeInfo by remember { mutableStateOf(npuInfo) }

    LaunchedEffect(Unit) {
        while (true) {
            running = prefs.running
            state = prefs.statusState
            status = prefs.statusText
            workerId = prefs.workerId
            pending = prefs.pendingBatch
            uptime = prefs.uptimeSeconds
            lastTask = prefs.lastTask
            if (workerId.isNotBlank() && prefs.mode == "connect") {
                try {
                    val stats = withContext(Dispatchers.IO) {
                        BatchApi(prefs.serverUrl).worker(workerId)
                    }
                    pending = stats.pendingBatch
                    uptime = stats.uptimeSeconds
                    state = prefs.statusState
                } catch (_: Exception) {
                    // The status card already shows connection errors.
                }
            }
            delay(3_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("NPU King", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Gold)
        Text("Android NPU compute client", fontSize = 12.sp, color = Dim)
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == "standalone",
                onClick = {
                    mode = "standalone"
                    prefs.mode = "standalone"
                    error = ""
                    testResult = ""
                },
                label = { Text("Standalone") },
                enabled = !running,
            )
            FilterChip(
                selected = mode == "connect",
                onClick = {
                    mode = "connect"
                    prefs.mode = "connect"
                    error = ""
                    testResult = ""
                },
                label = { Text("Connect") },
                enabled = !running,
            )
        }

        Spacer(Modifier.height(14.dp))

        if (mode == "standalone") {
            StandalonePanel(
                npuInfo = probeInfo,
                onProbe = {
                    probeInfo = NpuDetector(context).detect()
                    testResult = "Probe refreshed at ${Build.MODEL}"
                },
            )
        } else {
            StatusCard(
                state = state,
                status = status,
                workerId = workerId,
                pending = pending,
                uptime = uptime,
                lastTask = lastTask,
            )

            Spacer(Modifier.height(14.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardBg),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Connection", fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text("Tracker URL") },
                        enabled = !running,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = companySlug,
                        onValueChange = { companySlug = it.trim().lowercase() },
                        label = { Text("Company pool (optional)") },
                        placeholder = { Text("acme — blank = platform BATCH pool") },
                        enabled = !running,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = payout,
                        onValueChange = { payout = it.trim() },
                        label = { Text("Payout address (SS58)") },
                        placeholder = { Text("5…") },
                        enabled = !running,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Rewards are paid in the pool's coin to this address.",
                            fontSize = 11.sp,
                            color = Dim,
                            modifier = Modifier.weight(1f).padding(top = 6.dp),
                        )
                        TextButton(
                            enabled = !running && serverUrl.isNotBlank(),
                            onClick = {
                                testResult = "Testing…"
                                scope.launch {
                                    testResult = try {
                                        val health = withContext(Dispatchers.IO) {
                                            BatchApi(serverUrl.trim()).health()
                                        }
                                        "Reachable: ${health.optString("service", "tracker")} · asset #${health.optString("asset_id", "—")}"
                                    } catch (e: Exception) {
                                        "Unreachable: ${e.message}"
                                    }
                                }
                            },
                        ) { Text("Test") }
                    }
                    if (testResult.isNotBlank()) {
                        Text(testResult, color = if (testResult.startsWith("Reachable")) Green else Dim, fontSize = 11.sp)
                    }
                    if (serverUrl.trim().startsWith("http://")) {
                        Text(
                            "Beta warning: this tracker is using plain HTTP. Do not use a valuable payout address until HTTPS is live.",
                            color = Gold,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    if (error.isNotBlank()) {
                        Text(error, color = Red, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Button(
                onClick = {
                    error = ""
                    if (running) {
                        onStop()
                        running = false
                    } else {
                        val url = serverUrl.trim().trimEnd('/')
                        val slug = companySlug.trim().lowercase()
                        val address = payout.trim()
                        when {
                            !url.startsWith("http://") && !url.startsWith("https://") ->
                                error = "Tracker URL must start with http:// or https://"
                            slug.isNotEmpty() && !slug.matches(Regex("[a-z0-9_-]+")) ->
                                error = "Company slug may contain only a-z, 0-9, - and _"
                            !looksLikeSs58(address) ->
                                error = "Enter a valid CoCo SS58 address (starts with 5)"
                            else -> {
                                prefs.serverUrl = url
                                prefs.companySlug = slug
                                prefs.payoutAddress = address
                                prefs.mode = "connect"
                                onStart()
                                running = true
                                state = "CONNECTING"
                                status = "Starting…"
                            }
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = if (running) Red else Green),
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                Text(
                    if (running) "STOP WORKER" else "START WORKER",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        NpuCard(probeInfo)
        Spacer(Modifier.height(14.dp))
        DeviceCard()

        Spacer(Modifier.height(16.dp))
        Text(
            "Beta: compute is a test stub (no real NPU inference yet). " +
                "BATCH and company coins are CoCo assets; payouts are signed by the pool owner, not by this phone.",
            fontSize = 11.sp,
            color = Dim,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StandalonePanel(npuInfo: NpuInfo, onProbe: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBg),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Standalone", fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text(
                "No tracker connection. Use this mode to inspect the phone's NPU profile. " +
                    "Switch to Connect when you want to work in a BatchDaddy pool.",
                fontSize = 12.sp,
                color = Dim,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Detected: ${npuInfo.chipset} · ${npuInfo.vendor}",
                fontSize = 12.sp,
                color = Gold,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onProbe,
                colors = ButtonDefaults.buttonColors(containerColor = Gold),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("RUN DEVICE PROBE", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun StatusCard(
    state: String,
    status: String,
    workerId: String,
    pending: Double,
    uptime: Double,
    lastTask: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBg),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Worker", fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    state,
                    color = when (state) {
                        "ONLINE", "WORKING" -> Green
                        "ERROR" -> Red
                        else -> Dim
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            InfoRow("Status", status)
            InfoRow("Worker ID", workerId.ifBlank { "not registered" })
            InfoRow("Pending rewards", formatAmount(pending), Gold)
            InfoRow("Phone uptime", formatUptime(uptime))
            InfoRow("Last task", lastTask.ifBlank { "—" })
        }
    }
}

@Composable
private fun NpuCard(npuInfo: NpuInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBg),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("NPU", fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(8.dp))
            InfoRow("Chipset", npuInfo.chipset)
            InfoRow("Vendor", npuInfo.vendor)
            InfoRow("Driver", npuInfo.driver)
            InfoRow("API", npuInfo.apiLevel)
            InfoRow("Compute units", npuInfo.computeUnits.toString())
            InfoRow("Max memory", "${npuInfo.maxMemoryMB.toInt()} MB")
            InfoRow(
                "Available",
                if (npuInfo.available) "YES" else "NO",
                if (npuInfo.available) Green else Red,
            )
        }
    }
}

@Composable
private fun DeviceCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBg),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Device", fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(8.dp))
            InfoRow("Model", "${Build.MANUFACTURER} ${Build.MODEL}")
            InfoRow("Android", Build.VERSION.RELEASE)
            InfoRow("API level", "${Build.VERSION.SDK_INT}")
            InfoRow("App", "0.3.0")
            InfoRow("Package", "com.batchdaddy")
        }
    }
}

private fun looksLikeSs58(value: String): Boolean {
    if (value.length !in 47..48) return false
    if (!value.startsWith("5")) return false
    return value.all { it in "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz" }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = Color(0xFFE6EDF3),
    mono: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Dim, fontSize = 12.sp)
        Text(
            value,
            color = valueColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

private fun formatAmount(value: Double): String = String.format("%.4f", value)

private fun formatUptime(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
