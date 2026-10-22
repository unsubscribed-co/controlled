package dev.unsubscribed.controlled

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TvException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { Unreachable, Rejected, PairingTimeout, CertificateChanged, Timeout, Failed, Closed }
}

/** What pairing gives back, to store: the client key and the certificate pin (null on the ws:// fallback). */
data class Pairing(val clientKey: String, val pin: String?)

class TvClient(private val ip: String, private val base: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val idCounter = AtomicLong(1)
    private val requests = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val subscriptions = ConcurrentHashMap<String, SendChannel<JsonObject>>()

    private val _closed = MutableStateFlow(false)
    val closed: StateFlow<Boolean> = _closed.asStateFlow()

    @Volatile
    private var socket: WebSocket? = null

    /** The client the main socket opened with, for the pointer socket; [secure] says whether that was wss. */
    @Volatile
    private var openedWith: OkHttpClient? = null
    @Volatile
    private var secure = true
    /** The pinned TLS client, kept even on the ws fallback: the TV serves app icons on https://<ip>:3001. */
    @Volatile
    private var iconClient: OkHttpClient? = null

    /**
     * Opens the socket and registers. [clientKey] and [pin] come from storage (null the first time).
     * [onPrompt] is called when the TV shows Accept. Returns the key and pin to store.
     * Throws TvException: Unreachable (no socket), CertificateChanged (pin mismatch), Rejected (error on register),
     * PairingTimeout (no answer to the prompt within 60 s), Timeout (no hello/register answer within 5 s).
     */
    suspend fun connect(clientKey: String?, pin: String?, onPrompt: () -> Unit = {}): Pairing {
        if (_closed.value) {
            throw TvException(TvException.Kind.Closed, "Client has already been closed")
        }
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000

        val trustManager = TvTrustManager(pin)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), null)
        }

        val secureClient = base.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(3, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()

        var activeSocket: WebSocket? = null
        var activePin: String? = null
        iconClient = secureClient

        try {
            activeSocket = openSocketBridge("wss://$ip:3001", secureClient)
            activePin = trustManager.leafPin ?: pin
            openedWith = secureClient
        } catch (t: Throwable) {
            if (trustManager.pinMismatch) {
                throw TvException(TvException.Kind.CertificateChanged, "Certificate pin mismatch", t)
            }
            // Only a refused 3001 means a TV without it; a timeout means it's asleep or gone, and trying 3000 too would
            // double every attempt of the power-on loop.
            if (isRefused(t)) {
                val plainClient = base.newBuilder()
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .pingInterval(15, TimeUnit.SECONDS)
                    .build()
                try {
                    activeSocket = openSocketBridge("ws://$ip:3000", plainClient)
                    activePin = null
                    openedWith = plainClient
                    secure = false
                } catch (fallbackError: Throwable) {
                    if (isUnreachable(fallbackError)) {
                        throw TvException(
                            TvException.Kind.Unreachable,
                            "TV unreachable on port 3001 and 3000",
                            fallbackError
                        )
                    }
                    throw TvException(
                        TvException.Kind.Failed,
                        "Connection to fallback ws://$ip:3000 failed",
                        fallbackError
                    )
                }
            } else if (isUnreachable(t)) {
                throw TvException(TvException.Kind.Unreachable, "TV unreachable on port 3001", t)
            } else {
                throw TvException(TvException.Kind.Failed, "TLS connection failed: ${t.message}", t)
            }
        }

        socket = activeSocket
        log { "connect: socket open ${ms()} ms (secure=$secure)" }

        // Step 1: Hello handshake
        val helloDeferred = CompletableDeferred<JsonObject>()
        requests["hello"] = helloDeferred
        if (!activeSocket.send(Ssap.hello())) {
            activeSocket.cancel()
            socket = null
            throw TvException(TvException.Kind.Closed, "Failed to send hello")
        }

        try {
            withTimeout(5000) {
                helloDeferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            requests.remove("hello")
            activeSocket.cancel()
            socket = null
            throw TvException(TvException.Kind.Timeout, "Hello timed out after 5 s", e)
        } catch (e: Throwable) {
            requests.remove("hello")
            activeSocket.cancel()
            socket = null
            throw if (e is TvException) e else TvException(TvException.Kind.Closed, "Hello failed", e)
        }

        log { "connect: hello ${ms()} ms" }
        // aiowebostv asks for system info before registering: newer webOS versions want it. Its answer doesn't matter.
        runCatching { request("system/getSystemInfo") }
        log { "connect: systemInfo ${ms()} ms" }

        // Step 2: Register exchange
        val registerChannel = Channel<JsonObject>(Channel.UNLIMITED)
        subscriptions["register_0"] = registerChannel

        try {
            if (!activeSocket.send(Ssap.register(clientKey))) {
                throw TvException(TvException.Kind.Closed, "Failed to send register")
            }

            val firstMsg = try {
                withTimeout(5000) {
                    registerChannel.receive()
                }
            } catch (e: TimeoutCancellationException) {
                throw TvException(TvException.Kind.Timeout, "Register timed out after 5 s", e)
            }

            val firstType = firstMsg["type"]?.jsonPrimitive?.contentOrNull
            if (firstType == "error") {
                val err = firstMsg["error"]?.jsonPrimitive?.contentOrNull ?: "Registration rejected"
                throw TvException(TvException.Kind.Rejected, err)
            }

            val resolvedKey: String
            if (firstType == "registered") {
                resolvedKey = firstMsg["payload"]?.jsonObject?.get("client-key")?.jsonPrimitive?.contentOrNull
                    ?: throw TvException(TvException.Kind.Failed, "Missing client-key in registered message")
            } else if (firstType == "response") {
                onPrompt()
                val secondMsg = try {
                    withTimeout(60000) {
                        registerChannel.receive()
                    }
                } catch (e: TimeoutCancellationException) {
                    throw TvException(TvException.Kind.PairingTimeout, "Pairing prompt timed out after 60 s", e)
                }

                val secondType = secondMsg["type"]?.jsonPrimitive?.contentOrNull
                if (secondType == "error") {
                    val err = secondMsg["error"]?.jsonPrimitive?.contentOrNull ?: "Registration rejected"
                    throw TvException(TvException.Kind.Rejected, err)
                }
                if (secondType != "registered") {
                    throw TvException(TvException.Kind.Failed, "Unexpected registration response type: $secondType")
                }

                resolvedKey = secondMsg["payload"]?.jsonObject?.get("client-key")?.jsonPrimitive?.contentOrNull
                    ?: throw TvException(TvException.Kind.Failed, "Missing client-key in registered message")
            } else {
                throw TvException(TvException.Kind.Failed, "Unexpected response type for register: $firstType")
            }

            log { "connect: registered ${ms()} ms" }
            return Pairing(clientKey = resolvedKey, pin = activePin)
        } catch (t: Throwable) {
            activeSocket.cancel()
            socket = null
            throw if (t is TvException) t else TvException(TvException.Kind.Closed, t.message ?: "Register failed", t)
        } finally {
            subscriptions.remove("register_0")
            registerChannel.close()
        }
    }

    /** Sends a request and waits up to 5 s for its answer's payload; TvException Timeout / Failed (type error or returnValue false) / Closed. */
    suspend fun request(uri: String, payload: JsonObject = JsonObject(emptyMap())): JsonObject {
        val activeSocket = socket ?: throw TvException(TvException.Kind.Closed, "Client is closed")
        if (_closed.value) throw TvException(TvException.Kind.Closed, "Client is closed")

        val id = idCounter.getAndIncrement().toString()
        val deferred = CompletableDeferred<JsonObject>()
        requests[id] = deferred

        val msg = Ssap.request(id, uri, payload, type = "request")
        if (!activeSocket.send(msg)) {
            requests.remove(id)
            throw TvException(TvException.Kind.Closed, "Failed to send request")
        }

        val response = try {
            withTimeout(5000) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            requests.remove(id)
            throw TvException(TvException.Kind.Timeout, "Request $uri timed out after 5 s", e)
        } catch (e: TvException) {
            throw e
        } catch (e: Throwable) {
            throw TvException(TvException.Kind.Closed, "Request failed", e)
        }

        val type = response["type"]?.jsonPrimitive?.contentOrNull
        if (type == "error") {
            val error = response["error"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
            throw TvException(TvException.Kind.Failed, "Request failed: $error")
        }

        val respPayload = response["payload"]?.jsonObject
            ?: throw TvException(TvException.Kind.Failed, "Missing payload in response")

        val returnValue = respPayload["returnValue"]?.jsonPrimitive?.booleanOrNull
        if (returnValue != true) {
            val errorText = respPayload["errorText"]?.jsonPrimitive?.contentOrNull
                ?: response["error"]?.jsonPrimitive?.contentOrNull
                ?: "returnValue was false"
            throw TvException(TvException.Kind.Failed, "Request failed: $errorText")
        }

        return respPayload
    }

    /** Sends a request without waiting (system/turnOff answers unreliably). */
    fun fire(uri: String, payload: JsonObject = JsonObject(emptyMap())) {
        val activeSocket = socket ?: return
        if (_closed.value) return
        val id = idCounter.getAndIncrement().toString()
        val msg = Ssap.request(id, uri, payload, type = "request")
        activeSocket.send(msg)
    }

    /** Cold Flow of every payload pushed for a subscription; unsubscribes when collection stops. */
    fun subscribe(uri: String, payload: JsonObject = JsonObject(emptyMap())): Flow<JsonObject> = flow {
        val activeSocket = socket ?: throw TvException(TvException.Kind.Closed, "Client is closed")
        if (_closed.value) throw TvException(TvException.Kind.Closed, "Client is closed")

        val id = idCounter.getAndIncrement().toString()
        val channel = Channel<JsonObject>(Channel.BUFFERED)
        subscriptions[id] = channel

        val subscribeMsg = Ssap.request(id, uri, payload, type = "subscribe")
        if (!activeSocket.send(subscribeMsg)) {
            subscriptions.remove(id)
            throw TvException(TvException.Kind.Closed, "Failed to send subscribe request")
        }

        try {
            for (msg in channel) {
                val p = msg["payload"]?.jsonObject ?: JsonObject(emptyMap())
                emit(p)
            }
        } finally {
            subscriptions.remove(id)
            channel.close()
            try {
                val unsubscribeMsg = Ssap.request(id, uri, JsonObject(emptyMap()), type = "unsubscribe")
                activeSocket.send(unsubscribeMsg)
            } catch (_: Exception) {
            }
        }
    }

    /** Opens the pointer socket (see above). */
    suspend fun pointer(): PointerSocket {
        val payload = request("com.webos.service.networkinput/getPointerInputSocket")
        val rawPath = payload["socketPath"]?.jsonPrimitive?.contentOrNull
            ?: throw TvException(TvException.Kind.Failed, "Missing socketPath in response")
        val secureUrl = if (secure) Ssap.securePointerUrl(rawPath) else rawPath
        val client = openedWith ?: throw TvException(TvException.Kind.Closed, "Client is not connected")

        val pointerWebSocket = try {
            suspendCancellableCoroutine<WebSocket> { cont ->
                val request = Request.Builder().url(secureUrl).build()
                val opened = AtomicBoolean(false)
                val ws = client.newWebSocket(
                    request,
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            if (opened.compareAndSet(false, true) && cont.isActive) {
                                cont.resume(webSocket)
                            }
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            if (opened.compareAndSet(false, true) && cont.isActive) {
                                cont.resumeWithException(t)
                            }
                        }
                    }
                )
                cont.invokeOnCancellation {
                    ws.cancel()
                }
            }
        } catch (t: Throwable) {
            throw TvException(TvException.Kind.Failed, "Failed to open pointer socket: ${t.message}", t)
        }

        return PointerSocket(pointerWebSocket)
    }

    /** An app icon: the TV serves them on the pinned port, under a token that's only valid for this connection. */
    suspend fun fetch(url: String): ByteArray? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val client = iconClient ?: return@withContext null
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body.bytes() else null }
        }.getOrNull()
    }

    fun close() {
        val activeSocket = socket
        socket = null
        activeSocket?.close(1000, "Normal closure")
        terminate(TvException(TvException.Kind.Closed, "Client closed"))
    }

    private suspend fun openSocketBridge(url: String, client: OkHttpClient): WebSocket =
        suspendCancellableCoroutine { cont ->
            val request = Request.Builder().url(url).build()
            val opened = AtomicBoolean(false)

            val ws = client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (opened.compareAndSet(false, true) && cont.isActive) {
                            cont.resume(webSocket)
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        handleMessage(text)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                        terminate(TvException(TvException.Kind.Closed, "TV closed the socket ($code: $reason)"))
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        terminate(TvException(TvException.Kind.Closed, "Socket closed ($code: $reason)"))
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (opened.compareAndSet(false, true)) {
                            if (cont.isActive) {
                                cont.resumeWithException(t)
                            }
                        } else {
                            terminate(TvException(TvException.Kind.Closed, "Socket failure", t))
                        }
                    }
                }
            )

            cont.invokeOnCancellation {
                ws.cancel()
            }
        }

    private fun handleMessage(text: String) {
        val root = try {
            json.decodeFromString<JsonObject>(text)
        } catch (_: Exception) {
            return
        }

        val id = root["id"]?.jsonPrimitive?.contentOrNull
        val type = root["type"]?.jsonPrimitive?.contentOrNull

        if (id == null) {
            if (type == "hello") {
                requests.remove("hello")?.complete(root)
            }
            return
        }

        val sub = subscriptions[id]
        if (sub != null) {
            sub.trySend(root)
            return
        }

        val deferred = requests.remove(id)
        if (deferred != null) {
            deferred.complete(root)
            return
        }

        if (id == "hello" || type == "hello") {
            requests.remove("hello")?.complete(root)
        }
    }

    private fun terminate(cause: TvException) {
        if (_closed.compareAndSet(expect = false, update = true)) {
            socket = null
            for ((_, deferred) in requests) {
                deferred.completeExceptionally(cause)
            }
            requests.clear()

            for ((_, channel) in subscriptions) {
                channel.close(cause)
            }
            subscriptions.clear()
        }
    }

    private fun isRefused(t: Throwable): Boolean =
        generateSequence(t) { it.cause }.any { it is java.net.ConnectException }

    private fun isUnreachable(t: Throwable): Boolean {
        var curr: Throwable? = t
        while (curr != null) {
            if (curr is java.net.ConnectException ||
                curr is java.net.NoRouteToHostException ||
                curr is java.net.SocketTimeoutException ||
                curr is java.net.UnknownHostException
            ) {
                return true
            }
            curr = curr.cause
        }
        return false
    }

    // Deliberately custom: the TV's certificate is self-signed, so the check is the pin (trust on first use), not a CA.
    @android.annotation.SuppressLint("CustomX509TrustManager")
    private class TvTrustManager(private var expectedPin: String?) : X509TrustManager {
        @Volatile
        var leafPin: String? = expectedPin
            private set

        @Volatile
        var pinMismatch: Boolean = false
            private set

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty()) {
                throw CertificateException("Empty certificate chain")
            }
            val leaf = chain[0]
            val pin = Ssap.pin(leaf)
            val expected = expectedPin
            if (expected == null) {
                expectedPin = pin
                leafPin = pin
            } else if (expected.equals(pin, ignoreCase = true)) {
                leafPin = pin
            } else {
                pinMismatch = true
                throw CertificateException("Certificate pin mismatch: expected $expected but got $pin")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}

