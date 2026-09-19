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
import android.location.Location
import android.os.IBinder
import android.os.Handler
import android.net.ConnectivityManager
import android.net.Network
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.tasks.CancellationTokenSource
import com.ferforastieri.valkyris.core.network.ValkyrisRepository
import com.ferforastieri.valkyris.core.security.SessionStore
import kotlinx.coroutines.CancellationException
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
import com.ferforastieri.valkyris.core.model.LocationReport
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
import javax.inject.Inject

@AndroidEntryPoint
class LocationTrackingService : Service() {
    @Inject lateinit var api: ValkyrisApi
    @Inject lateinit var repository: ValkyrisRepository
    @Inject lateinit var sessions: SessionStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var locationClient: FusedLocationProviderClient
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) { result.locations.sortedBy { it.time }.forEach { report(it) } }
    }
    private lateinit var preferences: SharedPreferences
    private val reportingMutex = Mutex()
    private var updatesRegistered = false
    private var currentRequest: CancellationTokenSource? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var destroyed = false
    private var networkRegistered = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            mainHandler.post { if (!destroyed && updatesRegistered) requestFreshLocation() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationClient = LocationServices.getFusedLocationProviderClient(this)
        preferences = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        createChannel()
        runCatching {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
            networkRegistered = true
        }
    }

    @SuppressLint("MissingPermission") // guarded by hasLocationPermission immediately below
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasLocationPermission()) {
            LocationTrackingStatus.set(LocationTrackingStatus.State.PermissionRequired)
            stopSelf(); return START_NOT_STICKY
        }
        val owner = sessionKey()
        if (owner == null) { stopSelf(); return START_NOT_STICKY }
        if (preferences.getString("owner", null) != owner) preferences.edit().clear().putString("owner", owner).apply()
        preferences.edit().putBoolean(TRACKING_ENABLED, true).apply()
        startForeground(NOTIFICATION_ID, notification())
        // Re-entering the app must request a new fix even if periodic updates
        // were already registered before an OEM suspended location delivery.
        if (intent?.getBooleanExtra("refresh", false) == true || !updatesRegistered) requestFreshLocation()
        if (updatesRegistered) return START_STICKY
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, CHECK_INTERVAL_MS)
            .setMinUpdateIntervalMillis(CHECK_INTERVAL_MS)
            .setMinUpdateDistanceMeters(0f)
            .setMaxUpdateAgeMillis(0L)
            .setWaitForAccurateLocation(true)
            .build()
        updatesRegistered = true
        runCatching {
            locationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
                .addOnFailureListener {
                    updatesRegistered = false
                    LocationTrackingStatus.set(LocationTrackingStatus.State.LocationUnavailable)
                    Log.w("ValkyrisLocation", "Location updates unavailable: ${it.javaClass.simpleName}")
                }
        }.onFailure { updatesRegistered = false; LocationTrackingStatus.set(LocationTrackingStatus.State.LocationUnavailable) }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun requestFreshLocation() {
        if (destroyed || !hasLocationPermission() || currentRequest != null) return
        val cancellation = CancellationTokenSource()
        currentRequest = cancellation
        val startedAt = System.currentTimeMillis()
        LocationTrackingStatus.set(LocationTrackingStatus.State.Locating)
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(0)
            .setDurationMillis(30_000)
            .build()
        fun unavailable() {
            if (!destroyed && preferences.getLong(LAST_SENT_AT, 0) < startedAt) {
                LocationTrackingStatus.set(LocationTrackingStatus.State.LocationUnavailable)
            }
        }
        runCatching {
            locationClient.getCurrentLocation(request, cancellation.token)
                .addOnSuccessListener { location ->
                    if (!destroyed) {
                        if (location == null) unavailable() else report(location, force = true)
                    }
                }
                .addOnFailureListener { unavailable() }
                .addOnCompleteListener { if (currentRequest === cancellation) currentRequest = null }
        }.onFailure { currentRequest = null; unavailable() }
    }

    private fun sessionKey(): String? = sessions.get()?.let {
        java.security.MessageDigest.getInstance("SHA-256")
            .digest((it.baseUrl + "\n" + it.token).toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun report(location: Location, force: Boolean = false) {
        scope.launch {
            reportingMutex.withLock {
                val owner = sessionKey()
                if (owner == null) { stopSelf(); return@withLock }
                if (owner != preferences.getString("owner", null)) return@withLock
                if (!shouldSendLocation(location, System.currentTimeMillis(), preferences.getLong(LAST_FIX_AT, 0),
                        preferences.getLong(LAST_SENT_AT, 0), preferences.getLong(CONFIRM_UNTIL, 0), hasMoved(location), force)) {
                    if (force) LocationTrackingStatus.set(
                        if (System.currentTimeMillis() - preferences.getLong(LAST_SENT_AT, 0) < 30_000) LocationTrackingStatus.State.Updated
                        else LocationTrackingStatus.State.LocationUnavailable,
                    )
                    return@withLock
                }
                try {
                    val result = api.reportMyLocation(LocationReport(
                        latitude = location.latitude, longitude = location.longitude,
                        accuracy = location.accuracy.toDouble(), occurredAt = Instant.ofEpochMilli(location.time).toString(),
                    ))
                    if (owner != sessionKey()) return@withLock
                    persistReportedLocation(location, result.pendingConfirmations > 0)
                    LocationTrackingStatus.set(LocationTrackingStatus.State.Updated)
                    runCatching { repository.refreshUsers() }
                    runCatching { repository.refreshMe() }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    LocationTrackingStatus.set(LocationTrackingStatus.State.SendFailed)
                    Log.w("ValkyrisLocation", "Location upload failed: ${error.javaClass.simpleName}")
                }
            }
        }
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

    override fun onDestroy() {
        destroyed = true
        currentRequest?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        scope.cancel()
        runCatching { locationClient.removeLocationUpdates(locationCallback) }
        if (networkRegistered) runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        super.onDestroy()
    }
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
        private const val CHECK_INTERVAL_MS = 60 * 1000L
        private const val MOVEMENT_DISTANCE_METERS = 100f
        fun start(context: Context, refresh: Boolean = false) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, LocationTrackingService::class.java).putExtra("refresh", refresh)) }
                .onFailure { LocationTrackingStatus.set(LocationTrackingStatus.State.PermissionRequired) }
        }
        fun stop(context: Context) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().apply()
            context.stopService(Intent(context, LocationTrackingService::class.java))
            LocationTrackingStatus.set(LocationTrackingStatus.State.Idle)
        }
    }
}
