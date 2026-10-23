plugins {
    id("com.android.application")
}

android {
    namespace = "com.derich.estacacast.camera"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.derich.estacacast.camera"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.github.pedroSG94.RootEncoder:library:2.8.1")
}
