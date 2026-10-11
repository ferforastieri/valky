package com.ferforastieri.valkyris

import com.ferforastieri.valkyris.core.design.MapVisualStyle
import com.ferforastieri.valkyris.core.design.valkyrisTileSource
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.MapTileIndex
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Guards the map tile URLs: a malformed base URL (osmdroid needs a trailing
// slash) leaves the Android map as an empty grid, and a keyed provider now
// watermarks the tiles. OpenStreetMap is key-free.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MapTileSourceTest {
    private fun style(dark: Boolean) = MapVisualStyle(accent = 0, surface = 0, foreground = 0, outline = 0, dark = dark)

    @Test
    fun tilesUseOpenStreetMapWithoutApiKey() {
        val index = MapTileIndex.getTileIndex(13, 3035, 4649)
        assertEquals(
            "https://tile.openstreetmap.org/13/3035/4649.png",
            valkyrisTileSource(style(dark = false)).getTileURLString(index),
        )
        assertEquals(
            "https://tile.openstreetmap.org/13/3035/4649.png",
            valkyrisTileSource(style(dark = true)).getTileURLString(index),
        )
    }
}
