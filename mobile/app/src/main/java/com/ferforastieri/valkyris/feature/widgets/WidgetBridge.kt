package com.ferforastieri.valkyris.feature.widgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.ferforastieri.valkyris.core.model.Camera
import com.ferforastieri.valkyris.core.model.LightDevice
import com.ferforastieri.valkyris.core.model.RemoteDevice
import com.ferforastieri.valkyris.core.network.ValkyrisApi
import com.ferforastieri.valkyris.core.security.SessionStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream

/** Everything the home screen widgets know between two refreshes. */
@Serializable
internal data class WidgetLight(
    val id: String,
    val name: String,
    val room: String = "",
    val power: Boolean = false,
    val online: Boolean = false,
    val brightness: Int = 0,
)

@Serializable
internal data class WidgetRemote(val id: String, val name: String)

@Serializable
internal data class WidgetCamera(val id: String, val name: String)

@Serializable
internal data class WidgetSnapshot(
    val lights: List<WidgetLight> = emptyList(),
    val remotes: List<WidgetRemote> = emptyList(),
    val camera: WidgetCamera? = null,
    val available: Boolean = false,
    val updatedAt: Long = 0L,
    val message: String = "",
)

/**
 * Reads and writes the widget state. The widgets never keep credentials of their
 * own: they use the same session the app stores, through the sealed session
 * store, and every call goes through the authenticated Valkyris API.
 */
internal object WidgetBridge {
    private const val STORE = "valkyris_widgets"
    private const val KEY_STATE = "state"
    private const val KEY_PHOTO = "photo"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun api(context: Context): ValkyrisApi {
        val app = context.applicationContext
        val store = SessionStore(app)
        return ValkyrisApi(store::get)
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun state(context: Context): WidgetSnapshot {
        val raw = prefs(context).getString(KEY_STATE, null) ?: return WidgetSnapshot()
        return runCatching { json.decodeFromString<WidgetSnapshot>(raw) }.getOrDefault(WidgetSnapshot())
    }

    private fun save(context: Context, snapshot: WidgetSnapshot) {
        prefs(context).edit().putString(KEY_STATE, json.encodeToString(snapshot)).apply()
    }

    fun signedIn(context: Context): Boolean = SessionStore(context.applicationContext).get() != null

    /** Asks the backend for the current bulbs, cameras and remotes. */
    suspend fun refresh(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!signedIn(context)) {
            save(context, WidgetSnapshot(available = false, updatedAt = System.currentTimeMillis(), message = "conecte o aplicativo"))
            return@withContext true
        }
        val api = api(context)
        val lights = runCatching { api.lights() }.getOrNull()
        val cameras = runCatching { api.cameras() }.getOrNull()
        val remotes = runCatching { api.remotes() }.getOrNull()
        if (lights == null && cameras == null && remotes == null) {
            val previous = state(context)
            save(context, previous.copy(message = "sem conexão com o Valkyris"))
            return@withContext false
        }
        val snapshot = WidgetSnapshot(
            lights = lights.orEmpty().sortedByDescending(LightDevice::favorite).map { it.toWidget() },
            remotes = remotes.orEmpty().sortedByDescending(RemoteDevice::favorite).map { WidgetRemote(it.id, it.name) },
            camera = cameras.orEmpty().sortedByDescending(Camera::favorite).firstOrNull()?.let { WidgetCamera(it.id, it.name) },
            available = true,
            updatedAt = System.currentTimeMillis(),
            message = "",
        )
        val previous = state(context)
        save(context, snapshot)
        if (snapshot.camera != null) fetchPhoto(context) else clearPhoto(context)
        previous != snapshot
    }

    private fun LightDevice.toWidget() = WidgetLight(
        id = id,
        name = name,
        room = room,
        power = state.power,
        online = state.online,
        brightness = state.brightness,
    )

    private fun photoFile(context: Context) = File(context.applicationContext.filesDir, "widget_camera.jpg")

