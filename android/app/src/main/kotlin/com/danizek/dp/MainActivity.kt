package com.danizek.dp

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.BatteryManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    companion object {
        const val REQUEST_CODE = 500
    }

    private val CHANNEL = "com.danizek.netguard/vpn"
    var canStartVpn = false;
    var vpnService: NetguardVpnService? = null
    var serviceConnectionHandler: ServiceConnection? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine);
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler {
                call, result ->
            if (call.method == "start") {
                val intent = VpnService.prepare(this)
                if (intent == null) {
                    canStartVpn = true
                    startVpn()
                    return@setMethodCallHandler
                }

                startActivityForResult(intent, REQUEST_CODE)
                result.success(1)
            } else if (call.method == "stop") {
                vpnService?.disconnect()
                result.success(1)
            } else if (call.method == "getData") {
                if (vpnService == null) {
                    result.success(LongArray(2) { 0 })
                    return@setMethodCallHandler
                }

                val data = LongArray(2)
                data[0] = vpnService!!.numReceived
                data[1] = vpnService!!.numSent
                result.success(data)
            } else {
                result.notImplemented()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            canStartVpn = true
            startVpn()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceConnectionHandler != null) {
            unbindService(serviceConnectionHandler as ServiceConnection)
        }
    }

    fun startVpn() {
        intent = Intent(context, NetguardVpnService::class.java)
        intent.action = NetguardVpnService.START_VPN
        startService(intent)

        serviceConnectionHandler = object: ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                Log.v("NetguardVpnService", "Service connected")
                if (service == null) {
                    Log.v("NetguardVpnService", "binder is null")
                    return
                }

                vpnService = (service as NetguardVpnService.LocalBinder).getService()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.v("NetguardVpnService", "Service disconnected")
            }
        }

        bindService(intent, serviceConnectionHandler as ServiceConnection, 0)
    }
}