class PointerSocket internal constructor(private val socket: WebSocket) {
    fun button(name: String): Boolean = socket.send("type:button\nname:$name\n\n")
    fun move(dx: Int, dy: Int): Boolean = socket.send("type:move\ndx:$dx\ndy:$dy\ndown:0\n\n")
    fun click(): Boolean = socket.send("type:click\n\n")
    fun scroll(dx: Int, dy: Int): Boolean = socket.send("type:scroll\ndx:$dx\ndy:$dy\n\n")
    fun close() {
        socket.close(1000, "Normal closure")
    }
}

/** Pure helpers, unit-tested on the JVM. */
object Ssap {
    /** aiowebostv's manifest (handshake.py, 0.10.0), unsigned: measured to pair on Juan's TV (controlled/plan). */
    val PERMISSIONS: List<String> = listOf(
        "APP_TO_APP", "CLOSE", "CONTROL_AUDIO", "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK", "CONTROL_INPUT_MEDIA_PLAYBACK",
        "CONTROL_INPUT_MEDIA_RECORDING", "CONTROL_INPUT_TEXT", "CONTROL_INPUT_TV", "CONTROL_MOUSE_AND_KEYBOARD", "CONTROL_POWER",
        "CONTROL_TV_SCREEN", "LAUNCH", "LAUNCH_WEBAPP", "READ_APP_STATUS", "READ_COUNTRY_INFO", "READ_CURRENT_CHANNEL",
        "READ_INPUT_DEVICE_LIST", "READ_INSTALLED_APPS", "READ_LGE_SDX", "READ_LGE_TV_INPUT_EVENTS", "READ_NETWORK_STATE",
        "READ_NOTIFICATIONS", "READ_POWER_STATE", "READ_RUNNING_APPS", "READ_SETTINGS", "READ_TV_CHANNEL_LIST",
        "READ_TV_CURRENT_TIME", "READ_UPDATE_INFO", "SEARCH", "TEST_OPEN", "TEST_PROTECTED", "TEST_SECURE",
        "UPDATE_FROM_REMOTE_APP", "WRITE_NOTIFICATION_ALERT", "WRITE_NOTIFICATION_TOAST", "WRITE_SETTINGS",
    )

