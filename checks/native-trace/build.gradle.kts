plugins { kotlin("jvm") version "2.4.21" }
repositories { mavenCentral() }
kotlin { jvmToolchain(17) }
dependencyLocking { lockAllConfigurations() }
val observedSources = layout.buildDirectory.dir("observed-sources")
val syncObservedSources = tasks.register<Sync>("syncObservedSources") {
    from("../../core/data/src/androidTest/kotlin/com/t1doo/aiyou/data/NativeStepTrace.kt")
    into(observedSources)
}
kotlin.sourceSets.getByName("main").kotlin.srcDir(observedSources)
tasks.named("compileKotlin") { dependsOn(syncObservedSources) }
dependencies { testImplementation("junit:junit:4.13.2") }
tasks.test { useJUnit() }
