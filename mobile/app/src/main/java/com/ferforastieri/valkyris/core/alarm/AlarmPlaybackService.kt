package com.ferforastieri.valkyris.core.alarm

import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.content.ContextCompat
import com.ferforastieri.valkyris.R

/** Playback belongs to the alert, not to the lifetime of its full-screen UI. */
class AlarmPlaybackService : Service() {
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { stopSelf() }
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val eventId = intent?.getStringExtra("eventId") ?: return START_NOT_STICKY
        @Suppress("DEPRECATION")
        val notification = intent.getParcelableExtra<Notification>("notification") ?: return START_NOT_STICKY
        if (activeEventId != eventId) stopPlayback()
        activeEventId = eventId
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(eventId.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else startForeground(eventId.hashCode(), notification)
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 120_000)
        if (player != null) return START_NOT_STICKY
        val audio = getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stopSelf()
            }.build()
        focus = request
        if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (intent.getStringExtra("sound") == "ringtone") RingtoneManager.TYPE_RINGTONE else RingtoneManager.TYPE_ALARM
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, type)
        play(uri ?: fallbackUri(), fallback = uri == null)
        if (intent.getBooleanExtra("vibrate", true)) {
            getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0), attributes)
        }
        return START_NOT_STICKY
    }

    private fun fallbackUri() = Uri.parse("android.resource://$packageName/${R.raw.alarm_fallback}")

    private fun play(uri: Uri, fallback: Boolean) {
        val next = MediaPlayer()
        player = next
        runCatching {
            next.setAudioAttributes(attributes)
            next.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            next.setDataSource(this, uri)
            next.isLooping = true
            next.setOnPreparedListener { it.start() }
            next.setOnErrorListener { _, _, _ ->
                next.release()
                player = null
                if (!fallback) play(fallbackUri(), true) else stopSelf()
                true
            }
            next.prepareAsync()
        }.onFailure {
            next.release()
            player = null
            if (!fallback) play(fallbackUri(), true) else stopSelf()
        }
    }

    private fun stopPlayback() {
        player?.release()
        player = null
        getSystemService(Vibrator::class.java).cancel()
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        stopPlayback()
        activeEventId = null
        // Keep the event available for acknowledgement after silencing.
        stopForeground(STOP_FOREGROUND_DETACH)
        super.onDestroy()
    }

    companion object {
        private var activeEventId: String? = null
        fun start(context: Context, eventId: String, sound: String, vibrate: Boolean, notification: Notification) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, AlarmPlaybackService::class.java)
                    .putExtra("eventId", eventId).putExtra("sound", sound)
                    .putExtra("vibrate", vibrate).putExtra("notification", notification))
            }.onFailure {
                // A downgraded FCM message may not permit starting a service.
                // The alarm notification's system sound remains the fallback.
                Log.w("ValkyrisAlarm", "Alarm service unavailable: ${it.javaClass.simpleName}")
            }
        }
        fun stop(context: Context, eventId: String) {
            if (activeEventId == eventId) context.stopService(Intent(context, AlarmPlaybackService::class.java))
        }
    }
}

class AlarmSilenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmPlaybackService.stop(context, intent.getStringExtra("eventId").orEmpty())
    }
}
