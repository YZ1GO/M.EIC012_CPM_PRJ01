package com.cpm.cleave

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import com.cpm.cleave.data.sync.ConnectivitySyncTrigger
import com.cpm.cleave.data.sync.SyncWorkScheduler
import com.cpm.cleave.dependencyinjection.AppContainer
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

class CleaveApplication : Application() {

    companion object {
        private const val TAG = "CleaveAppCheck"
    }

    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
        initializeFirebaseAppCheck()
        SyncWorkScheduler.schedule(this)
        ConnectivitySyncTrigger.register(this)
    }

    private fun initializeFirebaseAppCheck() {
        val appCheck = FirebaseAppCheck.getInstance()
        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        var providerName = "PlayIntegrity"
        if (isDebuggable) {
            try {
                val debugFactoryClass = Class.forName(
                    "com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory"
                )
                val getInstance = debugFactoryClass.getMethod("getInstance")
                val debugFactory = getInstance.invoke(null)
                appCheck.installAppCheckProviderFactory(debugFactory as com.google.firebase.appcheck.AppCheckProviderFactory)
                providerName = "Debug"
            } catch (e: Exception) {
                // Debug provider not available, fall back to Play Integrity
                appCheck.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
            }
        } else {
            appCheck.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
        }

        Log.i(
            TAG,
            "Initialized App Check provider=$providerName isDebuggable=$isDebuggable buildDebug=${BuildConfig.DEBUG}"
        )

        appCheck.getAppCheckToken(false)
            .addOnSuccessListener { tokenResult ->
                Log.i(TAG, "Initial App Check token acquired. tokenLength=${tokenResult.token.length}")
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "Initial App Check token fetch failed: ${error.message}", error)
            }
    }
}
