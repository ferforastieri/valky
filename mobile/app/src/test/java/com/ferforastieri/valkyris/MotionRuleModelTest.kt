package com.ferforastieri.valkyris

import com.ferforastieri.valkyris.core.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class MotionRuleModelTest {
    private val json = Json { ignoreUnknownKeys = true }
    @Test fun oldRulesRemainUnrestricted() {
        val rule = json.decodeFromString<Rule>("""{"cameraId":"cam","name":"Movement","detectorTypes":["motion"]}""")
        assertNull(rule.motion)
        assertTrue(rule.schedule.days.isEmpty())
        assertEquals(60, rule.cooldownSeconds)
    }
    @Test fun upsertPreservesRegionAndOvernightSchedule() {
        val input = RuleUpsertRequest("cam", "Berço", listOf("motion"), RuleActionsRequest(true,true,false), RuleSchedule(listOf(1),"22:00","06:00","America/Sao_Paulo"), MotionSettings(MotionRegion(.1,.2,.4,.5),20,.03),120)
        val output = json.decodeFromString<RuleUpsertRequest>(json.encodeToString(input))
        assertEquals(input, output)
        assertEquals(20, output.motion?.minDurationSeconds)
        assertEquals("22:00", output.schedule.start)
    }
}
