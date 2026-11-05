package dev.unsubscribed.controlled

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.ConcurrentLinkedQueue
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * Android TV and Google TV: the Android TV Remote protocol v2, the one Google's own phone remote speaks (androidtvremote2,
 * Apache-2.0, is the reference). Pairing on 6467 with a code the TV shows, then a remote session on 6466; both are TLS
 * with this phone's own certificate, and protobuf framed by a varint length. Measured on an onn 4K stick (Android TV 14,
 * 2026-11-05): pairing, keys, power state, the open app and app links work; text goes in through the IME message with
 * counters 0/0 (the TV's own counters are refused), at the cursor; letters sent as keys are ignored, Delete and Enter work.
 */
object Gtv {
    const val PAIRING_PORT = 6467
    const val REMOTE_PORT = 6466
    const val SERVICE_TYPE = "_androidtvremote2._tcp."

    /** Ping, keys, IME, power, volume, app links (androidtvremote2's Feature flags): what this app asks for. */
    const val FEATURES = 1 or 2 or 4 or 32 or 64 or 512

    // Android KeyEvent codes, as the TV takes them.
    const val HOME = 3; const val BACK = 4; const val UP = 19; const val DOWN = 20; const val LEFT = 21; const val RIGHT = 22
    const val CENTER = 23; const val VOLUME_UP = 24; const val VOLUME_DOWN = 25; const val POWER = 26; const val ENTER = 66
    const val DEL = 67; const val MENU = 82; const val PLAY = 126; const val PAUSE = 127; const val MUTE = 164
    const val CHANNEL_UP = 166; const val CHANNEL_DOWN = 167

    /** The remote's button names (as the LG pointer socket takes them) to key codes. */
    val BUTTONS = mapOf("UP" to UP, "DOWN" to DOWN, "LEFT" to LEFT, "RIGHT" to RIGHT, "ENTER" to CENTER, "BACK" to BACK, "HOME" to HOME, "MENU" to MENU)

    /**
     * The dock: this protocol can't list a TV's apps, so these are the ones on the onn remote's buttons plus a few more.
     * [id] is the package (what the TV reports as the open app). The tile is the brand's colour with a short [mark] (no
     * logos: they're the brands' trademarks, not ours to ship); the protocol gives no icons. [link] opens it; on a box without the app a web link lands in some browser (measured: Downloader),
     * so until the app has been seen open there Controlled opens its Play Store page instead, as the remote's own button
     * does (measured: Disney+ on the onn stick), which offers Install or Open.
     * shortcut: YouTube and Netflix links measured; the others are their sites' links, unchecked until seen on a box.
     */
    data class App(val id: String, val title: String, val mark: String, val link: String, val color: Long, val onColor: Long)

    val APPS = listOf(
        App("com.google.android.youtube.tv", "YouTube", "Y", "https://www.youtube.com", 0xFFFF0000, 0xFFFFFFFF),
        App("com.netflix.ninja", "Netflix", "N", "https://www.netflix.com/title", 0xFF000000, 0xFFE50914),
        App("com.disney.disneyplus", "Disney+", "D+", "https://www.disneyplus.com", 0xFF0E1B4D, 0xFFFFFFFF),
        App("com.cbs.ott", "Paramount+", "P+", "https://www.paramountplus.com", 0xFF0064FF, 0xFFFFFFFF),
        App("com.amazon.amazonvideo.livingroom", "Prime Video", "PV", "https://app.primevideo.com", 0xFF00A8E1, 0xFF0F171E),
        App("com.wbd.stream", "Max", "M", "https://play.max.com", 0xFF002BE7, 0xFFFFFFFF),
        App("com.spotify.tv.android", "Spotify", "S", "spotify://", 0xFF1DB954, 0xFF000000),
    )

    fun app(id: String): App? = APPS.firstOrNull { it.id == id }

    /** What a tile opens: the app's own link once it's been seen on this box, else its Play Store page. */
    fun launchLink(id: String, seen: Collection<String>): String? =
        app(id)?.let { if (id in seen) it.link else "market://details?id=$id" }

    // Protobuf, the few bits these messages use: varints and length-delimited fields.

    fun varint(value: Long): ByteArray {
        var v = value
        val out = ArrayList<Byte>()
        do {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            out += (if (v != 0L) b or 0x80 else b).toByte()
        } while (v != 0L)
        return out.toByteArray()
    }

    fun int(field: Int, value: Int) = varint((field shl 3).toLong()) + varint(value.toLong() and 0xFFFFFFFFL)
    fun bytes(field: Int, value: ByteArray) = varint((field shl 3 or 2).toLong()) + varint(value.size.toLong()) + value
    fun str(field: Int, value: String) = bytes(field, value.toByteArray())
    fun msg(field: Int, vararg parts: ByteArray) = bytes(field, parts.fold(ByteArray(0)) { a, b -> a + b })

    /** One message on the wire: its length as a varint, then the message. */
    fun frame(message: ByteArray) = varint(message.size.toLong()) + message

    /** A message's fields in order: (number, value), value a Long for varints and a ByteArray for the rest. */
    fun parse(buf: ByteArray): List<Pair<Int, Any>> {
        val out = ArrayList<Pair<Int, Any>>()
        var p = 0
        fun readVarint(): Long {
            var n = 0L
            var shift = 0
            while (true) {
                val b = buf[p++].toInt() and 0xFF
                n = n or ((b and 0x7F).toLong() shl shift)
                shift += 7
                if (b and 0x80 == 0) return n
            }
        }
        while (p < buf.size) {
            val key = readVarint()
            val number = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> out += number to readVarint()
                2 -> { val n = readVarint().toInt(); out += number to buf.copyOfRange(p, p + n); p += n }
                5 -> { out += number to buf.copyOfRange(p, p + 4); p += 4 }
                1 -> { out += number to buf.copyOfRange(p, p + 8); p += 8 }
                else -> error("wire type ${key and 7}")
            }
        }
        return out
    }

    /**
     * The TV turned this phone's certificate down in the handshake (TLS alert certificate_unknown or bad_certificate):
     * it no longer has it paired. Measured: a pairing the stick dropped answered certificate_unknown (2026-11-05).
     */
    fun forgotUs(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { Regex("CERTIFICATE_UNKNOWN|BAD_CERTIFICATE", RegexOption.IGNORE_CASE).containsMatchIn(it.message.orEmpty()) }

    fun field(buf: ByteArray, number: Int): Any? = parse(buf).firstOrNull { it.first == number }?.second

    // Pairing (polo): an outer message with protocol version 2 and status OK around each step.

    private fun outer(vararg body: ByteArray) = int(1, 2) + int(2, 200) + body.fold(ByteArray(0)) { a, b -> a + b }
    private val HEX_6 = int(1, 3) + int(2, 6)

    fun pairingRequest(client: String) = outer(msg(10, str(1, "atvremote"), str(2, client)))
    fun pairingOptions() = outer(msg(20, bytes(1, HEX_6), int(3, 1)))
    fun pairingConfiguration() = outer(msg(30, bytes(1, HEX_6), int(2, 1)))
    fun pairingSecret(secret: ByteArray) = outer(msg(40, bytes(1, secret)))

    /** The pairing reply's status: 200 OK; anything else is the TV saying no (400 error, 402 bad secret). */
    fun status(reply: ByteArray) = (field(reply, 2) as? Long)?.toInt() ?: 0

    /**
     * The secret for the 6 hex digits the TV shows: SHA-256 over both certificates' RSA modulus and exponent (client,
     * then TV) and the code's last 4 digits. Null when the code's first 2 digits aren't the hash's first byte: a typo.
     */
    fun secret(client: RSAPublicKey, tv: RSAPublicKey, code: String): ByteArray? {
        val clean = code.trim().uppercase()
        if (clean.length != 6 || clean.any { it !in "0123456789ABCDEF" }) return null
        val sha = MessageDigest.getInstance("SHA-256")
        for (n in listOf(client.modulus, client.publicExponent, tv.modulus, tv.publicExponent)) sha.update(unsigned(n))
        sha.update(clean.substring(2).chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        val hash = sha.digest()
        return hash.takeIf { (it[0].toInt() and 0xFF) == clean.substring(0, 2).toInt(16) }
    }

    /** A positive number's big-endian bytes without the sign byte BigInteger adds. */
    fun unsigned(n: BigInteger): ByteArray = n.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }

    // The remote session's messages (androidtvremote2's remotemessage.proto field numbers).

    fun configure(features: Int) = msg(1, int(1, features), msg(2, int(3, 1), str(4, "1"), str(5, "atvremote"), str(6, BuildConfig.VERSION_NAME)))
    fun setActive(features: Int) = msg(2, int(1, features))
    fun pong(value: Int) = msg(9, int(1, value))
    fun key(code: Int) = msg(10, int(1, code), int(2, 3)) // 3: a short press
    fun text(value: String) = msg(21, int(1, 0), int(2, 0), msg(3, int(1, 1), msg(2, int(1, value.length - 1), int(2, value.length - 1), str(3, value))))
    fun appLink(link: String) = msg(90, str(1, link))
}

/** This phone's identity for Google TVs: one RSA key and self-signed certificate, made once in the Android keystore. */
object GtvIdentity {
    // v3: v1 lacked raw RSA (TLS needs it for PSS); v2 named every install "Controlled", and the onn stick dropped a
    // pairing whenever another "Controlled" paired. shortcut: that the name is the cause is inferred from two drops, not
    // proven; pair two phones with one TV to confirm. A key's rules and name can't change, hence a new alias.
    private const val ALIAS = "controlled-gtv-3"

    /** What the TV lists this phone as, e.g. "Controlled (POCO X7 Pro)". */
    val name = "Controlled (${Build.MODEL})"

    fun certificate(): X509Certificate {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(2048)
                        // Unique per install, so two phones with Controlled are two remotes to the TV.
                        .setCertificateSubject(X500Principal("CN=Controlled ${java.util.UUID.randomUUID().toString().take(8)}"))
                        .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                        // Conscrypt pads PSS itself, then asks the keystore for the raw RSA operation.
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE, KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                        .setRandomizedEncryptionRequired(false)
                        .build(),
                )
                generateKeyPair()
            }
        }
        return ks.getCertificate(ALIAS) as X509Certificate
    }

    private fun privateKey(): PrivateKey = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(ALIAS, null) as PrivateKey

    /** A TLS socket to [ip]:[port] presenting this phone's certificate; [trust] checks the TV's. */
    fun connect(ip: String, port: Int, trust: X509TrustManager): SSLSocket {
        val cert = certificate()
        val key = privateKey()
        val keys = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null
            override fun getCertificateChain(alias: String?) = arrayOf(cert)
            override fun getPrivateKey(alias: String?) = key
        }
        val context = SSLContext.getInstance("TLS").apply { init(arrayOf(keys), arrayOf(trust), null) }
        val socket = context.socketFactory.createSocket() as SSLSocket
        try {
            socket.connect(InetSocketAddress(ip, port), 3_000)
            socket.soTimeout = 0
            socket.startHandshake()
        } catch (e: Exception) {
            socket.close()
            throw e
        }
        return socket
    }
}

