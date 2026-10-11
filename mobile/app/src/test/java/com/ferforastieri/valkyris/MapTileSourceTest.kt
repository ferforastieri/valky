package com.ferforastieri.valkyris

import com.ferforastieri.valkyris.core.design.MapVisualStyle
import com.ferforastieri.valkyris.core.design.valkyrisTileSource
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.MapTileIndex
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Guards the map tile URLs: a malformed base URL or a wrong tile size leaves the
// Android map as an empty grid, which is hard to notice in unit tests otherwise.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MapTileSourceTest {
    private fun style(dark: Boolean) = MapVisualStyle(accent = 0, surface = 0, foreground = 0, outline = 0, dark = dark)

    @Test
    fun lightTilesUseCartoVoyager() {
        val source = valkyrisTileSource(style(dark = false))
        assertEquals(
            "https://basemaps.cartocdn.com/rastertiles/voyager/13/3035/4649.png",
            source.getTileURLString(MapTileIndex.getTileIndex(13, 3035, 4649)),
        )
    }

    @Test
    fun darkTilesUseCartoDark() {
        val source = valkyrisTileSource(style(dark = true))
        assertEquals(
            "https://basemaps.cartocdn.com/dark_all/13/3035/4649.png",
            source.getTileURLString(MapTileIndex.getTileIndex(13, 3035, 4649)),
        )
    }
}
