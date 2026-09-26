package com.flick.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

private const val TAG = "FlickTV"

/**
 * Advertises _phonecast._tcp so the phone finds this TV by mDNS instead of a
 * hardcoded IP. This is what /etc/avahi/services/phonecast.service does on
 * the Pi.
 */
class Advertiser(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    fun start(port: Int, name: String) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = "_phonecast._tcp."
            setPort(port)
        }
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registered: NsdServiceInfo) {
                // NSD renames on a collision, so log what actually went out.
                Log.i(TAG, "advertising ${registered.serviceName} on port $port")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "mDNS registration failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "mDNS unregistered")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "mDNS unregistration failed: $errorCode")
            }
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
        listener = registration
    }

    fun stop() {
        val registration = listener ?: return
        listener = null
        try {
            nsd.unregisterService(registration)
        } catch (e: IllegalArgumentException) {
            // Registration never completed, so there is nothing to undo.
        }
    }
}
