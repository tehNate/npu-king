package com.batchdaddy.npu

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.FileReader

/**
 * Auto-detects the phone's NPU and its capabilities.
 *
 * Detection strategy:
 * 1. Check Build properties for SoC info
 * 2. Probe known NPU driver paths
 * 3. Query NNAPI capabilities (Android 8.1+)
 * 4. Fingerprint chipset vendor for specific APIs
 */
data class NpuInfo(
    val chipset: String,
    val driver: String,
    val apiLevel: String,
    val computeUnits: Float,
    val maxMemoryMB: Float,
    val supportedOps: List<String>,
    val vendor: String,   // mediatek / qualcomm / samsung / google / hisilicon / generic
    val available: Boolean
)

class NpuDetector(private val context: Context) {

    companion object {
        private const val TAG = "NpuDetector"

        // Known NPU driver sysfs/proc paths
        private val NPU_PATHS = listOf(
            "/sys/class/npu",               // MediaTek / HiSilicon
            "/sys/devices/platform/13a00000.npu", // HiSilicon Kirin
            "/dev/neuron",                  // MediaTek Neuron
            "/dev/npu",                     // Spreadtrum / Qualcomm (some)
            "/sys/kernel/npu",              // Samsung Exynos
            "/vendor/lib/libneuron_adapter.so", // MediaTek
            "/vendor/lib64/libneuron_adapter.so",
            "/vendor/lib/libOpenCL.so",     // Some NPUs expose via OpenCL
            "/system/lib/libedgetpu_client.so", // Google Coral / Tensor
            "/vendor/lib/libqnn*.so",       // Qualcomm QNN
        )
    }

    fun detect(): NpuInfo {
        val soc = detectSoC()
        val vendor = detectVendor(soc)
        val driver = detectDriver()
        val nnapiLevel = detectNnapiLevel()
        val pathsFound = probeNpuPaths()

        return NpuInfo(
            chipset = soc,
            driver = "$driver (paths: $pathsFound)",
            apiLevel = "NNAPI $nnapiLevel",
            computeUnits = estimateComputeUnits(vendor),
            maxMemoryMB = estimateMemoryMB(),
            supportedOps = listOf("ADD", "MUL", "CONV_2D", "AVERAGE_POOL_2D", "RELU", "SOFTMAX", "FULLY_CONNECTED", "TRANSPOSE"),
            vendor = vendor,
            available = pathsFound > 0 || nnapiLevel > 0
        )
    }

    private fun detectSoC(): String {
        Build.HARDWARE.takeIf { it.isNotBlank() && it != "unknown" }?.let { return it }
        Build.BOARD.takeIf { it.isNotBlank() }?.let { return it }
        // Build.SOC_MANUFACTURER is API 31+. Guard it so Android 8–11 does not
        // hit NoSuchFieldError while resolving the field.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER.takeIf { it.isNotBlank() }?.let { return it }
        }
        return Build.MODEL
    }

    private fun detectVendor(soc: String): String {
        val lower = soc.lowercase()
        return when {
            lower.contains("mt") || lower.contains("mediatek") || lower.contains("dimensity") -> "mediatek"
            lower.contains("sm") || lower.contains("qualcomm") || lower.contains("snapdragon") -> "qualcomm"
            lower.contains("exynos") || lower.contains("s5e") || lower.contains("samsung") -> "samsung"
            lower.contains("tensor") || lower.contains("gs") || lower.contains("google") -> "google"
            lower.contains("kirin") || lower.contains("hi") || lower.contains("hisilicon") -> "hisilicon"
            lower.contains("t") || lower.contains("spreadtrum") || lower.contains("unisoc") -> "spreadtrum"
            else -> "generic"
        }
    }

    private fun detectDriver(): String {
        return try {
            val nnapi = Class.forName("android.os.NnApi")
            val method = nnapi.getMethod("getDeviceList")
            val devices = method.invoke(null) as? List<*>
            if (!devices.isNullOrEmpty()) "NNAPI ($devices)" else "NNAPI"
        } catch (_: Exception) {
            "NNAPI (base)"
        }
    }

    private fun detectNnapiLevel(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                val nnApi = Class.forName("android.os.NnApi")
                val method = nnApi.getMethod("getFeatureLevel", Long::class.javaPrimitiveType)
                method.invoke(null, 0L) as? Int ?: 0
            } catch (_: Exception) {
                Build.VERSION.SDK_INT
            }
        } else 0
    }

    private fun probeNpuPaths(): Int {
        return NPU_PATHS.count { path ->
            try {
                val file = java.io.File(path)
                file.exists()
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun estimateComputeUnits(vendor: String): Float {
        return when (vendor) {
            "mediatek" -> 8f      // Dimensity NPU 6.0/7.0
            "qualcomm" -> 6f      // Hexagon NPU
            "samsung" -> 4f       // Exynos NPU
            "google" -> 4f        // Tensor TPU
            "hisilicon" -> 4f     // Da Vinci
            else -> 2f
        }
    }

    private fun estimateMemoryMB(): Float {
        val maxMem = try {
            val proc = BufferedReader(FileReader("/proc/meminfo"))
            val line = proc.readLine()
            proc.close()
            line?.let {
                val parts = it.split("\\s+".toRegex())
                if (parts.size >= 2) parts[1].toFloat() / 1024f else 2048f
            } ?: 2048f
        } catch (_: Exception) { 2048f }
        return maxMem * 0.2f  // NPUs typically get ~20% of total RAM
    }
}
