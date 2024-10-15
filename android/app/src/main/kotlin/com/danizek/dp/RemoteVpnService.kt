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
import java.io.EOFException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileReader
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPair
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.LinkedList
import java.util.Queue
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory


class RemoteVpnService : VpnService(), IVpn {
    inner class LocalBinder : Binder(), com.danizek.dp.IBinder {
        override fun getService(): IVpn {
            return this@RemoteVpnService
        }
    }

    companion object {
        const val START_VPN = "NetguardVpnServiceStart"
        const val STOP_VPN = "NetguardVpnServiceStop"
        const val TAG = "NetguardVpnService"
    }

    private val binder = LocalBinder()
    private var vpnDescriptor: ParcelFileDescriptor? = null
    private var streamFromDevice: FileInputStream? = null
    private var streamToDevice: FileOutputStream? = null
    private var appInputStream: InputStream? = null
    private var appOutputStream: OutputStream? = null
    private val stopFlag = AtomicBoolean(false)
    private var relayThread: Thread? = null
    private var remoteReaderThread: Thread? = null
    private var remoteHost: String = ""
    private var remotePort: Int = 0
    private var allowedApps: ArrayList<String> = ArrayList()
    private var appSocket: SSLSocket? = null
    private val buffer = ByteArray(65535)
    private val inputBuffer = ByteArray(65535)
    private var status: VpnActiveStatus = VpnActiveStatus.INACTIVE
    private val pendingRemotePackets: Queue<ByteArray> = LinkedList()
    private var caCertPath = "ca.cer"
    private var clientCertPath = "clientCert.cer"
    private var clientKeyFile = "key.key"

    override fun getBinder(): com.danizek.dp.IBinder {
        return binder
    }

