package com.ferforastieri.valkyris.feature.people

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class LocationBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val enabled = context.getSharedPreferences("location_tracking", Context.MODE_PRIVATE)
            .getBoolean("tracking_enabled", false)
        if (enabled) LocationTrackingService.start(context)
    }
}
