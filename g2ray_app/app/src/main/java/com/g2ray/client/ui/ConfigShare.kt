package com.g2ray.client.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun encodeConfigQr(value: String, size: Int = 768): Bitmap {
    require(value.isNotBlank() && value.length <= 16_384) { "Config is empty or too large" }
    val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8"))
    val pixels = IntArray(size * size) { index ->
        if (matrix[index % size, index / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

@Composable
internal fun ConfigShareButton(rawUri: String) {
    var showing by remember { mutableStateOf(false) }
    IconButton(onClick = { showing = true }) { Icon(Icons.Default.Share, "Share config") }
    if (showing) ConfigShareDialog(rawUri, onDismiss = { showing = false })
}

@Composable
private fun ConfigShareDialog(rawUri: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var qrRequested by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val qr by produceState<Result<Bitmap>?>(null, rawUri, qrRequested) {
        value = if (qrRequested) withContext(Dispatchers.Default) { runCatching { encodeConfigQr(rawUri) } } else null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (qrRequested) "Config QR" else "Share config", Modifier.weight(1f))
                FeatureInfoButton(FeatureHelp.Share)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (qrRequested) {
                    val result = qr
                    when {
                        result == null -> CircularProgressIndicator()
                        result.isSuccess -> Image(result.getOrThrow().asImageBitmap(), "Scannable config QR",
                            Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White))
                        else -> Text("This config is too large for a QR. Use Copy instead.")
                    }
                } else {
                    TextButton(onClick = { qrRequested = true }) { Text("Show QR code") }
                }
                TextButton(onClick = {
                    message = if (runCatching { copyConfigLinkToClipboard(context, rawUri) }.getOrDefault(false))
                        "Config copied" else "Clipboard unavailable"
                }) { Text("Copy link") }
                message?.let { Text(it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
