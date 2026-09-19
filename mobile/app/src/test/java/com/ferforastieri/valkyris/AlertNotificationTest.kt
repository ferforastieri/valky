package com.ferforastieri.valkyris

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import com.ferforastieri.valkyris.core.alarm.AlarmNotifier
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "pt-rBR", sdk = [28, 35])
class AlertNotificationTest {
    @Test fun firebaseRegistrationModeIsEnabled() {
        val app = RuntimeEnvironment.getApplication()
        val info = app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA)
        assertTrue(info.metaData.getBoolean("firebase_messaging_installation_id_enabled"))
    }

    @Test fun alertsUsePortugueseAndTheCorrectSoundChannel() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifier = AlarmNotifier(app)
        val manager = app.getSystemService(NotificationManager::class.java)
        notifier.show("cry", "cam", "baby_cry", .9, true, false)
        notifier.show("bark", "cam", "dog_bark", .8, false, false)
        val notifications = manager.activeNotifications.associate { it.id to it.notification }
        val alarm = requireNotNull(notifications["cry".hashCode()])
        val event = requireNotNull(notifications["bark".hashCode()])
        assertEquals("Choro de bebê", alarm.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Latido", event.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(AlarmNotifier.ALARMS, alarm.channelId)
        assertEquals(AlarmNotifier.EVENTS, event.channelId)
        assertNotNull(manager.getNotificationChannel(AlarmNotifier.ALARMS).sound)
        assertEquals(NotificationCompat.GROUP_ALERT_CHILDREN, alarm.groupAlertBehavior)
        assertEquals(NotificationCompat.GROUP_ALERT_CHILDREN, notifications[AlarmNotifier.SUMMARY_ID]?.groupAlertBehavior)
        notifier.cancel("cry")
        assertFalse(manager.activeNotifications.any { it.id == "cry".hashCode() })
    }

    @Test fun areaNotificationNamesThePersonAndPlace() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AlarmNotifier(app).show("arrival", "", "place_entered", 1.0, false, false, "Miriam", "Casa")
        val notification = app.getSystemService(NotificationManager::class.java).activeNotifications.single { it.id == "arrival".hashCode() }.notification
        assertEquals("Miriam chegou em Casa", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(AlarmNotifier.EVENTS, notification.channelId)
    }

    @Test fun cameraPresentationReachesBothNotificationAndAlarmScreen() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val alerts = com.ferforastieri.valkyris.core.model.AlertPresentation(
            notificationTitle = "Som no quarto", notificationBody = "Abra o alarme",
            alarmTitle = "Atenção ao quarto", alarmBody = "Verifique a câmera", alarmSound = "silent", vibrate = false, fullScreen = false,
        )
        AlarmNotifier(app).show("custom", "cam", "baby_cry", .9, true, false, cameraName = "Quarto", alerts = alerts)
        val manager = app.getSystemService(NotificationManager::class.java)
        val notification = manager.activeNotifications.single { it.id == "custom".hashCode() }.notification
        assertEquals("Som no quarto", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Abra o alarme", notification.extras.getString(Notification.EXTRA_TEXT))
        assertEquals("Quarto", notification.extras.getString(Notification.EXTRA_SUB_TEXT))
        assertNull(notification.fullScreenIntent)
        val channel = manager.getNotificationChannel(notification.channelId)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        val alarmAction = notification.actions.first { shadowOf(it.actionIntent).savedIntent.component?.className?.endsWith("AlarmActivity") == true }
        assertEquals("Atenção ao quarto", shadowOf(alarmAction.actionIntent).savedIntent.getStringExtra("alarmTitle"))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun audibleAlarmStartsPlaybackEvenWithoutFullScreen() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AlarmNotifier(app).show("sound", "cam", "baby_cry", .9, true, false,
            alerts = com.ferforastieri.valkyris.core.model.AlertPresentation(fullScreen = false))
        val intent = requireNotNull(shadowOf(app).nextStartedService)
        assertEquals("sound", intent.getStringExtra("eventId"))
        assertEquals("alarm", intent.getStringExtra("sound"))
        assertTrue(intent.component?.className?.endsWith("AlarmPlaybackService") == true)
        val notification = app.getSystemService(NotificationManager::class.java).activeNotifications.single { it.id == "sound".hashCode() }.notification
        assertNull(notification.fullScreenIntent)
        assertEquals(android.media.AudioAttributes.USAGE_ALARM,
            app.getSystemService(NotificationManager::class.java).getNotificationChannel(notification.channelId).audioAttributes.usage)
    }
}
