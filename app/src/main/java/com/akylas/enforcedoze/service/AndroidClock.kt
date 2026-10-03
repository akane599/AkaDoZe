package com.akylas.enforcedoze.service

import android.os.SystemClock
import com.akylas.enforcedoze.doze.Clock

class AndroidClock : Clock {
    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()
    override fun wallTime(): Long = System.currentTimeMillis()
}
