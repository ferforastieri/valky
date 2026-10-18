package com.ferforastieri.valky.core.alarm

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
import com.ferforastieri.valky.R

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
        val notification = runCatching { intent.getParcelableExtra<Notification>("notification") }.getOrNull() ?: return START_NOT_STICKY
        if (activeEventId != eventId) stopPlayback()
        activeEventId = eventId
        // Android 12+ recusa iniciar o serviço em primeiro plano a partir do background
        // (ForegroundServiceStartNotAllowedException) e alguns aparelhos negam o tipo
        // mediaPlayback (SecurityException): sem a notificação o alarme não pode tocar
        // aqui, então apenas encerra em vez de derrubar o processo.
        val foreground = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(eventId.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else startForeground(eventId.hashCode(), notification)
        }.onFailure { Log.w("ValkyAlarm", "Foreground notification unavailable: ${it.javaClass.simpleName}") }.isSuccess
        if (!foreground) { stopSelf(); return START_NOT_STICKY }
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
        val granted = runCatching { audio.requestAudioFocus(request) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (intent.getStringExtra("sound") == "ringtone") RingtoneManager.TYPE_RINGTONE else RingtoneManager.TYPE_ALARM
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, type)
        // The alert plays at the phone's own alarm volume: no per-app volume is
        // applied, so it always matches the system alarm level.
        play(uri ?: fallbackUri(), fallback = uri == null)
        if (intent.getBooleanExtra("vibrate", true)) {
            runCatching { getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0), attributes) }
                .onFailure { Log.w("ValkyAlarm", "Vibration unavailable: ${it.javaClass.simpleName}") }
        }
        return START_NOT_STICKY
    }

    private fun fallbackUri() = Uri.parse("android.resource://$packageName/${R.raw.alarm_fallback}")

    private fun play(uri: Uri, fallback: Boolean) {
        runCatching {
            val next = MediaPlayer()
            player = next
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
            // O MediaPlayer pode falhar ao ser criado ou ao receber a fonte de áudio
            // (ringtone removido, arquivo corrompido): cai no som embutido e, se nem
            // isso funcionar, encerra o serviço sem derrubar o app.
            player?.release()
            player = null
            if (!fallback) play(fallbackUri(), true) else stopSelf()
        }
    }

    private fun stopPlayback() {
        runCatching {
            player?.release()
            getSystemService(Vibrator::class.java).cancel()
            focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        }
        player = null
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
                Log.w("ValkyAlarm", "Alarm service unavailable: ${it.javaClass.simpleName}")
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
