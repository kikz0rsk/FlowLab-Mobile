package com.danizek.dp

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.IBinder
import android.util.Log
import com.danizek.dp.MainActivity.Companion.REQUEST_CODE

class VpnApiMessenger(
    private val context: MainActivity
) : VpnApi {
    var canStartVpn = false
    var canOpenTree = false
    var vpnService: IVpn? = null
    var serviceConnectionHandler: ServiceConnection? = null
    var lastConfig: VpnConfiguration? = null

    fun startVpn() {
        if (lastConfig == null) {
            return
        }
        startVpn(lastConfig!!)
    }

    override fun startVpn(configuration: VpnConfiguration) {
        lastConfig = configuration
//        if (!canOpenTree) {
//            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
//            context.startActivityForResult(intent, 900)
//            return
//        }
        if (!prepareVpn()) {
            return
        }

        var intent = Intent(context, RemoteVpnService::class.java)
        intent.putExtra("remoteHost", configuration.host)
        intent.putExtra("remotePort", configuration.port)
        intent.putExtra("caCertPath", configuration.caCertPath)
        intent.putExtra("clientCertPath", configuration.clientCertPath)
        intent.putExtra("clientKeyPath", configuration.clientKeyPath)
        intent.putStringArrayListExtra("allowedApps", ArrayList(configuration.allowedApps))
        intent.action = RemoteVpnService.START_VPN
        context.startService(intent)

        serviceConnectionHandler = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                Log.v("NetguardVpnService", "Service connected")
                if (service == null) {
                    Log.v("NetguardVpnService", "binder is null")
                    return
                }

                vpnService = (service as com.danizek.dp.IBinder).getService()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.v("NetguardVpnService", "Service disconnected")
            }
        }

        context.bindService(intent, serviceConnectionHandler as ServiceConnection, 0)
    }

    fun prepareVpn(): Boolean {
        val intent = VpnService.prepare(context) ?: return true

        context.startActivityForResult(intent, REQUEST_CODE)
        return false
    }

    fun unbind() {
        serviceConnectionHandler?.let { context.unbindService(it) }
    }

    override fun stopVpn() {
        vpnService?.stopVpn()
    }

    override fun getVpnStatus(): VpnStatus {
        if (vpnService == null) {
            return VpnStatus(VpnActiveStatus.INACTIVE)
        }

        return vpnService!!.getVpnStatus()
    }
}