package com.ferforastieri.valky

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import com.ferforastieri.valky.core.design.ValkyTheme
import com.ferforastieri.valky.navigation.ValkyApp
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private lateinit var apkInstaller: ApkInstaller

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        apkInstaller = ApkInstaller(this)
        requestBlePermissions()
        viewModel.acceptLaunch(intent?.data, intent?.getStringExtra("eventId"), intent?.getStringExtra("cameraId"), intent?.getStringExtra("section"))
        enableEdgeToEdge()
        setContent {
            val theme by viewModel.theme.collectAsStateWithLifecycle()
            ValkyTheme(theme) {
                LaunchedEffect(Unit) {
                    viewModel.apkDownloads.collect { apkInstaller.download(it.url, it.version) }
                }
                ValkyApp(viewModel)
            }
        }
    }

    private fun requestBlePermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val missing = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 0x42)
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
        viewModel.acceptLaunch(intent.data, intent.getStringExtra("eventId"), intent.getStringExtra("cameraId"), intent.getStringExtra("section"))
    }
}