    private suspend fun fetchPhoto(context: Context) {
        val camera = state(context).camera ?: return
        val bytes = runCatching { api(context).downloadCameraSnapshot(camera.id) }.getOrNull() ?: return
        val decoded = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() ?: return
        // Widgets travel through Binder: keep the picture small enough.
        val scaled = scale(decoded, 640)
        runCatching {
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 72, out)
                photoFile(context).writeBytes(out.toByteArray())
            }
            if (scaled != decoded) decoded.recycle()
        }
    }

    private fun scale(bitmap: Bitmap, maxWidth: Int): Bitmap {
        if (bitmap.width <= maxWidth) return bitmap
        val ratio = maxWidth.toFloat() / bitmap.width
        return Bitmap.createScaledBitmap(bitmap, maxWidth, (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
    }

    private fun clearPhoto(context: Context) {
        runCatching { photoFile(context).delete() }
    }

    /** Decodes the cached snapshot for the widget, or null when there is none. */
    fun photo(context: Context): Bitmap? {
        val file = photoFile(context)
        if (!file.exists()) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    fun photoTimestamp(context: Context): Long = runCatching { photoFile(context).lastModified() }.getOrDefault(0L)

    suspend fun toggleLight(context: Context, id: String) {
        val light = state(context).lights.firstOrNull { it.id == id } ?: return
        runCatching { api(context).controlLight(id, com.ferforastieri.valkyris.core.model.LightStatePatch(power = !light.power)) }
        save(
            context,
            state(context).copy(
                lights = state(context).lights.map { if (it.id == id) it.copy(power = !light.power) else it },
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun sendKey(context: Context, key: String) {
        val remote = state(context).remotes.firstOrNull() ?: return
        runCatching { api(context).sendRemoteKey(remote.id, key) }
    }

    suspend fun toggleRemotePower(context: Context) {
        val remote = state(context).remotes.firstOrNull() ?: return
        runCatching { api(context).powerRemote(remote.id) }
    }
}

/** Renders every placed widget from the cached snapshot. */
internal fun refreshWidgetViews(context: Context) {
    val manager = AppWidgetManager.getInstance(context)
    listOf(LightsWidgetProvider::class.java, CameraWidgetProvider::class.java, RemoteWidgetProvider::class.java).forEach { provider ->
        val ids = manager.getAppWidgetIds(ComponentName(context, provider))
        if (ids.isNotEmpty()) {
            val intent = Intent(context, provider).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            context.sendBroadcast(intent)
        }
    }
}

/**
 * Common scheduling: while at least one widget is on the home screen the state
 * is refreshed in the background every fifteen minutes, and every tap can ask
 * for an immediate refresh.
 */
abstract class ValkyrisWidgetProvider : AppWidgetProvider() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        scheduleRefresh(context)
        requestRefresh(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        cancelRefresh(context)
    }

    companion object {
        const val ACTION_ALARM = "com.ferforastieri.valkyris.widget.ALARM"
        private const val INTERVAL = AlarmManager.INTERVAL_FIFTEEN_MINUTES

        /** Refreshes right away, used when a widget appears or its data is old. */
        fun requestRefresh(context: Context) {
            context.sendBroadcast(Intent(context, WidgetActionReceiver::class.java).setAction(WidgetActionReceiver.ACTION_REFRESH))
        }

        fun scheduleRefresh(context: Context) {
            val manager = context.getSystemService(AlarmManager::class.java) ?: return
            manager.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 60_000L, INTERVAL, alarmIntent(context))
        }

        fun cancelRefresh(context: Context) {
            val manager = context.getSystemService(AlarmManager::class.java) ?: return
            manager.cancel(alarmIntent(context))
        }

        private fun alarmIntent(context: Context): PendingIntent {
            val intent = Intent(context, WidgetActionReceiver::class.java).setAction(ACTION_ALARM)
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        fun pendingRefresh(context: Context, requestCode: Int): PendingIntent {
            val intent = Intent(context, WidgetActionReceiver::class.java).setAction(WidgetActionReceiver.ACTION_REFRESH)
            return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        fun pendingToggleLight(context: Context, id: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, WidgetActionReceiver::class.java).setAction(WidgetActionReceiver.ACTION_TOGGLE_LIGHT).putExtra(WidgetActionReceiver.EXTRA_ID, id)
            return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        fun pendingRemoteKey(context: Context, key: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, WidgetActionReceiver::class.java).setAction(WidgetActionReceiver.ACTION_REMOTE_KEY).putExtra(WidgetActionReceiver.EXTRA_KEY, key)
            return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        fun pendingOpenApp(context: Context, section: String?, requestCode: Int): PendingIntent {
            val launcher = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
            val intent = Intent(launcher).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).setPackage(context.packageName)
            section?.let { intent.putExtra(EXTRA_SECTION, it) }
            return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        const val EXTRA_SECTION = "section"
    }
}
