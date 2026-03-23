package com.cpm.cleave

import android.app.Application
import com.cpm.cleave.data.sync.ConnectivitySyncTrigger
import com.cpm.cleave.data.sync.SyncWorkScheduler

class CleaveApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SyncWorkScheduler.schedule(this)
        ConnectivitySyncTrigger.register(this)
    }
}
