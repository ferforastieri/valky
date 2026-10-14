package com.ferforastieri.valkyris.feature.lights

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
import androidx.compose.ui.draw.scale
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.*
import com.ferforastieri.valkyris.R
import com.ferforastieri.valkyris.core.design.onColorFor
import com.ferforastieri.valkyris.core.design.warmWhiteColor
import com.ferforastieri.valkyris.core.model.LightColor
import com.ferforastieri.valkyris.core.model.LightDevice
import com.ferforastieri.valkyris.core.model.LightStatePatch

/**
 * Página da lâmpada em tela cheia: cabeçalho com o nome e o liga/desliga, e um
 * painel com as abas "Cor" e "Branco", blocos arrastáveis e o brilho.
 */
@Composable
fun LightDetailPage(
    light: LightDevice,
    busy: Boolean,
    onDismiss: () -> Unit,
    onChange: (LightStatePatch) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            LightDetailContent(light, busy, onDismiss, onChange)
        }
    }
}

@Composable
internal fun LightDetailContent(
    light: LightDevice,
    busy: Boolean,
    onDismiss: () -> Unit,
    onChange: (LightStatePatch) -> Unit,
) {
    var brightness by remember(light.id) { mutableFloatStateOf(light.state.brightness.coerceIn(1, 100).toFloat()) }
    var temperature by remember(light.id) { mutableFloatStateOf(light.state.temperatureKelvin.coerceIn(2700, 6500).toFloat()) }
    var hue by remember(light.id) { mutableFloatStateOf(light.state.color.hue.coerceIn(0, 360).toFloat()) }
    var saturation by remember(light.id) { mutableFloatStateOf(light.state.color.saturation.coerceIn(0, 100).toFloat()) }
    // 0 = Cor, 1 = Branco
    var tab by remember(light.id) { mutableIntStateOf(if (light.state.mode == "color") 0 else 1) }
    val on = light.state.power
    val reachable = light.state.online && !busy
    val aura = if (light.state.mode == "color") {
        Color.hsv(hue, saturation / 100f, (brightness / 100f).coerceAtLeast(.45f))
    } else {
        warmWhiteColor(temperature.toInt())
    }
    // Mexer em cor, branco ou brilho também acende a lâmpada: sem isso, escolher
    // uma cor numa luz apagada não muda nada (parecia que ela tinha "apagado").
    fun apply(patch: LightStatePatch) = onChange(if (light.state.power) patch else patch.copy(power = true))

    Column(Modifier.fillMaxSize()) {
        // Cabeçalho sobre um fundo ambiente na cor da lâmpada.
        Box(
            Modifier.fillMaxWidth().height(232.dp)
                .background(Brush.verticalGradient(listOf(aura.copy(alpha = .55f), MaterialTheme.colorScheme.surface))),
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
                Spacer(Modifier.height(30.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(
                        if (on) Lucide.Lightbulb else Lucide.LightbulbOff,
                        null,
                        Modifier.size(28.dp),
                        tint = if (on) onColorFor(aura) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(light.name, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    IconButton(onClick = onDismiss) { Icon(Lucide.X, stringResource(R.string.close)) }
                }
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.light_power_label), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Switch(
                    checked = on,
                    onCheckedChange = { onChange(LightStatePatch(power = it)) },
                    enabled = reachable,
                    modifier = Modifier.scale(1.18f),
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.secondary,
                        checkedThumbColor = Color.White,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                    ),
                )
                Spacer(Modifier.height(20.dp))
            }
        }
        // Painel de ajustes.
        Surface(
            Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
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
                    DetailTab(stringResource(R.string.light_tab_color), tab == 0, light.capabilities.color, Modifier.weight(1f)) { tab = 0; if (saturation < 5f) saturation = 100f }
                    DetailTab(stringResource(R.string.light_tab_white), tab == 1, light.capabilities.brightness || light.capabilities.colorTemperature, Modifier.weight(1f)) { tab = 1 }
                }
                if (!light.state.online) Text(stringResource(R.string.unavailable), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (tab == 0) {
                    // Sem sugestões: o bloco de matiz é o controle. Ao arrastar a
                    // saturação vai a 100 — sem isso, uma lâmpada que estava no
                    // branco (saturação 0) continuaria branca e a cor não aparecia.
                    GradientBar(
                        brush = Brush.horizontalGradient(hueSpectrum),
                        value = hue / 360f,
                        enabled = reachable,
                        height = 170.dp,
                        badge = stringResource(R.string.light_hue_badge, hue.toInt()),
                        onChange = { hue = it * 360f; saturation = 100f },
                        onFinished = { apply(LightStatePatch(color = LightColor(hue.toInt(), saturation.toInt().coerceAtLeast(100), brightness.toInt()))) },
                    )
                } else {
                    GradientBar(
                        brush = Brush.horizontalGradient(listOf(warmWhiteColor(2700), warmWhiteColor(4000), warmWhiteColor(6500))),
                        value = (temperature - 2700f) / 3800f,
                        enabled = reachable,
                        height = 170.dp,
                        badge = stringResource(R.string.light_kelvin_badge, temperature.toInt()),
                        onChange = { temperature = 2700f + it * 3800f },
                        onFinished = { apply(LightStatePatch(temperatureKelvin = temperature.toInt())) },
                    )
                }
                Text(stringResource(R.string.light_brightness), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                GradientBar(
                    brush = Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, aura)),
                    value = (brightness - 1f) / 99f,
                    enabled = reachable,
                    height = 66.dp,
                    onChange = { brightness = (1f + it * 99f).coerceIn(1f, 100f) },
                    onFinished = { apply(LightStatePatch(brightness = brightness.toInt())) },
                    overlay = {
                        Row(Modifier.align(Alignment.CenterStart).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Lucide.Sun, null, Modifier.size(22.dp), tint = Color.White.copy(alpha = .92f))
                            Spacer(Modifier.width(8.dp))
                            Text("${brightness.toInt()}%", color = Color.White, fontWeight = FontWeight.SemiBold)
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
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
    ) {
        Text(
            label,
            Modifier.padding(vertical = 14.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .4f)
                selected -> MaterialTheme.colorScheme.secondary
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
 * Bloco em gradiente com um indicador arrastável (ou toque direto). O valor
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
