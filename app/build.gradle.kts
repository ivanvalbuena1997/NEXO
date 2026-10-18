plugins {
    id("com.android.application")
}

android {
    namespace = "com.nexo.vita"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nexo.vita"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
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

    testImplementation("junit:junit:4.13.2")
}