/** The TV's certificate: whatever it is the first time (trust on first use, as with LG), the same one after. */
@SuppressLint("CustomX509TrustManager")
private class GtvTrust(private val expected: String?) : X509TrustManager {
    @Volatile var seen: X509Certificate? = null
        private set
    @Volatile var mismatch = false
        private set

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
        if (expected != null && !expected.equals(Ssap.pin(leaf), ignoreCase = true)) {
            mismatch = true
            throw CertificateException("certificate changed")
        }
        seen = leaf
    }
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private fun InputStream.readFrame(): ByteArray {
    var n = 0
    var shift = 0
    while (true) {
        val b = read()
        if (b < 0) throw java.io.EOFException()
        n = n or ((b and 0x7F) shl shift)
        shift += 7
        if (b and 0x80 == 0) break
    }
    val buf = ByteArray(n)
    var got = 0
    while (got < n) {
        val r = read(buf, got, n - got)
        if (r < 0) throw java.io.EOFException()
        got += r
    }
    return buf
}

private fun OutputStream.writeFrame(message: ByteArray) {
    write(Gtv.frame(message))
    flush()
}

/** Pairing in two steps: [start] makes the TV show a code, [finish] sends the secret for it and returns the TV's pin. */
class GtvPairing private constructor(private val socket: SSLSocket, private val tvCert: X509Certificate) {
    companion object {
        /** Opens pairing with the TV at [ip]: when this returns, the code is on its screen. */
        suspend fun start(ip: String): GtvPairing = withContext(Dispatchers.IO) {
            val trust = GtvTrust(null)
            val socket = try {
                GtvIdentity.connect(ip, Gtv.PAIRING_PORT, trust)
            } catch (e: Exception) {
                throw TvException(TvException.Kind.Unreachable, "pairing: $e", e)
            }
            try {
                val out = socket.outputStream
                val input = socket.inputStream
                for (step in listOf(Gtv.pairingRequest(GtvIdentity.name), Gtv.pairingOptions(), Gtv.pairingConfiguration())) {
                    out.writeFrame(step)
                    if (Gtv.status(input.readFrame()) != 200) throw TvException(TvException.Kind.Rejected, "pairing refused")
                }
                GtvPairing(socket, trust.seen ?: throw TvException(TvException.Kind.Failed, "no certificate"))
            } catch (e: TvException) {
                socket.close(); throw e
            } catch (e: Exception) {
                socket.close(); throw TvException(TvException.Kind.Failed, "pairing: $e", e)
            }
        }
    }

