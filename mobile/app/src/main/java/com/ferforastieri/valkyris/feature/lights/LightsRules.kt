package com.ferforastieri.valkyris.feature.lights

import com.ferforastieri.valkyris.core.model.LightCandidate
import com.ferforastieri.valkyris.core.model.LightDevice

/** A local key is always sixteen characters; the backend enforces the same rule. */
internal fun lightKeyValid(key: String): Boolean = key.trim().length == 16

/**
 * Candidates that are not registered yet. A bulb answers with its identifier or,
 * when the firmware hides it, only with an address: both are used to tell an
 * already registered bulb apart so the list never offers the same device twice.
 */
internal fun unregisteredCandidates(candidates: List<LightCandidate>, lights: List<LightDevice>): List<LightCandidate> =
    candidates.filterNot { candidate ->
        lights.any { light ->
            (candidate.deviceId.isNotBlank() && light.deviceId == candidate.deviceId) ||
                (light.lastAddress.isNotBlank() && light.lastAddress == candidate.address)
        }
    }
