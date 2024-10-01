package com.danizek.dp

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.logging.Logger

class NetguardVpnService : VpnService() {
    companion object {
        const val START_VPN = "NetguardVpnServiceStart"
        const val STOP_VPN = "NetguardVpnServiceStop"
        const val TAG = "NetguardVpnService"
    }

    inner class LocalBinder : Binder() {
        fun getService(): NetguardVpnService = this@NetguardVpnService
    }

    var vpnDescriptor: ParcelFileDescriptor? = null
    private val binder = LocalBinder()
    var relayThread: Thread? = null
    var streamFromDevice: FileInputStream? = null
    var streamToDevice: FileOutputStream? = null
    var numSent: Long = 0
    var numReceived: Long = 0

    fun setupVpn() {
        if (vpnDescriptor != null) {
            return
        }

        // TODO run relay server

        val builder = Builder()
        builder.addAddress("192.168.255.2", 32)
        builder.addRoute("0.0.0.0", 0)
        builder.addDnsServer("1.1.1.1")
        vpnDescriptor = builder.establish()
        Log.v(TAG, vpnDescriptor.toString())
        streamFromDevice = FileInputStream(vpnDescriptor?.fileDescriptor)
        streamToDevice = FileOutputStream(vpnDescriptor?.fileDescriptor)
        relayThread = Thread {
            while (true) {
                packetLoop()
            }
        }
        relayThread!!.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            START_VPN -> {
                setupVpn()
            }

            STOP_VPN -> {
                disconnect()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    fun disconnect() {
        vpnDescriptor?.close()
        vpnDescriptor = null
    }

    fun getIpSrcAddress(buffer: ByteBuffer): ByteArray {
        val arr = ByteArray(4)
        arr[0] = buffer.get(12)
        arr[1] = buffer.get(13)
        arr[2] = buffer.get(14)
        arr[3] = buffer.get(15)

        return arr
    }

    fun getIpDestAddress(buffer: ByteBuffer): ByteArray {
        val arr = ByteArray(4)

        arr[0] = buffer.get(16)
        arr[1] = buffer.get(17)
        arr[2] = buffer.get(18)
        arr[3] = buffer.get(19)

        return arr
    }

    fun sendDataFromDevice() {
        val buffer = ByteArray(65535)
        val length = streamFromDevice!!.read(buffer)
        if (length == 0) {
            return
        }

        numSent += length
        val packet = Packet.deserialize(buffer.copyOfRange(0, length))
        val srcIp = getIpSrcAddress(ByteBuffer.wrap(buffer))
        val destIp = getIpDestAddress(ByteBuffer.wrap(buffer))

        Log.v(
            TAG,
            "srcIp: "
                    + srcIp[0].toUByte()
                    + "." + srcIp[1].toUByte()
                    + "." + srcIp[2].toUByte()
                    + "." + srcIp[3].toUByte()
                    + " destIp: " + destIp[0].toUByte()
                    + "." + destIp[1].toUByte() + "."
                    + destIp[2].toUByte() + "."
                    + destIp[3].toUByte() + " "
                    + if (buffer[9].toUInt() == 0x06u) { "TCP" } else { "UDP" }
                    + " srcPort: " + packet.sourcePort + " destPort: " + packet.destinationPort
        )
    }

    fun receiveDataFromInternet() {

    }

    fun packetLoop() {
        sendDataFromDevice()
        receiveDataFromInternet()
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

}