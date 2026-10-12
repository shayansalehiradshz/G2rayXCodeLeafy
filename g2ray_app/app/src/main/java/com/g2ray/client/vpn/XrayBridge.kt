package com.g2ray.client.vpn

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Proxy

data class XrayRuntimeCapabilities(
    val classFound: Boolean,
    val registerDialerControllerFound: Boolean,
    val registerListenerControllerFound: Boolean,
    val setTunFdFound: Boolean,
    val newRunRequestFound: Boolean,
    val runFromJsonFound: Boolean,
    val stopFound: Boolean,
    val getStateFound: Boolean,
    val pingFound: Boolean = false,
    /** The current libXray API exposes all lifecycle calls through invoke(String). */
    val invokeFound: Boolean = false
) {
    val canStart: Boolean
        get() = classFound &&
            registerDialerControllerFound &&
            registerListenerControllerFound &&
            newRunRequestFound &&
            runFromJsonFound &&
            stopFound &&
            getStateFound &&
            (invokeFound || setTunFdFound)
}

data class XrayCallResult(
    val success: Boolean,
    val error: String?
) {
    companion object {
        fun fromJson(response: String): XrayCallResult {
            if (response.isBlank()) {
                return XrayCallResult(success = false, error = "libXray returned an empty response")
            }
            return try {
                val obj = JSONObject(response)
                XrayCallResult(
                    success = obj.optBoolean("success", false),
                    error = obj.optString("error").takeIf { it.isNotBlank() }
                )
            } catch (e: Exception) {
                XrayCallResult(success = false, error = "Failed to parse libXray response: ${e.message}")
            }
        }

        fun fromBase64(encoded: String): XrayCallResult {
            if (encoded.isBlank()) {
                return XrayCallResult(success = false, error = "libXray returned an empty response")
            }
            return try {
                val json = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
                fromJson(json)
            } catch (e: Exception) {
                XrayCallResult(success = false, error = "Failed to decode libXray response: ${e.message}")
            }
        }
    }
}

data class XrayPingResult(
    val success: Boolean,
    val delayMs: Long?,
    val error: String?
) {
    companion object {
        fun fromJson(response: String): XrayPingResult {
            if (response.isBlank()) {
                return XrayPingResult(success = false, delayMs = null, error = "libXray returned an empty ping response")
            }
            return try {
                val obj = JSONObject(response)
                val rawData = obj.opt("data")
                val delay = when (rawData) {
                    is Number -> rawData.toLong()
                    is JSONObject -> rawData.optLong("delay").takeIf { rawData.has("delay") }
                    else -> null
                }
                XrayPingResult(
                    success = obj.optBoolean("success", false),
                    delayMs = delay,
                    error = obj.optString("error").takeIf { it.isNotBlank() }
                )
            } catch (e: Exception) {
                XrayPingResult(success = false, delayMs = null, error = "Failed to parse libXray ping response: ${e.message}")
            }
        }

        fun fromBase64(encoded: String): XrayPingResult {
            if (encoded.isBlank()) {
                return XrayPingResult(success = false, delayMs = null, error = "libXray returned an empty ping response")
            }
            return try {
                val json = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
                fromJson(json)
            } catch (e: Exception) {
                XrayPingResult(success = false, delayMs = null, error = "Failed to decode libXray ping response: ${e.message}")
            }
        }
    }
}

data class XrayPingBatchConfig(
    val configPath: String,
    val outboundTag: String? = null
)

data class XrayPingBatchResult(
    val success: Boolean,
    val results: List<XrayPingResult>,
    val error: String?
) {
    companion object {
        fun fromJson(response: String): XrayPingBatchResult {
            if (response.isBlank()) {
                return XrayPingBatchResult(
                    success = false,
                    results = emptyList(),
                    error = "libXray returned an empty ping batch response"
                )
            }
            return try {
                val obj = JSONObject(response)
                val topLevelError = obj.optString("error").takeIf { it.isNotBlank() }
                val data = obj.optJSONObject("data")
                val rawResults = data?.optJSONArray("results")
                if (!obj.optBoolean("success", false) || rawResults == null) {
                    XrayPingBatchResult(
                        success = false,
                        results = emptyList(),
                        error = topLevelError ?: "libXray did not return ping batch results"
                    )
                } else {
                    val results = buildList(rawResults.length()) {
                        for (index in 0 until rawResults.length()) {
                            val item = rawResults.optJSONObject(index)
                            if (item == null) {
                                add(XrayPingResult(false, null, "Invalid ping batch result at index $index"))
                                continue
                            }
                            val delay = item.optLong("delay").takeIf { item.has("delay") }
                            add(
                                XrayPingResult(
                                    success = item.optBoolean("success", false),
                                    delayMs = delay,
                                    error = item.optString("error").takeIf { it.isNotBlank() }
                                )
                            )
                        }
                    }
                    XrayPingBatchResult(success = true, results = results, error = topLevelError)
                }
            } catch (e: Exception) {
                XrayPingBatchResult(
                    success = false,
                    results = emptyList(),
                    error = "Failed to parse libXray ping batch response: ${e.message}"
                )
            }
        }
    }
}

