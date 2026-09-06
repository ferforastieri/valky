package com.ferforastieri.valkyris.feature.rules

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.ferforastieri.valkyris.core.model.MotionRegion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

@Composable
internal fun MotionRegionEditor(
    cameraId: String,
    region: MotionRegion?,
    onRegion: (MotionRegion) -> Unit,
    preview: (suspend (String) -> ByteArray)?,
) {
    var refresh by remember(cameraId) { mutableIntStateOf(0) }
    var bitmap by remember(cameraId) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var loading by remember(cameraId) { mutableStateOf(false) }
    var error by remember(cameraId) { mutableStateOf<String?>(null) }
    val currentOnRegion by rememberUpdatedState(onRegion)
    LaunchedEffect(cameraId, refresh) {
        loading = true
        error = null
        try {
            val bytes = checkNotNull(preview) { "Prévia indisponível" }(cameraId)
            bitmap = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            if (bitmap == null) error = "Não foi possível abrir a imagem da câmera."
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "Não foi possível carregar a imagem. Verifique a câmera e tente novamente."
        } finally {
            loading = false
        }
    }
    Text("Região da imagem", style = MaterialTheme.typography.labelLarge)
    Text("Arraste de um canto ao outro para marcar o berço ou a região a observar. Mantenha o enquadramento fixo; após mover a câmera, selecione a região novamente.", style = MaterialTheme.typography.bodySmall)
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    bitmap?.let { frame ->
        val accent = MaterialTheme.colorScheme.secondary
        var origin by remember(cameraId) { mutableStateOf<Offset?>(null) }
        var draft by remember(cameraId) { mutableStateOf<MotionRegion?>(null) }
        Box(Modifier.fillMaxWidth().aspectRatio(frame.width.toFloat() / frame.height)) {
            Image(frame.asImageBitmap(), "Imagem da câmera para selecionar região", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            Canvas(Modifier.fillMaxSize().pointerInput(cameraId) {
                detectDragGestures(
                    onDragStart = { origin = it; draft = null },
                    onDragCancel = { origin = null; draft = null },
                    onDragEnd = {
                        draft?.takeIf { it.width >= .05 && it.height >= .05 }?.let(currentOnRegion)
                        origin = null; draft = null
                    },
                ) { change, _ ->
                    change.consume()
                    origin?.let { start ->
                        val x1 = (start.x / size.width).coerceIn(0f, 1f)
                        val y1 = (start.y / size.height).coerceIn(0f, 1f)
                        val x2 = (change.position.x / size.width).coerceIn(0f, 1f)
                        val y2 = (change.position.y / size.height).coerceIn(0f, 1f)
                        draft = MotionRegion(min(x1,x2).toDouble(), min(y1,y2).toDouble(), abs(x2-x1).toDouble(), abs(y2-y1).toDouble())
                    }
                }
            }) {
                (draft ?: region)?.let { selected ->
                    val topLeft = Offset((selected.x * size.width).toFloat(), (selected.y * size.height).toFloat())
                    val rectSize = Size((selected.width * size.width).toFloat(), (selected.height * size.height).toFloat())
                    drawRect(accent.copy(alpha = .25f), topLeft, rectSize)
                    drawRect(accent, topLeft, rectSize, style = Stroke(3.dp.toPx()))
                }
            }
        }
    }
    if (region == null) Text("Marque uma região com pelo menos 5% da largura e da altura da imagem para salvar.", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { refresh++ }, enabled = !loading) { Text("Atualizar imagem") }
}
