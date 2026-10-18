package com.ferforastieri.valky.core.design

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.ferforastieri.valky.R

@Composable
fun ValkyMark(modifier: Modifier = Modifier) {
    // A marca oficial (escudo com asas e gema) vive em vetor, a mesma do ícone
    // do aplicativo, do painel e da landing: uma fonte só para a marca.
    Image(
        painter = painterResource(R.drawable.valky_symbol),
        contentDescription = null,
        modifier = modifier,
    )
}
