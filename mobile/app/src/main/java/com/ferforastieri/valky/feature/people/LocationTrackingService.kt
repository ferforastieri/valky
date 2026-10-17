package com.ferforastieri.valky.feature.people

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.os.IBinder
import android.os.Handler
import android.os.SystemClock
import android.net.ConnectivityManager
import android.net.Network
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.tasks.CancellationTokenSource
import com.ferforastieri.valky.core.network.ValkyRepository
import com.ferforastieri.valky.core.security.SessionStore
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
import com.ferforastieri.valky.R
import com.ferforastieri.valky.core.model.LocationReport
import com.ferforastieri.valky.core.network.ValkyApi
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
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
    @Inject lateinit var api: ValkyApi
    @Inject lateinit var repository: ValkyRepository
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
        scheduleWatchdog()
        // Re-entering the app must request a new fix even if periodic updates
        // were already registered before an OEM suspended location delivery.
        if (intent?.getBooleanExtra("refresh", false) == true || intent?.getBooleanExtra(EXTRA_WATCHDOG, false) == true || !updatesRegistered) requestFreshLocation()
        if (updatesRegistered) return START_STICKY
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, CHECK_INTERVAL_MS)
            .setMinUpdateIntervalMillis(CHECK_INTERVAL_MS)
            .setMinUpdateDistanceMeters(0f)
            .setMaxUpdateAgeMillis(0L)
            // Waiting for a perfect fix can stall delivery indefinitely on weak
            // GPS, so accept timely updates and filter poor accuracy afterwards.
            .setWaitForAccurateLocation(false)
            .build()
        updatesRegistered = true
        runCatching {
            locationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
                .addOnFailureListener {
                    updatesRegistered = false
                    LocationTrackingStatus.set(LocationTrackingStatus.State.LocationUnavailable)
                    Log.w("ValkyLocation", "Location updates unavailable: ${it.javaClass.simpleName}")
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

    private fun batteryLevel(): Int {
        // getIntProperty(BATTERY_PROPERTY_CAPACITY) returns 0/unknown on several
        // devices; the sticky battery broadcast is the reliable source.
        val intent = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) return -1
        return (level * 100 / scale).coerceIn(0, 100)
    }

    private fun report(location: Location, force: Boolean = false) {        scope.launch {
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
                        battery = batteryLevel(),
                    ))
                    if (owner != sessionKey()) return@withLock
                    persistReportedLocation(location, result.pendingConfirmations > 0)
                    LocationTrackingStatus.set(LocationTrackingStatus.State.Updated)
                    coroutineScope {
                        launch { runCatching { repository.refreshUsers() } }
                        launch { runCatching { repository.refreshMe() } }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    LocationTrackingStatus.set(LocationTrackingStatus.State.SendFailed)
                    Log.w("ValkyLocation", "Location upload failed: ${error.javaClass.simpleName}")
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

    // Swiping the task away must not stop location sharing. Ask the system to
    // bring the tracker back a moment later; the watchdog alarm keeps it alive
    // even when an OEM aggressively freezes background work.
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!destroyed) {
            runCatching {
                val restart = PendingIntent.getForegroundService(
                    this,
                    RESTART_REQUEST,
                    Intent(this, LocationTrackingService::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                getSystemService(AlarmManager::class.java)
                    .set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 2_000, restart)
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun scheduleWatchdog() {
        runCatching {
            val watchdog = PendingIntent.getForegroundService(
                this,
                WATCHDOG_REQUEST,
                Intent(this, LocationTrackingService::class.java).putExtra(EXTRA_WATCHDOG, true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            getSystemService(AlarmManager::class.java).setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS,
                WATCHDOG_INTERVAL_MS,
                watchdog,
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun createChannel() {
        NotificationChannel(
            CHANNEL_ID,
            "Rastreamento Valky",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Mostra quando o Valky atualiza a posição deste telefone."
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
        private const val EXTRA_WATCHDOG = "watchdog"
        private const val WATCHDOG_REQUEST = 118
        private const val RESTART_REQUEST = 119
        private const val WATCHDOG_INTERVAL_MS = 15 * 60_000L
        fun start(context: Context, refresh: Boolean = false) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, LocationTrackingService::class.java).putExtra("refresh", refresh)) }
                .onFailure { LocationTrackingStatus.set(LocationTrackingStatus.State.PermissionRequired) }
        }
        fun stop(context: Context) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().apply()
            runCatching {
                val manager = context.getSystemService(AlarmManager::class.java)
                listOf(WATCHDOG_REQUEST, RESTART_REQUEST).forEach { request ->
                    val pending = PendingIntent.getForegroundService(
                        context,
                        request,
                        Intent(context, LocationTrackingService::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    manager.cancel(pending)
                }
            }
            context.stopService(Intent(context, LocationTrackingService::class.java))
            LocationTrackingStatus.set(LocationTrackingStatus.State.Idle)
        }
    }
}
