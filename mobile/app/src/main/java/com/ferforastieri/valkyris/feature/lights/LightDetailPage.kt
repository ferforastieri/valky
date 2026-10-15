package com.ferforastieri.valkyris.feature.lights

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.*
import com.ferforastieri.valkyris.R
import androidx.compose.runtime.LaunchedEffect
import com.ferforastieri.valkyris.core.design.ValkyrisTopBar
import com.ferforastieri.valkyris.core.design.AnimatedBulbIcon
import com.ferforastieri.valkyris.core.design.warmWhiteColor
import com.ferforastieri.valkyris.core.model.LightColor
import com.ferforastieri.valkyris.core.model.LightDevice
import com.ferforastieri.valkyris.core.model.LightStatePatch

/**
 * Página da lâmpada: uma tela de verdade, com a barra de navegação do app
 * embaixo e o cabeçalho no padrão da aplicação. O painel tem as abas Cor e
 * Branco, blocos de seleção livres (duas dimensões) e o brilho — que é
 * independente em cada modo.
 */
@Composable
fun LightDetailScreen(
    lightId: String,
    onBack: () -> Unit,
    onNotifications: () -> Unit = {},
    unreadNotifications: Int = 0,
    vm: LightDetailViewModel = hiltViewModel(),
) {
    val lights by vm.lights.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose {} }
    val light = lights.firstOrNull { it.id == lightId }
    val missing by vm.missing.collectAsStateWithLifecycle()
    if (light == null) {
        // Pode ser o primeiro toque, vindo do widget: busca antes de desistir.
        LaunchedEffect(lightId) { vm.ensure(lightId) }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (missing) Text(stringResource(R.string.light_missing), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else CircularProgressIndicator()
        }
        return
    }
    LightDetailContent(light, busy, onBack, onNotifications, unreadNotifications) { vm.control(light.id, it) }
}

