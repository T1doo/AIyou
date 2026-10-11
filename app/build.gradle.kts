plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.screenshot)
}

val robolectricSdk = configurations.create("robolectricSdk") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val prepareRobolectricSdk = tasks.register<Copy>("prepareRobolectricSdk") {
    from(robolectricSdk)
    into(layout.buildDirectory.dir("robolectric-sdk"))
}

android {
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
    namespace = "com.t1doo.aiyou"
    compileSdk = 37
    compileSdkMinor = 0
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.t1doo.aiyou"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.3-d02-text"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = true
        warningsAsErrors = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            it.dependsOn(prepareRobolectricSdk)
            // Gradle resolves the fixed, locked Maven SDK; the test runner gets
            // no independent downloader and installs no Android SDK package.
            it.systemProperty("robolectric.offline", "true")
            it.systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-sdk").get().asFile.absolutePath)
            it.jvmArgs(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            )
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(libs.coroutines.android)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(project(":core:designsystem"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.robolectric.runtime)
    androidTestImplementation(libs.android.test.runner)
    androidTestImplementation(libs.android.test.junit)
    androidTestImplementation(libs.junit)
    robolectricSdk(libs.robolectric.sdk)
    "screenshotTestImplementation"(platform(libs.compose.bom))
    "screenshotTestImplementation"(libs.compose.ui.tooling)
    "screenshotTestImplementation"(libs.screenshot.validation.api)
}
