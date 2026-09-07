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
import android.os.IBinder
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import android.util.Log
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
class LocationTrackingService : Service() {
    @Inject lateinit var api: ValkyrisApi
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var locationClient: FusedLocationProviderClient
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) { result.locations.sortedBy { it.time }.forEach(::report) }
    }
    private lateinit var preferences: SharedPreferences
    private val reportingMutex = Mutex()
    private var updatesRegistered = false

    override fun onCreate() {
        super.onCreate()
        locationClient = LocationServices.getFusedLocationProviderClient(this)
        preferences = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        createChannel()
    }

    @SuppressLint("MissingPermission") // guarded by hasLocationPermission immediately below
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasLocationPermission()) { stopSelf(); return START_NOT_STICKY }
        preferences.edit().putBoolean(TRACKING_ENABLED, true).apply()
        startForeground(NOTIFICATION_ID, notification())
        if (updatesRegistered) return START_STICKY
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, CHECK_INTERVAL_MS)
            .setMinUpdateIntervalMillis(CHECK_INTERVAL_MS)
            .setMinUpdateDistanceMeters(0f)
            .setMaxUpdateAgeMillis(0L)
            .setWaitForAccurateLocation(true)
            .build()
        updatesRegistered = true
        locationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            .addOnFailureListener {
                updatesRegistered = false
                Log.w("ValkyrisLocation", "Location updates unavailable: ${it.javaClass.simpleName}")
            }
        return START_STICKY
    }

    private fun report(location: Location) {
        scope.launch {
            reportingMutex.withLock {
                if (!shouldReport(location)) return@withLock
                val result = runCatching {
                    api.reportMyLocation(PersonLocation(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracy = location.accuracy.toDouble(),
                        address = resolveAddress(location),
                        occurredAt = Instant.ofEpochMilli(location.time).toString(),
                    ))
                }.getOrNull()
                if (result != null) persistReportedLocation(location, result.pendingConfirmations > 0)
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
        if (now - lastSentAt < 55_000) return false
        if (now < preferences.getLong(CONFIRM_UNTIL, 0L)) return true
        return hasMoved(location)
    }

    private fun hasMoved(location: Location): Boolean {
        if (!preferences.contains(LAST_SENT_LATITUDE) || !preferences.contains(LAST_SENT_LONGITUDE)) return true
        val lastLocation = Location("last-reported").apply {
            latitude = Double.fromBits(preferences.getLong(LAST_SENT_LATITUDE, 0L))
            longitude = Double.fromBits(preferences.getLong(LAST_SENT_LONGITUDE, 0L))
        }
        val uncertainty = location.accuracy + preferences.getFloat(LAST_ACCURACY, 0f)
        return lastLocation.distanceTo(location) >= maxOf(MOVEMENT_DISTANCE_METERS, uncertainty)
    }

    private fun persistReportedLocation(location: Location, confirmationPending: Boolean) {
        val now = System.currentTimeMillis()
        // Fresh follow-ups confirm geofences; history still deduplicates stationary fixes.
        val until = preferences.getLong(CONFIRM_UNTIL, 0L)
        preferences.edit()
            .putLong(CONFIRM_UNTIL, if (confirmationPending || now >= until || hasMoved(location)) now + 4 * 60_000L else until)
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

    override fun onDestroy() { scope.cancel(); runCatching { locationClient.removeLocationUpdates(locationCallback) }; super.onDestroy() }
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
        .setContentTitle(getString(R.string.location_notification_title))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    companion object {
        private const val CHANNEL_ID = "location-tracking"
        private const val NOTIFICATION_ID = 117
        private const val TRACKING_ENABLED = "tracking_enabled"
        private const val PREFERENCES = "location_tracking"
        private const val LAST_SENT_AT = "last_sent_at"
        private const val CONFIRM_UNTIL = "confirm_until"
        private const val LAST_FIX_AT = "last_fix_at"
        private const val LAST_ACCURACY = "last_accuracy"
        private const val LAST_SENT_LATITUDE = "last_sent_latitude"
        private const val LAST_SENT_LONGITUDE = "last_sent_longitude"
        private const val REPORT_INTERVAL_MS = 15 * 60 * 1000L
        private const val CHECK_INTERVAL_MS = 60 * 1000L
        private const val MOVEMENT_DISTANCE_METERS = 100f
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, LocationTrackingService::class.java))
    }
}
