package com.flowbangla.app.api

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

enum class ProviderHealthStatus {
    READY,
    QUOTA_LOW,
    COOLDOWN_429,
    CIRCUIT_OPEN,
    MODEL_RETIRED
}

data class ProviderCircuitState(
    var consecutiveFailures: Int = 0,
    var circuitOpenUntilElapsedMs: Long = 0L,
    var rateLimitUntilElapsedMs: Long = 0L,
    var lastErrorReason: String? = null
)

class ProviderHealthTracker(context: Context? = null) {

    private val states = ConcurrentHashMap<String, ProviderCircuitState>()
    private val deadModels = ConcurrentHashMap<String, Long>() // modelId -> expiryElapsedMs
    private val prefs = context?.getSharedPreferences("flow_quota_counters", Context.MODE_PRIVATE)

    private fun getElapsedRealtime(): Long {
        return try {
            SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    }

    fun isProviderAvailable(providerKey: String): Boolean {
        val st = states[providerKey.lowercase()] ?: return true
        val now = getElapsedRealtime()
        if (now < st.rateLimitUntilElapsedMs) return false
        if (now < st.circuitOpenUntilElapsedMs) return false
        return true
    }

    fun isModelDead(modelId: String): Boolean {
        val exp = deadModels[modelId.lowercase()] ?: return false
        return getElapsedRealtime() < exp
    }

    fun recordSuccess(providerKey: String, audioSecondsBilled: Int = 0) {
        states[providerKey.lowercase()] = ProviderCircuitState()
        incrementDailyUsage(providerKey.lowercase(), audioSecondsBilled)
    }

    fun recordHttpError(providerKey: String, modelId: String?, httpCode: Int, retryAfterSec: Long?, reason: String) {
        val key = providerKey.lowercase()
        val st = states.getOrPut(key) { ProviderCircuitState() }
        val now = getElapsedRealtime()
        st.lastErrorReason = "HTTP $httpCode: ${reason.take(80)}"

        when (httpCode) {
            429 -> {
                val cooldownMs = ((retryAfterSec ?: 60L).coerceIn(10L, 600L)) * 1000L
                st.rateLimitUntilElapsedMs = now + cooldownMs
            }
            404, 410 -> {
                // Model retired/not found -> blacklist model for 24h so we never waste latency hitting it again!
                if (!modelId.isNullOrBlank()) {
                    deadModels[modelId.lowercase()] = now + (24 * 60 * 60 * 1000L)
                }
                st.consecutiveFailures++
                if (st.consecutiveFailures >= 3) {
                    st.circuitOpenUntilElapsedMs = now + 120_000L // 2-minute circuit breaker
                }
            }
            else -> {
                st.consecutiveFailures++
                if (st.consecutiveFailures >= 3) {
                    st.circuitOpenUntilElapsedMs = now + 120_000L
                }
            }
        }
    }

    fun getStatus(providerKey: String): ProviderHealthStatus {
        val st = states[providerKey.lowercase()] ?: return ProviderHealthStatus.READY
        val now = getElapsedRealtime()
        return when {
            now < st.rateLimitUntilElapsedMs -> ProviderHealthStatus.COOLDOWN_429
            now < st.circuitOpenUntilElapsedMs -> ProviderHealthStatus.CIRCUIT_OPEN
            else -> ProviderHealthStatus.READY
        }
    }

    fun getLastError(providerKey: String): String? = states[providerKey.lowercase()]?.lastErrorReason

    private fun incrementDailyUsage(providerKey: String, audioSec: Int) {
        if (prefs == null) return
        val todayKey = try {
            java.time.LocalDate.now().toString()
        } catch (_: Throwable) {
            "today"
        }
        val countKey = "${providerKey}_req_$todayKey"
        val secKey = "${providerKey}_sec_$todayKey"
        prefs.edit()
            .putInt(countKey, prefs.getInt(countKey, 0) + 1)
            .putInt(secKey, prefs.getInt(secKey, 0) + audioSec)
            .apply()
    }

    fun getTodayRequestCount(providerKey: String): Int {
        if (prefs == null) return 0
        val todayKey = try {
            java.time.LocalDate.now().toString()
        } catch (_: Throwable) {
            "today"
        }
        return prefs.getInt("${providerKey.lowercase()}_req_$todayKey", 0)
    }
}
