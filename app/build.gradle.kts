plugins {
    id("com.android.application")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.nexo.vita"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nexo.vita"
        minSdk = 28
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.0"
    }

    // La clave de firma no está en el repositorio: GitHub Actions la descifra
    // de los Secrets y pasa su ruta y contraseña por variables de entorno.
    signingConfigs {
        create("release") {
            val ruta = System.getenv("NEXO_KEYSTORE_FILE")
            if (!ruta.isNullOrBlank()) {
                storeFile = file(ruta)
                storePassword = System.getenv("NEXO_KEYSTORE_PASSWORD")
                keyAlias = "nexo"
                keyPassword = System.getenv("NEXO_KEYSTORE_PASSWORD")
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    // Firebase (mismo proyecto que V.I.T.A.) e inicio de sesión con Google.
    implementation(platform("com.google.firebase:firebase-bom:35.0.2"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    testImplementation("junit:junit:4.13.2")
}
