package com.batchdaddy.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.batchdaddy.Prefs
import com.batchdaddy.net.ApiException
import com.batchdaddy.net.BatchApi
import com.batchdaddy.net.NpuSpec
import com.batchdaddy.net.RemoteTask
import com.batchdaddy.npu.NpuDetector
import com.batchdaddy.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * NPU King foreground worker: registers this phone with a BatchDaddy tracker
 * over HTTPS REST, joins an optional company pool, heartbeats, pulls tasks,
 * runs the beta compute stub, and posts results. Rewards are accrued
 * server-side; payouts are sent separately by the treasury script, so no chain
 * keys exist on the phone.
 */
class WorkerService : Service() {

    companion object {
        private const val TAG = "NpuKingWorker"
        private const val CHANNEL_ID = "npuking_worker"
        private const val NOTIF_ID = 1
        private const val HEARTBEAT_MS = 30_000L
        private const val POLL_MS = 5_000L
        private const val STATS_MS = 15_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: Prefs
    private var loop: Job? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Starting worker…", "NPU King"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (loop?.isActive != true) {
            prefs.running = true
            loop = scope.launch { runWorker() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        prefs.running = false
        prefs.statusState = "OFFLINE"
        prefs.statusText = "Stopped"
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private suspend fun runWorker() {
        val npuInfo = NpuDetector(this).detect()
        val npu = NpuSpec(
            chipset = npuInfo.chipset,
            vendor = npuInfo.vendor,
            driver = npuInfo.driver,
            apiLevel = npuInfo.apiLevel,
            computeUnits = npuInfo.computeUnits,
            maxMemoryMB = npuInfo.maxMemoryMB,
            supportedOps = npuInfo.supportedOps,
        )
        val deviceId = resolveDeviceId()
        val deviceSecret = resolveDeviceSecret()
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val androidVersion = Build.VERSION.RELEASE

        while (scope.isActive) {
            try {
                prefs.statusState = "CONNECTING"
                prefs.statusText = "Registering with ${prefs.serverUrl}"
                notify("Connecting…", "NPU King")

                val api = BatchApi(prefs.serverUrl)
                val reg = api.register(
                    deviceId = deviceId,
                    deviceSecret = deviceSecret,
                    deviceName = deviceName,
                    npu = npu,
                    batteryPct = batteryLevel(),
                    charging = isCharging(),
                    androidVersion = androidVersion,
                    payoutAddress = prefs.payoutAddress,
                )
                if (reg.workerId.isBlank() || reg.apiToken.isBlank()) {
                    throw ApiException(0, "Tracker returned no worker id/token")
                }
                prefs.workerId = reg.workerId
                prefs.apiToken = reg.apiToken

                val companySlug = prefs.companySlug.trim()
                if (companySlug.isNotBlank()) {
                    prefs.statusText = "Joining company pool $companySlug…"
                    notify("Joining $companySlug…", "Company pool membership")
                    try {
                        api.joinCompany(companySlug, reg.workerId, reg.apiToken, prefs.payoutAddress)
                    } catch (e: ApiException) {
                        // Do not use ApiException here: the outer handler clears
                        // the worker token on 403/404 and retries every 2 s. A
                        // bad company slug should keep the identity and retry
                        // on the normal 15 s backoff.
                        throw IllegalStateException("Company join failed: ${e.message}")
                    }
                }

                prefs.statusState = "ONLINE"
                prefs.statusText = if (companySlug.isBlank()) {
                    "Connected as ${reg.workerId} · platform pool"
                } else {
                    "Connected as ${reg.workerId} · $companySlug"
                }
                notify(
                    "Connected: ${reg.workerId}",
                    if (companySlug.isBlank()) "Waiting for platform batch jobs"
                    else "Waiting for $companySlug batch jobs"
                )

                var lastHeartbeat = 0L
                var lastStats = 0L
                var working = false

                while (scope.isActive) {
                    val now = SystemClock.elapsedRealtime()

                    if (now - lastHeartbeat >= HEARTBEAT_MS) {
                        api.heartbeat(
                            workerId = prefs.workerId,
                            token = prefs.apiToken,
                            batteryPct = batteryLevel(),
                            charging = isCharging(),
                            status = if (working) "WORKING" else "IDLE",
                        )
                        lastHeartbeat = now
                        prefs.lastHeartbeat = System.currentTimeMillis()
                    }

                    if (now - lastStats >= STATS_MS) {
                        val stats = api.worker(prefs.workerId)
                        prefs.pendingBatch = stats.pendingBatch
                        prefs.uptimeSeconds = stats.uptimeSeconds
                        lastStats = now
                    }

                    val task = api.nextTask(prefs.workerId, prefs.apiToken)
                    if (task != null) {
                        working = true
                        prefs.statusState = "WORKING"
                        prefs.statusText = "Task ${task.taskId} (job ${task.jobId})"
                        notify("Working: ${task.taskId}", "NPU King")
                        executeTask(api, task)
                        prefs.lastTask = task.taskId
                        prefs.statusState = "ONLINE"
                        prefs.statusText = "Last task ${task.taskId} done"
                        notify("Task done: ${task.taskId}", "Waiting for next batch job")
                        working = false
                    }

                    delay(POLL_MS)
                }
            } catch (e: ApiException) {
                // A deleted/rotated worker must re-register rather than loop on 403.
                if (e.status == 403 || e.status == 404) prefs.clearWorker()
                // A changed/lost install identity gets a fresh worker instead of a 409 loop.
                if (e.status == 409) {
                    prefs.clearWorker()
                    prefs.deviceId = ""
                    prefs.deviceSecret = ""
                }
                fail(e.message ?: "API error ${e.status}")
                delay(if (e.status == 403 || e.status == 404 || e.status == 409) 2_000L else 15_000L)
            } catch (e: Exception) {
                fail(e.message ?: e.javaClass.simpleName)
                delay(15_000L)
            }
        }
    }

    private suspend fun executeTask(api: BatchApi, task: RemoteTask) {
        val started = SystemClock.elapsedRealtime()
        val result = processOnNpu(task)
        val elapsedMs = (SystemClock.elapsedRealtime() - started).toDouble()
        val dataB64 = Base64.encodeToString(result, Base64.NO_WRAP)
        api.result(
            workerId = prefs.workerId,
            token = prefs.apiToken,
            jobId = task.jobId,
            taskId = task.taskId,
            dataB64 = dataB64,
            computeTimeMs = elapsedMs,
        )
        Log.i(TAG, "Task ${task.taskId} done in ${elapsedMs}ms (${result.size} bytes)")
    }

    /**
     * BETA compute stub: deterministic XOR of the task input with the payload.
     * This is intentionally labelled beta — real NNAPI/QNN/Neuron dispatch and
     * result verification come after the beta. Keep the measured time honest.
     */
    private fun processOnNpu(task: RemoteTask): ByteArray {
        val input = decode(task.inputB64)
        val payload = decode(task.payloadB64)
        if (input.isEmpty()) return payload
        if (payload.isEmpty()) return input
        val out = ByteArray(input.size)
        for (i in input.indices) {
            out[i] = (input[i].toInt() xor payload[i % payload.size].toInt()).toByte()
        }
        return out
    }

    private fun decode(b64: String): ByteArray = try {
        if (b64.isBlank()) ByteArray(0) else Base64.decode(b64, Base64.DEFAULT)
    } catch (_: Exception) {
        ByteArray(0)
    }

    private fun fail(message: String) {
        Log.w(TAG, "Worker error: $message")
        prefs.statusState = "ERROR"
        prefs.statusText = message
        notify("NPU King worker error", message.take(80))
    }

    /**
     * Random per-install identity. Deliberately not ANDROID_ID: reinstall creates
     * a new worker, and the ID is not guessable from the public tracker.
     */
    private fun resolveDeviceId(): String {
        if (prefs.deviceId.isNotBlank()) return prefs.deviceId
        val id = "android-" + UUID.randomUUID().toString().replace("-", "")
        prefs.deviceId = id
        return id
    }

    private fun resolveDeviceSecret(): String {
        if (prefs.deviceSecret.isNotBlank()) return prefs.deviceSecret
        val secret = UUID.randomUUID().toString() + UUID.randomUUID().toString()
        prefs.deviceSecret = secret
        return secret
    }

    private fun batteryLevel(): Float {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toFloat()
    }

    private fun isCharging(): Boolean {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        return bm.isCharging
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "NPU King Worker", NotificationManager.IMPORTANCE_LOW
        )
        channel.description = "NPU King worker status"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun notify(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(title, text))
    }
}