    /** False when [code] can't be the one on screen (checked before sending, so a typo can be fixed); else the TV's pin. */
    suspend fun finish(code: String): String? = withContext(Dispatchers.IO) {
        val secret = Gtv.secret(GtvIdentity.certificate().publicKey as RSAPublicKey, tvCert.publicKey as RSAPublicKey, code) ?: return@withContext null
        try {
            socket.outputStream.writeFrame(Gtv.pairingSecret(secret))
            if (Gtv.status(socket.inputStream.readFrame()) != 200) throw TvException(TvException.Kind.Rejected, "secret refused")
            Ssap.pin(tvCert)
        } catch (e: TvException) {
            throw e
        } catch (e: Exception) {
            throw TvException(TvException.Kind.Failed, "pairing: $e", e)
        } finally {
            socket.close()
        }
    }

    fun cancel() {
        runCatching { socket.close() }
    }
}

/**
 * The remote session with a paired Google TV. [open] connects and answers the TV's handshake; then keys, text and links
 * go out in order through one writer, and the TV's state arrives in [on], [app] and [volume]. [closed] turns true once.
 */
class GtvClient(private val ip: String, private val pin: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outbox = Channel<ByteArray>(Channel.UNLIMITED)
    private var socket: SSLSocket? = null

    private val _closed = MutableStateFlow(false)
    val closed: StateFlow<Boolean> = _closed.asStateFlow()
    /** The TV's screen is on (remote_start); null until it says. */
    val on = MutableStateFlow<Boolean?>(null)
    /** The package of the app in front. */
    val app = MutableStateFlow<String?>(null)
    /** Level and muted, when the TV reports them (a stick driving its TV over HDMI may not). */
    val volume = MutableStateFlow<Pair<Int, Boolean>?>(null)

    suspend fun open() = withContext(Dispatchers.IO) {
        val trust = GtvTrust(pin)
        val s = try {
            GtvIdentity.connect(ip, Gtv.REMOTE_PORT, trust)
        } catch (e: Exception) {
            val kind = when {
                trust.mismatch -> TvException.Kind.CertificateChanged
                Gtv.forgotUs(e) -> TvException.Kind.Rejected
                else -> TvException.Kind.Unreachable
            }
            throw TvException(kind, "remote: $e", e)
        }
        // TLS 1.3: a TV that refuses this phone's certificate says so after the handshake "succeeds", on the first read.
        // So connected means the TV's first message arrived; otherwise a refusal looked like a drop and was retried at once.
        val first = try {
            s.soTimeout = 5_000
            s.inputStream.readFrame().also { s.soTimeout = 0 }
        } catch (e: Exception) {
            s.close()
            throw TvException(if (Gtv.forgotUs(e)) TvException.Kind.Rejected else TvException.Kind.Unreachable, "remote: $e", e)
        }
        socket = s
        scope.launch { runCatching { for (m in outbox) s.outputStream.writeFrame(m) }; close() }
        scope.launch { runCatching { handle(first); while (true) handle(s.inputStream.readFrame()) }; close() }
    }

    private fun handle(message: ByteArray) {
        for ((number, value) in Gtv.parse(message)) {
            val body = value as? ByteArray ?: continue
            when (number) {
                1 -> send(Gtv.configure(Gtv.FEATURES and ((Gtv.field(body, 1) as? Long)?.toInt() ?: Gtv.FEATURES)))
                2 -> send(Gtv.setActive(Gtv.FEATURES))
                8 -> send(Gtv.pong(((Gtv.field(body, 1) as? Long) ?: 0L).toInt()))
                20 -> (Gtv.field(body, 1) as? ByteArray)?.let { info -> (Gtv.field(info, 12) as? ByteArray)?.let { app.value = String(it) } }
                40 -> on.value = (Gtv.field(body, 1) as? Long ?: 0L) != 0L
                50 -> (Gtv.field(body, 7) as? Long)?.let { level -> volume.value = level.toInt() to ((Gtv.field(body, 8) as? Long ?: 0L) != 0L) }
                3 -> log { "gtv error: ${Gtv.parse(body)}" }
            }
        }
    }

    private fun send(message: ByteArray) {
        outbox.trySend(message)
    }

    fun key(code: Int) = send(Gtv.key(code))
    fun text(value: String) { if (value.isNotEmpty()) send(Gtv.text(value)) }
    fun launch(link: String) = send(Gtv.appLink(link))

    fun close() {
        if (_closed.value) return
        _closed.value = true
        runCatching { socket?.close() }
        outbox.close()
        scope.cancel()
    }
}