    fun hello(): String = buildJsonObject {
        put("id", "hello")
        put("type", "hello")
        put("payload", buildJsonObject {})
    }.toString()

    fun register(clientKey: String?): String = buildJsonObject {
        put("id", "register_0")
        put("type", "register")
        put("payload", buildJsonObject {
            put("forcePairing", false)
            put("pairingType", "PROMPT")
            if (clientKey != null) {
                put("client-key", clientKey)
            }
            put("manifest", buildJsonObject {
                put("manifestVersion", 1)
                put("appVersion", "1.1")
                put("permissions", buildJsonArray {
                    for (perm in PERMISSIONS) {
                        add(perm)
                    }
                })
            })
        })
    }.toString()

    /** uri without the ssap:// prefix; adds it. */
    fun request(id: String, uri: String, payload: JsonObject, type: String = "request"): String = buildJsonObject {
        put("id", id)
        put("type", type)
        put("uri", if (uri.startsWith("ssap://")) uri else "ssap://$uri")
        put("payload", payload)
    }.toString()

    /** "ws://1.2.3.4:3000/resources/x/netinput.pointer.sock" -> "wss://1.2.3.4:3001/resources/x/netinput.pointer.sock"; other inputs unchanged. */
    fun securePointerUrl(socketPath: String): String {
        val match = Regex("""^ws://(\[[^]]+]|[^/:]+):3000(.*)$""").matchEntire(socketPath)
            ?: return socketPath
        return "wss://${match.groupValues[1]}:3001${match.groupValues[2]}"
    }

    fun pin(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(cert.publicKey.encoded)
        val hexChars = "0123456789abcdef"
        val sb = StringBuilder(hash.size * 2)
        for (b in hash) {
            val i = b.toInt() and 0xFF
            sb.append(hexChars[i ushr 4])
            sb.append(hexChars[i and 0x0F])
        }
        return sb.toString()
    }
}
