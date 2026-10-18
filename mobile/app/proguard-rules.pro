# Regras do aplicativo para o build de release (isMinifyEnabled + isShrinkResources).
# As bibliotecas abaixo não trazem regras de consumidor próprias, então os poucos
# pontos que o R8 não enxerga sozinho ficam aqui. Sempre preferir keeps estreitos:
# um "-keep class ** { *; }" genérico infla o APK e ainda esconde erros de R8.

-dontwarn org.conscrypt.**

# org.osmdroid:osmdroid-android:6.1.20
# O ArchiveFileFactory registra os provedores de arquivo offline num
# Map<String, Class<? extends IArchiveFile>> e os cria com Class.newInstance().
# O R8 mantém as classes (aparecem como literais no código), mas pode remover o
# construtor sem argumentos que o newInstance() exige; sem ele, abrir um mapa
# .zip/.sqlite/.mbtiles/.gemf falha (o osmdroid loga o erro e o mapa não abre).
# Os demais pontos de entrada (MapView, GeoPoint, XYTileSource, Configuration)
# são chamados direto do código Kotlin e não precisam de keep.
-keepclassmembers class org.osmdroid.tileprovider.modules.ZipFileArchive { <init>(); }
-keepclassmembers class org.osmdroid.tileprovider.modules.DatabaseFileArchive { <init>(); }
-keepclassmembers class org.osmdroid.tileprovider.modules.MBTilesFileArchive { <init>(); }
-keepclassmembers class org.osmdroid.tileprovider.modules.GEMFFileArchive { <init>(); }
# org.osmdroid.util.** não tem reflexão: só foi encontrada em
# org.metalev.multitouch.controller.MultiTouchController, que consulta métodos de
# android.view.MotionEvent (classe do sistema, nunca ofuscada). Nada a manter ali.

# com.journeyapps:zxing-android-embedded:4.3.0
# O scanner infla o layout zxing_capture.xml, que cita DecoratedBarcodeView,
# BarcodeView e ViewfinderView pelo nome completo: o LayoutInflater constrói essas
# classes por reflexão e o R8 não lê XML. Neste build o aapt2 já gera as regras
# para elas (intermediates/aapt_proguard_file/.../aapt_rules.txt), mas como a
# biblioteca não traz regras de consumidor, mantemos os keeps explícitos para não
# depender desse detalhe do AGP. O CaptureActivity está declarado no nosso
# AndroidManifest e é coberto pela regra gerada a partir do manifesto; as demais
# classes (ScanContract, BarcodeEncoder...) são usadas direto do código.
-keep class com.journeyapps.barcodescanner.DecoratedBarcodeView { <init>(...); }
-keep class com.journeyapps.barcodescanner.BarcodeView { <init>(...); }
-keep class com.journeyapps.barcodescanner.ViewfinderView { <init>(...); }
