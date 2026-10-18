package com.ferforastieri.valky.core.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

// Paleta Valky: roxo principal (#806DF8) e secundário (#594DFC), com neutros
// levemente arroxeados para o texto e os fundos continuarem legíveis.
val CanvasLight=Color(0xFFF5F3FC);val SurfaceLight=Color(0xFFFBFAFF);val InkLight=Color(0xFF1A1726);val MutedLight=Color(0xFF6B6580);val SignalLight=Color(0xFF594DFC);val AlarmLight=Color(0xFFDC4A44)
val CanvasDark=Color(0xFF14121C);val SurfaceDark=Color(0xFF1C1926);val InkDark=Color(0xFFF2F0FA);val MutedDark=Color(0xFFA9A3BC);val SignalDark=Color(0xFF9B8CFA);val AlarmDark=Color(0xFFFF766E)
object ColorTokens {
    // Tile dos ícones de destaque. Segue o tema como os outros cartões: claro no
    // tema claro (com ícone roxo) e escuro no escuro — antes era um quadrado
    // preto fixo, que destoava no tema claro.
    val BrandTile:Color @Composable @ReadOnlyComposable get()=MaterialTheme.colorScheme.secondaryContainer
    val OnBrandTile:Color @Composable @ReadOnlyComposable get()=MaterialTheme.colorScheme.secondary
}
private val light=lightColorScheme(
    primary=Color(0xFF806DF8),onPrimary=Color(0xFFFFFFFF),primaryContainer=Color(0xFFE7E2FE),onPrimaryContainer=Color(0xFF241E52),
    secondary=Color(0xFF594DFC),onSecondary=Color(0xFFFFFFFF),secondaryContainer=Color(0xFFDFDBFE),onSecondaryContainer=Color(0xFF1B1550),
    tertiary=MutedLight,onTertiary=Color(0xFFFFFFFF),tertiaryContainer=Color(0xFFEDEAF8),onTertiaryContainer=InkLight,
    error=AlarmLight,onError=Color(0xFFFFFFFF),errorContainer=Color(0xFFFBE1DF),onErrorContainer=Color(0xFF6A1A17),
    background=CanvasLight,onBackground=InkLight,surface=SurfaceLight,onSurface=InkLight,
    surfaceVariant=Color(0xFFEFECFA),onSurfaceVariant=MutedLight,surfaceContainerLow=Color(0xFFF7F5FD),
    outline=Color(0xFFDCD7EE),outlineVariant=Color(0xFFE6E2F3),
)
private val dark=darkColorScheme(
    primary=Color(0xFF9B8CFA),onPrimary=Color(0xFF1A1433),primaryContainer=Color(0xFF322A6B),onPrimaryContainer=Color(0xFFE7E2FE),
    secondary=Color(0xFF8E85FB),onSecondary=Color(0xFF1B1550),secondaryContainer=Color(0xFF2E2775),onSecondaryContainer=Color(0xFFDFDBFE),
    tertiary=MutedDark,onTertiary=Color(0xFF1A1433),tertiaryContainer=Color(0xFF2A2536),onTertiaryContainer=InkDark,
    error=AlarmDark,onError=Color(0xFF32100E),errorContainer=Color(0xFF4C2623),onErrorContainer=Color(0xFFFFDAD6),
    background=CanvasDark,onBackground=InkDark,surface=SurfaceDark,onSurface=InkDark,
    surfaceVariant=Color(0xFF262231),onSurfaceVariant=MutedDark,surfaceContainerLow=Color(0xFF191623),
    outline=Color(0xFF3B3550),outlineVariant=Color(0xFF2C2740),
)
private val typography=Typography(
    headlineLarge=TextStyle(fontSize=40.sp,lineHeight=42.sp,fontWeight=FontWeight.SemiBold,letterSpacing=(-1.2).sp),
    headlineMedium=TextStyle(fontSize=30.sp,lineHeight=34.sp,fontWeight=FontWeight.SemiBold,letterSpacing=(-.7).sp),
    titleLarge=TextStyle(fontSize=22.sp,lineHeight=27.sp,fontWeight=FontWeight.SemiBold,letterSpacing=(-.3).sp),
    titleMedium=TextStyle(fontSize=16.sp,lineHeight=21.sp,fontWeight=FontWeight.SemiBold),
    labelLarge=TextStyle(fontSize=14.sp,lineHeight=18.sp,fontWeight=FontWeight.SemiBold),
)
@Composable
fun ValkyTheme(mode:String="system",content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (mode=="dark"||(mode=="system"&&isSystemInDarkTheme())) dark else light,
        shapes = Shapes(
            small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        ),
        typography = typography,
        content = content,
    )
}
@Composable fun SignalLine(modifier:Modifier=Modifier,color:Color=MaterialTheme.colorScheme.secondary){Canvas(modifier){val p=Path();p.moveTo(0f,size.height*.58f);p.cubicTo(size.width*.12f,size.height*.58f,size.width*.12f,size.height*.18f,size.width*.24f,size.height*.18f);p.cubicTo(size.width*.36f,size.height*.18f,size.width*.34f,size.height*.84f,size.width*.48f,size.height*.84f);p.cubicTo(size.width*.62f,size.height*.84f,size.width*.6f,size.height*.38f,size.width*.72f,size.height*.38f);p.cubicTo(size.width*.84f,size.height*.38f,size.width*.86f,size.height*.58f,size.width,size.height*.58f);drawPath(p,color,style=Stroke(width=4.dp.toPx(),cap=StrokeCap.Round))}}
