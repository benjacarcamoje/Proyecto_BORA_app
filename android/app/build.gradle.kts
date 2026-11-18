import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

val supabaseLocalProperties = Properties()
listOf(rootProject.file("../local.properties"), rootProject.file("local.properties"))
    .filter { it.isFile }
    .forEach { file -> file.inputStream().use { supabaseLocalProperties.load(it) } }
val supabaseAnonKey = System.getenv("BORA_SUPABASE_ANON_KEY")
    ?.takeIf(String::isNotBlank)
    ?: supabaseLocalProperties.getProperty("supabase.anonKey", "")
val escapedSupabaseAnonKey = supabaseAnonKey
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

android {
    namespace = "com.esp.bora"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.esp.bora"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "SUPABASE_URL", "\"https://supabase-grupo-esp.codepulse.cl\"")
        buildConfigField("String", "SUPABASE_BUCKET", "\"img-bora\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$escapedSupabaseAnonKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Huella digital
    implementation("androidx.biometric:biometric:1.1.0")

    // Lectura de la orientación (EXIF) de la foto y carga de imágenes
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.zxing:core:3.5.4")

    // Firebase (el BoM fija las versiones compatibles entre sí)
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-functions")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
}
