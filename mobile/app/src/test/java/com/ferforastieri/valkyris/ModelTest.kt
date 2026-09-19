package com.ferforastieri.valkyris

import com.ferforastieri.valkyris.core.model.Rule
import com.ferforastieri.valkyris.core.model.RuleActions
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    @Test fun alarmAndRecordingCanBeEnabledTogether(){val rule=Rule(cameraId="camera",name="Baby cry",detectorTypes=listOf("baby_cry"),actions=RuleActions(record=true,notify=true,alarm=true));assertTrue(rule.actions.alarm&&rule.actions.record)}

    @Test fun resettingCameraAlertsSendsAnExplicitObject() {
        val input = com.ferforastieri.valkyris.core.model.CreateCameraRequest(name="Camera",host="camera",username="",password="")
        val encoded = kotlinx.serialization.json.Json.encodeToString(com.ferforastieri.valkyris.core.model.CreateCameraRequest.serializer(), input)
        assertTrue(encoded.contains("\"alerts\":{}"))
    }
}

