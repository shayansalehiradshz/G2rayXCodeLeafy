package com.g2ray.client.parser

import android.net.Uri
import com.g2ray.client.model.G2RayProfile
import com.g2ray.client.model.Transport
import com.squareup.moshi.JsonReader
import java.net.URLDecoder
import okio.Buffer
import org.json.JSONObject

object VlessUriParser {

    private const val MAX_IMPORT_INPUT_CHARS = 1_000_000
    private const val MAX_PROFILE_URI_CHARS = 16_384
    private const val MAX_PROFILE_CANDIDATES = 500

    private val uuidRegex =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val routeCandidateSuffix = Regex("-auto-(?:\\d{1,3}-){3}\\d{1,3}$")

    private fun isTruthy(value: String?): Boolean {
        return value?.trim()?.lowercase() in setOf("1", "true", "yes", "on", "enabled")
    }

    private fun parseJsonObject(input: String, errorMessage: String): JSONObject {
        try {
            // JSONObject alone accepts lenient syntax and trailing data.
            JsonReader.of(Buffer().writeUtf8(input)).use { reader ->
                require(reader.peek() == JsonReader.Token.BEGIN_OBJECT)
                reader.skipValue()
                require(reader.peek() == JsonReader.Token.END_DOCUMENT)
            }
            return JSONObject(input)
        } catch (_: Exception) {
            throw IllegalArgumentException(errorMessage)
        }
    }

    private fun padBase64(value: String): String {
        val remainder = value.length % 4
        return if (remainder == 0) value else value + "=".repeat(4 - remainder)
    }

