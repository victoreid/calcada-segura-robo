plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.fiap.calcadasegura.robo"
    compileSdk = 34

    defaultConfig {
        applicationId = "br.fiap.calcadasegura.robo"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Só os testes usam biblioteca de fora. O app usa apenas o Android puro.
    testImplementation("junit:junit:4.13.2")
}
