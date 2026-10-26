plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.floweventbus.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.floweventbus.sample"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

// Generates SampleEventBusIndex so @Subscribe methods are called directly instead of via reflection.
ksp { arg("floweventbus.index", "com.floweventbus.sample.SampleEventBusIndex") }

dependencies {
    implementation(project(":floweventbus-android"))
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    ksp(project(":floweventbus-ksp"))
}
