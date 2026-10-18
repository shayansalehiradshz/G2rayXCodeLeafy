package com.g2ray.client.data

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal data class QuotaEstimate(
    val usedSeconds: Long,
    val budgetSeconds: Long,
    val sampledAtMs: Long,
    val cycle: String,
    val assumedRunning: Boolean
) {
    fun projectedUsedSeconds(nowMs: Long): Long? {
        if (cycle != quotaCycle(nowMs) || nowMs < sampledAtMs) return null
        return usedSeconds + if (assumedRunning) (nowMs - sampledAtMs) / 1000 else 0
    }
    fun label(nowMs: Long): String {
        val used = projectedUsedSeconds(nowMs) ?: return "Usage estimate expired; update from panel"
        val remaining = (budgetSeconds - used).coerceAtLeast(0)
        return "Estimated remaining: ${remaining / 3600}h ${(remaining % 3600) / 60}m" +
            if (assumedRunning) " if host kept running" else " at last update"
    }
}

internal fun quotaCycle(nowMs: Long): String = SimpleDateFormat("yyyy-MM", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}.format(Date(nowMs))

internal class QuotaEstimateStore(private val store: SecureKeyValueStore) {
    fun read(accountId: String): QuotaEstimate? = store.getString(key(accountId), null)?.let { text ->
        runCatching { fromJson(JSONObject(text)) }.getOrNull()
    }

    fun save(accountId: String, estimate: QuotaEstimate) {
        validate(estimate)
        store.putString(key(accountId), JSONObject().apply {
            put("used_seconds", estimate.usedSeconds)
            put("budget_seconds", estimate.budgetSeconds)
            put("sampled_at_ms", estimate.sampledAtMs)
            put("cycle", estimate.cycle)
            put("assumed_running", estimate.assumedRunning)
        }.toString())
    }

    fun recordConfirmedBlock(accountId: String, nowMs: Long = System.currentTimeMillis()) {
        store.putString("${key(accountId)}_blocked", quotaCycle(nowMs))
    }
    fun clearConfirmedBlock(accountId: String) {
        store.putString("${key(accountId)}_blocked", "")
    }
    fun isConfirmedBlocked(accountId: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        store.getString("${key(accountId)}_blocked", null) == quotaCycle(nowMs)

    private fun key(id: String): String { require(id.isNotBlank() && id.length <= 200); return "quota_estimate_$id" }

    companion object {
        fun fromJson(json: JSONObject): QuotaEstimate = QuotaEstimate(
            json.getLong("used_seconds"), json.getLong("budget_seconds"), json.getLong("sampled_at_ms"),
            json.getString("cycle"), json.optBoolean("assumed_running", false)
        ).also(::validate)
        private fun validate(value: QuotaEstimate) {
            require(value.usedSeconds in 0..31L * 24 * 3600 && value.budgetSeconds in 1..31L * 24 * 3600 &&
                value.sampledAtMs > 0 && value.cycle == quotaCycle(value.sampledAtMs)) { "Invalid quota estimate" }
        }
    }
}
