package com.ferforastieri.valkyris.core.design

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Let cards and actions use another line when enlarged text needs more room. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdaptiveRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(10.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = horizontalArrangement,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            maxItemsInEachRow = if (fontScale >= 1.3f || maxWidth < 320.dp) 1 else Int.MAX_VALUE,
        ) { content() }
    }
}
