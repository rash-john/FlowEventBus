plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.ksp.api)
}
