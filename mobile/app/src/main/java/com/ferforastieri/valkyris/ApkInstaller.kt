package com.ferforastieri.valkyris

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.provider.Settings
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
        ContextCompat.registerReceiver(context, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun download(rawURL: String, version: String) {
        val uri = Uri.parse(rawURL)
        val host = uri.host.orEmpty().lowercase()
        require(uri.scheme == "https" && (host == "github.com" || host.endsWith(".githubusercontent.com"))) {
            "APK download URL is not trusted"
        }
        val safeVersion = version.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "valkyris-$safeVersion.apk")
        if (target.exists()) target.delete()
        val request = DownloadManager.Request(uri)
            .setTitle("Valkyris $version")
            .setDescription(context.getString(R.string.downloading_update))
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(target))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        downloadId = downloads.enqueue(request)
    }

    private fun openDownloadedAPK() {
        downloads.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
            if (!cursor.moveToFirst()) return
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status != DownloadManager.STATUS_SUCCESSFUL) return
            val local = Uri.parse(cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)))
            val content = if (local.scheme == "file") {
                FileProvider.getUriForFile(context, "${context.packageName}.files", File(requireNotNull(local.path)))
            } else local
            pendingInstall = content
            resumePendingInstall()
        }
    }

    fun resumePendingInstall() {
        val apk = pendingInstall ?: return
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            return
        }
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apk, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        pendingInstall = null
    }

    override fun close() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    private companion object { const val APK_MIME = "application/vnd.android.package-archive" }
}
