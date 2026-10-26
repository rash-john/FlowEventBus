plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.ksp)
}

kotlin { jvmToolchain(17) }

// Generates an index for the test sources, so the tests cover the processor end to end.
ksp { arg("floweventbus.index", "com.floweventbus.TestEventBusIndex") }

dependencies {
    api(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    kspTest(project(":floweventbus-ksp"))
}
