package com.azb.recordcard

import android.app.Application
import com.azb.recordcard.app.AppController
import com.azb.recordcard.core.TransferNotifications

class RecordCardApplication : Application() {
    lateinit var controller: AppController
        private set

    override fun onCreate() {
        super.onCreate()
        TransferNotifications.ensureChannel(this)
        controller = AppController(this)
    }
}