@Composable
internal fun LightDetailContent(
    light: LightDevice,
    busy: Boolean,
    onDismiss: () -> Unit,
    onNotifications: () -> Unit = {},
    unreadNotifications: Int = 0,
    onChange: (LightStatePatch) -> Unit,
) {
    // O brilho do branco (ponto 22) e o brilho da cor (o valor dentro da cor)
    // são independentes: mexer num não pode derrubar o outro modo.
    var whiteBrightness by remember(light.id) { mutableFloatStateOf(light.state.brightness.coerceIn(1, 100).toFloat()) }
    var colorValue by remember(light.id) { mutableFloatStateOf(light.state.color.value.coerceIn(1, 100).toFloat()) }
    var temperature by remember(light.id) { mutableFloatStateOf(light.state.temperatureKelvin.coerceIn(2700, 6500).toFloat()) }
    var hue by remember(light.id) { mutableFloatStateOf(light.state.color.hue.coerceIn(0, 360).toFloat()) }
    var saturation by remember(light.id) { mutableFloatStateOf(light.state.color.saturation.coerceIn(1, 100).toFloat()) }
    // 0 = Cor, 1 = Branco
    var tab by remember(light.id) { mutableIntStateOf(if (light.state.mode == "color") 0 else 1) }
    val colorTab = tab == 0
    val on = light.state.power
    val reachable = light.state.online && !busy
    val aura = if (colorTab) {
        Color.hsv(hue, saturation / 100f, (colorValue / 100f).coerceAtLeast(.25f))
    } else {
        warmWhiteColor(temperature.toInt())
    }
    // Mexer em cor, branco ou brilho também acende a lâmpada.
    fun apply(patch: LightStatePatch) = onChange(if (light.state.power) patch else patch.copy(power = true))

    Column(Modifier.fillMaxSize()) {
        // Mesma barra das outras telas do app (marca, título e sino), com o X
        // para fechar — antes esta tela tinha um cabeçalho próprio.
        ValkyrisTopBar(
            title = light.name,
            onNotifications = onNotifications,
            unreadNotifications = unreadNotifications,
            actions = { IconButton(onClick = onDismiss) { Icon(Lucide.X, stringResource(R.string.close)) } },
        )
        // Energia num cartão igual aos outros do app.
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.light_power_label), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(if (on) R.string.favorite_light_on else R.string.favorite_light_off),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = on,
                    onCheckedChange = { onChange(LightStatePatch(power = it)) },
                    enabled = reachable,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.secondary,
                        checkedThumbColor = MaterialTheme.colorScheme.onSecondary,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                    ),
                )
            }
        }
        // Painel de ajustes.
        Surface(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 18.dp, vertical = 6.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DetailTab(stringResource(R.string.light_tab_color), colorTab, light.capabilities.color, Modifier.weight(1f)) { tab = 0 }
                    DetailTab(stringResource(R.string.light_tab_white), !colorTab, light.capabilities.brightness || light.capabilities.colorTemperature, Modifier.weight(1f)) { tab = 1 }
                }
                if (!light.state.online) Text(stringResource(R.string.unavailable), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (colorTab) {
                    // Livre: X = matiz, Y = saturação.
                    FreeBlock(
                        horizontal = Brush.horizontalGradient(hueSpectrum),
                        overlay = Brush.verticalGradient(listOf(Color.Transparent, Color.White)),
                        x = hue / 360f,
                        y = 1f - saturation / 100f,
                        enabled = reachable,
                        badge = stringResource(R.string.light_hue_badge, hue.toInt()),
                        onPick = { px, py -> hue = px * 360f; saturation = ((1f - py) * 100f).coerceAtLeast(1f) },
                        onFinished = { apply(LightStatePatch(mode = "color", color = LightColor(hue.toInt(), saturation.toInt(), colorValue.toInt()))) },
                    )
                } else {
                    // Livre: X = temperatura do branco, Y = brilho do branco.
                    FreeBlock(
                        horizontal = Brush.horizontalGradient(listOf(warmWhiteColor(2700), warmWhiteColor(4000), warmWhiteColor(6500))),
                        overlay = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .82f))),
                        x = (temperature - 2700f) / 3800f,
                        y = 1f - (whiteBrightness - 1f) / 99f,
                        enabled = reachable,
                        badge = stringResource(R.string.light_kelvin_badge, temperature.toInt()),
                        onPick = { px, _ -> temperature = 2700f + px * 3800f },
                        onFinished = { apply(LightStatePatch(mode = "white", temperatureKelvin = temperature.toInt(), brightness = whiteBrightness.toInt())) },
                    )
                }
                Text(stringResource(R.string.light_brightness), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                // O brilho edita o valor do modo escolhido: na cor, o próprio
                // valor da cor (sem voltar para o branco); no branco, o ponto 22.
                val shown = if (colorTab) colorValue else whiteBrightness
                GradientBar(
                    brush = Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.surface, aura)),
                    value = (shown - 1f) / 99f,
                    enabled = reachable,
                    height = 66.dp,
                    onChange = { fraction -> val pct = (1f + fraction * 99f).coerceIn(1f, 100f); if (colorTab) colorValue = pct else whiteBrightness = pct },
                    onFinished = {
                        if (colorTab) apply(LightStatePatch(mode = "color", color = LightColor(hue.toInt(), saturation.toInt(), colorValue.toInt())))
                        else apply(LightStatePatch(mode = "white", brightness = whiteBrightness.toInt()))
                    },
                    overlay = {
                        Row(Modifier.align(Alignment.CenterStart).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Lucide.Sun, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Text("${shown.toInt()}%", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun DetailTab(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
    ) {
        Text(
            label,
            Modifier.padding(vertical = 14.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .4f)
                selected -> MaterialTheme.colorScheme.onSecondaryContainer
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

private val hueSpectrum = listOf(
    Color.hsv(0f, 1f, 1f), Color.hsv(60f, 1f, 1f), Color.hsv(120f, 1f, 1f),
    Color.hsv(180f, 1f, 1f), Color.hsv(240f, 1f, 1f), Color.hsv(300f, 1f, 1f), Color.hsv(360f, 1f, 1f),
)

/**
 * Bloco de seleção livre: o toque ou o arrasto escolhe as duas coordenadas
 * (0..1 cada). Em cima entra o gradiente do eixo X e por cima dele o véu do
 * eixo Y, então qualquer ponto do bloco é uma combinação válida.
 */
@Composable
internal fun FreeBlock(
    horizontal: Brush,
    overlay: Brush,
    x: Float,
    y: Float,
    enabled: Boolean,
    onPick: (Float, Float) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    badge: String? = null,
) {
    var widthPx by remember { mutableFloatStateOf(1f) }
    var heightPx by remember { mutableFloatStateOf(1f) }
    Box(
        modifier.fillMaxWidth().height(height)
            .clip(RoundedCornerShape(18.dp))
            .background(horizontal)
            .onSizeChanged {
                widthPx = it.width.toFloat().coerceAtLeast(1f)
                heightPx = it.height.toFloat().coerceAtLeast(1f)
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    onPick((offset.x / widthPx).coerceIn(0f, 1f), (offset.y / heightPx).coerceIn(0f, 1f))
                    onFinished()
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(onDragEnd = { onFinished() }, onDragCancel = { onFinished() }) { change, _ ->
                    change.consume()
                    onPick((change.position.x / widthPx).coerceIn(0f, 1f), (change.position.y / heightPx).coerceIn(0f, 1f))
                }
            },
    ) {
        Box(Modifier.matchParentSize().background(overlay))
        Canvas(Modifier.fillMaxSize()) {
            val radius = 14.dp.toPx()
            val px = (x.coerceIn(0f, 1f) * size.width).coerceIn(radius, size.width - radius)
            val py = (y.coerceIn(0f, 1f) * size.height).coerceIn(radius, size.height - radius)
            val center = Offset(px, py)
            drawCircle(Color.White, radius = radius, center = center)
            drawCircle(Color.Black.copy(alpha = .20f), radius = radius, center = center, style = Stroke(2f))
        }
        badge?.let {
            Surface(
                Modifier.align(Alignment.TopEnd).padding(10.dp),
                shape = RoundedCornerShape(50),
                color = Color.Black.copy(alpha = .30f),
            ) { Text(it, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = Color.White) }
        }
        if (!enabled) Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.surface.copy(alpha = .35f)))
    }
}

/**
 * Barra em gradiente com um indicador arrastável (ou toque direto). O valor
 * chega em 0..1 e o rótulo mostra a unidade do ajuste.
 */
@Composable
internal fun GradientBar(
    brush: Brush,
    value: Float,
    enabled: Boolean,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 58.dp,
    badge: String? = null,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
) {
    var widthPx by remember { mutableFloatStateOf(1f) }
    Box(
        modifier.fillMaxWidth().height(height)
            .clip(RoundedCornerShape(16.dp))
            .background(brush)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    onChange((offset.x / widthPx).coerceIn(0f, 1f))
                    onFinished()
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(onDragEnd = { onFinished() }, onDragCancel = { onFinished() }) { change, _ ->
                    change.consume()
                    onChange((change.position.x / widthPx).coerceIn(0f, 1f))
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = 13.dp.toPx()
            val x = (value * size.width).coerceIn(radius, size.width - radius)
            val center = Offset(x, size.height / 2f)
            drawCircle(Color.White, radius = radius, center = center)
            drawCircle(Color.Black.copy(alpha = .12f), radius = radius, center = center, style = Stroke(1f))
        }
        overlay?.invoke(this)
        badge?.let {
            Surface(
                Modifier.align(Alignment.TopEnd).padding(8.dp),
                shape = RoundedCornerShape(50),
                color = Color.Black.copy(alpha = .28f),
            ) { Text(it, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = Color.White) }
        }
        if (!enabled) Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.surface.copy(alpha = .35f)))
    }
}
