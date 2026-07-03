package com.pcontrol.app

import android.app.Service
import android.content.Intent
import android.os.IBinder

class TrackerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
