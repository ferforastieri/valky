package com.ferforastieri.valkyris.core.alarm

import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ferforastieri.valkyris.R
import com.ferforastieri.valkyris.core.design.ValkyrisTheme
import com.ferforastieri.valkyris.core.network.ValkyrisApi
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TriangleAlert

@AndroidEntryPoint class AlarmActivity:ComponentActivity(){@Inject lateinit var api:ValkyrisApi;@Inject lateinit var notifier:AlarmNotifier;private var ringtone:Ringtone?=null;private var vibrator:Vibrator?=null;override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);if(Build.VERSION.SDK_INT>=27){setShowWhenLocked(true);setTurnScreenOn(true)}else{@Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)};val eventId=intent.getStringExtra("eventId").orEmpty();val type=intent.getStringExtra("eventType")?:"event";ringtone=RingtoneManager.getRingtone(this,RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)).apply{if(android.os.Build.VERSION.SDK_INT>=28)isLooping=true;play()};vibrator=getSystemService(Vibrator::class.java).apply{vibrate(VibrationEffect.createWaveform(longArrayOf(0,800,400),0))};setContent{ValkyrisTheme{Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.error){Column(Modifier.fillMaxSize().padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Icon(Lucide.TriangleAlert,null,Modifier.size(72.dp),tint=MaterialTheme.colorScheme.onError);Spacer(Modifier.height(24.dp));Text(stringResource(R.string.alarm_title),style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onError);Text(type.replace('_',' ').replaceFirstChar{it.uppercase()},color=MaterialTheme.colorScheme.onError);Spacer(Modifier.height(40.dp));Button({acknowledge(eventId)},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.surface,contentColor=MaterialTheme.colorScheme.onSurface),modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.acknowledge))};TextButton({stopAndFinish()},Modifier.fillMaxWidth()){Text(stringResource(R.string.silence),color=MaterialTheme.colorScheme.onError)}}}}}}
    private fun acknowledge(id:String){lifecycleScope.launch{runCatching{api.acknowledge(id)};notifier.cancel(id);stopAndFinish()}}
    private fun stopAndFinish(){ringtone?.stop();vibrator?.cancel();finishAndRemoveTask()}
    override fun onDestroy(){ringtone?.stop();vibrator?.cancel();super.onDestroy()}}
