plugins { kotlin("jvm") version "2.4.21"; id("com.google.devtools.ksp") version "2.3.12" }
kotlin { jvmToolchain(17) }
dependencyLocking { lockAllConfigurations() }
// Compile actual data, Android adapter/receivers/service/broker. Only the unused Activity and R are shells.
kotlin.sourceSets.main { kotlin.srcDir("../../core/data/src/main/kotlin") }
val production=layout.buildDirectory.dir("app-production")
val syncProduction=tasks.register<Sync>("syncProduction") {
    listOf("AndroidReminderDriver.kt","AndroidReminderRuntime.kt","ReminderReceivers.kt","ReminderJobService.kt",
        "ReminderOperationBridge.kt","ReminderVmRuntime.kt","ReminderNativeEngine.kt","ReminderProcessOwner.kt").forEach {
        from("../../app/src/main/java/com/t1doo/aiyou/records/$it")
    }
    listOf("AppAccessController.kt","AppAccessPreferenceOperations.kt","FileAppAccessPreference.kt").forEach{
        from("../../app/src/main/java/com/t1doo/aiyou/ui/$it")
    }
    into(production)
}
kotlin.sourceSets.main { kotlin.srcDir(production) }
val androidAars=configurations.create("androidAars"){isTransitive=false}
val extracted=layout.buildDirectory.dir("aar-classes")
val unpack=tasks.register("unpackAndroidAars") {
    inputs.files(androidAars);outputs.dir(extracted)
    doLast { androidAars.files.forEach{aar->copy{from(zipTree(aar));include("classes.jar");filePermissions{unix("rw-r--r--")};into(extracted.get().dir(aar.nameWithoutExtension))}} }
}
val sdk=configurations.create("robolectricSdk"){isTransitive=false}
val prepareSdk=tasks.register<Copy>("prepareRobolectricSdk"){from(sdk);into(layout.buildDirectory.dir("robolectric-sdk"))}
val androidJars=fileTree(extracted){include("**/*.jar")}
dependencies {
    implementation(project(":core:model"));implementation(project(":core:domain"))
    implementation("org.robolectric:android-all:15-robolectric-13954326")
    implementation(libs.coroutines.android);implementation("androidx.room:room-common-jvm:2.8.5")
    implementation(androidJars)
    androidAars("androidx.room:room-runtime-android:2.8.5@aar")
    androidAars("androidx.sqlite:sqlite-android:2.7.0@aar")
    androidAars("net.zetetic:sqlcipher-android:4.19.1@aar")
    androidAars("androidx.test:monitor:1.8.0@aar")
    androidAars("androidx.test.espresso:espresso-idling-resource:3.7.0@aar")
    ksp(libs.room.compiler)
    testImplementation(libs.junit);testImplementation(libs.robolectric.runtime)
    sdk(libs.robolectric.sdk)
}
ksp{arg("room.schemaLocation",layout.buildDirectory.dir("actual-room-export").get().asFile.path)}
tasks.configureEach{if(name.startsWith("ksp")){dependsOn(unpack,syncProduction)}}
tasks.named("compileKotlin"){dependsOn(unpack,syncProduction)}
subprojects{
    layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("isolated-"+name))
    dependencyLocking{lockAllConfigurations();lockFile.set(rootProject.layout.projectDirectory.file("locks/$name.lockfile"))}
}
tasks.test {
    dependsOn(prepareSdk);maxHeapSize="768m";maxParallelForks=1
    systemProperty("robolectric.offline","true")
    systemProperty("robolectric.dependency.dir",layout.buildDirectory.dir("robolectric-sdk").get().asFile.absolutePath)
    systemProperty("aiyou.root",rootProject.file("../..").absolutePath)
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED","--add-opens=java.base/java.util=ALL-UNNAMED")
    doFirst {
        val process=ProcessBuilder("python3",rootProject.file("../../scripts/verify-reminder-native-results.py").absolutePath,"--capture").inheritIO().start()
        check(process.waitFor()==0){"Cannot bind controlled native test inputs"}
    }
    testLogging.exceptionFormat=org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
}
