package com.danizek.dp

import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMEncryptedKeyPair
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder
import java.io.Closeable
import java.io.EOFException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileReader
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory

/**
 * A [VpnService] that tunnels the device's IP traffic to a remote endpoint over a
 * mutually-authenticated TLS connection.
 *
 * Two threads move packets in opposite directions:
 *  - the relay thread reads from the TUN interface and writes to the remote socket;
 *  - the remote reader thread reads from the remote socket and writes back to the TUN interface.
 */
class RemoteVpnService : VpnService(), IVpn {

    inner class LocalBinder : Binder(), com.danizek.dp.IBinder {
        override fun getService(): IVpn = this@RemoteVpnService
    }

    companion object {
        const val START_VPN = "NetguardVpnServiceStart"
        const val STOP_VPN = "NetguardVpnServiceStop"
        const val TAG = "NetguardVpnService"

        /** Maximum size of a single IP packet we relay. */
        private const val MAX_PACKET_SIZE = 65535

        /** Bytes peeked to determine the IP version and total packet length. */
        private const val IP_HEADER_PEEK = 20
        private const val IPV6_HEADER_SIZE = 40

        private const val CONNECT_TIMEOUT_MS = 2000
        private const val JOIN_TIMEOUT_MS = 3000L

        private val EMPTY_PASSWORD = CharArray(0)
    }

    private val binder = LocalBinder()

    /** Guards against concurrent start/stop; also acts as the "is running" flag. */
    private val running = AtomicBoolean(false)

    /** Signals the threads to stop. */
    private val stopFlag = AtomicBoolean(false)

    private var vpnDescriptor: ParcelFileDescriptor? = null
    private var streamFromDevice: FileInputStream? = null
    private var streamToDevice: FileOutputStream? = null
    private var appSocket: SSLSocket? = null

    private var relayThread: Thread? = null
    private var remoteReaderThread: Thread? = null

    @Volatile
    private var status: VpnActiveStatus = VpnActiveStatus.INACTIVE

    // connection parameters, populated from the start intent
    private var remoteHost: String = ""
    private var remotePort: Int = 0
    private var allowedApps: List<String> = emptyList()
    private var caCertPath = "ca.cer"
    private var clientCertPath = "clientCert.cer"
    private var clientKeyFile = "key.key"

    override fun getBinder(): com.danizek.dp.IBinder = binder

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            START_VPN -> {
                remoteHost = intent.getStringExtra("remoteHost").orEmpty()
                remotePort = intent.getLongExtra("remotePort", 0).toInt()
                allowedApps = intent.getStringArrayListExtra("allowedApps") ?: emptyList()
                caCertPath = intent.getStringExtra("caCertPath").orEmpty()
                clientCertPath = intent.getStringExtra("clientCertPath").orEmpty()
                clientKeyFile = intent.getStringExtra("clientKeyPath").orEmpty()

                Log.v(TAG, "Starting VPN to $remoteHost:$remotePort")
                startVpn()
            }

