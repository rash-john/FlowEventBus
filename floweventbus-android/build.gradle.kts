plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "com.floweventbus.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":floweventbus"))
    api(libs.kotlinx.coroutines.android)          // provides Dispatchers.Main -> MAIN thread modes
    api(libs.androidx.lifecycle.runtime.ktx)      // lifecycleScope / repeatOnLifecycle in public inline APIs
}
