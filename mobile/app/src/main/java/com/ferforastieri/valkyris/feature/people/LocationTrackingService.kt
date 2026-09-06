package com.ferforastieri.valkyris.feature.people

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ferforastieri.valkyris.R
import com.ferforastieri.valkyris.core.model.PersonLocation
import com.ferforastieri.valkyris.core.network.ValkyrisApi
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

@AndroidEntryPoint
class LocationTrackingService : Service(), LocationListener {
    @Inject lateinit var api: ValkyrisApi
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var locationManager: LocationManager
    private var personId = ""

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        personId = intent?.getStringExtra(EXTRA_PERSON_ID).orEmpty()
        if (personId.isBlank() || !hasLocationPermission()) { stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIFICATION_ID, notification())
        runCatching {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 120_000L, 30f, this, Looper.getMainLooper())
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let(::report)
        }
        return START_STICKY
    }

    override fun onLocationChanged(location: Location) = report(location)
    private fun report(location: Location) {
        if (personId.isBlank()) return
        scope.launch {
            runCatching {
                api.reportLocation(personId, PersonLocation(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy.toDouble(),
                    occurredAt = Instant.ofEpochMilli(location.time).toString(),
                ))
            }
        }
    }

    override fun onDestroy() { runCatching { locationManager.removeUpdates(this) }; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun createChannel() { (getSystemService(NotificationManager::class.java)).createNotificationChannel(NotificationChannel(CHANNEL_ID, "Rastreamento Valkyris", NotificationManager.IMPORTANCE_LOW)) }
    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_launcher).setContentTitle("Rastreamento ativo").setContentText("O Valkyris está registrando a localização deste telefone.").setOngoing(true).build()

    companion object {
        private const val CHANNEL_ID = "location-tracking"
        private const val NOTIFICATION_ID = 117
        private const val EXTRA_PERSON_ID = "person_id"
        fun start(context: Context, personId: String) = ContextCompat.startForegroundService(context, Intent(context, LocationTrackingService::class.java).putExtra(EXTRA_PERSON_ID, personId))
    }
}
