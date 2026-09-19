package com.ferforastieri.valkyris

import android.app.Application
import android.app.Notification
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Looper
import com.ferforastieri.valkyris.core.alarm.AlarmNotifier
import com.ferforastieri.valkyris.core.alarm.AlarmPlaybackService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 35])
class AlarmPlaybackTest {
    @Test fun serviceUsesAlarmAudioAndReleasesPlaybackAndFocus() {
        val app = RuntimeEnvironment.getApplication()
        AlarmNotifier(app).createChannels()
        var created: MediaPlayer? = null
        ShadowMediaPlayer.setCreateListener { player, _ -> created = player }
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(2000, 0) }
        val controller = Robolectric.buildService(AlarmPlaybackService::class.java).create()
        val service = controller.get()
        val notification = Notification.Builder(app, AlarmNotifier.ALARMS).setSmallIcon(R.drawable.ic_notification).build()
        service.onStartCommand(Intent(app, AlarmPlaybackService::class.java)
            .putExtra("eventId", "cry").putExtra("sound", "alarm").putExtra("vibrate", false)
            .putExtra("notification", notification), 0, 1)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        val player = requireNotNull(created)
        assertEquals(AudioAttributes.USAGE_ALARM, shadowOf(player).audioAttributes.usage)
        assertTrue(player.isLooping)
        assertTrue(player.isPlaying)
        val audio = shadowOf(app.getSystemService(AudioManager::class.java))
        assertEquals(AudioAttributes.USAGE_ALARM, audio.lastAudioFocusRequest.audioFocusRequest.audioAttributes.usage)
        controller.destroy()
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(player).state)
        assertNotNull(audio.lastAbandonedAudioFocusRequest)
    }
}
