plugins { id("com.android.application") version "9.4.1" }
android {
    namespace = "com.t1doo.aiyou.recoveryprobe"
    compileSdk = 37
    compileSdkMinor = 0
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "com.t1doo.aiyou.recoveryprobe"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "958ab-one-recovery"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = true; warningsAsErrors = true }
}
// Platform Instrumentation/UiAutomation only: no new library or production dependency.
