package com.ferforastieri.valkyris.feature.people

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class LocationTrackingService : Service(), LocationListener {
    @Inject lateinit var api: ValkyrisApi
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var locationManager: LocationManager
    private lateinit var preferences: SharedPreferences
    private val reportingMutex = Mutex()
    private var updatesRegistered = false

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        preferences = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        createChannel()
    }

    @SuppressLint("MissingPermission") // guarded by hasLocationPermission immediately below
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasLocationPermission()) { stopSelf(); return START_NOT_STICKY }
        preferences.edit().putBoolean(TRACKING_ENABLED, true).apply()
        startForeground(NOTIFICATION_ID, notification())
        if (updatesRegistered) return START_STICKY
        runCatching {
            locationManager.removeUpdates(this)
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
                if (locationManager.isProviderEnabled(provider)) {
                    // Receive a reasonably fresh fix, but report it only when the
                    // persisted five-minute/movement policy below allows it.
                    locationManager.requestLocationUpdates(provider, CHECK_INTERVAL_MS, MOVEMENT_DISTANCE_METERS, this, Looper.getMainLooper())
                    locationManager.getLastKnownLocation(provider)?.let(::report)
                }
            }
            updatesRegistered = true
        }
        return START_STICKY
    }

    override fun onLocationChanged(location: Location) = report(location)
    private fun report(location: Location) {
        scope.launch {
            reportingMutex.withLock {
                if (!shouldReport(location)) return@withLock
                val completed = runCatching {
                    api.reportMyLocation(PersonLocation(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracy = location.accuracy.toDouble(),
                        address = resolveAddress(location),
                        occurredAt = Instant.ofEpochMilli(location.time).toString(),
                    ))
                }.isSuccess
                if (completed) persistReportedLocation(location)
            }
        }
    }

    private fun shouldReport(location: Location): Boolean {
        val now = System.currentTimeMillis()
        if (!location.hasAccuracy() || !location.accuracy.isFinite() || location.accuracy <= 0 || location.accuracy > 200) return false
        if (location.time < now - 2 * 60_000 || location.time > now + 30_000) return false
        if (location.time <= preferences.getLong(LAST_FIX_AT, 0L)) return false
        val lastSentAt = preferences.getLong(LAST_SENT_AT, 0L)
        if (lastSentAt == 0L || now - lastSentAt >= REPORT_INTERVAL_MS) return true
        if (now - lastSentAt < 30_000) return false
        if (!preferences.contains(LAST_SENT_LATITUDE) || !preferences.contains(LAST_SENT_LONGITUDE)) return true
        val lastLocation = Location("last-reported").apply {
            latitude = Double.fromBits(preferences.getLong(LAST_SENT_LATITUDE, 0L))
            longitude = Double.fromBits(preferences.getLong(LAST_SENT_LONGITUDE, 0L))
        }
        val uncertainty = location.accuracy + preferences.getFloat(LAST_ACCURACY, 0f)
        return lastLocation.distanceTo(location) >= maxOf(MOVEMENT_DISTANCE_METERS, uncertainty)
    }

    private fun persistReportedLocation(location: Location) {
        preferences.edit()
            .putLong(LAST_SENT_AT, System.currentTimeMillis())
            .putLong(LAST_FIX_AT, location.time)
            .putFloat(LAST_ACCURACY, location.accuracy)
            .putLong(LAST_SENT_LATITUDE, location.latitude.toBits())
            .putLong(LAST_SENT_LONGITUDE, location.longitude.toBits())
            .apply()
    }

    @Suppress("DEPRECATION")
    private fun resolveAddress(location: Location): String = runCatching {
        if (!Geocoder.isPresent()) return@runCatching ""
        val address = Geocoder(this, Locale.getDefault())
            .getFromLocation(location.latitude, location.longitude, 1)
            ?.firstOrNull()
            ?: return@runCatching ""
        address.displayAddress()
    }.getOrDefault("")

    private fun Address.displayAddress(): String {
        val completeAddress = if (maxAddressLineIndex >= 0) {
            (0..maxAddressLineIndex).mapNotNull { getAddressLine(it)?.trim()?.takeIf { value -> value.isNotBlank() } }.joinToString(", ")
        } else ""
        return completeAddress.ifBlank {
            listOf(featureName, thoroughfare, subThoroughfare, locality, subAdminArea, adminArea, postalCode, countryName)
                .mapNotNull { it?.trim()?.takeIf { value -> value.isNotBlank() } }
                .distinct()
                .joinToString(", ")
        }.take(320)
    }

    override fun onDestroy() { scope.cancel(); runCatching { locationManager.removeUpdates(this) }; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun createChannel() {
        NotificationChannel(
            CHANNEL_ID,
            "Rastreamento Valkyris",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Mostra quando o Valkyris atualiza a posição deste telefone."
            setShowBadge(false)
        }.also { getSystemService(NotificationManager::class.java).createNotificationChannel(it) }
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setColorized(false)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentTitle("Valkyris · localização ativa")
        .setContentText("Este telefone aparece no mapa da família.")
        .setStyle(NotificationCompat.BigTextStyle().bigText("O Valkyris atualiza sua posição em segundo plano para manter o mapa da família atual."))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    companion object {
        private const val CHANNEL_ID = "location-tracking"
        private const val NOTIFICATION_ID = 117
        private const val TRACKING_ENABLED = "tracking_enabled"
        private const val PREFERENCES = "location_tracking"
        private const val LAST_SENT_AT = "last_sent_at"
        private const val LAST_FIX_AT = "last_fix_at"
        private const val LAST_ACCURACY = "last_accuracy"
        private const val LAST_SENT_LATITUDE = "last_sent_latitude"
        private const val LAST_SENT_LONGITUDE = "last_sent_longitude"
        private const val REPORT_INTERVAL_MS = 5 * 60 * 1000L
        private const val CHECK_INTERVAL_MS = 60 * 1000L
        private const val MOVEMENT_DISTANCE_METERS = 50f
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, LocationTrackingService::class.java))
    }
}
