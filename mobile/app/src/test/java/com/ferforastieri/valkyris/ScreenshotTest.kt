package com.ferforastieri.valkyris

/**
 * Marca os testes de screenshot (Roborazzi/Robolectric). Eles baixam o runtime
 * do Android na primeira execução, então ficam fora do CI: rodam localmente com
 * `./gradlew :app:recordRoborazziDebug -Pscreenshots`.
 */
interface ScreenshotTest
