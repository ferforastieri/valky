package com.ferforastieri.valkyris.core.design

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Armchair
import com.composables.icons.lucide.Baby
import com.composables.icons.lucide.BedDouble
import com.composables.icons.lucide.Cctv
import com.composables.icons.lucide.ChefHat
import com.composables.icons.lucide.DoorClosedLocked
import com.composables.icons.lucide.Flower2
import com.composables.icons.lucide.HouseHeart
import com.composables.icons.lucide.Laptop
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.PawPrint
import com.composables.icons.lucide.ShowerHead
import com.composables.icons.lucide.Warehouse

fun cameraIcon(value: String): ImageVector = when (value) {
    "nursery" -> Lucide.HouseHeart
    "baby" -> Lucide.Baby
    "bottle" -> BabyBottleIcon
    "dog" -> Lucide.PawPrint
    "bedroom" -> Lucide.BedDouble
    "office" -> Lucide.Laptop
    "entrance" -> Lucide.DoorClosedLocked
    "living_room" -> Lucide.Armchair
    "yard" -> Lucide.Flower2
    "garage" -> Lucide.Warehouse
    "kitchen" -> Lucide.ChefHat
    "bathroom" -> Lucide.ShowerHead
    else -> Lucide.Cctv
}

private val BabyBottleIcon: ImageVector = ImageVector.Builder(
    name = "BabyBottle",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(10f, 2f)
        curveTo(10.4f, 3f, 10.5f, 4f, 10.5f, 5f)
        lineTo(13.5f, 5f)
        curveTo(13.5f, 4f, 13.6f, 3f, 14f, 2f)
        close()
        moveTo(9f, 5f)
        lineTo(15f, 5f)
        lineTo(15f, 8f)
        curveTo(16.4f, 9.2f, 17f, 10.5f, 17f, 12f)
        lineTo(17f, 19f)
        curveTo(17f, 20.7f, 15.7f, 22f, 14f, 22f)
        lineTo(10f, 22f)
        curveTo(8.3f, 22f, 7f, 20.7f, 7f, 19f)
        lineTo(7f, 12f)
        curveTo(7f, 10.5f, 7.6f, 9.2f, 9f, 8f)
        close()
        moveTo(10f, 13f)
        lineTo(13f, 13f)
        moveTo(10f, 17f)
        lineTo(13f, 17f)
    }
}.build()
