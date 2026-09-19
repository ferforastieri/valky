package com.ferforastieri.valkyris.feature.people

import android.location.Location

internal fun shouldSendLocation(
    location: Location, now: Long, lastFixAt: Long, lastSentAt: Long,
    confirmUntil: Long, moved: Boolean, force: Boolean,
): Boolean {
    if (!location.hasAccuracy() || !location.accuracy.isFinite() || location.accuracy <= 0 || location.accuracy > 200) return false
    if (!location.latitude.isFinite() || !location.longitude.isFinite() || location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return false
    if (location.time < now - 120_000 || location.time > now + 30_000 || location.time <= lastFixAt) return false
    if (force || lastSentAt == 0L || now - lastSentAt >= 15 * 60_000) return true
    if (now - lastSentAt < 55_000) return false
    return now < confirmUntil || moved
}
