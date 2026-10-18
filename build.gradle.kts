// Firebase BoM 35 está compilado con metadatos de Kotlin 2.4; el Kotlin que trae
// AGP 9.0.1 (2.2.10) no los lee. Se usa un Kotlin más nuevo, como indica la nota
// de AGP 9 («To use a newer KGP»).
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.20")
    }
}

plugins {
    id("com.android.application") version "9.0.1" apply false
    id("com.google.gms.google-services") version "4.5.0" apply false
}
