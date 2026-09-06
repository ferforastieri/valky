package com.ferforastieri.valkyris.core.design

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.UserRound
import java.io.ByteArrayOutputStream

private const val avatarPrefix = "data:image/jpeg;base64,"

@Composable
fun ProfileAvatar(avatarData: String, contentDescription: String?, modifier: Modifier = Modifier) {
    val bitmap = remember(avatarData) { avatarBitmap(avatarData) }
    Surface(modifier = modifier, shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
        Box(contentAlignment = Alignment.Center) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                androidx.compose.material3.Icon(
                    Lucide.UserRound,
                    contentDescription = contentDescription,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

fun profileMarkerDrawable(context: Context, avatarData: String) = avatarBitmap(avatarData)?.let { bitmap ->
    val size = (48 * context.resources.displayMetrics.density).toInt()
    RoundedBitmapDrawableFactory.create(
        context.resources,
        Bitmap.createScaledBitmap(bitmap, size, size, true),
    ).apply { isCircular = true }
}

fun encodeProfileAvatar(context: Context, uri: Uri): String? = runCatching {
    val source = context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) ?: return null
    val largestSide = maxOf(source.width, source.height).coerceAtLeast(1)
    val scaled = if (largestSide > 512) {
        val scale = 512f / largestSide
        Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true)
    } else source
    val bytes = ByteArrayOutputStream().use { output ->
        check(scaled.compress(Bitmap.CompressFormat.JPEG, 82, output))
        output.toByteArray()
    }
    if (bytes.size > 220_000) return null
    avatarPrefix + Base64.encodeToString(bytes, Base64.NO_WRAP)
}.getOrNull()

private fun avatarBitmap(value: String): Bitmap? = runCatching {
    if (!value.startsWith(avatarPrefix)) return null
    val bytes = Base64.decode(value.removePrefix(avatarPrefix), Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()