data class XrayDelaySummary(
    val success: Boolean,
    val medianMs: Long?,
    val p95Ms: Long?,
    val jitterMs: Long?,
    val successfulSamples: Int,
    val totalSamples: Int,
    val error: String?
)

interface XrayBridge {
    fun isAvailable(): Boolean
    fun capabilities(): XrayRuntimeCapabilities
    fun registerDialerController(protectFd: (Int) -> Boolean)
    fun registerListenerController(protectFd: (Int) -> Boolean)
    fun setTunFd(fd: Int)
    fun newRunFromJsonRequest(datDir: String, configJson: String): String
    fun runFromJson(base64Request: String): XrayCallResult
    fun ping(base64Request: String): XrayPingResult
    /** Optional modern API; callers must retain a sequential ping fallback. */
    fun pingBatch(
        configs: List<XrayPingBatchConfig>,
        timeoutSeconds: Int,
        testUrl: String
    ): XrayPingBatchResult = XrayPingBatchResult(
        success = false,
        results = emptyList(),
        error = "libXray pingBatch is not available in this build"
    )
    fun stop(): XrayCallResult
    fun getState(): Boolean
}

class ReflectionXrayBridge internal constructor(
    private val suppliedXrayClass: Class<*>? = null
) : XrayBridge {
    private companion object {
        // libXray keeps Xray's dialer and outbound manager in process-wide state.
        // Serialize structured calls so concurrent delay scans cannot corrupt a
        // running instance or replace its global state underneath it.
        val INVOKE_LOCK = Any()
    }

    private val xrayClass: Class<*>? by lazy {
        suppliedXrayClass ?: listOf("libXray.LibXray", "libxray.LibXray", "LibXray")
            .firstNotNullOfOrNull { className -> runCatching { Class.forName(className) }.getOrNull() }
    }

    override fun isAvailable(): Boolean = capabilities().canStart

    override fun capabilities(): XrayRuntimeCapabilities = XrayRuntimeCapabilities(
        classFound = xrayClass != null,
        registerDialerControllerFound = findMethodOrNull(
            setOf("RegisterDialerController", "registerDialerController")
        ) { it.parameterTypes.size == 1 } != null,
        registerListenerControllerFound = findMethodOrNull(
            setOf("RegisterListenerController", "registerListenerController")
        ) { it.parameterTypes.size == 1 } != null,
        setTunFdFound = findMethodOrNull(setOf("SetTunFd", "setTunFd")) {
            it.parameterTypes.size == 1 && it.parameterTypes[0].let { type ->
                type == java.lang.Integer.TYPE ||
                    type == java.lang.Integer::class.java ||
                    type == java.lang.Long.TYPE ||
                    type == java.lang.Long::class.java
            }
        } != null,
        newRunRequestFound = findMethodOrNull(
            setOf("NewXrayRunFromJSONRequest", "newXrayRunFromJSONRequest", "newXrayRunFromJsonRequest")
        ) {
            it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == String::class.java &&
                it.returnType == String::class.java
        } != null || findInvokeMethodOrNull() != null,
        runFromJsonFound = findMethodOrNull(setOf("RunXrayFromJSON", "runXrayFromJSON", "runXrayFromJson")) {
            it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == String::class.java &&
                it.returnType == String::class.java
        } != null || findInvokeMethodOrNull() != null,
        stopFound = findMethodOrNull(setOf("StopXray", "stopXray")) {
            it.parameterTypes.isEmpty() && it.returnType == String::class.java
        } != null || findInvokeMethodOrNull() != null,
        getStateFound = findMethodOrNull(setOf("GetXrayState", "getXrayState")) {
            it.parameterTypes.isEmpty() &&
                (it.returnType == java.lang.Boolean.TYPE || it.returnType == java.lang.Boolean::class.java)
        } != null || findInvokeMethodOrNull() != null,
        pingFound = findMethodOrNull(setOf("Ping", "ping")) {
            it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == String::class.java &&
                it.returnType == String::class.java
        } != null || findInvokeMethodOrNull() != null,
        invokeFound = findInvokeMethodOrNull() != null
    )

    override fun registerDialerController(protectFd: (Int) -> Boolean) {
        registerController(
            "RegisterDialerController",
            "registerDialerController",
            protectFd = protectFd
        )
    }

    override fun registerListenerController(protectFd: (Int) -> Boolean) {
        registerController(
            "RegisterListenerController",
            "registerListenerController",
            protectFd = protectFd
        )
    }

    override fun setTunFd(fd: Int) {
        // libXray v26.7+ removed SetTunFd. The caller places the descriptor in
        // root.env.xray.tun.fd before runXrayFromJson instead.
        if (findInvokeMethodOrNull() != null) return
        val method = findMethod(setOf("SetTunFd", "setTunFd")) {
            it.parameterTypes.size == 1 && it.parameterTypes[0].let { type ->
                type == java.lang.Integer.TYPE ||
                    type == java.lang.Integer::class.java ||
                    type == java.lang.Long.TYPE ||
                    type == java.lang.Long::class.java
            }
        }
        val parameter = method.parameterTypes.firstOrNull()
        if (parameter == java.lang.Long.TYPE || parameter == java.lang.Long::class.java) {
            method.invoke(null, fd.toLong())
        } else {
            method.invoke(null, fd)
        }
    }

    override fun newRunFromJsonRequest(datDir: String, configJson: String): String {
        if (findInvokeMethodOrNull() != null) {
            return JSONObject().apply {
                put("apiVersion", 1)
                put("method", "runXrayFromJson")
                put("payload", JSONObject().put("configJSON", configJson))
            }.toString()
        }
        val method = findMethod(
            setOf("NewXrayRunFromJSONRequest", "newXrayRunFromJSONRequest", "newXrayRunFromJsonRequest")
        ) {
            it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == String::class.java &&
                it.returnType == String::class.java
        }
        return method.invoke(null, datDir, configJson) as String
    }

    override fun runFromJson(base64Request: String): XrayCallResult {
        if (findInvokeMethodOrNull() != null) {
            return XrayCallResult.fromJson(callInvoke(base64Request))
        }
        val method = findMethod(setOf("RunXrayFromJSON", "runXrayFromJSON", "runXrayFromJson")) {
            it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == String::class.java &&
                it.returnType == String::class.java
        }
        return XrayCallResult.fromBase64(method.invoke(null, base64Request) as String)
    }

    override fun ping(base64Request: String): XrayPingResult {
        if (findInvokeMethodOrNull() != null) {
            val legacyRequest = decodeRequestObject(base64Request)
            val payload = JSONObject().apply {
                legacyRequest.optString("configPath")
                    .takeIf { it.isNotBlank() }
                    ?.let { put("configPath", it) }
                if (legacyRequest.has("timeout")) put("timeout", legacyRequest.optInt("timeout"))
                legacyRequest.optString("url")
                    .takeIf { it.isNotBlank() }
                    ?.let { put("url", it) }
                legacyRequest.optString("proxy")
                    .takeIf { it.isNotBlank() }
                    ?.let { put("proxy", it) }
            }
            val request = JSONObject().apply {
                put("apiVersion", 1)
                put("method", "ping")
                put("payload", payload)
            }.toString()
            return XrayPingResult.fromJson(callInvoke(request))
        }
        val method = findMethod(setOf("Ping", "ping")) {
            it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == String::class.java &&
                it.returnType == String::class.java
        }
        return XrayPingResult.fromBase64(method.invoke(null, base64Request) as String)
    }

    override fun pingBatch(
        configs: List<XrayPingBatchConfig>,
        timeoutSeconds: Int,
        testUrl: String
    ): XrayPingBatchResult {
        if (configs.isEmpty()) {
            return XrayPingBatchResult(false, emptyList(), "No configs were provided for ping batch")
        }
        if (configs.size > 5) {
            return XrayPingBatchResult(false, emptyList(), "Ping batch accepts at most 5 configs")
        }
        if (timeoutSeconds <= 0 || testUrl.isBlank()) {
            return XrayPingBatchResult(false, emptyList(), "Invalid ping batch timeout or URL")
        }
        if (findInvokeMethodOrNull() == null) {
            return XrayPingBatchResult(false, emptyList(), "libXray pingBatch requires the modern invoke API")
        }

        val request = JSONObject().apply {
            put("apiVersion", 1)
            put("method", "pingBatch")
            put("payload", JSONObject().apply {
                put("configs", JSONArray().apply {
                    configs.forEach { config ->
                        put(JSONObject().apply {
                            put("configPath", config.configPath)
                            config.outboundTag
                                ?.takeIf { it.isNotBlank() }
                                ?.let { put("outboundTag", it) }
                        })
                    }
                })
                put("timeout", timeoutSeconds)
                put("url", testUrl)
            })
        }.toString()
        return XrayPingBatchResult.fromJson(callInvoke(request))
    }

    override fun stop(): XrayCallResult {
        if (findInvokeMethodOrNull() != null) {
            val request = JSONObject().apply {
                put("apiVersion", 1)
                put("method", "stopXray")
            }.toString()
            return XrayCallResult.fromJson(callInvoke(request))
        }
        val method = findMethod(setOf("StopXray", "stopXray")) {
            it.parameterTypes.isEmpty() && it.returnType == String::class.java
        }
        return XrayCallResult.fromBase64(method.invoke(null) as String)
    }

    override fun getState(): Boolean {
        if (findInvokeMethodOrNull() != null) {
            val request = JSONObject().apply {
                put("apiVersion", 1)
                put("method", "getXrayState")
            }.toString()
            val response = JSONObject(callInvoke(request))
            if (!response.optBoolean("success", false)) {
                throw IllegalStateException(
                    response.optString("error").takeIf { it.isNotBlank() } ?: "libXray state query failed"
                )
            }
            return response.optJSONObject("data")?.optBoolean("running", false) ?: false
        }
        val method = findMethod(setOf("GetXrayState", "getXrayState")) {
            it.parameterTypes.isEmpty() &&
                (it.returnType == java.lang.Boolean.TYPE || it.returnType == java.lang.Boolean::class.java)
        }
        return method.invoke(null) as Boolean
    }

    private fun registerController(vararg methodNames: String, protectFd: (Int) -> Boolean) {
        val method = findMethod(methodNames.toSet()) { it.parameterTypes.size == 1 }
        val controllerType = method.parameterTypes.firstOrNull()
            ?: throw IllegalStateException("libXray controller registration has no controller parameter")
        val controller = Proxy.newProxyInstance(
            controllerType.classLoader,
            arrayOf(controllerType)
        ) { proxy, invokedMethod, args ->
            if (invokedMethod.name.equals("ProtectFd", ignoreCase = true)) {
                val fd = (args?.firstOrNull() as? Number)?.toInt() ?: return@newProxyInstance false
                protectFd(fd)
            } else {
                when (invokedMethod.name) {
                    "toString" -> "G2RayProtectFdController"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> defaultProxyReturnValue(invokedMethod.returnType)
                }
            }
        }
        method.invoke(null, controller)
    }

    private fun callInvoke(requestJson: String): String {
        val method = findInvokeMethodOrNull() ?: throw NoSuchMethodException(
            "Missing compatible libXray invoke(String) method"
        )
        return synchronized(INVOKE_LOCK) {
            method.invoke(null, requestJson) as? String
                ?: throw IllegalStateException("libXray invoke returned a non-string response")
        }
    }

    private fun decodeRequestObject(encodedOrJson: String): JSONObject {
        val value = encodedOrJson.trim()
        if (value.startsWith("{")) {
            return JSONObject(value)
        }
        return JSONObject(String(Base64.decode(encodedOrJson, Base64.DEFAULT), Charsets.UTF_8))
    }

    private fun defaultProxyReturnValue(returnType: Class<*>): Any? {
        return when (returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> 0.toChar()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            else -> null
        }
    }

    private fun findMethod(names: Set<String>, matches: (Method) -> Boolean): Method {
        val clazz = xrayClass ?: throw IllegalStateException(
            XrayCoreManager.MISSING_CORE_MESSAGE
        )
        return clazz.methods.firstOrNull { method ->
            method.name in names && matches(method)
        } ?: throw NoSuchMethodException("Missing compatible libXray method: ${names.joinToString(" or ")}")
    }

    private fun findMethodOrNull(names: Set<String>, matches: (Method) -> Boolean): Method? {
        val clazz = xrayClass ?: return null
        return clazz.methods.firstOrNull { method ->
            method.name in names && matches(method)
        }
    }

    private fun findInvokeMethodOrNull(): Method? = findMethodOrNull(setOf("invoke", "Invoke")) {
        it.parameterTypes.size == 1 &&
            it.parameterTypes[0] == String::class.java &&
            it.returnType == String::class.java
    }
}
