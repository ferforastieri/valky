package com.ferforastieri.valkyris.core.model

import androidx.annotation.StringRes
import com.ferforastieri.valkyris.R

@StringRes
fun detectorLabelRes(type: String): Int = when (type.trim().lowercase()) {
    "place_entered" -> R.string.event_place_entered
    "place_exited" -> R.string.event_place_exited
    "motion" -> R.string.detector_motion
    "person" -> R.string.detector_person
    "tamper" -> R.string.detector_tamper
    "baby_cry" -> R.string.detector_baby_cry
    "crying" -> R.string.detector_crying
    "scream" -> R.string.detector_scream
    "glass_break" -> R.string.detector_glass_break
    "smoke_alarm" -> R.string.detector_smoke_alarm
    "fire_alarm" -> R.string.detector_fire_alarm
    "siren" -> R.string.detector_siren
    "doorbell" -> R.string.detector_doorbell
    "knock" -> R.string.detector_knock
    "dog_bark" -> R.string.detector_dog_bark
    else -> R.string.detector_other
}

internal fun locationEventTitle(type: String, personName: String?, placeName: String?): String? {
    val person = personName?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: "Pessoa"
    val place = placeName?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: "uma área"
    return when (type) {
        "place_entered" -> "$person chegou em $place"
        "place_exited" -> "$person saiu de $place"
        else -> null
    }
}
