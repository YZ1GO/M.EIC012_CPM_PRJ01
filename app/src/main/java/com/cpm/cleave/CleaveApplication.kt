package com.cpm.cleave

import android.app.Application
import android.content.pm.ApplicationInfo
import com.cpm.cleave.data.sync.ConnectivitySyncTrigger
import com.cpm.cleave.data.sync.SyncWorkScheduler
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

class CleaveApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initializeFirebaseAppCheck()
        SyncWorkScheduler.schedule(this)
        ConnectivitySyncTrigger.register(this)
    }

    private fun initializeFirebaseAppCheck() {
        val appCheck = FirebaseAppCheck.getInstance()
        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebuggable) {
            try {
                val debugFactoryClass = Class.forName(
                    "com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory"
                )
                val getInstance = debugFactoryClass.getMethod("getInstance")
                val debugFactory = getInstance.invoke(null)
                appCheck.installAppCheckProviderFactory(debugFactory as com.google.firebase.appcheck.AppCheckProviderFactory)
            } catch (e: Exception) {
                // Debug provider not available, fall back to Play Integrity
                appCheck.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
            }
        } else {
            appCheck.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
        }
    }
}
