plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.poolvisionpro"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.poolvisionpro"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.8-autolive"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies { testImplementation("junit:junit:4.13.2"); implementation("org.opencv:opencv:4.10.0") }