    override fun startVpn() {
        if (vpnDescriptor != null) {
            return
        }

        val builder = Builder()
        builder.addAddress("192.168.255.2", 32)
        builder.addAddress("fd00::2", 32)
        builder.addRoute("0.0.0.0", 0)
        builder.addRoute("::", 0)
        builder.addDnsServer("1.1.1.1")
        builder.addDnsServer("2606:4700:4700::1111")
        if (allowedApps.isNotEmpty()) {
            for (app in allowedApps) {
                builder.addAllowedApplication(app)
            }
        }
        builder.allowFamily(OsConstants.AF_INET)
        builder.allowFamily(OsConstants.AF_INET6)
        builder.setBlocking(true)

        Log.v(TAG, "caCertPath: $caCertPath")
        Log.v(TAG, "clientCertPath: $clientCertPath")
        Log.v(TAG, "clientKeyFile: $clientKeyFile")

        vpnDescriptor = builder.establish()
        Log.v(TAG, vpnDescriptor.toString())
        streamFromDevice = FileInputStream(vpnDescriptor?.fileDescriptor)
        streamToDevice = FileOutputStream(vpnDescriptor?.fileDescriptor)
        stopFlag.set(false)

        Security.addProvider(BouncyCastleProvider())
        // Load CA cert
        var reader = PEMParser(FileReader(caCertPath))
        val caCertHolder = reader.readObject() as X509CertificateHolder
        val certificateConverter = JcaX509CertificateConverter()
        val caCert: X509Certificate = certificateConverter.getCertificate(caCertHolder)
        reader.close()
        val caKeyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        caKeyStore.load(null, null)
        caKeyStore.setCertificateEntry("ca-certificate", caCert)
        val trustManagerFactory =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(caKeyStore)

        // Load client cert
        reader = PEMParser(FileReader(clientCertPath))
        val certHolder = reader.readObject() as X509CertificateHolder
        reader.close()

        val cert = certificateConverter.getCertificate(certHolder)

        reader = PEMParser(FileReader(clientKeyFile))
        val keyObject = reader.readObject()
        reader.close()

        val keyConverter = JcaPEMKeyConverter()

        var privateKey: PrivateKey? = null

        if (keyObject is PEMEncryptedKeyPair) {
            val provider = JcePEMDecryptorProviderBuilder().build("".toCharArray())
            val keyPair: KeyPair = keyConverter.getKeyPair(keyObject.decryptKeyPair(provider))
            privateKey = keyPair.getPrivate()
        } else if (keyObject is PEMKeyPair) {
            val keyPair: KeyPair = keyConverter.getKeyPair(keyObject as PEMKeyPair)
            privateKey = keyPair.getPrivate()
        } else if (keyObject is PrivateKey) {
            privateKey = keyObject as PrivateKey
        } else if (keyObject is PrivateKeyInfo) {
            val info = PrivateKeyInfo.getInstance(keyObject)
            privateKey = keyConverter.getPrivateKey(info)
        } else {
            throw java.lang.Exception(
                java.lang.String.format(
                    "Unsported type of keyFile %s",
                    clientKeyFile
                )
            )
        }

        val clientKeyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        clientKeyStore.load(null, null)
        clientKeyStore.setCertificateEntry("certificate", cert)
        clientKeyStore.setKeyEntry(
            "private-key",
            privateKey,
            "".toCharArray(),
            arrayOf<Certificate>(cert)
        )

        val keyManagerFactory = KeyManagerFactory
            .getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(clientKeyStore, "".toCharArray())

        relayThread = Thread {
            Log.v(TAG, "Relay thread start")
            val tcpSocket = Socket()
            tcpSocket.bind(InetSocketAddress(0))
            protect(tcpSocket)
            status = VpnActiveStatus.CONNECTING
            try {
                tcpSocket!!.connect(InetSocketAddress(remoteHost, remotePort), 2000)
            } catch (e: Exception) {
                Log.v(TAG, "Failed to connect to remote host")
                tcpSocket!!.close()
                status = VpnActiveStatus.ERROR
                return@Thread
            }
            val context = SSLContext.getInstance("TLS");
            context.init(keyManagerFactory.keyManagers, trustManagerFactory.trustManagers, null)
            appSocket =
                context.socketFactory.createSocket(
                    tcpSocket,
                    remoteHost,
                    remotePort,
                    true
                ) as SSLSocket

            try {
                appSocket!!.startHandshake()
            } catch (e: Exception) {
                Log.v(TAG, "Failed during SSL handshake: $e")
                appSocket!!.close()
                status = VpnActiveStatus.ERROR
                return@Thread
            }

            status = VpnActiveStatus.ACTIVE
            appInputStream = appSocket!!.inputStream
            appOutputStream = appSocket!!.outputStream
            remoteReaderThread = Thread {
                try {
                    while (!stopFlag.get()) {
                        readExactly(appInputStream!!, inputBuffer, 0, 20)
                        var totalLength = 0
                        totalLength = if (((inputBuffer[0].toInt() shr 4) and 0xFF) == 4) {
                            ((inputBuffer[2].toInt() and 0xFF) shl 8) or (inputBuffer[3].toInt() and 0xFF)
                        } else {
                            (((inputBuffer[4].toInt() and 0xFF) shl 8) or (inputBuffer[5].toInt() and 0xFF)) + 40
                        }
                        readExactly(appInputStream!!, inputBuffer, 20, totalLength - 20)
                        //                        Log.v(TAG, "Received from remote")
                        streamToDevice!!.write(inputBuffer, 0, totalLength)
                        streamToDevice!!.flush()
                    }
                } catch (e: InterruptedException) {
                    Log.v(TAG, "Relay thread interrupted")
                } catch (e: EOFException) {
                    Log.v(TAG, "EOF")
                } catch (e: Exception) {
                    Log.v(TAG, "Exception: $e")
                }
            }
            remoteReaderThread!!.start()

            while (!stopFlag.get()) {
                try {
                    val readBytes = streamFromDevice!!.read(buffer)
                    if (readBytes == -1) {
                        throw EOFException("EOF")
                    }
//                    Log.v(TAG, "Read $readBytes bytes from device")
                    appOutputStream!!.write(buffer, 0, readBytes)
                    appOutputStream!!.flush()
//                    Log.v(TAG, "Sent to remote")
                } catch (e: InterruptedException) {
                    Log.v(TAG, "Relay thread interrupted")
                    break
                } catch (e: EOFException) {
                    Log.v(TAG, "EOF")
                    break
                } catch (e: Exception) {
                    Log.v(TAG, "Exception: $e")
                    status = VpnActiveStatus.ERROR
                    break
                }
            }
            try {
                appInputStream?.close()
                appOutputStream?.close()
                appSocket?.close()
                remoteReaderThread!!.interrupt()
                remoteReaderThread!!.join()
            } catch (_: InterruptedException) {}
            catch (_: Exception) {}
        }
        relayThread!!.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            START_VPN -> {
                remoteHost = intent.getStringExtra("remoteHost")!!
                remotePort = intent.getLongExtra("remotePort", 0).toInt()
                allowedApps = intent.getStringArrayListExtra("allowedApps")!!
                caCertPath = intent.getStringExtra("caCertPath")!!
                clientCertPath = intent.getStringExtra("clientCertPath")!!
                clientKeyFile = intent.getStringExtra("clientKeyPath")!!

                Log.v(TAG, "Remote host: $remoteHost")
                Log.v(TAG, "Remote port: $remotePort")
                startVpn()
            }

            STOP_VPN -> {
                stopVpn()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun stopVpn() {
        streamFromDevice?.close();
        relayThread?.interrupt()
        relayThread?.join()
        stopFlag.set(true)
        streamFromDevice?.close()
        streamToDevice?.close()
        vpnDescriptor?.close()
        vpnDescriptor = null
        pendingRemotePackets.clear()
        status = VpnActiveStatus.INACTIVE
        stopSelf()
    }

    override fun getVpnStatus(): VpnStatus {
        return VpnStatus(getStatus())
    }

    private fun readExactly(stream: InputStream, buffer: ByteArray, offset: Int, length: Int) {
        var curOffset = offset
        while (curOffset < offset + length) {
            val read = stream.read(buffer, curOffset, offset + length - curOffset)
            if (read == -1) {
                throw EOFException("EOF")
            }
            curOffset += read
        }
    }

    fun getStatus(): VpnActiveStatus {
        return status
    }
}