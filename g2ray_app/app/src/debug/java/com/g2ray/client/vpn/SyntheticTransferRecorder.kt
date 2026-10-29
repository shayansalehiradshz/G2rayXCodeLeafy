package com.g2ray.client.vpn

/** Test diagnostics only. Never used for ordinary user requests or in a release APK. */
internal class SyntheticTransferRecorder(
    private val nowMs: () -> Long,
    private val report: (String) -> Unit
) {
    enum class Kind { Download, Upload }

    fun <T> record(kind: Kind, transfer: () -> T): T {
        fun time(): Long? = runCatching(nowMs).getOrNull()
        fun emit(value: String) { runCatching { report(value) } }
        val started = time()
        val label = kind.name.lowercase()
        var outcome = "failed"
        var errorType = "none"
        emit("event=bulk_begin kind=$label request_start_ms=${started ?: "unknown"}")
        try {
            val result = transfer()
            outcome = "completed"
            return result
        } catch (error: Throwable) {
            errorType = error.javaClass.simpleName.ifBlank { "Throwable" }
            throw error
        } finally {
            val finished = time()
            emit("event=bulk_end kind=$label outcome=$outcome error_type=$errorType " +
                "request_start_ms=${started ?: "unknown"} request_end_ms=${finished ?: "unknown"}")
        }
    }
}
