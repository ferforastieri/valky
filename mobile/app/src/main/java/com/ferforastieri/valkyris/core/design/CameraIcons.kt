package com.ferforastieri.valkyris.core.design

import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.lucide.BedDouble
import com.composables.icons.lucide.BriefcaseBusiness
import com.composables.icons.lucide.Car
import com.composables.icons.lucide.CookingPot
import com.composables.icons.lucide.DoorOpen
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Milk
import com.composables.icons.lucide.Sofa
import com.composables.icons.lucide.Trees
import com.composables.icons.lucide.Video

fun cameraIcon(value: String): ImageVector = when (value) {
    "baby" -> Lucide.Milk
    "bedroom" -> Lucide.BedDouble
    "office" -> Lucide.BriefcaseBusiness
    "entrance" -> Lucide.DoorOpen
    "living_room" -> Lucide.Sofa
    "yard" -> Lucide.Trees
    "garage" -> Lucide.Car
    "kitchen" -> Lucide.CookingPot
    else -> Lucide.Video
}
