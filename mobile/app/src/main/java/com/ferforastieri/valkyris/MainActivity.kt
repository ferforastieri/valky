package com.ferforastieri.valkyris

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import com.ferforastieri.valkyris.core.design.ValkyrisTheme
import com.ferforastieri.valkyris.navigation.ValkyrisApp
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private lateinit var apkInstaller: ApkInstaller

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        setTaskDescription(android.app.ActivityManager.TaskDescription(
            getString(com.ferforastieri.valkyris.R.string.app_name),
            android.graphics.BitmapFactory.decodeResource(resources, com.ferforastieri.valkyris.R.drawable.valkyris_mark),
        ))
        apkInstaller = ApkInstaller(this)
        viewModel.acceptLaunch(intent?.data, intent?.getStringExtra("eventId"), intent?.getStringExtra("cameraId"))
        enableEdgeToEdge()
        setContent {
            val theme by viewModel.theme.collectAsStateWithLifecycle()
            ValkyrisTheme(theme) {
                LaunchedEffect(Unit) {
                    viewModel.apkDownloads.collect { apkInstaller.download(it.url, it.version) }
                }
                ValkyrisApp(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPushRegistration()
        viewModel.checkForUpdates()
        if (::apkInstaller.isInitialized) apkInstaller.resumePendingInstall()
    }

    override fun onDestroy() {
        if (::apkInstaller.isInitialized) apkInstaller.close()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.acceptLaunch(intent.data, intent.getStringExtra("eventId"), intent.getStringExtra("cameraId"))
    }
}
