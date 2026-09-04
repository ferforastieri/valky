plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("androidx.room")
    id("io.github.takahirom.roborazzi")
}

if (providers.environmentVariable("VALKYRIS_FIREBASE_ENABLED").orNull == "true") {
    apply(plugin = "com.google.gms.google-services")
}

val valkyrisVersionName = providers.environmentVariable("VALKYRIS_VERSION_NAME").orElse("1.0.5")
val valkyrisVersionCode = providers.environmentVariable("VALKYRIS_VERSION_CODE").map(String::toInt).orElse(13)

android {
    namespace = "com.ferforastieri.valkyris"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ferforastieri.valkyris"
        minSdk = 26
        targetSdk = 36
        versionCode = valkyrisVersionCode.get()
        versionName = valkyrisVersionName.get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("release") {
            val storePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!System.getenv("ANDROID_KEYSTORE_PATH").isNullOrBlank()) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging.resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    lint { abortOnError = true; warningsAsErrors = false }
    testOptions.unitTests.isIncludeAndroidResources = true
}

room { schemaDirectory("$projectDir/schemas") }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.08.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.composables:icons-lucide-cmp:2.2.1")
    implementation("androidx.navigation:navigation-compose:2.9.6")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("com.google.dagger:hilt-android:2.60.1")
    ksp("com.google.dagger:hilt-compiler:2.60.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.3.0")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.2.0")
    implementation("androidx.appcompat:appcompat:1.7.1")

    implementation("io.ktor:ktor-client-core:3.3.2")
    implementation("io.ktor:ktor-client-okhttp:3.3.2")
    implementation("io.ktor:ktor-client-content-negotiation:3.3.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:5.2.1")

    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.0")
    implementation("androidx.media3:media3-ui:1.11.0")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation(platform("com.google.firebase:firebase-bom:34.18.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation(platform("androidx.compose:compose-bom:2025.08.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.70.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.70.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