    private fun tryDecodeBase64Subscription(input: String): String? {
        val normalized = padBase64(input.trim().replace("\\s".toRegex(), ""))
        return try {
            val bytes = android.util.Base64.decode(normalized, android.util.Base64.DEFAULT)
            bytes.toString(Charsets.UTF_8)
        } catch (_: Exception) {
            try {
                val bytes = android.util.Base64.decode(normalized, android.util.Base64.URL_SAFE)
                bytes.toString(Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun extractVlessLines(
        input: String,
        maxCandidates: Int = MAX_PROFILE_CANDIDATES
    ): List<String> {
        val found = Regex("(?i)vless://\\S+")
            .findAll(input)
            .take(maxCandidates)
            .map { match ->
                match.value.trim().trimEnd(',', ';', '.', ')', ']', '}', '"', '\'')
            }
            .filter { it.startsWith("vless://", ignoreCase = true) }
            .toList()

        if (found.isNotEmpty()) {
            return found
        }

        return input.trim().lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("vless://", ignoreCase = true) }
            .take(maxCandidates)
            .toList()
    }

    fun parseFirstProfile(input: String): Result<G2RayProfile> {
        if (input.length > MAX_IMPORT_INPUT_CHARS) {
            return Result.failure(IllegalArgumentException("Config import text is too large"))
        }
        val source = runCatching { ConfigImportPayload.profileText(input) }.getOrElse { return Result.failure(it) }
        val candidates = extractVlessLines(source)

        for (candidate in candidates) {
            val result = parse(candidate)
            if (result.isSuccess) return result
        }

        val decoded = tryDecodeBase64Subscription(source)
        if (decoded != null) {
            for (candidate in extractVlessLines(decoded)) {
                val result = parse(candidate)
                if (result.isSuccess) return result
            }
        }

        return Result.failure(IllegalArgumentException("No supported G2ray vless:// profile found"))
    }

    fun parseAllProfiles(input: String): List<Result<G2RayProfile>> {
        if (input.length > MAX_IMPORT_INPUT_CHARS) {
            return listOf(Result.failure(IllegalArgumentException("Config import text is too large")))
        }
        val source = runCatching { ConfigImportPayload.profileText(input) }
            .getOrElse { return listOf(Result.failure(it)) }
        var candidates = extractVlessLines(source, MAX_PROFILE_CANDIDATES + 1)
        if (candidates.isEmpty()) {
            val decoded = tryDecodeBase64Subscription(source)
            if (decoded != null) {
                candidates = extractVlessLines(decoded, MAX_PROFILE_CANDIDATES + 1)
            }
        }
        if (candidates.size > MAX_PROFILE_CANDIDATES) {
            return listOf(
                Result.failure(
                    IllegalArgumentException("A single import can contain at most $MAX_PROFILE_CANDIDATES configs")
                )
            )
        }
        return candidates.map { parse(it) }
    }

    fun parse(raw: String): Result<G2RayProfile> {
        return try {
            val uri = raw.trim()
            require(uri.length <= MAX_PROFILE_URI_CHARS) { "Config link is too large" }
            if (!uri.startsWith("vless://", ignoreCase = true)) {
                return Result.failure(IllegalArgumentException("Only vless:// profiles are supported"))
            }

            val parsed = Uri.parse(uri)
            val uuid = parsed.userInfo ?: return Result.failure(IllegalArgumentException("Missing UUID"))
            require(uuidRegex.matches(uuid)) { "Invalid UUID" }

            val host = parsed.host?.takeIf { it.isNotBlank() }
                ?: return Result.failure(IllegalArgumentException("Missing host"))
            val port = parsed.port.takeIf { it in 1..65535 }
                ?: return Result.failure(IllegalArgumentException("Missing or invalid port"))

            val security = parsed.getQueryParameter("security")?.lowercase() ?: ""
            val type = parsed.getQueryParameter("type")?.lowercase() ?: ""
            val sni = parsed.getQueryParameter("sni")
            val headerHost = parsed.getQueryParameter("host")
            val path = parsed.getQueryParameter("path")
            require(!path.isNullOrBlank()) { "Missing path" }
            require(path.startsWith("/")) { "Path must start with /" }
            val mode = parsed.getQueryParameter("mode")
            val extra = parsed.getQueryParameter("extra")
            val finalMaskJson = parsed.getQueryParameter("fm")?.trim()?.takeIf { it.isNotEmpty() }
            val cipherSuites = parsed.getQueryParameter("cs")?.trim()?.takeIf { it.isNotEmpty() }
            val alpnParam = parsed.getQueryParameter("alpn") ?: ""
            val alpn = alpnParam
                .split(",")
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
            val fp = parsed.getQueryParameter("fp")
            val allowInsecure = isTruthy(parsed.getQueryParameter("allowInsecure"))
            val insecure = isTruthy(parsed.getQueryParameter("insecure"))

            val encryption = parsed.getQueryParameter("encryption")?.trim()?.lowercase() ?: ""
            require(encryption == "none") { "G2ray VLESS profiles require encryption=none" }

            val name = parsed.encodedFragment?.let { Uri.decode(it) } ?: "Custom Config"

            require(security == "tls") { "G2ray Codespaces profiles require security=tls" }
            require(!sni.isNullOrBlank()) { "Missing SNI" }
            require(!headerHost.isNullOrBlank()) { "Missing Host header" }

            val transport = when (type) {
                "xhttp" -> Transport.XHTTP
                "ws" -> Transport.WS
                else -> throw IllegalArgumentException("Only xhttp and ws transports are supported")
            }

            if (transport == Transport.XHTTP) {
                require(mode in SUPPORTED_XHTTP_MODES) { "G2ray XHTTP profiles require mode=${SUPPORTED_XHTTP_MODES.joinToString("|")}" }
                require(alpn.contains("h2")) { "XHTTP Codespaces profiles require alpn=h2" }
            }

            val xhttpExtraJson = extra?.trim()?.takeIf { it.isNotEmpty() }?.let {
                require(transport == Transport.XHTTP) { "XHTTP extra requires type=xhttp" }
                require(it.length <= MAX_XHTTP_EXTRA_CHARS) { "XHTTP extra JSON is too large" }
                parseJsonObject(it, "Invalid XHTTP extra JSON object").toString()
            }

            finalMaskJson?.let {
                require(it.length <= MAX_FINAL_MASK_CHARS) { "FinalMask JSON is too large" }
                parseJsonObject(it, "Invalid FinalMask JSON")
            }
            cipherSuites?.let {
                require(it.length <= MAX_CIPHER_SUITES_CHARS) { "Cipher suites value is too large" }
                require(fp.equals("unsafe", ignoreCase = true)) {
                    "Cipher suites require fp=unsafe because browser fingerprints override them"
                }
            }

            require(!allowInsecure) { "allowInsecure=1 is not accepted by default" }
            require(!insecure) { "insecure=1 is not accepted by default" }

            val profile = G2RayProfile(
                rawUri = uri,
                name = name,
                uuid = uuid,
                address = host,
                port = port,
                security = security,
                sni = sni,
                hostHeader = headerHost,
                alpn = alpn,
                fingerprint = fp,
                transport = transport,
                path = path,
                mode = mode,
                allowInsecure = allowInsecure,
                finalMaskJson = finalMaskJson,
                cipherSuites = cipherSuites,
                xhttpExtraJson = xhttpExtraJson
            )

            Result.success(profile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Replaces only the dial address and display fragment. TLS SNI, Host,
     * transport, path, ALPN, fingerprint, and advanced query parameters stay
     * byte-for-byte identical to the validated source profile.
     */
    fun withEndpointAddress(profile: G2RayProfile, address: String): Result<G2RayProfile> {
        return try {
            require(isUsablePublicIpv4(address)) { "Route candidate must be a public IPv4 address" }
            val raw = profile.rawUri.trim()
            val authorityStart = raw.indexOf("://").takeIf { it >= 0 }?.plus(3)
                ?: throw IllegalArgumentException("Invalid VLESS URI")
            val queryStart = raw.indexOf('?', authorityStart).takeIf { it >= 0 } ?: raw.length
            val fragmentStart = raw.indexOf('#', authorityStart).takeIf { it >= 0 } ?: raw.length
            val authorityEnd = minOf(queryStart, fragmentStart)
            require(authorityEnd > authorityStart) { "Invalid VLESS authority" }

            val suffixEnd = fragmentStart.coerceAtMost(raw.length)
            val suffix = raw.substring(authorityEnd, suffixEnd)
            val generatedName = buildString {
                append(profile.name.ifBlank { "G2Ray" }.replace(routeCandidateSuffix, ""))
                append("-auto-")
                append(address.replace('.', '-'))
            }
            val rebuilt = buildString {
                append(raw, 0, authorityStart)
                append(profile.uuid)
                append('@')
                append(address)
                append(':')
                append(profile.port)
                append(suffix)
                append('#')
                append(Uri.encode(generatedName))
            }
            Result.success(parse(rebuilt).getOrThrow())
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    fun semanticRouteKey(profile: G2RayProfile): String {
        return listOf(
            profile.uuid.lowercase(),
            profile.address.lowercase(),
            profile.port.toString(),
            profile.security.lowercase(),
            profile.sni.orEmpty().lowercase(),
            profile.hostHeader.orEmpty().lowercase(),
            profile.transport.name,
            profile.path,
            profile.mode.orEmpty().lowercase(),
            profile.alpn.joinToString(",") { it.lowercase() },
            profile.fingerprint.orEmpty().lowercase(),
            profile.finalMaskJson.orEmpty(),
            profile.cipherSuites.orEmpty(),
            profile.xhttpExtraJson.orEmpty()
        ).joinToString("\u001f")
    }

    fun transportTemplateKey(profile: G2RayProfile): String {
        return listOf(
            profile.uuid.lowercase(),
            profile.port.toString(),
            profile.security.lowercase(),
            profile.sni.orEmpty().lowercase(),
            profile.hostHeader.orEmpty().lowercase(),
            profile.transport.name,
            profile.path,
            profile.mode.orEmpty().lowercase(),
            profile.alpn.joinToString(",") { it.lowercase() },
            profile.fingerprint.orEmpty().lowercase(),
            profile.finalMaskJson.orEmpty(),
            profile.cipherSuites.orEmpty(),
            profile.xhttpExtraJson.orEmpty()
        ).joinToString("\u001f")
    }

    fun isIpv4Literal(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() &&
                part.length <= 3 &&
                part.all(Char::isDigit) &&
                (part.toIntOrNull() ?: -1) in 0..255
        }
    }

    /** Rejects non-routable, private, documentation, benchmark, and multicast ranges. */
    fun isUsablePublicIpv4(value: String): Boolean {
        if (!isIpv4Literal(value)) return false
        val octets = value.split('.').map(String::toInt)
        val first = octets[0]
        val second = octets[1]
        val third = octets[2]
        return when {
            first == 0 || first == 10 || first == 127 || first >= 224 -> false
            first == 100 && second in 64..127 -> false
            first == 169 && second == 254 -> false
            first == 172 && second in 16..31 -> false
            first == 192 && second == 168 -> false
            first == 192 && second == 0 && third in setOf(0, 2) -> false
            first == 192 && second == 88 && third == 99 -> false
            first == 198 && second in setOf(18, 19) -> false
            first == 198 && second == 51 && third == 100 -> false
            first == 203 && second == 0 && third == 113 -> false
            else -> true
        }
    }

    private val SUPPORTED_XHTTP_MODES = setOf("packet-up", "stream-up", "stream-one", "auto")
    private const val MAX_FINAL_MASK_CHARS = 8_192
    private const val MAX_CIPHER_SUITES_CHARS = 4_096
    private const val MAX_XHTTP_EXTRA_CHARS = 8_192
}
