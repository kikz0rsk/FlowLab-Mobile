package com.danizek.dp;

interface IVpn {
    fun getBinder() : IBinder
    fun startVpn()
    fun stopVpn()
    fun getVpnStatus() : VpnStatus
}
