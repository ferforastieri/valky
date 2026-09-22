package com.ferforastieri.valkyris.feature.devices

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.ferforastieri.valkyris.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ferforastieri.valkyris.feature.cameras.CamerasScreen
import com.ferforastieri.valkyris.feature.lights.LightsScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(admin:Boolean,onCamera:(String)->Unit,initialPage:Int=0){
    var page by remember{mutableIntStateOf(initialPage)}
    Column(Modifier.fillMaxSize()){
        PrimaryTabRow(selectedTabIndex=page,modifier=Modifier.padding(horizontal=18.dp)){
            Tab(page==0,{page=0},text={Text(stringResource(R.string.cameras))})
            Tab(page==1,{page=1},text={Text(stringResource(R.string.lighting))})
        }
        Box(Modifier.weight(1f)){if(page==0)CamerasScreen(onCamera=onCamera,canManage=admin)else LightsScreen(admin=admin)}
    }
}
