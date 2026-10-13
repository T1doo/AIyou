plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
}

tasks.test { useJUnit() }
