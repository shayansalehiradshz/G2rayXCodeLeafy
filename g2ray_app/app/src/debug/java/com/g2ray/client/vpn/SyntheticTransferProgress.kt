package com.g2ray.client.vpn

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer

/** Bytes returned to the reader / accepted by the writer, not remote acknowledgements. */
internal class SyntheticTransferProgress(private val nowMs: () -> Long) {
    private var bytes = 0L
    private var first: Long? = null
    private var last: Long? = null
    private val samples = ArrayDeque<Pair<Long, Long>>()
    private var discarded = false

    @Synchronized fun advance(count: Long) {
        if (count <= 0L) return
        bytes = if (count > Long.MAX_VALUE - bytes) Long.MAX_VALUE else bytes + count
        val time = runCatching(nowMs).getOrNull() ?: return
        if (first == null) first = time
        last = time
        if (samples.isEmpty() || time - samples.last().first >= 250L) {
            if (samples.size == MAX_SAMPLES) { samples.removeFirst(); discarded = true }
            samples.addLast(time to bytes)
        }
    }

    @Synchronized fun summary(): String =
        "observed_bytes=$bytes first_progress_ms=${first ?: "unknown"} " +
            "last_progress_ms=${last ?: "unknown"} progress_capped=$discarded " +
            "progress_samples=${samples.joinToString(",") { "${it.first}:${it.second}" }.ifEmpty { "none" }}"

    private companion object { const val MAX_SAMPLES = 16 }
}

internal class SyntheticProgressRequestBody(
    private val delegate: RequestBody,
    private val progress: SyntheticTransferProgress
) : RequestBody() {
    override fun contentType(): MediaType? = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()
    override fun isDuplex(): Boolean = delegate.isDuplex()
    override fun isOneShot(): Boolean = delegate.isOneShot()

    override fun writeTo(sink: BufferedSink) {
        val forwarding = object : ForwardingSink(sink) {
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                progress.advance(byteCount)
            }
        }.buffer()
        delegate.writeTo(forwarding)
        forwarding.flush()
    }
}
