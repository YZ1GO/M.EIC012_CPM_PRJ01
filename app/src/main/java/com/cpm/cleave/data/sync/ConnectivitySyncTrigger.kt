package com.cpm.cleave.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

object ConnectivitySyncTrigger {
    fun register(context: Context) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return

        connectivityManager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    SyncWorkScheduler.enqueueOneShotSync(context)
                }
            }
        )
    }
}