/** Google TVs on this Wi-Fi, as they announce themselves (mDNS); each emitted once its address is resolved. */
@Suppress("DEPRECATION") // resolveService: its replacement is API 34+, and resolving one at a time is all it needs.
fun gtvDiscover(context: Context): Flow<FoundTv> = callbackFlow {
    val nsd = context.getSystemService(NsdManager::class.java)
    val pending = ConcurrentLinkedQueue<NsdServiceInfo>()
    var resolving = false
    lateinit var next: () -> Unit
    val resolve = object : NsdManager.ResolveListener {
        override fun onResolveFailed(info: NsdServiceInfo, error: Int) { resolving = false; next() }
        override fun onServiceResolved(info: NsdServiceInfo) {
            val ip = (if (Build.VERSION.SDK_INT >= 34) info.hostAddresses.firstOrNull { it is java.net.Inet4Address } else info.host)?.hostAddress
            if (ip != null) trySend(FoundTv(ip, info.serviceName, null, null, Brand.GoogleTv))
            resolving = false
            next()
        }
    }
    next = { synchronized(pending) { if (!resolving) pending.poll()?.let { resolving = true; nsd.resolveService(it, resolve) } } }
    val discovery = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) { pending += info; next() }
        override fun onServiceLost(info: NsdServiceInfo) = Unit
        override fun onDiscoveryStarted(type: String) = Unit
        override fun onDiscoveryStopped(type: String) = Unit
        override fun onStartDiscoveryFailed(type: String, error: Int) { close() }
        override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
    }
    nsd.discoverServices(Gtv.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
    awaitClose { runCatching { nsd.stopServiceDiscovery(discovery) } }
}
