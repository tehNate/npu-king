package com.batchdaddy

import android.content.Context

const val DEFAULT_SERVER = "https://batch.cocoio.cc"

/**
 * Shared settings for the UI and the foreground service.
 *
 * The SharedPreferences file is intentionally still named "batchdaddy": NPU
 * King keeps the com.batchdaddy package for upgrade continuity, so an existing
 * 0.2.x install keeps its worker identity and payout address when it upgrades.
 */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("batchdaddy", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("server_url", DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(value) = sp.edit().putString("server_url", value.trim().trimEnd('/')).apply()

    /**
     * "standalone" = device/NPU probe only, no tracker.
     * "connect"    = register with a BatchDaddy tracker as a worker.
     * Existing installs default to connect; fresh installs default to standalone.
     */
    var mode: String
        get() = sp.getString(
            "mode",
            if (sp.contains("server_url") || sp.contains("worker_id")) "connect" else "standalone"
        ) ?: "standalone"
        set(value) = sp.edit().putString("mode", value).apply()

    var companySlug: String
        get() = sp.getString("company_slug", "") ?: ""
        set(value) = sp.edit().putString("company_slug", value.trim().lowercase()).apply()

    var payoutAddress: String
        get() = sp.getString("payout_address", "") ?: ""
        set(value) = sp.edit().putString("payout_address", value.trim()).apply()

    var deviceId: String
        get() = sp.getString("device_id", "") ?: ""
        set(value) = sp.edit().putString("device_id", value).apply()

    var deviceSecret: String
        get() = sp.getString("device_secret", "") ?: ""
        set(value) = sp.edit().putString("device_secret", value).apply()

    var workerId: String
        get() = sp.getString("worker_id", "") ?: ""
        set(value) = sp.edit().putString("worker_id", value).apply()

    var apiToken: String
        get() = sp.getString("api_token", "") ?: ""
        set(value) = sp.edit().putString("api_token", value).apply()

    var running: Boolean
        get() = sp.getBoolean("running", false)
        set(value) = sp.edit().putBoolean("running", value).apply()

    var statusState: String
        get() = sp.getString("status_state", "OFFLINE") ?: "OFFLINE"
        set(value) = sp.edit().putString("status_state", value).apply()

    var statusText: String
        get() = sp.getString("status_text", "Not started") ?: "Not started"
        set(value) = sp.edit().putString("status_text", value).apply()

    var lastTask: String
        get() = sp.getString("last_task", "") ?: ""
        set(value) = sp.edit().putString("last_task", value).apply()

    var lastHeartbeat: Long
        get() = sp.getLong("last_heartbeat", 0L)
        set(value) = sp.edit().putLong("last_heartbeat", value).apply()

    var pendingBatch: Double
        get() = sp.getFloat("pending_batch", 0f).toDouble()
        set(value) = sp.edit().putFloat("pending_batch", value.toFloat()).apply()

    var uptimeSeconds: Double
        get() = sp.getFloat("uptime_seconds", 0f).toDouble()
        set(value) = sp.edit().putFloat("uptime_seconds", value.toFloat()).apply()

    fun clearWorker() {
        sp.edit().remove("worker_id").remove("api_token").apply()
    }

    fun resetStatus() {
        sp.edit()
            .putString("status_state", "OFFLINE")
            .putString("status_text", "Not started")
            .putBoolean("running", false)
            .apply()
    }
}
