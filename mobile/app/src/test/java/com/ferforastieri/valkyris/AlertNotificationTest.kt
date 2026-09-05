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
}
