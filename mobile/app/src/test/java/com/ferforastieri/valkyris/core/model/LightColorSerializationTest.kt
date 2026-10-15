package com.ferforastieri.valkyris.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cor precisa ir sempre completa no corpo da requisição. O serializador do
 * app omite campos iguais ao valor padrão, e a saturação padrão (100) era a que
 * sumia — a lâmpada recebia saturação 0 e ficava branca.
 */
class LightColorSerializationTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    @Test
    fun colourPatchCarriesEveryField() {
        val body = json.encodeToString(
            LightStatePatch.serializer(),
            LightStatePatch(color = LightColor(hue = 200, saturation = 100, value = 60)),
        )
        assertTrue("saturação sumiu: $body", body.contains("\"saturation\":100"))
        assertTrue("matiz sumiu: $body", body.contains("\"hue\":200"))
        assertTrue("valor sumiu: $body", body.contains("\"value\":60"))
    }

    @Test
    fun redHueZeroIsNotDropped() {
        val body = json.encodeToString(
            LightStatePatch.serializer(),
            LightStatePatch(color = LightColor(hue = 0, saturation = 100, value = 100)),
        )
        assertTrue("matiz zero foi omitido: $body", body.contains("\"hue\":0"))
    }
}