            STOP_VPN -> stopVpn()
        }
        return START_STICKY
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun getVpnStatus(): VpnStatus = VpnStatus(status)

    fun getStatus(): VpnActiveStatus = status

    override fun startVpn() {
        if (!running.compareAndSet(false, true)) {
            Log.v(TAG, "startVpn ignored, already running")
            return
        }

        try {
            stopFlag.set(false)

            val descriptor = establishInterface()
            vpnDescriptor = descriptor
            streamFromDevice = FileInputStream(descriptor.fileDescriptor)
            streamToDevice = FileOutputStream(descriptor.fileDescriptor)

            val sslContext = buildSslContext()

            relayThread = Thread({ runTunnel(sslContext) }, "vpn-relay").also { it.start() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            status = VpnActiveStatus.ERROR
            teardown()
            running.set(false)
        }
    }

    override fun stopVpn() {
        if (!running.compareAndSet(true, false)) {
            return
        }
        teardown()
        status = VpnActiveStatus.INACTIVE
        stopSelf()
    }

    /**
     * Signals the threads to stop, unblocks any threads parked in a blocking read by closing their
     * streams and socket, then joins them and releases the TUN interface.
     */
    private fun teardown() {
        stopFlag.set(true)

        closeQuietly(streamFromDevice)
        closeQuietly(streamToDevice)
        closeQuietly(appSocket)

        // The relay thread joins its own remote-reader thread in its finally block, so joining the
        // relay thread here transitively waits for both threads to finish.
        joinQuietly(relayThread)
        closeQuietly(vpnDescriptor)

        relayThread = null
        remoteReaderThread = null
        appSocket = null
        streamFromDevice = null
        streamToDevice = null
        vpnDescriptor = null
    }

    private fun establishInterface(): ParcelFileDescriptor {
        val builder = Builder().apply {
            addAddress("192.168.255.2", 32)
            addAddress("fd00::2", 32)
            addRoute("0.0.0.0", 0)
            addRoute("::", 0)
            addDnsServer("1.1.1.1")
            addDnsServer("2606:4700:4700::1111")
            allowFamily(OsConstants.AF_INET)
            allowFamily(OsConstants.AF_INET6)
            setBlocking(true)
        }
        for (app in allowedApps) {
            try {
                builder.addAllowedApplication(app)
            } catch (e: Exception) {
                Log.w(TAG, "Skipping unknown allowed application: $app", e)
            }
        }
        return builder.establish() ?: throw IOException("VpnService.Builder.establish() returned null")
    }

    /**
     * Connects to the remote endpoint and runs the two packet threads until stopped or an error
     * occurs. Runs on the relay thread and owns the lifecycle of its remote-reader thread.
     */
    private fun runTunnel(sslContext: SSLContext) {
        status = VpnActiveStatus.CONNECTING

        val socket = try {
            connectRemote(sslContext)
        } catch (e: Exception) {
            if (!stopFlag.get()) {
                Log.w(TAG, "Failed to establish tunnel", e)
                status = VpnActiveStatus.ERROR
            }
            return
        }
        appSocket = socket
        status = VpnActiveStatus.ACTIVE

        val tunInput = streamFromDevice ?: return
        val tunOutput = streamToDevice ?: return

        val reader = Thread({
            try {
                forwardRemoteToDevice(socket.inputStream, tunOutput)
            } catch (e: Exception) {
                if (!stopFlag.get()) Log.w(TAG, "Remote reader stopped", e)
            }
        }, "vpn-remote-reader")
        remoteReaderThread = reader
        reader.start()

        try {
            forwardDeviceToRemote(tunInput, socket.outputStream)
        } catch (e: Exception) {
            if (!stopFlag.get()) {
                Log.w(TAG, "Device reader stopped", e)
                status = VpnActiveStatus.ERROR
            }
        } finally {
            // Unblock and join the remote reader before this thread exits
            closeQuietly(socket)
            joinQuietly(reader)
        }
    }

    private fun connectRemote(sslContext: SSLContext): SSLSocket {
        val tcpSocket = Socket()
        try {
            tcpSocket.bind(InetSocketAddress(0))
            if (!protect(tcpSocket)) {
                throw IOException("Failed to protect socket from VPN routing")
            }
            tcpSocket.connect(InetSocketAddress(remoteHost, remotePort), CONNECT_TIMEOUT_MS)

            val sslSocket = sslContext.socketFactory
                .createSocket(tcpSocket, remoteHost, remotePort, true) as SSLSocket
            sslSocket.startHandshake()
            return sslSocket
        } catch (e: Exception) {
            closeQuietly(tcpSocket)
            throw e
        }
    }

    /** Reads packets from the TUN interface and forwards them to the remote socket. */
    private fun forwardDeviceToRemote(from: FileInputStream, to: OutputStream) {
        val buffer = ByteArray(MAX_PACKET_SIZE)
        while (!stopFlag.get()) {
            val read = from.read(buffer)
            if (read == -1) throw EOFException("TUN interface closed")
            to.write(buffer, 0, read)
            to.flush()
        }
    }

    /** Reads length-framed IP packets from the remote socket and writes them to the TUN interface. */
    private fun forwardRemoteToDevice(from: InputStream, to: FileOutputStream) {
        val buffer = ByteArray(MAX_PACKET_SIZE)
        while (!stopFlag.get()) {
            readExactly(from, buffer, 0, IP_HEADER_PEEK)
            val totalLength = packetLength(buffer)
            readExactly(from, buffer, IP_HEADER_PEEK, totalLength - IP_HEADER_PEEK)
            to.write(buffer, 0, totalLength)
            to.flush()
        }
    }

    /** Derives the total packet length from a peeked IP header. */
    private fun packetLength(header: ByteArray): Int {
        val version = (header[0].toInt() shr 4) and 0x0F
        return if (version == 4) {
            ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        } else {
            val payloadLength = ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)
            payloadLength + IPV6_HEADER_SIZE
        }
    }

    private fun readExactly(stream: InputStream, buffer: ByteArray, offset: Int, length: Int) {
        var read = 0
        while (read < length) {
            val count = stream.read(buffer, offset + read, length - read)
            if (count == -1) throw EOFException("Remote stream closed")
            read += count
        }
    }

    private fun buildSslContext(): SSLContext {
        Security.addProvider(BouncyCastleProvider())
        val keyManagers = buildKeyManagers(clientCertPath, clientKeyFile)
        val trustManagers = buildTrustManagers(caCertPath)
        return SSLContext.getInstance("TLS").apply {
            init(keyManagers, trustManagers, null)
        }
    }

    private fun buildTrustManagers(caCertPath: String): Array<TrustManager> {
        val caCert = readCertificate(caCertPath)
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("ca-certificate", caCert)
        }
        return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
            init(keyStore)
            trustManagers
        }
    }

    private fun buildKeyManagers(certPath: String, keyPath: String): Array<KeyManager> {
        val cert = readCertificate(certPath)
        val privateKey = readPrivateKey(keyPath)
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("certificate", cert)
            setKeyEntry("private-key", privateKey, EMPTY_PASSWORD, arrayOf<Certificate>(cert))
        }
        return KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).run {
            init(keyStore, EMPTY_PASSWORD)
            keyManagers
        }
    }

    private fun readCertificate(path: String): X509Certificate {
        PEMParser(FileReader(path)).use { parser ->
            val holder = parser.readObject() as? X509CertificateHolder
                ?: throw IOException("No X.509 certificate found in $path")
            return JcaX509CertificateConverter().getCertificate(holder)
        }
    }

    private fun readPrivateKey(path: String): PrivateKey {
        PEMParser(FileReader(path)).use { parser ->
            val keyObject = parser.readObject()
            val converter = JcaPEMKeyConverter()
            return when (keyObject) {
                is PEMEncryptedKeyPair -> {
                    val decryptor = JcePEMDecryptorProviderBuilder().build(EMPTY_PASSWORD)
                    converter.getKeyPair(keyObject.decryptKeyPair(decryptor)).private
                }
                is PEMKeyPair -> converter.getKeyPair(keyObject).private
                is PrivateKey -> keyObject
                is PrivateKeyInfo -> converter.getPrivateKey(keyObject)
                else -> throw IOException("Unsupported key file format: $path")
            }
        }
    }

    private fun closeQuietly(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (e: Exception) {
            Log.v(TAG, "Ignoring error while closing resource", e)
        }
    }

    private fun joinQuietly(thread: Thread?, timeoutMs: Long = JOIN_TIMEOUT_MS) {
        if (thread == null || thread === Thread.currentThread()) return
        try {
            thread.join(timeoutMs)
            if (thread.isAlive) {
                Log.w(TAG, "Thread '${thread.name}' did not terminate within ${timeoutMs}ms")
                thread.interrupt()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
