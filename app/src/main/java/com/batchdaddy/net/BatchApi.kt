package com.batchdaddy.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, message: String) : IOException(message)

data class NpuSpec(
    val chipset: String,
    val vendor: String,
    val driver: String,
    val apiLevel: String,
    val computeUnits: Float,
    val maxMemoryMB: Float,
    val supportedOps: List<String>,
)

data class RegisterResult(val workerId: String, val apiToken: String, val assetId: String?)

data class RemoteTask(
    val jobId: String,
    val taskId: String,
    val payloadB64: String,
    val inputB64: String,
    val timeoutS: Int,
)

data class WorkerStats(val pendingBatch: Double, val uptimeSeconds: Double, val online: Boolean)

/**
 * REST client for the BatchDaddy tracker (batch.cocoio.cc or a local URL).
 * Shared OkHttp client so creating a client per screen is cheap.
 */
class BatchApi(baseUrl: String) {
    private val root = baseUrl.trim().trimEnd('/')

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val req = Request.Builder()
            .url(root + path)
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val detail = try {
                    JSONObject(text).optString("detail", text)
                } catch (_: Exception) {
                    text
                }
                throw ApiException(resp.code, "HTTP ${resp.code}: ${detail.take(300)}")
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private fun get(path: String): JSONObject {
        val req = Request.Builder().url(root + path).get().build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val detail = try {
                    JSONObject(text).optString("detail", text)
                } catch (_: Exception) {
                    text
                }
                throw ApiException(resp.code, "HTTP ${resp.code}: ${detail.take(300)}")
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    fun register(
        deviceId: String,
        deviceSecret: String,
        deviceName: String,
        npu: NpuSpec,
        batteryPct: Float,
        charging: Boolean,
        androidVersion: String,
        payoutAddress: String,
    ): RegisterResult {
        val npuJson = JSONObject()
            .put("chipset", npu.chipset)
            .put("vendor", npu.vendor)
            .put("driver", npu.driver)
            .put("api_level", npu.apiLevel)
            .put("compute_units", npu.computeUnits.toDouble())
            .put("max_memory_mb", npu.maxMemoryMB.toDouble())
            .put("supported_ops", org.json.JSONArray(npu.supportedOps))
        val body = JSONObject()
            .put("device_id", deviceId)
            .put("device_secret", deviceSecret)
            .put("device_name", deviceName)
            .put("npu", npuJson)
            .put("battery_pct", batteryPct.toDouble())
            .put("charging", charging)
            .put("android_version", androidVersion)
            .put("payout_address", payoutAddress)
        val out = post("/api/worker/register", body)
        return RegisterResult(
            workerId = out.optString("worker_id"),
            apiToken = out.optString("api_token"),
            assetId = out.optString("asset_id").ifBlank { null },
        )
    }

    /** Quick connectivity check used by the Connect screen. */
    fun health(): JSONObject = get("/api/health")

    /**
     * Opt this worker into a company pool. The tracker verifies the worker
     * token and the SS58 payout address before accepting the membership.
     */
    fun joinCompany(slug: String, workerId: String, token: String, payoutAddress: String) {
        val safeSlug = slug.trim().lowercase()
        if (safeSlug.isBlank()) return
        val body = JSONObject()
            .put("worker_id", workerId)
            .put("token", token)
            .put("payout_address", payoutAddress)
        post("/api/companies/$safeSlug/join", body)
    }

    fun heartbeat(
        workerId: String,
        token: String,
        batteryPct: Float,
        charging: Boolean,
        status: String,
    ) {
        val body = JSONObject()
            .put("worker_id", workerId)
            .put("token", token)
            .put("battery_pct", batteryPct.toDouble())
            .put("charging", charging)
            .put("cpu_load", 0.0)
            .put("npu_load", 0.0)
            .put("memory_used_mb", 0)
            .put("status", status)
        post("/api/worker/heartbeat", body)
    }

    fun nextTask(workerId: String, token: String): RemoteTask? {
        val body = JSONObject()
            .put("worker_id", workerId)
            .put("token", token)
            .put("max_tasks", 1)
        val out = post("/api/worker/next-task", body)
        val task = out.optJSONObject("task") ?: out
        val taskId = task.optString("task_id")
        if (taskId.isBlank()) return null
        return RemoteTask(
            jobId = task.optString("job_id"),
            taskId = taskId,
            payloadB64 = task.optString("payload_b64"),
            inputB64 = task.optString("input_b64"),
            timeoutS = task.optInt("timeout_s", 300),
        )
    }

    fun result(
        workerId: String,
        token: String,
        jobId: String,
        taskId: String,
        dataB64: String,
        computeTimeMs: Double,
        error: String = "",
    ) {
        val body = JSONObject()
            .put("worker_id", workerId)
            .put("token", token)
            .put("job_id", jobId)
            .put("task_id", taskId)
            .put("data_b64", dataB64)
            .put("compute_time_ms", computeTimeMs)
            .put("error", error)
        post("/api/worker/result", body)
    }

    fun worker(workerId: String): WorkerStats {
        val out = get("/api/workers/" + workerId)
        return WorkerStats(
            pendingBatch = out.optDouble("pending_batch", 0.0),
            uptimeSeconds = out.optDouble("uptime_seconds", 0.0),
            online = out.optBoolean("online", false),
        )
    }
}
