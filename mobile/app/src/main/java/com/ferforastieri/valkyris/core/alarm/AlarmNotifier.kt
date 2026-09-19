package com.ferforastieri.valkyris.core.alarm

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ferforastieri.valkyris.MainActivity
import com.ferforastieri.valkyris.R
import com.ferforastieri.valkyris.core.model.AlertPresentation
import com.ferforastieri.valkyris.core.model.detectorLabelRes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlarmNotifier @Inject constructor(@param:ApplicationContext private val context: Context) {
    fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        manager.createNotificationChannel(
            NotificationChannel(EVENTS, context.getString(R.string.notification_channel_events), NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(ALARMS, context.getString(R.string.notification_channel_alarm), NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                setSound(sound, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(manager.isNotificationPolicyAccessGranted)
            },
        )
    }

    fun show(eventId: String, cameraId: String, type: String, confidence: Double, alarm: Boolean, opensCamera: Boolean, personName: String = "", placeName: String = "", cameraName: String = "", alerts: AlertPresentation = AlertPresentation()) {
        createChannels()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val defaultTitle = com.ferforastieri.valkyris.core.model.locationEventTitle(type, personName, placeName) ?: context.getString(detectorLabelRes(type))
        val title = alerts.notificationTitle.ifBlank {
            defaultTitle
        }
        val body = alerts.notificationBody.ifBlank {
            context.getString(if (alarm) R.string.notification_alarm_body else R.string.notification_event_body)
        }
        val channel = presentationChannel(alarm, alerts)
        val eventIntent = PendingIntent.getActivity(
            context,
            eventId.hashCode(),
            Intent(context, MainActivity::class.java).putExtra("eventId", eventId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarmIntent = PendingIntent.getActivity(
            context,
            eventId.hashCode() + 1,
            Intent(context, AlarmActivity::class.java).putExtra("eventId", eventId).putExtra("eventType", type)
                .putExtra("alarmTitle", alerts.alarmTitle.ifBlank { defaultTitle })
                .putExtra("alarmBody", alerts.alarmBody.ifBlank { context.getString(R.string.notification_alarm_body) }).putExtra("cameraName", cameraName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cameraIntent = PendingIntent.getActivity(
            context,
            eventId.hashCode() + 2,
            Intent(context, MainActivity::class.java).putExtra("cameraId", cameraId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = if (opensCamera && cameraId.isNotBlank()) cameraIntent else eventIntent
        val manager = context.getSystemService(NotificationManager::class.java)
        val canUseFullScreen = !alarm || Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent()
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setSubText(cameraName.takeIf { it.isNotBlank() })
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(if (alarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_EVENT)
            .setNumber(1)
            .setGroup(EVENT_GROUP)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .apply {
                if (cameraId.isNotBlank() && !opensCamera) addAction(0, context.getString(R.string.open_camera), cameraIntent)
                if (opensCamera) addAction(0, context.getString(R.string.view_event), eventIntent)
            }
            .apply { if (alarm) addAction(0, context.getString(R.string.open_alarm), alarmIntent) else if (!opensCamera) addAction(0, context.getString(R.string.view_event), eventIntent) }
            .apply { if (alarm && alerts.fullScreen && canUseFullScreen) setFullScreenIntent(alarmIntent, true) }
            .apply {
                if (alarm) {
                    val silence = PendingIntent.getBroadcast(
                        context, eventId.hashCode(), Intent(context, AlarmSilenceReceiver::class.java).putExtra("eventId", eventId),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    addAction(0, context.getString(R.string.silence), silence)
                    setDeleteIntent(silence)
                }
            }
            .build()
        NotificationManagerCompat.from(context).notify(eventId.hashCode(), notification)
        if (alarm && alerts.alarmSound != "silent" && manager.getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE) {
            AlarmPlaybackService.start(context, eventId, alerts.alarmSound, alerts.vibrate, notification)
        }
        updateBadge()
    }

    fun cancel(eventId: String) {
        AlarmPlaybackService.stop(context, eventId)
        NotificationManagerCompat.from(context).cancel(eventId.hashCode())
        updateBadge()
    }

    fun cancelAll() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.notification.group == EVENT_GROUP }.forEach {
            NotificationManagerCompat.from(context).cancel(it.id)
        }
        context.stopService(Intent(context, AlarmPlaybackService::class.java))
    }

    private fun presentationChannel(alarm: Boolean, alerts: AlertPresentation): String {
        if (alerts.vibrate && (!alarm || alerts.alarmSound == "alarm")) return if (alarm) ALARMS else EVENTS
        val id = "${if (alarm) ALARMS else EVENTS}_${if (alarm) alerts.alarmSound else "default"}_${alerts.vibrate}"
        val sound = when {
            !alarm -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            alerts.alarmSound == "silent" -> null
            alerts.alarmSound == "ringtone" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            else -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(id, context.getString(if (alarm) R.string.notification_channel_alarm else R.string.notification_channel_events), NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(alerts.vibrate)
                setSound(sound, AudioAttributes.Builder().setUsage(if (alarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION).build())
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        return id
    }

    private fun updateBadge() {
        if (Build.VERSION.SDK_INT < 23) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val count = manager.activeNotifications.count {
            it.id != SUMMARY_ID && it.notification.group == EVENT_GROUP
        }
        if (count == 0) {
            NotificationManagerCompat.from(context).cancel(SUMMARY_ID)
            return
        }
        val summary = NotificationCompat.Builder(context, EVENTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.pending_alerts))
            .setGroup(EVENT_GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setNumber(count)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setOnlyAlertOnce(true)
            .build()
        NotificationManagerCompat.from(context).notify(SUMMARY_ID, summary)
    }

    companion object {
        const val EVENTS = "valkyris_events"
        const val EVENT_GROUP = "valkyris-home-events"
        const val SUMMARY_ID = 0x56414C
        const val ALARMS = "valkyris_alarms"
    }
}
