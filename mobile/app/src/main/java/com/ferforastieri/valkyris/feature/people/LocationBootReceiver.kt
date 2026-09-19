package com.ferforastieri.valkyris.feature.people

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class LocationBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val enabled = context.getSharedPreferences("location_tracking", Context.MODE_PRIVATE)
            .getBoolean("tracking_enabled", false)
        val backgroundAllowed = android.os.Build.VERSION.SDK_INT < 29 || androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (enabled && backgroundAllowed) LocationTrackingService.start(context)
    }
}
