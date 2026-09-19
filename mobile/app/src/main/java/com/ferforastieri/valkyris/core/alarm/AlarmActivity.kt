package com.ferforastieri.valkyris.core.alarm

import android.os.Bundle
import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ferforastieri.valkyris.R
import com.ferforastieri.valkyris.core.design.ValkyrisTheme
import com.ferforastieri.valkyris.core.model.detectorLabelRes
import com.ferforastieri.valkyris.core.network.ValkyrisApi
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TriangleAlert

@AndroidEntryPoint
class AlarmActivity : ComponentActivity() {
    @Inject lateinit var api: ValkyrisApi
    @Inject lateinit var notifier: AlarmNotifier
    @Inject lateinit var preferences: com.ferforastieri.valkyris.core.preferences.AppPreferences
    private var saving by mutableStateOf(false)
    private var failed by mutableStateOf(false)
    private val eventId get() = intent.getStringExtra("eventId").orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        setContent {
            val theme by preferences.theme.collectAsState(initial = "system")
            ValkyrisTheme(mode = theme) {
                BackHandler { silence() }
                AlarmContent(
                    title = intent.getStringExtra("alarmTitle").orEmpty().ifBlank { getString(R.string.alarm_title) },
                    body = intent.getStringExtra("alarmBody").orEmpty().ifBlank {
                        getString(detectorLabelRes(intent.getStringExtra("eventType") ?: "event"))
                    },
                    cameraName = intent.getStringExtra("cameraName").orEmpty(),
                    saving = saving, failed = failed,
                    onAcknowledge = ::acknowledge, onSilence = ::silence,
                )
            }
        }
    }

    private fun acknowledge() {
        if (saving) return
        AlarmPlaybackService.stop(this, eventId)
        saving = true
        failed = false
        lifecycleScope.launch {
            runCatching { api.acknowledge(eventId) }
                .onSuccess { notifier.cancel(eventId); finishAndRemoveTask() }
                .onFailure { failed = true }
            saving = false
        }
    }

    private fun silence() {
        AlarmPlaybackService.stop(this, eventId)
        finishAndRemoveTask()
    }
}

@Composable
internal fun AlarmContent(
    title: String, body: String, cameraName: String,
    saving: Boolean = false, failed: Boolean = false,
    onAcknowledge: () -> Unit = {}, onSilence: () -> Unit = {},
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer) {
                    Icon(Lucide.TriangleAlert, null, Modifier.padding(20.dp).size(40.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                }
                Text(stringResource(R.string.alarm_title), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                Text(title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                if (cameraName.isNotBlank()) Text(cameraName, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Text(body, Modifier.fillMaxWidth().padding(20.dp), style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center)
                }
                if (failed) Text(stringResource(R.string.alarm_ack_failed), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onAcknowledge, Modifier.fillMaxWidth(), enabled = !saving) {
                        Text(stringResource(R.string.acknowledge), textAlign = TextAlign.Center)
                    }
                    OutlinedButton(onSilence, Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.silence), textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}
