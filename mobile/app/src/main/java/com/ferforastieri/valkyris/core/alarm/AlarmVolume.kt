package com.ferforastieri.valkyris.core.alarm

import android.content.Context

/**
 * Per-device alarm volume, stored locally so each phone can set its own level.
 * Kept in SharedPreferences so the playback service can read it synchronously.
 */
object AlarmVolume {
    private const val PREFS = "valkyris_alarm"
    private const val KEY = "alarm_volume"
    const val DEFAULT = 100

    fun read(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, DEFAULT).coerceIn(0, 100)

    fun write(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, value.coerceIn(0, 100)).apply()
    }
}
