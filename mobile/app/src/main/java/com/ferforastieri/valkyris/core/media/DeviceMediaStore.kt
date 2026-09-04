package com.ferforastieri.valkyris.core.media

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceMediaStore @Inject constructor(@param:ApplicationContext private val context: Context) {
    suspend fun savePhoto(bytes: ByteArray, name: String) = save(
        bytes = bytes,
        name = name,
        mimeType = "image/jpeg",
        relativeDirectory = Environment.DIRECTORY_PICTURES,
        collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
    )

    suspend fun saveVideo(bytes: ByteArray, name: String) = save(
        bytes = bytes,
        name = name,
        mimeType = "video/mp4",
        relativeDirectory = Environment.DIRECTORY_MOVIES,
        collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
    )

    private suspend fun save(
        bytes: ByteArray,
        name: String,
        mimeType: String,
        relativeDirectory: String,
        collection: android.net.Uri,
    ) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$relativeDirectory/Valkyris")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val directory = File(
                    Environment.getExternalStoragePublicDirectory(relativeDirectory),
                    "Valkyris",
                ).apply { mkdirs() }
                @Suppress("DEPRECATION")
                put(MediaStore.MediaColumns.DATA, File(directory, name).absolutePath)
            }
        }
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(collection, values)) { "MediaStore rejected the file" }
        try {
            checkNotNull(resolver.openOutputStream(uri)) { "MediaStore could not open the file" }.use {
                it.write(bytes)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}
