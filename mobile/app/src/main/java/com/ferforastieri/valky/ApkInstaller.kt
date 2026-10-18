package com.ferforastieri.valky

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

class ApkInstaller(private val context: Context) : AutoCloseable {
    private val downloads = context.getSystemService(DownloadManager::class.java)
    private var downloadId = -1L
    private var pendingInstall: Uri? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) == downloadId) openDownloadedAPK()
        }
    }

    init {
        // O registro é feito no onCreate do MainActivity: uma falha aqui não pode
        // fechar o app, no pior caso o instalador não abre sozinho ao terminar.
        runCatching {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onFailure { Log.w("ValkyUpdate", "Download receiver unavailable: ${it.javaClass.simpleName}") }
    }

    fun download(rawURL: String, version: String) {
        val uri = Uri.parse(rawURL)
        val host = uri.host.orEmpty().lowercase()
        // Antes isto era um require(): a URL vem do servidor e a exceção estourava
        // dentro do LaunchedEffect do MainActivity, derrubando o app. Agora apenas
        // ignora o download e registra o aviso.
        if (uri.scheme != "https" || (host != "github.com" && !host.endsWith(".githubusercontent.com"))) {
            Log.w("ValkyUpdate", "APK download URL is not trusted: ${uri.scheme}://$host")
            return
        }
        val safeVersion = version.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "valky-$safeVersion.apk")
        if (target.exists()) target.delete()
        val request = DownloadManager.Request(uri)
            .setTitle("Valky $version")
            .setDescription(context.getString(R.string.downloading_update))
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(target))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        runCatching { downloadId = downloads.enqueue(request) }
            .onFailure { Log.w("ValkyUpdate", "Update download could not be queued: ${it.javaClass.simpleName}") }
    }

    private fun openDownloadedAPK() {
        runCatching {
            downloads.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
                if (!cursor.moveToFirst()) return@use
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                if (status != DownloadManager.STATUS_SUCCESSFUL) return@use
                val local = Uri.parse(cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)))
                val content = if (local.scheme == "file") {
                    // O caminho pode vir vazio ou o FileProvider pode recusar o arquivo
                    // (antes o requireNotNull/getUriForFile derrubava o processo no
                    // broadcast de download concluído).
                    val path = local.path ?: return@use
                    runCatching { FileProvider.getUriForFile(context, "${context.packageName}.files", File(path)) }.getOrNull() ?: return@use
                } else local
                pendingInstall = content
                resumePendingInstall()
            }
        }.onFailure { Log.w("ValkyUpdate", "Downloaded APK could not be opened: ${it.javaClass.simpleName}") }
    }

    fun resumePendingInstall() {
        val apk = pendingInstall ?: return
        runCatching {
            if (!context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                return
            }
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apk, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            pendingInstall = null
        }.onFailure {
            // Alguns aparelhos não têm a tela de fontes desconhecidas nem um instalador
            // que aceite o arquivo (ActivityNotFoundException/SecurityException) e isto
            // é chamado do onResume: apenas avisa e tenta de novo no próximo resume.
            Log.w("ValkyUpdate", "Install screen unavailable: ${it.javaClass.simpleName}")
        }
    }

    override fun close() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    private companion object { const val APK_MIME = "application/vnd.android.package-archive" }
}
